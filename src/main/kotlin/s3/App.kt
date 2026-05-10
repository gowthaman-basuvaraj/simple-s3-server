package s3

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class Server : CliktCommand() {
    private val port by option("--port", help = "Server port").int().default(9393)
    private val allowedIps by option("--allowed-ips", help = "Comma-separated IPs allowed to create buckets and put objects").required()
    private val defaultExpiryDays by option("--default-expiry-days", help = "Default expiry days for buckets when header is not set").int().default(30)
    private val dbName by option("--db", help = "SQLite database file path").default("storage/s3.db")

    override fun run() {
        File("storage/data").mkdirs()
        try {
            Db.init("jdbc:sqlite:$dbName")
        } catch (e: Exception) {
            System.err.println("Failed to open database '$dbName': ${e.message}")
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
        Cleanup.start()

        val ips = allowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        createApp(ips, defaultExpiryDays).start(port)
        println("my-own-s3 running on http://localhost:$port")
        println("Allowed IPs: $ips")
    }
}

fun main(args: Array<String>) = Server().main(args)

fun createApp(allowedIps: Set<String> = emptySet(), defaultExpiryDays: Int = 30): Javalin {
    val app = Javalin.create()

    // GET / — ListBuckets
    app.get("/") { ctx ->
        val rs = Db.conn.createStatement().executeQuery("SELECT name, created_at FROM buckets ORDER BY name")
        val buckets = StringBuilder()
        while (rs.next()) {
            buckets.append("""
    <Bucket>
      <Name>${rs.getString("name")}</Name>
      <CreationDate>${rs.getString("created_at").replace(" ", "T") + "Z"}</CreationDate>
    </Bucket>""")
        }
        ctx.status(200)
            .contentType("application/xml")
            .result("""<?xml version="1.0" encoding="UTF-8"?>
<ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <Owner>
    <ID>my-own-s3</ID>
    <DisplayName>my-own-s3</DisplayName>
  </Owner>
  <Buckets>$buckets
  </Buckets>
</ListAllMyBucketsResult>""")
    }

    // PUT /{bucket} — CreateBucket
    app.put("/{bucket}") { ctx ->
        if (!checkIp(ctx, allowedIps)) return@put

        val bucket = ctx.pathParam("bucket")
        val expiryDays = ctx.header("x-amz-meta-expiry-days")?.toIntOrNull() ?: defaultExpiryDays

        val stmt = Db.conn.prepareStatement("INSERT OR IGNORE INTO buckets (name, expiry_days) VALUES (?, ?)")
        stmt.setString(1, bucket)
        stmt.setInt(2, expiryDays)
        stmt.executeUpdate()

        ctx.status(200)
            .header("Location", "/$bucket")
            .contentType("application/xml")
            .result("""<?xml version="1.0" encoding="UTF-8"?>
<CreateBucketResult>
  <Location>/$bucket</Location>
</CreateBucketResult>""")
    }

    // HEAD /{bucket} — HeadBucket
    app.head("/{bucket}") { ctx ->
        val bucket = ctx.pathParam("bucket")
        val rs = Db.conn.prepareStatement("SELECT 1 FROM buckets WHERE name = ?").apply { setString(1, bucket) }.executeQuery()
        if (!rs.next()) {
            ctx.status(404)
            return@head
        }
        ctx.status(200)
            .header("x-amz-bucket-region", "us-east-1")
    }

    // PUT /{bucket}/{key} — PutObject
    app.put("/{bucket}/<key>") { ctx ->
        if (!checkIp(ctx, allowedIps)) return@put

        val bucket = ctx.pathParam("bucket")
        val key = ctx.pathParam("key")

        val bucketCheck = Db.conn.prepareStatement("SELECT 1 FROM buckets WHERE name = ?")
        bucketCheck.setString(1, bucket)
        if (!bucketCheck.executeQuery().next()) {
            ctx.s3Error(404, "NoSuchBucket", "The specified bucket does not exist.", "/$bucket/$key")
            return@put
        }

        val body = ctx.bodyAsBytes()
        if (body.isEmpty()) {
            ctx.s3Error(400, "MissingContent", "Request body is empty.", "/$bucket/$key")
            return@put
        }

        val contentType = ctx.contentType() ?: "application/octet-stream"
        val filePath = "storage/data/${UUID.randomUUID()}"
        File(filePath).writeBytes(body)
        val etag = md5Hex(body)

        val stmt = Db.conn.prepareStatement(
            "INSERT OR REPLACE INTO objects (bucket, key, content_type, file_path, etag, size) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.setString(1, bucket)
        stmt.setString(2, key)
        stmt.setString(3, contentType)
        stmt.setString(4, filePath)
        stmt.setString(5, etag)
        stmt.setLong(6, body.size.toLong())
        stmt.executeUpdate()

        ctx.status(200)
            .header("ETag", "\"$etag\"")
            .result("")
    }

    // HEAD /{bucket}/{key} — HeadObject
    app.head("/{bucket}/<key>") { ctx ->
        val bucket = ctx.pathParam("bucket")
        val key = ctx.pathParam("key")

        val stmt = Db.conn.prepareStatement(
            "SELECT content_type, etag, size, created_at FROM objects WHERE bucket = ? AND key = ?"
        )
        stmt.setString(1, bucket)
        stmt.setString(2, key)
        val rs = stmt.executeQuery()
        if (!rs.next()) {
            ctx.status(404)
            return@head
        }

        ctx.status(200)
            .contentType(rs.getString("content_type"))
            .header("ETag", "\"${rs.getString("etag")}\"")
            .header("Content-Length", rs.getLong("size").toString())
            .header("Last-Modified", rs.getString("created_at"))
    }

    // GET /{bucket}/{key} — GetObject
    app.get("/{bucket}/<key>") { ctx ->
        val bucket = ctx.pathParam("bucket")
        val key = ctx.pathParam("key")

        val stmt = Db.conn.prepareStatement(
            "SELECT content_type, file_path, etag, size FROM objects WHERE bucket = ? AND key = ?"
        )
        stmt.setString(1, bucket)
        stmt.setString(2, key)
        val rs = stmt.executeQuery()
        if (!rs.next()) {
            ctx.s3Error(404, "NoSuchKey", "The specified key does not exist.", "/$bucket/$key")
            return@get
        }

        val contentType = rs.getString("content_type")
        val filePath = rs.getString("file_path")
        val etag = rs.getString("etag")
        val size = rs.getLong("size")
        val file = File(filePath)
        if (!file.exists()) {
            ctx.s3Error(404, "NoSuchKey", "The specified key does not exist.", "/$bucket/$key")
            return@get
        }

        ctx.contentType(contentType)
            .header("ETag", "\"$etag\"")
            .header("Content-Length", size.toString())
            .result(file.inputStream())
    }

    return app
}

private fun checkIp(ctx: Context, allowedIps: Set<String>): Boolean {
    val clientIp = ctx.header("X-Forwarded-For")?.split(",")?.first()?.trim() ?: ctx.ip()
    if (clientIp in allowedIps) return true
    ctx.s3Error(403, "AccessDenied", "Access Denied", ctx.path())
    return false
}

private fun Context.s3Error(status: Int, code: String, message: String, resource: String) {
    this.status(status)
        .contentType("application/xml")
        .result("""<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>$code</Code>
  <Message>$message</Message>
  <Resource>$resource</Resource>
  <RequestId>${UUID.randomUUID()}</RequestId>
</Error>""")
}

private fun md5Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(data)
    return digest.joinToString("") { "%02x".format(it) }
}
