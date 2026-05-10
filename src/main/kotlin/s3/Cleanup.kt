package s3

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Cleanup {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cleanup").apply { isDaemon = true }
    }

    fun start() {
        scheduler.scheduleAtFixedRate(::deleteExpired, 0, 1, TimeUnit.DAYS)
    }

    private fun deleteExpired() {
        try {
            val conn = Db.conn
            // Find objects whose age exceeds their bucket's expiry_days
            val query = """
                SELECT o.id, o.file_path FROM objects o
                JOIN buckets b ON o.bucket = b.name
                WHERE datetime(o.created_at, '+' || b.expiry_days || ' days') < datetime('now')
            """
            val ids = mutableListOf<Int>()
            conn.createStatement().executeQuery(query).use { rs ->
                while (rs.next()) {
                    ids.add(rs.getInt("id"))
                    File(rs.getString("file_path")).delete()
                }
            }
            if (ids.isNotEmpty()) {
                conn.createStatement().executeUpdate(
                    "DELETE FROM objects WHERE id IN (${ids.joinToString(",")})"
                )
                println("Cleanup: deleted ${ids.size} expired object(s)")
            }
        } catch (e: Exception) {
            System.err.println("Cleanup error: ${e.message}")
        }
    }
}
