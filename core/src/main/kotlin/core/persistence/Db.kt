
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
          mac TEXT,
          hostname TEXT,
          os TEXT,
          vendor TEXT,
          online INTEGER,
          lastSeen INTEGER
      )"""
    )
    // Lightweight migration for existing installs
    runCatching {
      c.createStatement().execute("ALTER TABLE devices ADD COLUMN os TEXT")
    }
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
