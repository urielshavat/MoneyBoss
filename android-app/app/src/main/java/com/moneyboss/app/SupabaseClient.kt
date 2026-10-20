package com.moneyboss.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class SupabaseClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun baseRequest(path: String): Request.Builder {
        return Request.Builder()
            .url("${Config.SUPABASE_URL}$path")
            .header("apikey", Config.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            .header("Content-Type", "application/json")
    }

    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val encoded = token.trim().uppercase()
        val req = baseRequest("/rest/v1/user_tokens?token=eq.$encoded&select=token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body?.string().orEmpty()
            val arr = JSONArray(body)
            arr.length() > 0
        }
    }

    suspend fun getBalance(token: String): Double = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("p_user_token", token.trim().uppercase()).toString()
        val req = baseRequest("/rest/v1/rpc/get_balance")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty().trim()
            if (!resp.isSuccessful) throw IllegalStateException("get_balance failed: ${resp.code} $body")
            parseScalarDouble(body)
        }
    }

    suspend fun insertExpense(
        token: String,
        amount: Double,
        name: String,
        category: String = Config.DEFAULT_CATEGORY,
        externalId: String? = null,
        createdAt: String = Instant.now().toString()
    ): Unit = withContext(Dispatchers.IO) {
        val row = JSONObject()
            .put("amount", amount)
            .put("name", name)
            .put("category", category)
            .put("user_token", token.trim().uppercase())
            .put("created_at", createdAt)
        if (externalId != null) {
            row.put("external_id", externalId)
        }
        val req = baseRequest("/rest/v1/transactions")
            .header("Prefer", "return=minimal")
            .post(row.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                // Unique violation on external_id — treat as already recorded
                if (resp.code == 409) return@withContext
                throw IllegalStateException("insert failed: ${resp.code} $err")
            }
        }
    }

    private fun parseScalarDouble(body: String): Double {
        body.removeSurrounding("\"").toDoubleOrNull()?.let { return it }
        if (body.startsWith("{")) {
            val obj = JSONObject(body)
            if (obj.has("get_balance")) return obj.getDouble("get_balance")
            obj.keys().asSequence().firstOrNull()?.let { key ->
                return obj.optDouble(key, 0.0)
            }
        }
        return 0.0
    }
}
