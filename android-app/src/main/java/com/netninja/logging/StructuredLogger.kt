package com.netninja.logging

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured logging with JSON format, log levels, and rotation.
 * Addresses GAP-05: No Logging Strategy
 */
class StructuredLogger(private val ctx: Context, private val tag: String = "NetNinja") {
  
  enum class Level(val priority: Int) {
    DEBUG(Log.DEBUG),
    INFO(Log.INFO),
    WARN(Log.WARN),
    ERROR(Log.ERROR)
  }

  @Serializable
  data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val exception: String? = null
  )

  private val json = Json { ignoreUnknownKeys = true }
  private val memoryBuffer = ConcurrentLinkedQueue<LogEntry>()
  private val maxBufferSize = 1000
  private val bytesWritten = AtomicLong(0)
  private val maxLogFileSize = 5 * 1024 * 1024 // 5MB
  
  private var currentLevel = Level.INFO
  private var enableFileLogging = true

  fun setLevel(level: Level) {
    currentLevel = level
  }

  fun enableFileLogging(enable: Boolean) {
    enableFileLogging = enable
  }

  fun debug(message: String, fields: Map<String, Any?> = emptyMap(), exception: Throwable? = null) {
    log(Level.DEBUG, message, fields, exception)
  }

  fun info(message: String, fields: Map<String, Any?> = emptyMap(), exception: Throwable? = null) {
    log(Level.INFO, message, fields, exception)
  }

  fun warn(message: String, fields: Map<String, Any?> = emptyMap(), exception: Throwable? = null) {
    log(Level.WARN, message, fields, exception)
  }

  fun error(message: String, fields: Map<String, Any?> = emptyMap(), exception: Throwable? = null) {
    log(Level.ERROR, message, fields, exception)
  }

  private fun log(level: Level, message: String, fields: Map<String, Any?>, exception: Throwable?) {
    if (level.priority < currentLevel.priority) return

    val entry = LogEntry(
      timestamp = System.currentTimeMillis(),
      level = level.name,
      tag = tag,
      message = message,
      fields = fields.mapValues { it.value?.toString() ?: "null" },
      exception = exception?.stackTraceToString()
    )

    // Logcat
    val logMessage = buildString {
      append(message)
      if (fields.isNotEmpty()) {
        append(" ")
        append(fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
      }
    }
    when (level) {
      Level.DEBUG -> Log.d(tag, logMessage, exception)
      Level.INFO -> Log.i(tag, logMessage, exception)
      Level.WARN -> Log.w(tag, logMessage, exception)
      Level.ERROR -> Log.e(tag, logMessage, exception)
    }

    // Memory buffer
    memoryBuffer.add(entry)
    while (memoryBuffer.size > maxBufferSize) {
      memoryBuffer.poll()
    }

    // File logging
    if (enableFileLogging) {
      writeToFile(entry)
    }
  }

  private fun writeToFile(entry: LogEntry) {
    try {
      val logDir = File(ctx.filesDir, "logs").apply { mkdirs() }
      val logFile = File(logDir, "netninja.log")
      
      // Rotate if needed
      if (logFile.exists() && bytesWritten.get() > maxLogFileSize) {
        rotateLogFile(logFile)
        bytesWritten.set(0)
      }

      val jsonLine = json.encodeToString(entry) + "\n"
      logFile.appendText(jsonLine)
      bytesWritten.addAndGet(jsonLine.length.toLong())
    } catch (e: Exception) {
      Log.e(tag, "Failed to write log to file", e)
    }
  }

  private fun rotateLogFile(currentFile: File) {
    val timestamp = System.currentTimeMillis()
    val rotatedFile = File(currentFile.parent, "netninja-$timestamp.log")
    currentFile.renameTo(rotatedFile)
    
    // Clean old logs (keep last 7 days)
    val logDir = currentFile.parentFile ?: return
    val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
    logDir.listFiles()?.forEach { file ->
      if (file.name.startsWith("netninja-") && file.lastModified() < cutoff) {
        file.delete()
      }
    }
  }

  fun getRecentLogs(count: Int = 100): List<LogEntry> {
    return memoryBuffer.toList().takeLast(count)
  }

  fun clearLogs() {
    memoryBuffer.clear()
  }
}
