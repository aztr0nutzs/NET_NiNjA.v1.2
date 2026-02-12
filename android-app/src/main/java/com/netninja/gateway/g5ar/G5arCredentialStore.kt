package com.netninja.gateway.g5ar

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class G5arCredentialStore(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context,
    "g5ar_credentials",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
  )

  fun save(username: String, password: String) {
    prefs.edit()
      .putString(KEY_USERNAME, username)
      .putString(KEY_PASSWORD, password)
      .apply()
  }

  fun load(): Pair<String, String>? {
    val user = prefs.getString(KEY_USERNAME, null)?.trim().orEmpty()
    val pass = prefs.getString(KEY_PASSWORD, null).orEmpty()
    if (user.isBlank() || pass.isBlank()) return null
    return user to pass
  }

  fun clear() {
    prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
  }

  companion object {
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
  }
}
