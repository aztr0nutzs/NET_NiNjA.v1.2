
package core.persistence

import core.model.Device
import java.sql.Connection

class DeviceDao(private val conn: Connection) {
  fun upsert(d: Device) {
    conn.prepareStatement(
      "INSERT OR REPLACE INTO devices(id,ip,name,mac,hostname,os,vendor,online,lastSeen,owner,room,note,trust,type,status,via,signal,activityToday,traffic) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    ).use { ps ->
      ps.setString(1, d.id)
      ps.setString(2, d.ip)
      ps.setString(3, d.name)
      ps.setString(4, d.mac)
      ps.setString(5, d.hostname)
      ps.setString(6, d.os)
      ps.setString(7, d.vendor)
      ps.setInt(8, if (d.online) 1 else 0)
      ps.setLong(9, d.lastSeen)
      ps.setString(10, d.owner)
      ps.setString(11, d.room)
      ps.setString(12, d.note)
      ps.setString(13, d.trust)
      ps.setString(14, d.type)
      ps.setString(15, d.status)
      ps.setString(16, d.via)
      ps.setString(17, d.signal)
      ps.setString(18, d.activityToday)
      ps.setString(19, d.traffic)
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
          name = rs.getString("name"),
          mac = rs.getString("mac"),
          hostname = rs.getString("hostname"),
          os = rs.getString("os"),
          vendor = rs.getString("vendor"),
          online = rs.getInt("online") == 1,
          lastSeen = rs.getLong("lastSeen"),
          owner = rs.getString("owner"),
          room = rs.getString("room"),
          note = rs.getString("note"),
          trust = rs.getString("trust"),
          type = rs.getString("type"),
          status = rs.getString("status"),
          via = rs.getString("via"),
          signal = rs.getString("signal"),
          activityToday = rs.getString("activityToday"),
          traffic = rs.getString("traffic")
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
          name = rs.getString("name"),
          mac = rs.getString("mac"),
          hostname = rs.getString("hostname"),
          os = rs.getString("os"),
          vendor = rs.getString("vendor"),
          online = rs.getInt("online") == 1,
          lastSeen = rs.getLong("lastSeen"),
          owner = rs.getString("owner"),
          room = rs.getString("room"),
          note = rs.getString("note"),
          trust = rs.getString("trust"),
          type = rs.getString("type"),
          status = rs.getString("status"),
          via = rs.getString("via"),
          signal = rs.getString("signal"),
          activityToday = rs.getString("activityToday"),
          traffic = rs.getString("traffic")
        )
      }
    }

  fun getById(id: String): Device? = get(id)

  fun delete(id: String): Int =
    conn.prepareStatement("DELETE FROM devices WHERE id=?").use { ps ->
      ps.setString(1, id)
      ps.executeUpdate()
    }
}
