package server

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared-secret auth for the desktop/server runtime.
 *
 * - If `NET_NINJA_TOKEN` is set, it is used as the active token.
 * - Otherwise, a token is loaded from a token file next to the DB (or created and persisted).
 * - Rotation keeps the previous token valid for a short grace period (in-memory only).
 */
object ServerApiAuth {
  const val QUERY_PARAM = "token"
  const val HEADER_TOKEN = "X-NetNinja-Token"

  private data class TokenState(
    val current: String,
    val previous: String? = null,
    val previousValidUntilMs: Long = 0L
  )

  private val state = AtomicReference<TokenState?>(null)

  fun currentTokenOrNull(): String? = state.get()?.current

  fun loadOrCreate(dbPath: String, envToken: String?): String {
    val trimmedEnv = envToken?.trim()?.takeIf { it.isNotBlank() }
    if (!trimmedEnv.isNullOrBlank()) {
      state.set(TokenState(current = trimmedEnv))
      return trimmedEnv
    }

    val file = tokenFileForDb(dbPath)
    val existing = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
    if (existing.length >= 24) {
      state.set(TokenState(current = existing))
      return existing
    }

    val created = generateToken()
    state.set(TokenState(current = created))
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(created, Charsets.UTF_8)
    }
    return created
  }

  fun validate(provided: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
    val p = provided?.trim().orEmpty()
    if (p.isBlank()) return false
    val s = state.get() ?: return false
    if (p == s.current) return true
    val prev = s.previous
    return prev != null && p == prev && nowMs <= s.previousValidUntilMs
  }

  fun rotate(dbPath: String, graceMs: Long = 5 * 60_000L): RotationResult {
    val now = System.currentTimeMillis()
    val current = state.get()?.current ?: loadOrCreate(dbPath, envToken = null)
    val next = generateToken()
    val validUntil = now + graceMs.coerceAtLeast(0)

    state.set(TokenState(current = next, previous = current, previousValidUntilMs = validUntil))
    runCatching {
      val file = tokenFileForDb(dbPath)
      file.parentFile?.mkdirs()
      file.writeText(next, Charsets.UTF_8)
    }
    return RotationResult(token = next, previousValidUntilMs = validUntil)
  }

  data class RotationResult(val token: String, val previousValidUntilMs: Long)

  private fun tokenFileForDb(dbPath: String): File {
    val db = File(dbPath).absoluteFile
    val dir = db.parentFile ?: File(".")
    return File(dir, "netninja.token")
  }

  private fun generateToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}

