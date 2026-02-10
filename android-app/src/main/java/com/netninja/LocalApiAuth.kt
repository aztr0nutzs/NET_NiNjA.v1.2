package com.netninja

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Shared-secret auth for the Android embedded localhost server.
 *
 * Threat model: other apps on the device (and any remote clients if the server is ever bound
 * to a non-loopback interface) should not be able to call `/api/v1/` endpoints without the secret.
 */
object LocalApiAuth {
  private const val PREFS_NAME = "netninja_local_api"
  private const val KEY_TOKEN = "api_token_v1"

  const val QUERY_PARAM = "token"
  const val HEADER_TOKEN = "X-NetNinja-Token"

  data class Rotation(val token: String, val previousValidUntilMs: Long?)

  @Volatile private var previousToken: String? = null
  @Volatile private var previousValidUntilMs: Long? = null

  private fun newToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
  }

  fun getOrCreateToken(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
    if (existing.length >= 24) return existing

    val token = newToken()
    prefs.edit().putString(KEY_TOKEN, token).apply()
    return token
  }

  fun validate(ctx: Context, providedRaw: String?): Boolean {
    val provided = providedRaw?.trim().orEmpty()
    if (provided.isBlank()) return false

    val current = getOrCreateToken(ctx)
    if (provided == current) return true

    val prev = previousToken
    val untilMs = previousValidUntilMs
    if (prev != null && untilMs != null && System.currentTimeMillis() <= untilMs && provided == prev) return true

    return false
  }

  fun rotate(ctx: Context, graceMs: Long = 5 * 60_000L): Rotation {
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val current = getOrCreateToken(ctx)

    val next = newToken()
    prefs.edit().putString(KEY_TOKEN, next).apply()

    if (graceMs > 0) {
      previousToken = current
      previousValidUntilMs = System.currentTimeMillis() + graceMs
    } else {
      previousToken = null
      previousValidUntilMs = null
    }

    return Rotation(token = next, previousValidUntilMs = previousValidUntilMs)
  }
}
