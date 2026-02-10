package com.netninja

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple token-bucket limiter.
 *
 * Used to throttle abusive clients (e.g., repeated unauthorized attempts) without adding heavy deps.
 */
class RateLimiter(
  private val capacity: Double,
  private val refillTokensPerMs: Double
) {
  private data class Bucket(var tokens: Double, var lastRefillMs: Long)

  private val buckets = ConcurrentHashMap<String, Bucket>()

  fun tryConsume(key: String, tokens: Double = 1.0, nowMs: Long = System.currentTimeMillis()): Boolean {
    require(tokens > 0.0) { "tokens must be > 0" }
    val bucket = buckets.computeIfAbsent(key) { Bucket(capacity, nowMs) }
    synchronized(bucket) {
      val elapsed = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L)
      if (elapsed > 0L) {
        val refill = elapsed.toDouble() * refillTokensPerMs
        bucket.tokens = (bucket.tokens + refill).coerceAtMost(capacity)
        bucket.lastRefillMs = nowMs
      }
      if (bucket.tokens + 1e-9 < tokens) return false
      bucket.tokens -= tokens
      return true
    }
  }
}

