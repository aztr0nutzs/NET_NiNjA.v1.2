package core.persistence

import core.model.DeviceEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class EventDaoTest {
  @Test
  fun insertAndHistoryQueryWithLimit() {
    val dbFile = tempDbFile()
    Db.open(dbFile.absolutePath).use { conn ->
      val dao = EventDao(conn)
      dao.insert(DeviceEvent("dev-1", 1000L, "NEW_DEVICE"))
      dao.insert(DeviceEvent("dev-1", 2000L, "DEVICE_ONLINE"))
      dao.insert(DeviceEvent("dev-1", 3000L, "IP_CHANGED"))
      dao.insert(DeviceEvent("dev-2", 1500L, "NEW_DEVICE"))

      val allForDevice = dao.history("dev-1", limit = 100)
      assertEquals(3, allForDevice.size)
      assertEquals(listOf(1000L, 2000L, 3000L), allForDevice.map { it.ts })

      val limited = dao.history("dev-1", limit = 2)
      assertEquals(2, limited.size)
      assertEquals(listOf("NEW_DEVICE", "DEVICE_ONLINE"), limited.map { it.event })
    }
  }

  private fun tempDbFile(): File {
    val f = File.createTempFile("netninja-event-dao-", ".sqlite")
    if (f.exists()) f.delete()
    f.deleteOnExit()
    return f
  }
}
