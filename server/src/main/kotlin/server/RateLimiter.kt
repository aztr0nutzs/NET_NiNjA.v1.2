package server

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple token-bucket rate limiter keyed by an arbitrary string.
 *
 * This is intentionally small and dependency-free. It is "good enough" for:
 * - throttling repeated unauthorized requests
 * - limiting sensitive endpoints (token rotation)
 */
class RateLimiter(
  private val capacity: Double,
  private val refillTokensPerMs: Double
) {
  private data class Bucket(var tokens: Double, var lastRefillMs: Long)
  private val buckets = ConcurrentHashMap<String, Bucket>()

  fun tryConsume(key: String, tokens: Double = 1.0, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (tokens <= 0.0) return true
    val b = buckets.computeIfAbsent(key) { Bucket(capacity, nowMs) }
    synchronized(b) {
      refill(b, nowMs)
      return if (b.tokens >= tokens) {
        b.tokens -= tokens
        true
      } else {
        false
      }
    }
  }

  private fun refill(b: Bucket, nowMs: Long) {
    val elapsed = (nowMs - b.lastRefillMs).coerceAtLeast(0)
    if (elapsed <= 0) return
    b.tokens = (b.tokens + elapsed * refillTokensPerMs).coerceAtMost(capacity)
    b.lastRefillMs = nowMs
  }
}

