package com.netninja.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Real speed-test engine backed by OkHttp streaming requests.
 *
 * Runs a full **ping → download → upload** cycle, reporting incremental
 * progress through the supplied [ProgressCallback].  All blocking I/O
 * is dispatched on [Dispatchers.IO]; the caller may launch from any dispatcher.
 *
 * The engine checks [abortFlag] between every significant I/O step so it
 * cooperates with the existing `/api/v1/speedtest/abort` mechanism.
 */
class SpeedTestEngine(
  private val client: OkHttpClient = defaultClient(),
  private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 100, maxDelayMs = 400)
) {

  /** Measurement target URLs — where bytes are sourced / sunk. */
  data class Targets(
    val pingUrl: String = DEFAULT_PING_URL,
    val downloadUrl: String = DEFAULT_DOWNLOAD_URL,
    val uploadUrl: String = DEFAULT_UPLOAD_URL,
    val downloadBytes: Long = 25_000_000L,
    val uploadBytes: Long = 12_000_000L,
    val pingRounds: Int = 8
  )

  /** Aggregate result after a complete test run. */
  data class Result(
    val pingMs: Double,
    val jitterMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val lossPct: Double,
    val bufferbloat: String
  )

  /** Incremental progress callback – invoked from [Dispatchers.IO]. */
  fun interface ProgressCallback {
    fun onUpdate(
      phase: String,
      progress: Int,
      pingMs: Double?,
      jitterMs: Double?,
      downloadMbps: Double?,
      uploadMbps: Double?
    )
  }

  /**
   * Run the full speed-test cycle.
   *
   * @return [Result] with final measurements
   * @throws SpeedTestAbortedException if abort was requested during the test
   * @throws IOException on unrecoverable network failure
   */
  suspend fun execute(
    targets: Targets = Targets(),
    abortFlag: () -> Boolean,
    onProgress: ProgressCallback
  ): Result = withContext(Dispatchers.IO) {

    // ── Phase 1: Ping ───────────────────────────────────────────────
    onProgress.onUpdate("PING", 0, null, null, null, null)
    val pingResult = measurePing(targets.pingUrl, targets.pingRounds, abortFlag) { round, total, latency, jitter ->
      val pct = ((round.toDouble() / total) * 100).toInt().coerceIn(0, 100)
      onProgress.onUpdate("PING", pct, latency, jitter, null, null)
    }
    checkAbort(abortFlag)

    // ── Phase 2: Download ───────────────────────────────────────────
    onProgress.onUpdate("DOWNLOAD", 0, pingResult.avgMs, pingResult.jitterMs, null, null)
    val dlMbps = measureDownload(
      targets.downloadUrl,
      targets.downloadBytes,
      abortFlag
    ) { pct, currentMbps ->
      onProgress.onUpdate("DOWNLOAD", pct, pingResult.avgMs, pingResult.jitterMs, currentMbps, null)
    }
    checkAbort(abortFlag)

    // ── Phase 3: Upload ─────────────────────────────────────────────
    onProgress.onUpdate("UPLOAD", 0, pingResult.avgMs, pingResult.jitterMs, dlMbps, null)
    val ulMbps = measureUpload(
      targets.uploadUrl,
      targets.uploadBytes,
      abortFlag
    ) { pct, currentMbps ->
      onProgress.onUpdate("UPLOAD", pct, pingResult.avgMs, pingResult.jitterMs, dlMbps, currentMbps)
    }
    checkAbort(abortFlag)

    // ── Derive secondary metrics ────────────────────────────────────
    val lossPct = estimateLoss(pingResult.jitterMs, pingResult.samples)
    val bufferbloat = when {
      pingResult.jitterMs < 6.0 -> "low"
      pingResult.jitterMs < 14.0 -> "med"
      else -> "high"
    }

    val result = Result(
      pingMs = pingResult.avgMs,
      jitterMs = pingResult.jitterMs,
      downloadMbps = dlMbps,
      uploadMbps = ulMbps,
      lossPct = lossPct,
      bufferbloat = bufferbloat
    )
    onProgress.onUpdate("COMPLETE", 100, result.pingMs, result.jitterMs, result.downloadMbps, result.uploadMbps)
    result
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Ping
  // ═══════════════════════════════════════════════════════════════════

  private data class PingResult(val avgMs: Double, val jitterMs: Double, val samples: List<Double>)

  private suspend fun measurePing(
    url: String,
    rounds: Int,
    abortFlag: () -> Boolean,
    onRound: (round: Int, total: Int, latencyMs: Double, jitterMs: Double) -> Unit
  ): PingResult {
    val samples = mutableListOf<Double>()

    for (i in 1..rounds) {
      checkAbort(abortFlag)
      // Cache-busting via query param
      val bustUrl = if (url.contains("?")) "$url&_t=${System.nanoTime()}" else "$url?_t=${System.nanoTime()}"
      val request = Request.Builder().url(bustUrl).head().build()
      val t0 = System.nanoTime()
      try {
        retryPolicy.execute("speedtest:ping:$i") {
          client.newCall(request).execute().use { response ->
            response.body?.bytes()  // drain body if any
          }
        }.getOrThrow()
        val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
        samples.add(latencyMs)
      } catch (e: IOException) {
        Log.w(TAG, "Ping round $i failed: ${e.message}")
        // Record a high sentinel value so jitter reflects the loss
        samples.add(samples.lastOrNull()?.times(2.0) ?: 500.0)
      }
      val avg = samples.average()
      val jitter = if (samples.size >= 2) stddev(samples) else 0.0
      onRound(i, rounds, avg, jitter)
      delay(50)
    }
    return PingResult(
      avgMs = samples.average(),
      jitterMs = if (samples.size >= 2) stddev(samples) else 0.0,
      samples = samples
    )
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Download — stream a GET response body and time throughput
  // ═══════════════════════════════════════════════════════════════════

  private fun measureDownload(
    url: String,
    bytes: Long,
    abortFlag: () -> Boolean,
    onProgress: (pct: Int, currentMbps: Double) -> Unit
  ): Double {
    val fullUrl = if (url.contains("?")) "$url&bytes=$bytes" else "$url?bytes=$bytes"
    val request = Request.Builder()
      .url(fullUrl)
      .header("Cache-Control", "no-store")
      .build()

    val t0 = System.nanoTime()
    var received = 0L
    var lastReportNanos = t0

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Download request failed: HTTP ${response.code}")
      val stream = response.body?.byteStream() ?: throw IOException("Empty download response body")
      val buffer = ByteArray(64 * 1024)

      while (true) {
        checkAbort(abortFlag)
        val read = stream.read(buffer)
        if (read <= 0) break
        received += read

        val now = System.nanoTime()
        val elapsedSec = (now - t0) / 1_000_000_000.0
        // Throttle progress reports to ~60 ms intervals
        if (elapsedSec > 0 && (now - lastReportNanos) > 60_000_000L) {
          val mbps = (received * 8.0) / (elapsedSec * 1_000_000.0)
          val pct = if (bytes > 0) ((received.toDouble() / bytes) * 100).toInt().coerceIn(0, 100) else 50
          onProgress(pct, mbps)
          lastReportNanos = now
        }
      }
    }

    val elapsedSec = (System.nanoTime() - t0) / 1_000_000_000.0
    return if (elapsedSec > 0) (received * 8.0) / (elapsedSec * 1_000_000.0) else 0.0
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Upload — stream a POST request body and time throughput
  // ═══════════════════════════════════════════════════════════════════

  private fun measureUpload(
    url: String,
    bytes: Long,
    abortFlag: () -> Boolean,
    onProgress: (pct: Int, currentMbps: Double) -> Unit
  ): Double {
    val startNanos = longArrayOf(0L)
    val sentBytes = longArrayOf(0L)
    val lastReportNanos = longArrayOf(0L)

    val body = object : RequestBody() {
      override fun contentType() = OCTET_STREAM
      override fun contentLength() = bytes

      override fun writeTo(sink: BufferedSink) {
        startNanos[0] = System.nanoTime()
        lastReportNanos[0] = startNanos[0]
        val chunk = ByteArray(64 * 1024)
        var sent = 0L
        while (sent < bytes) {
          if (abortFlag()) throw SpeedTestAbortedException()
          val n = minOf(chunk.size.toLong(), bytes - sent).toInt()
          sink.write(chunk, 0, n)
          sent += n
          sentBytes[0] = sent

          val now = System.nanoTime()
          val elapsedSec = (now - startNanos[0]) / 1_000_000_000.0
          if (elapsedSec > 0 && (now - lastReportNanos[0]) > 60_000_000L) {
            val mbps = (sent * 8.0) / (elapsedSec * 1_000_000.0)
            val pct = ((sent.toDouble() / bytes) * 100).toInt().coerceIn(0, 100)
            onProgress(pct, mbps)
            lastReportNanos[0] = now
          }
        }
        sink.flush()
      }
    }

    val request = Request.Builder()
      .url(url)
      .post(body)
      .build()

    client.newCall(request).execute().use { response ->
      response.body?.bytes()  // drain response
    }

    val elapsedSec = (System.nanoTime() - startNanos[0]) / 1_000_000_000.0
    val sent = sentBytes[0]
    return if (elapsedSec > 0) (sent * 8.0) / (elapsedSec * 1_000_000.0) else 0.0
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════

  private fun stddev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    return sqrt(variance)
  }

  /** Heuristic: high jitter / spread → estimated packet loss. */
  private fun estimateLoss(jitterMs: Double, samples: List<Double>): Double {
    if (samples.isEmpty()) return 0.0
    val maxPing = samples.maxOrNull() ?: return 0.0
    val avgPing = samples.average()
    if (avgPing <= 0) return 0.0
    val ratio = (maxPing - avgPing) / avgPing
    return (ratio * 2.0).coerceIn(0.0, 15.0)
  }

  private fun checkAbort(flag: () -> Boolean) {
    if (flag()) throw SpeedTestAbortedException()
  }

  /** Thrown when the test is cancelled via the abort flag. */
  class SpeedTestAbortedException : IOException("Speed test aborted")

  companion object {
    private const val TAG = "SpeedTestEngine"
    private val OCTET_STREAM = "application/octet-stream".toMediaType()

    //  Cloudflare speed-test endpoints (public, well-known)
    const val DEFAULT_PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
    const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"

    /** Build targets for the local Ktor loopback server. */
    fun loopbackTargets(port: Int): Targets = Targets(
      pingUrl = "http://127.0.0.1:$port/api/v1/speedtest/ping",
      downloadUrl = "http://127.0.0.1:$port/api/v1/speedtest/download",
      uploadUrl = "http://127.0.0.1:$port/api/v1/speedtest/upload"
    )

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }
}
