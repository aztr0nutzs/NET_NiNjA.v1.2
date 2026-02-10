
package core.persistence

import core.model.DeviceEvent
import java.sql.Connection

class EventDao(private val conn: Connection) {
  fun insert(e: DeviceEvent) {
    conn.prepareStatement("INSERT INTO events(deviceId, ts, event) VALUES(?,?,?)").use { ps ->
      ps.setString(1, e.deviceId)
      ps.setLong(2, e.ts)
      ps.setString(3, e.event)
      ps.executeUpdate()
    }
  }

  fun history(deviceId: String, limit: Int = 2000): List<DeviceEvent> {
    val out = mutableListOf<DeviceEvent>()
    conn.prepareStatement(
      "SELECT * FROM events WHERE deviceId=? ORDER BY ts ASC LIMIT ?"
    ).use { ps ->
      ps.setString(1, deviceId)
      ps.setInt(2, limit)
      ps.executeQuery().use { rs ->
        while (rs.next()) {
          out += DeviceEvent(rs.getString("deviceId"), rs.getLong("ts"), rs.getString("event"))
        }
      }
    }
    return out
  }
}
