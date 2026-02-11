package server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
  @Test
  fun allowsUpToCapacityThenRejects() {
    val limiter = RateLimiter(capacity = 2.0, refillTokensPerMs = 0.0)
    val now = 1_000L

    assertTrue(limiter.tryConsume("ip:1", nowMs = now))
    assertTrue(limiter.tryConsume("ip:1", nowMs = now))
    assertFalse(limiter.tryConsume("ip:1", nowMs = now))
  }

  @Test
  fun refillsBucketOverTime() {
    val limiter = RateLimiter(capacity = 2.0, refillTokensPerMs = 0.001) // 1 token / second
    val start = 1_000L

    assertTrue(limiter.tryConsume("ip:2", nowMs = start))
    assertTrue(limiter.tryConsume("ip:2", nowMs = start))
    assertFalse(limiter.tryConsume("ip:2", nowMs = start))

    assertFalse(limiter.tryConsume("ip:2", nowMs = start + 500))
    assertTrue(limiter.tryConsume("ip:2", nowMs = start + 1_000))
  }
}
