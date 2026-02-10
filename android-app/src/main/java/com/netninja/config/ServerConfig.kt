package com.netninja.config

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Centralized configuration for AndroidLocalServer.
 * All magic numbers and hardcoded timeouts are defined here.
 * Values can be overridden via SharedPreferences for runtime tuning.
 */
class ServerConfig(private val ctx: Context) {
  
  private val prefs: SharedPreferences by lazy {
    ctx.getSharedPreferences("netninja_server_config", Context.MODE_PRIVATE)
  }

  // Network scan configuration
  val scanTimeoutMs: Int
    get() = prefs.getInt("scan_timeout_ms", 300)
  
  val scanConcurrency: Int
    get() = prefs.getInt("scan_concurrency", 48)
  
  val maxScanTargets: Int
    get() = prefs.getInt("max_scan_targets", 4096)
  
  val minScanIntervalMs: Long
    get() = prefs.getLong("min_scan_interval_ms", TimeUnit.MINUTES.toMillis(1))

  // Port scanning configuration
  val portScanTimeoutMs: Int
    get() = prefs.getInt("port_scan_timeout_ms", 250)
  
  val portScanRetries: Int
    get() = prefs.getInt("port_scan_retries", 2)
  
  val commonPorts: List<Int>
    get() = listOf(22, 80, 443, 445, 3389, 5555, 161)

  // Reachability configuration
  val reachabilityTimeoutMs: Int
    get() = prefs.getInt("reachability_timeout_ms", 350)
  
  val reachabilityRetries: Int
    get() = prefs.getInt("reachability_retries", 2)
  
  val reachabilityProbePorts: IntArray
    get() = intArrayOf(80, 443, 22)

  // HTTP action configuration
  val httpConnectTimeoutMs: Int
    get() = prefs.getInt("http_connect_timeout_ms", 800)
  
  val httpReadTimeoutMs: Int
    get() = prefs.getInt("http_read_timeout_ms", 800)

  // Retry configuration
  val retryInitialDelayMs: Long
    get() = prefs.getLong("retry_initial_delay_ms", 40)
  
  val retryMaxDelayMs: Long
    get() = prefs.getLong("retry_max_delay_ms", 200)
  
  val retryMaxAttempts: Int
    get() = prefs.getInt("retry_max_attempts", 2)

  // Database configuration
  val maxEventsPerDevice: Int
    get() = prefs.getInt("max_events_per_device", 4000)

  // WebSocket configuration
  val wsPingPeriodSeconds: Long
    get() = prefs.getLong("ws_ping_period_seconds", 15)
  
  val wsTimeoutSeconds: Long
    get() = prefs.getLong("ws_timeout_seconds", 30)
  
  val wsMaxFrameSize: Long
    get() = prefs.getLong("ws_max_frame_size", 1_048_576)

  // Scheduler configuration
  val schedulerIntervalMs: Long
    get() = prefs.getLong("scheduler_interval_ms", TimeUnit.SECONDS.toMillis(30))

  // ARP warmup configuration
  val arpWarmupTimeoutMs: Int
    get() = prefs.getInt("arp_warmup_timeout_ms", 300)
  
  val arpWarmupBatchSize: Int
    get() = prefs.getInt("arp_warmup_batch_size", 64)

  // Logging configuration
  val logRetentionDays: Int
    get() = prefs.getInt("log_retention_days", 7)
  
  val maxLogEntries: Int
    get() = prefs.getInt("max_log_entries", 10000)

  // Server configuration
  val serverHost: String
    get() = prefs.getString("server_host", "127.0.0.1") ?: "127.0.0.1"
  
  val serverPort: Int
    get() = prefs.getInt("server_port", 8787)
  
  val engineRestartDelayMs: Long
    get() = prefs.getLong("engine_restart_delay_ms", 3000)

  /**
   * Update a configuration value at runtime.
   */
  fun set(key: String, value: Any) {
    prefs.edit().apply {
      when (value) {
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        else -> throw IllegalArgumentException("Unsupported config type: ${value::class.java}")
      }
      apply()
    }
  }

  /**
   * Reset all configuration to defaults.
   */
  fun resetToDefaults() {
    prefs.edit().clear().apply()
  }

  companion object {
    // Reasons for magic numbers:
    
    // scan_concurrency = 48: Balance between speed and resource usage.
    // Too high causes socket exhaustion, too low makes scans slow.
    
    // scan_timeout_ms = 300: Typical LAN response time is <100ms.
    // 300ms allows for congested networks without excessive wait.
    
    // min_scan_interval_ms = 60000: Prevents battery drain from
    // excessive scanning. One scan per minute is reasonable for monitoring.
    
    // port_scan_timeout_ms = 250: Port probes should be faster than
    // full reachability checks. 250ms is sufficient for LAN.
    
    // max_scan_targets = 4096: Prevents memory exhaustion on large
    // subnets. /20 networks have 4096 hosts.
  }
}
