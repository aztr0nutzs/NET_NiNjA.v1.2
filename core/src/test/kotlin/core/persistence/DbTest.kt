package core.persistence

import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbTest {
  @Test
  fun openCreatesSchemaAndAppliesPragmas() {
    val dbFile = tempDbFile()

    Db.open(dbFile.absolutePath).use { conn ->
      val tables = conn.createStatement().executeQuery(
        "SELECT name FROM sqlite_master WHERE type='table'"
      ).use { rs ->
        buildSet {
          while (rs.next()) add(rs.getString(1))
        }
      }
      assertTrue("devices" in tables)
      assertTrue("events" in tables)
      assertTrue("schema_version" in tables)

      val foreignKeys = conn.createStatement().executeQuery("PRAGMA foreign_keys;").use { rs ->
        if (rs.next()) rs.getInt(1) else 0
      }
      assertEquals(1, foreignKeys)

      val integrity = conn.createStatement().executeQuery("PRAGMA integrity_check;").use { rs ->
        if (rs.next()) rs.getString(1) else "missing"
      }
      assertEquals("ok", integrity)

      val version = conn.createStatement().executeQuery("SELECT version FROM schema_version WHERE id=1;").use { rs ->
        if (rs.next()) rs.getInt(1) else -1
      }
      assertEquals(1, version)
    }
  }

  @Test
  fun openMigratesExistingDevicesTableAndSchemaVersion() {
    val dbFile = tempDbFile()
    DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
      conn.createStatement().execute(
        "CREATE TABLE devices(id TEXT PRIMARY KEY, ip TEXT, mac TEXT, hostname TEXT, vendor TEXT, online INTEGER, lastSeen INTEGER)"
      )
      conn.createStatement().execute("CREATE TABLE events(deviceId TEXT, ts INTEGER, event TEXT)")
      conn.createStatement().execute(
        "CREATE TABLE schema_version(id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
      )
      conn.createStatement().execute("INSERT INTO schema_version(id, version, updatedAt) VALUES (1, 0, 0)")
    }

    Db.open(dbFile.absolutePath).use { conn ->
      val columns = conn.createStatement().executeQuery("PRAGMA table_info(devices);").use { rs ->
        buildSet {
          while (rs.next()) add(rs.getString("name"))
        }
      }
      val expectedColumns = setOf(
        "os", "name", "owner", "room", "note", "trust", "type", "status",
        "via", "signal", "activityToday", "traffic"
      )
      assertTrue(expectedColumns.all { it in columns })

      val version = conn.createStatement().executeQuery("SELECT version FROM schema_version WHERE id=1;").use { rs ->
        if (rs.next()) rs.getInt(1) else -1
      }
      assertEquals(1, version)
    }
  }

  private fun tempDbFile(): File {
    val f = File.createTempFile("netninja-core-db-", ".sqlite")
    if (f.exists()) f.delete()
    f.deleteOnExit()
    return f
  }
}
