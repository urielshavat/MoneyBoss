package com.moneyboss.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

class WalletNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = SupabaseClient()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in WALLET_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val combined = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString("\n")
        if (combined.isBlank()) return

        val parsed = WalletParser.parse(combined) ?: return
        val token = TokenStore(this).getToken() ?: return

        val externalId = "wallet_" + sha1(
            "${sbn.packageName}|${sbn.key}|${parsed.amount}|${parsed.merchant}"
        )

        scope.launch {
            try {
                api.insertExpense(
                    token = token,
                    amount = parsed.amount,
                    name = parsed.merchant,
                    externalId = externalId
                )
                notifyUser(parsed.merchant, parsed.amount)
                refreshWidgets()
                Log.i(TAG, "Recorded wallet expense: ${parsed.merchant} ${parsed.amount}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record wallet expense", e)
            }
        }
    }

    private fun notifyUser(merchant: String, amount: Double) {
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = "wallet_expenses"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "הוצאות Wallet", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val rounded = kotlin.math.round(amount).toInt()
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("נרשמה הוצאה")
            .setContentText("$merchant — ₪$rounded")
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, BalanceWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            BalanceWidgetProvider.requestUpdate(this, ids)
        }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "WalletListener"

        val WALLET_PACKAGES = setOf(
            "com.google.android.apps.walletnfcrel",
            "com.google.android.gms",
            "com.google.android.apps.nbu.paisa.user"
        )
    }
}

object WalletParser {
    // Examples:
    // "Paid ₪42.50 at Cafe X"
    // "תשלום של ₪50 אצל סופר יין"
    // "You spent $12.00 at Starbucks"
    // "₪36.90 · Aroma"
    private val amountPatterns = listOf(
        Regex("""(?:₪|NIS|ILS)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([\d]+(?:[.,]\d{1,2})?)\s*(?:₪|NIS|ILS)""", RegexOption.IGNORE_CASE),
        Regex("""(?:\$|USD)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    private val merchantPatterns = listOf(
        Regex("""(?:at|אצל|ב-|ב)\s+(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("""₪[\d.,]+\s*[·\-–|]\s*(.+)$""", RegexOption.MULTILINE),
        Regex("""Paid\s+.+?\s+at\s+(.+)$""", RegexOption.IGNORE_CASE)
    )

    data class Parsed(val amount: Double, val merchant: String)

    fun parse(raw: String): Parsed? {
        val amount = amountPatterns.firstNotNullOfOrNull { re ->
            re.find(raw)?.groupValues?.getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
        } ?: return null

        if (amount <= 0) return null

        var merchant = merchantPatterns.firstNotNullOfOrNull { re ->
            re.find(raw)?.groupValues?.getOrNull(1)?.trim()
        }

        if (merchant.isNullOrBlank()) {
            // Fallback: first non-amount line as merchant
            merchant = raw.lines()
                .map { it.trim() }
                .firstOrNull { line ->
                    line.isNotBlank() &&
                        !line.contains("₪") &&
                        !line.contains("Paid", ignoreCase = true) &&
                        !line.contains("תשלום")
                }
        }

        merchant = merchant
            ?.replace(Regex("""\s+"""), " ")
            ?.take(80)
            ?.ifBlank { null }
            ?: "Wallet"

        return Parsed(amount = amount, merchant = merchant)
    }
}
