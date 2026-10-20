package com.moneyboss.app

import android.content.Context

class TokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getToken(): String? {
        val t = prefs.getString(KEY_TOKEN, null)?.trim()?.uppercase().orEmpty()
        return t.takeIf { it.length == Config.TOKEN_LEN }
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim().uppercase()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREFS = "moneyboss_prefs"
        private const val KEY_TOKEN = "user_token"
    }
}
