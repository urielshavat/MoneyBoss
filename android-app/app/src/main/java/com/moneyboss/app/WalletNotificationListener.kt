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
        val pkg = sbn.packageName.orEmpty()
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val combined = listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString("\n")

        if (!isMonitoredPackage(pkg, combined)) return
        if (combined.isBlank()) return

        Log.i(TAG, "READ ok pkg=$pkg\n$combined")

        val parsed = WalletParser.parse(combined)
        val hasToken = TokenStore(this).getToken() != null
        val parseStatus = if (parsed != null) {
            "parse OK: ${parsed.merchant} ${parsed.amount}"
        } else {
            "parse FAIL"
        }
        val tokenStatus = if (hasToken) "token OK" else "token MISSING"
        notifyDebug(
            "Wallet read OK",
            "$parseStatus | $tokenStatus\n$pkg\n${combined.replace("\n", " | ").take(120)}"
        )

        if (parsed == null) return
        val token = TokenStore(this).getToken() ?: return

        val externalId = "wallet_" + sha1(
            "${pkg}|${sbn.key}|${parsed.amount}|${parsed.merchant}"
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
                Log.i(TAG, "Recorded: ${parsed.merchant} ${parsed.amount}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record wallet expense", e)
                notifyDebug("Wallet insert FAIL", e.message ?: "error")
            }
        }
    }

    private fun isMonitoredPackage(pkg: String, combined: String): Boolean {
        if (pkg in WALLET_PACKAGES) return true
        val lower = pkg.lowercase()
        if (lower.contains("wallet")) return true
        if (lower.contains("google.android.apps.nbu")) return true
        if (lower == "com.google.android.gms") {
            return combined.contains("\u20AA") ||
                combined.contains("\u05D1\u05E1\u05DA") ||
                combined.contains("\u05E2\u05E1\u05E7\u05D4") ||
                combined.contains("Paid", ignoreCase = true)
        }
        return false
    }

    private fun notifyDebug(title: String, body: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channelId = "wallet_debug"
            nm.createNotificationChannel(
                NotificationChannel(channelId, "MoneyBoss Debug", NotificationManager.IMPORTANCE_HIGH)
            )
            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "notifyDebug failed", e)
        }
    }

    private fun notifyUser(merchant: String, amount: Double) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channelId = "wallet_expenses"
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Wallet expenses", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val rounded = kotlin.math.round(amount).toInt()
            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Expense saved")
                .setContentText("$merchant - \u20AA$rounded")
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "notifyUser failed", e)
        }
    }

    private fun refreshWidgets() {
        try {
            val mgr = AppWidgetManager.getInstance(this)
            val ids = mgr.getAppWidgetIds(ComponentName(this, BalanceWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                BalanceWidgetProvider.requestUpdate(this, ids)
            }
        } catch (_: Exception) {
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
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.wallet",
            "com.google.android.apps.nbu.googlewallet"
        )
    }
}

object WalletParser {
    // Real HE Wallet: title "vr BAKERY", text "עסקה בסך 127.00 ₪ בכרטיס..."
    private val amountPatterns = listOf(
        Regex("""\u05D1\u05E1\u05DA\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([\d]+(?:[.,]\d{1,2})?)\s*(?:\u20AA|NIS|ILS)""", RegexOption.IGNORE_CASE),
        Regex("""(?:\u20AA|NIS|ILS)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:\$|USD)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    data class Parsed(val amount: Double, val merchant: String)

    fun parse(raw: String): Parsed? {
        val amount = amountPatterns.firstNotNullOfOrNull { re ->
            re.find(raw)?.groupValues?.getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
        } ?: return null
        if (amount <= 0) return null

        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        var merchant = lines.firstOrNull { line ->
            line.length >= 2 &&
                !line.contains("\u20AA") &&
                !line.contains("\u05E2\u05E1\u05E7\u05D4") &&
                !line.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") &&
                !line.contains("Paid", ignoreCase = true) &&
                line != "Google Wallet" &&
                line != "Wallet"
        }
        if (merchant.isNullOrBlank()) {
            merchant = lines.firstOrNull {
                !it.contains("\u20AA") && !it.contains("Paid", ignoreCase = true)
            }
        }
        merchant = merchant?.replace(Regex("""\s+"""), " ")?.take(80)?.ifBlank { null } ?: "Wallet"
        return Parsed(amount = amount, merchant = merchant)
    }
}
