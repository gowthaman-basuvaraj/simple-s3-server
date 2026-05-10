package s3

import java.sql.Connection
import java.sql.DriverManager

object Db {
    var conn: Connection = init("jdbc:sqlite:storage/s3.db")
        private set

    fun init(url: String): Connection {
        val c = DriverManager.getConnection(url)
        c.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS buckets (
                name TEXT PRIMARY KEY,
                expiry_days INTEGER NOT NULL,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
        """)
        c.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS objects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bucket TEXT NOT NULL,
                key TEXT NOT NULL,
                content_type TEXT NOT NULL,
                file_path TEXT NOT NULL,
                etag TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                UNIQUE(bucket, key),
                FOREIGN KEY(bucket) REFERENCES buckets(name)
            )
        """)
        conn = c
        return c
    }
}
