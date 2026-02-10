package com.netninja.network

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Retry logic for network operations with exponential backoff.
 * Addresses RISK-02: No Retry Logic for Network Operations
 */
class RetryPolicy(
  val maxAttempts: Int = 3,
  val initialDelayMs: Long = 100,
  val maxDelayMs: Long = 2000,
  val backoffMultiplier: Double = 2.0
) {

  /**
   * Determines if an exception is transient and worth retrying.
   */
  fun isTransient(throwable: Throwable): Boolean {
    return when (throwable) {
      is SocketTimeoutException -> true
      is IOException -> {
        // DNS timeout, connection refused, network unreachable
        val message = throwable.message?.lowercase() ?: ""
        message.contains("timeout") ||
          message.contains("connection refused") ||
          message.contains("network is unreachable") ||
          message.contains("no route to host")
      }
      else -> false
    }
  }

  /**
   * Execute a block with retry logic.
   */
  suspend fun <T> execute(
    operation: String,
    onRetry: ((attempt: Int, exception: Throwable) -> Unit)? = null,
    block: suspend () -> T
  ): Result<T> {
    var lastException: Throwable? = null
    var delayMs = initialDelayMs

    repeat(maxAttempts) { attempt ->
      try {
        return Result.success(block())
      } catch (e: Throwable) {
        lastException = e
        
        val isLastAttempt = attempt == maxAttempts - 1
        if (isLastAttempt || !isTransient(e)) {
          return Result.failure(e)
        }

        onRetry?.invoke(attempt + 1, e)
        
        if (delayMs > 0) {
          delay(delayMs)
        }
        delayMs = (delayMs * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
      }
    }

    return Result.failure(lastException ?: Exception("Retry failed"))
  }

  /**
   * Execute with retry, returning null on failure.
   */
  suspend fun <T> executeOrNull(
    operation: String,
    onRetry: ((attempt: Int, exception: Throwable) -> Unit)? = null,
    block: suspend () -> T
  ): T? {
    return execute(operation, onRetry, block).getOrNull()
  }
}
