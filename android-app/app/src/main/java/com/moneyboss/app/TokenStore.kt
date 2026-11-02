package com.moneyboss.app

import android.content.Context
import java.io.File

/**
 * File-based token store so MainActivity and the Wallet listener
 * (separate process) both see the same token.
 */
class TokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenFile = File(appContext.filesDir, TOKEN_FILE)

    fun getToken(): String? {
        // Prefer file (cross-process), fall back to prefs
        try {
            if (tokenFile.exists()) {
                val t = tokenFile.readText().trim().uppercase()
                if (t.length == Config.TOKEN_LEN) return t
            }
        } catch (_: Exception) {
        }
        val t = prefs.getString(KEY_TOKEN, null)?.trim()?.uppercase().orEmpty()
        return t.takeIf { it.length == Config.TOKEN_LEN }
    }

    fun saveToken(token: String) {
        val normalized = token.trim().uppercase()
        prefs.edit().putString(KEY_TOKEN, normalized).commit()
        try {
            tokenFile.writeText(normalized)
        } catch (_: Exception) {
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).commit()
        try {
            if (tokenFile.exists()) tokenFile.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS = "moneyboss_prefs"
        private const val KEY_TOKEN = "user_token"
        private const val TOKEN_FILE = "user_token.txt"
    }
}
