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

  fun getOrCreateToken(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
    if (existing.length >= 24) return existing

    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    prefs.edit().putString(KEY_TOKEN, token).apply()
    return token
  }
}
