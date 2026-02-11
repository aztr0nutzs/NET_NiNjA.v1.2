package core.persistence

import core.model.Device
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceDaoTest {
  @Test
  fun insertUpdateGetAllDeleteAndGetById() {
    val dbFile = tempDbFile()
    Db.open(dbFile.absolutePath).use { conn ->
      val dao = DeviceDao(conn)
      val d1 = Device(
        id = "dev-1",
        ip = "192.168.1.10",
        name = "Laptop",
        mac = "AA:BB:CC:DD:EE:FF",
        online = true,
        lastSeen = 1000L
      )

      dao.upsert(d1)
      assertEquals(1, dao.all().size)

      val fetched = dao.getById("dev-1")
      assertNotNull(fetched)
      assertEquals("Laptop", fetched.name)
      assertTrue(fetched.online)

      dao.upsert(d1.copy(ip = "192.168.1.11", name = "Laptop-Updated", online = false, lastSeen = 2000L))
      val updated = dao.getById("dev-1")
      assertNotNull(updated)
      assertEquals("192.168.1.11", updated.ip)
      assertEquals("Laptop-Updated", updated.name)
      assertEquals(false, updated.online)
      assertEquals(1, dao.all().size)

      assertEquals(1, dao.delete("dev-1"))
      assertNull(dao.getById("dev-1"))
      assertTrue(dao.all().isEmpty())
    }
  }

  private fun tempDbFile(): File {
    val f = File.createTempFile("netninja-device-dao-", ".sqlite")
    if (f.exists()) f.delete()
    f.deleteOnExit()
    return f
  }
}
