package com.netninja

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "netninja.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS devices(
        id TEXT PRIMARY KEY,
        ip TEXT,
        online INTEGER,
        lastSeen INTEGER,
        mac TEXT,
        hostname TEXT,
        vendor TEXT,
        os TEXT
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
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // No migrations yet. Bump version + migrate when schema changes.
  }
}
