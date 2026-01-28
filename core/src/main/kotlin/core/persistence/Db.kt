
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
    // Lightweight migration for existing installs
    runCatching {
      c.createStatement().execute("ALTER TABLE devices ADD COLUMN os TEXT")
    }
    val deviceColumns = listOf(
      "name",
      "owner",
      "room",
      "note",
      "trust",
      "type",
      "status",
      "via",
      "signal",
      "activityToday",
      "traffic"
    )
    deviceColumns.forEach { col ->
      runCatching {
        c.createStatement().execute("ALTER TABLE devices ADD COLUMN $col TEXT")
      }
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
