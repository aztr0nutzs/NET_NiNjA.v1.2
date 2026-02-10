package com.netninja

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "netninja.db", null, 4) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS devices(
        id TEXT PRIMARY KEY,
        ip TEXT,
        name TEXT,
        online INTEGER,
        lastSeen INTEGER,
        mac TEXT,
        hostname TEXT,
        vendor TEXT,
        os TEXT,
        owner TEXT,
        room TEXT,
        note TEXT,
        trust TEXT,
        type TEXT,
        status TEXT,
        via TEXT,
        signal TEXT,
        activityToday TEXT,
        traffic TEXT,
        openPorts TEXT
      )"""
    )

    db.execSQL(
      """CREATE TABLE IF NOT EXISTS events(
        deviceId TEXT,
        ts INTEGER,
        event TEXT
      )"""
    )

    db.execSQL(
      """CREATE TABLE IF NOT EXISTS rules(
        match TEXT,
        action TEXT
      )"""
    )

    db.execSQL(
      """CREATE TABLE IF NOT EXISTS schedules(
        entry TEXT
      )"""
    )

    db.execSQL(
      """CREATE TABLE IF NOT EXISTS logs(
        ts INTEGER,
        msg TEXT
      )"""
    )

    db.execSQL(
      """CREATE TABLE IF NOT EXISTS openclaw_kv(
        key TEXT PRIMARY KEY,
        value TEXT
      )"""
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      val columns = listOf(
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
      columns.forEach { col ->
        runCatching { db.execSQL("ALTER TABLE devices ADD COLUMN $col TEXT") }
      }
    }
    if (oldVersion < 3) {
      runCatching { db.execSQL("ALTER TABLE devices ADD COLUMN openPorts TEXT") }
    }
    if (oldVersion < 4) {
      runCatching {
        db.execSQL(
          """CREATE TABLE IF NOT EXISTS openclaw_kv(
            key TEXT PRIMARY KEY,
            value TEXT
          )"""
        )
      }
    }
  }
}
