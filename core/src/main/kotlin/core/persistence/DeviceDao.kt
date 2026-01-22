
package core.persistence

import core.model.Device
import java.sql.Connection

class DeviceDao(private val conn: Connection) {
  fun upsert(d: Device) {
    conn.prepareStatement(
      "INSERT OR REPLACE INTO devices(id,ip,mac,hostname,os,vendor,online,lastSeen) VALUES(?,?,?,?,?,?,?,?)"
    ).use { ps ->
      ps.setString(1, d.id)
      ps.setString(2, d.ip)
      ps.setString(3, d.mac)
      ps.setString(4, d.hostname)
      ps.setString(5, d.os)
      ps.setString(6, d.vendor)
      ps.setInt(7, if (d.online) 1 else 0)
      ps.setLong(8, d.lastSeen)
      ps.executeUpdate()
    }
  }

  fun all(): List<Device> {
    val out = mutableListOf<Device>()
    conn.createStatement().executeQuery("SELECT * FROM devices").use { rs ->
      while (rs.next()) {
        out += Device(
          id = rs.getString("id"),
          ip = rs.getString("ip"),
          mac = rs.getString("mac"),
          hostname = rs.getString("hostname"),
          os = rs.getString("os"),
          vendor = rs.getString("vendor"),
          online = rs.getInt("online") == 1,
          lastSeen = rs.getLong("lastSeen")
        )
      }
    }
    return out
  }

  fun get(id: String): Device? =
    conn.prepareStatement("SELECT * FROM devices WHERE id=?").use { ps ->
      ps.setString(1, id)
      ps.executeQuery().use { rs ->
        if (!rs.next()) return null
        Device(
          id = rs.getString("id"),
          ip = rs.getString("ip"),
          mac = rs.getString("mac"),
          hostname = rs.getString("hostname"),
          os = rs.getString("os"),
          vendor = rs.getString("vendor"),
          online = rs.getInt("online") == 1,
          lastSeen = rs.getLong("lastSeen")
        )
      }
    }
}
