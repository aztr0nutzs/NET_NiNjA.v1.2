
package core.persistence

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

object Db {
  private const val CURRENT_SCHEMA_VERSION = 1

  fun open(path: String = "netninja.db"): Connection {
    // sqlite-jdbc extracts native libraries to a temp directory. On Windows this can fail due to
    // temp path policies/locks. Prefer a repo-local temp dir when not explicitly configured.
    if (System.getProperty("org.sqlite.tmpdir").isNullOrBlank()) {
      val tmp = File(System.getProperty("user.dir"), "build/tmp/sqlite").apply { mkdirs() }
      System.setProperty("org.sqlite.tmpdir", tmp.absolutePath)
    }

    val c = DriverManager.getConnection("jdbc:sqlite:$path")

    // Defensive SQLite pragmas for server-side usage.
    // These are best-effort; if they fail (e.g., read-only filesystem) we still want the DB to open.
    runCatching { c.createStatement().execute("PRAGMA foreign_keys=ON;") }
    runCatching { c.createStatement().execute("PRAGMA journal_mode=WAL;") }
    runCatching { c.createStatement().execute("PRAGMA synchronous=NORMAL;") }
    runCatching { c.createStatement().execute("PRAGMA busy_timeout=5000;") }

    try {
      // Use a SAVEPOINT so migrations remain atomic even if the driver implicitly wrapped earlier statements
      // in a transaction (SQLite can do this for some PRAGMAs).
      runCatching { c.createStatement().execute("SAVEPOINT netninja_migrate;") }.getOrNull()

      // Fail fast on corruption so ops can restore from backup instead of silently serving partial data.
      val integrity = c.createStatement().executeQuery("PRAGMA integrity_check;").use { rs ->
        if (rs.next()) rs.getString(1) else "unknown"
      }
      if (integrity != "ok") {
        throw IllegalStateException("SQLite integrity_check failed: $integrity")
      }

      // Schema version tracking: one-row table.
      c.createStatement().execute(
        """CREATE TABLE IF NOT EXISTS schema_version(
            id INTEGER PRIMARY KEY CHECK (id = 1),
            version INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )"""
      )
      val existingVersion = c.createStatement().executeQuery("SELECT version FROM schema_version WHERE id=1;").use { rs ->
        if (rs.next()) rs.getInt(1) else null
      }
      if (existingVersion == null) {
        val now = System.currentTimeMillis()
        c.createStatement().execute("INSERT INTO schema_version(id, version, updatedAt) VALUES (1, $CURRENT_SCHEMA_VERSION, $now)")
      } else if (existingVersion != CURRENT_SCHEMA_VERSION) {
        val now = System.currentTimeMillis()
        c.createStatement().execute("UPDATE schema_version SET version=$CURRENT_SCHEMA_VERSION, updatedAt=$now WHERE id=1")
      }

      c.createStatement().execute(
        """CREATE TABLE IF NOT EXISTS devices(
            id TEXT PRIMARY KEY,
            ip TEXT,
            name TEXT,
            mac TEXT,
            hostname TEXT,
            os TEXT,
            vendor TEXT,
            online INTEGER,
            lastSeen INTEGER,
            owner TEXT,
            room TEXT,
            note TEXT,
            trust TEXT,
            type TEXT,
            status TEXT,
            via TEXT,
            signal TEXT,
            activityToday TEXT,
            traffic TEXT
        )"""
      )

      fun ensureColumn(table: String, column: String, ddlType: String) {
        val present = c.createStatement().executeQuery("PRAGMA table_info($table);").use { rs ->
          var found = false
          while (rs.next()) {
            if (rs.getString("name") == column) {
              found = true
              break
            }
          }
          found
        }
        if (!present) {
          c.createStatement().execute("ALTER TABLE $table ADD COLUMN $column $ddlType")
        }
      }

      // Lightweight migration for existing installs: add new columns only if absent.
      ensureColumn("devices", "os", "TEXT")
      ensureColumn("devices", "name", "TEXT")
      ensureColumn("devices", "owner", "TEXT")
      ensureColumn("devices", "room", "TEXT")
      ensureColumn("devices", "note", "TEXT")
      ensureColumn("devices", "trust", "TEXT")
      ensureColumn("devices", "type", "TEXT")
      ensureColumn("devices", "status", "TEXT")
      ensureColumn("devices", "via", "TEXT")
      ensureColumn("devices", "signal", "TEXT")
      ensureColumn("devices", "activityToday", "TEXT")
      ensureColumn("devices", "traffic", "TEXT")

      c.createStatement().execute(
        """CREATE TABLE IF NOT EXISTS events(
            deviceId TEXT,
            ts INTEGER,
            event TEXT
        )"""
      )
      c.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_devices_lastSeen ON devices(lastSeen)")
      c.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_events_device_ts ON events(deviceId, ts)")

      runCatching { c.createStatement().execute("RELEASE netninja_migrate;") }
    } catch (t: Throwable) {
      runCatching { c.createStatement().execute("ROLLBACK TO netninja_migrate;") }
      runCatching { c.createStatement().execute("RELEASE netninja_migrate;") }
      throw t
    }
    return c
  }
}
