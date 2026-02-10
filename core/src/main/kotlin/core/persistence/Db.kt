
package core.persistence

import java.sql.Connection
import java.sql.DriverManager

object Db {
  fun open(path: String = "netninja.db"): Connection {
    val c = DriverManager.getConnection("jdbc:sqlite:$path")
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
    return c
  }
}
