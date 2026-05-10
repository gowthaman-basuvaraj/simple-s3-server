package s3

import io.javalin.testtools.JavalinTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        File(tempDir, "data").mkdirs()
        Db.init("jdbc:sqlite::memory:")
    }

    private val textPlain = "text/plain".toMediaType()
    private val empty = "".toRequestBody(null)

    private fun createBucket(
        client: io.javalin.testtools.HttpClient,
        name: String,
        expiryDays: Int? = null
    ) = client.request("/$name") {
        val b = it.put(empty)
        if (expiryDays != null) b.header("x-amz-meta-expiry-days", expiryDays.toString())
        b
    }

    // --- ListBuckets ---

    @Test
    fun `list buckets returns XML`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "alpha", 10)
        createBucket(client, "beta", 20)
        val response = client.get("/")
        assertThat(response.code).isEqualTo(200)
        val body = response.body?.string()!!
        assertThat(body).contains("ListAllMyBucketsResult")
        assertThat(body).contains("<Name>alpha</Name>")
        assertThat(body).contains("<Name>beta</Name>")
    }

    // --- CreateBucket ---

    @Test
    fun `create bucket returns 200 with XML`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        val response = createBucket(client, "photos", 30)
        assertThat(response.code).isEqualTo(200)
        val body = response.body?.string()!!
        assertThat(body).contains("<Location>/photos</Location>")
        assertThat(response.header("Location")).isEqualTo("/photos")
    }

    @Test
    fun `create bucket uses default expiry when header missing`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        val response = createBucket(client, "defaultbucket")
        assertThat(response.code).isEqualTo(200)
    }

    @Test
    fun `create duplicate bucket is idempotent`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "dup", 7)
        val response = createBucket(client, "dup", 7)
        assertThat(response.code).isEqualTo(200)
    }

    // --- HeadBucket ---

    @Test
    fun `head existing bucket returns 200`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "exists", 10)
        val response = client.request("/exists") { it.head() }
        assertThat(response.code).isEqualTo(200)
    }

    @Test
    fun `head nonexistent bucket returns 404`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        val response = client.request("/nope") { it.head() }
        assertThat(response.code).isEqualTo(404)
    }

    // --- PutObject ---

    @Test
    fun `put object to nonexistent bucket returns 404`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        val response = client.request("/nope/file.txt") {
            it.put("hello".toRequestBody(textPlain))
        }
        assertThat(response.code).isEqualTo(404)
        assertThat(response.body?.string()).contains("NoSuchBucket")
    }

    @Test
    fun `put object returns 200 with ETag`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "docs", 10)
        val response = client.request("/docs/readme.txt") {
            it.put("Hello S3".toRequestBody(textPlain))
        }
        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("ETag")).isNotNull().startsWith("\"").endsWith("\"")
    }

    @Test
    fun `put empty body returns 400`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "docs")
        val response = client.request("/docs/empty.txt") { it.put(empty) }
        assertThat(response.code).isEqualTo(400)
        assertThat(response.body?.string()).contains("MissingContent")
    }

    // --- HeadObject ---

    @Test
    fun `head object returns metadata`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "docs")
        client.request("/docs/file.txt") { it.put("content".toRequestBody(textPlain)) }

        val response = client.request("/docs/file.txt") { it.head() }
        assertThat(response.code).isEqualTo(200)
        assertThat(response.header("ETag")).isNotNull()
        assertThat(response.header("Content-Length")).isEqualTo("7")
    }

    @Test
    fun `head nonexistent object returns 404`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "docs")
        val response = client.request("/docs/nope.txt") { it.head() }
        assertThat(response.code).isEqualTo(404)
    }

    // --- GetObject ---

    @Test
    fun `get object returns content with ETag`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "docs")
        client.request("/docs/readme.txt") { it.put("Hello S3".toRequestBody(textPlain)) }

        val response = client.get("/docs/readme.txt")
        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo("Hello S3")
        assertThat(response.header("ETag")).isNotNull()
        assertThat(response.header("Content-Length")).isEqualTo("8")
    }

    @Test
    fun `get nonexistent object returns 404 XML`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "empty")
        val response = client.get("/empty/nope.txt")
        assertThat(response.code).isEqualTo(404)
        assertThat(response.body?.string()).contains("NoSuchKey")
    }

    @Test
    fun `put and get with nested key path`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        createBucket(client, "files")
        client.request("/files/a/b/c.txt") { it.put("nested".toRequestBody(textPlain)) }

        val response = client.get("/files/a/b/c.txt")
        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo("nested")
    }

    // --- IP restriction ---

    @Test
    fun `allowed ip can create bucket and put object`() = JavalinTest.test(createApp(setOf("127.0.0.1"))) { _, client ->
        val response = createBucket(client, "secure")
        assertThat(response.code).isEqualTo(200)

        val putResponse = client.request("/secure/test.txt") {
            it.put("data".toRequestBody(textPlain))
        }
        assertThat(putResponse.code).isEqualTo(200)
    }

    @Test
    fun `blocked ip gets 403 on create bucket`() = JavalinTest.test(createApp(setOf("10.0.0.1"))) { _, client ->
        val response = createBucket(client, "nope")
        assertThat(response.code).isEqualTo(403)
        assertThat(response.body?.string()).contains("AccessDenied")
    }

    @Test
    fun `blocked ip gets 403 on put but get still works`() = JavalinTest.test(createApp(setOf("10.0.0.1"))) { _, client ->
        Db.conn.createStatement().executeUpdate("INSERT INTO buckets (name, expiry_days) VALUES ('open', 30)")
        Db.conn.prepareStatement("INSERT INTO objects (bucket, key, content_type, file_path, etag, size) VALUES (?, ?, ?, ?, ?, ?)").apply {
            val f = File(tempDir, "dl-test")
            f.writeText("public content")
            setString(1, "open")
            setString(2, "pub.txt")
            setString(3, "text/plain")
            setString(4, f.absolutePath)
            setString(5, "abc123")
            setLong(6, 14)
            executeUpdate()
        }

        val putResponse = client.request("/open/new.txt") {
            it.put("blocked".toRequestBody(textPlain))
        }
        assertThat(putResponse.code).isEqualTo(403)

        val getResponse = client.get("/open/pub.txt")
        assertThat(getResponse.code).isEqualTo(200)
        assertThat(getResponse.body?.string()).isEqualTo("public content")
    }

    @Test
    fun `empty allowedIps blocks all writes`() = JavalinTest.test(createApp(emptySet())) { _, client ->
        val response = createBucket(client, "blocked")
        assertThat(response.code).isEqualTo(403)
    }
}
