package com.netninja.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.netninja.Device
import com.netninja.DeviceEvent
import com.netninja.LocalDatabase
import com.netninja.logging.StructuredLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Database operations for devices extracted from AndroidLocalServer.
 * Addresses RISK-01: Refactor into focused modules.
 */
class DeviceRepository(
  private val ctx: Context,
  private val logger: StructuredLogger
) {
  
  private val db = LocalDatabase(ctx)
  private val devices = ConcurrentHashMap<String, Device>()
  private val deviceEvents = ConcurrentHashMap<String, MutableList<DeviceEvent>>()
  private val maxEventsPerDevice = 4000

  /**
   * Load all devices from database into memory.
   */
  fun loadDevices(): Map<String, Device> {
    val r = db.readableDatabase
    val loaded = mutableMapOf<String, Device>()

    try {
      val hasOpenPorts = hasColumn("devices", "openPorts")
      val sql = if (hasOpenPorts) {
        "SELECT id, ip, name, online, lastSeen, mac, hostname, vendor, os, owner, room, note, trust, type, status, via, signal, activityToday, traffic, openPorts FROM devices"
      } else {
        "SELECT id, ip, name, online, lastSeen, mac, hostname, vendor, os, owner, room, note, trust, type, status, via, signal, activityToday, traffic FROM devices"
      }
      
      r.rawQuery(sql, null).use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0)
          val openPortsRaw = if (hasOpenPorts) c.getString(19) else null
          val openPorts = openPortsRaw
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
            ?: emptyList()
          
          val device = Device(
            id = id,
            ip = c.getString(1),
            name = c.getString(2),
            online = c.getInt(3) == 1,
            lastSeen = c.getLong(4),
            mac = c.getString(5),
            hostname = c.getString(6),
            vendor = c.getString(7),
            os = c.getString(8),
            owner = c.getString(9),
            room = c.getString(10),
            note = c.getString(11),
            trust = c.getString(12),
            type = c.getString(13),
            status = c.getString(14),
            via = c.getString(15),
            signal = c.getString(16),
            activityToday = c.getString(17),
            traffic = c.getString(18),
            openPorts = openPorts
          )
          loaded[id] = device
          devices[id] = device
        }
      }
      logger.info("Loaded devices from database", mapOf("count" to loaded.size.toString()))
    } catch (e: Exception) {
      logger.error("Failed to load devices", mapOf("error" to e.message.toString()), e)
    }

    return loaded
  }

  /**
   * Load device events from database.
   */
  fun loadEvents(): Map<String, List<DeviceEvent>> {
    val r = db.readableDatabase
    val loaded = mutableMapOf<String, MutableList<DeviceEvent>>()

    try {
      r.rawQuery("SELECT deviceId, ts, event FROM events", null).use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0)
          val event = DeviceEvent(id, c.getLong(1), c.getString(2))
          loaded.getOrPut(id) { mutableListOf() }.add(event)
          deviceEvents.getOrPut(id) { mutableListOf() }.add(event)
        }
      }
      logger.info("Loaded device events", mapOf("devices" to loaded.size.toString()))
    } catch (e: Exception) {
      logger.error("Failed to load events", mapOf("error" to e.message.toString()), e)
    }

    return loaded
  }

  /**
   * Save or update a device.
   */
  fun saveDevice(device: Device) {
    try {
      val w = db.writableDatabase
      val cv = ContentValues().apply {
        put("id", device.id)
        put("ip", device.ip)
        put("name", device.name)
        put("online", if (device.online) 1 else 0)
        put("lastSeen", device.lastSeen)
        put("mac", device.mac)
        put("hostname", device.hostname)
        put("vendor", device.vendor)
        put("os", device.os)
        put("owner", device.owner)
        put("room", device.room)
        put("note", device.note)
        put("trust", device.trust)
        put("type", device.type)
        put("status", device.status)
        put("via", device.via)
        put("signal", device.signal)
        put("activityToday", device.activityToday)
        put("traffic", device.traffic)
        if (hasColumn("devices", "openPorts")) {
          put("openPorts", device.openPorts.joinToString(","))
        }
      }
      w.insertWithOnConflict("devices", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
      devices[device.id] = device
    } catch (e: Exception) {
      logger.error("Failed to save device", mapOf("deviceId" to device.id, "error" to e.message.toString()), e)
    }
  }

  /**
   * Record a device event.
   */
  fun recordEvent(deviceId: String, event: String) {
    try {
      val e = DeviceEvent(deviceId, System.currentTimeMillis(), event)
      val list = deviceEvents.getOrPut(deviceId) { mutableListOf() }
      list.add(e)

      // Trim old events
      if (list.size > maxEventsPerDevice) {
        list.subList(0, list.size - maxEventsPerDevice).clear()
      }

      // Save to database
      val w = db.writableDatabase
      val cv = ContentValues().apply {
        put("deviceId", e.deviceId)
        put("ts", e.ts)
        put("event", e.event)
      }
      w.insert("events", null, cv)
    } catch (e: Exception) {
      logger.error("Failed to record event", mapOf("deviceId" to deviceId, "event" to event, "error" to e.message.toString()), e)
    }
  }

  /**
   * Get a device by ID.
   */
  fun getDevice(id: String): Device? = devices[id]

  /**
   * Get all devices.
   */
  fun getAllDevices(): List<Device> = devices.values.toList()

  /**
   * Get events for a device.
   */
  fun getEvents(deviceId: String): List<DeviceEvent> = deviceEvents[deviceId].orEmpty()

  /**
   * Check if a column exists in a table.
   */
  private fun hasColumn(table: String, column: String): Boolean {
    return try {
      val cols = mutableSetOf<String>()
      db.readableDatabase.rawQuery("PRAGMA table_info($table);", null).use { c ->
        val nameIdx = c.getColumnIndex("name").takeIf { it >= 0 } ?: 1
        while (c.moveToNext()) {
          cols += c.getString(nameIdx)
        }
      }
      cols.contains(column)
    } catch (e: Exception) {
      logger.error("Failed to check column", mapOf("table" to table, "column" to column), e)
      false
    }
  }
}
