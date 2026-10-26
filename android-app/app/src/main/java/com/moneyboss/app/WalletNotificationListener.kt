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

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener CONNECTED")
        notifyDebug("MoneyBoss מאזין", "שירות קריאת התראות מחובר")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName.orEmpty()
        // Ignore our own notifications (avoid loops)
        if (pkg == packageName) return

        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras?.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras?.getCharSequence("android.bigText")?.toString().orEmpty()
        val subText = extras?.getCharSequence("android.subText")?.toString().orEmpty()
        val infoText = extras?.getCharSequence("android.infoText")?.toString().orEmpty()
        val combined = listOf(title, text, bigText, subText, infoText)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        if (!isMonitoredPackage(pkg, combined)) return

        Log.i(TAG, "READ ok pkg=$pkg\n$combined")

        val parsed = WalletParser.parse(combined)
        val hasToken = TokenStore(this).getToken() != null

        // Debug ping: always, even when parse/token/insert fail
        val preview = combined.replace("\n", " | ").take(120)
        val parseStatus = if (parsed != null) {
            "parse OK: ${parsed.merchant} ₪${parsed.amount}"
        } else {
            "parse FAIL"
        }
        val tokenStatus = if (hasToken) "token OK" else "token MISSING"
        notifyDebug(
            title = "Wallet נקרא ✓",
            body = "$parseStatus · $tokenStatus\n$pkg\n$preview"
        )

        if (parsed == null) {
            Log.w(TAG, "parse failed for pkg=$pkg")
            return
        }

        val token = TokenStore(this).getToken()
        if (token == null) {
            Log.w(TAG, "no saved token — skip insert")
            return
        }

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
                Log.i(TAG, "Recorded: ${parsed.merchant} ${parsed.amount} ext=$externalId")
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
        if (lower.contains("cal.card") || lower.contains("calapp") || lower.endsWith(".cal")) return true
        // Play Services: only if text looks like a card transaction
        if (lower == "com.google.android.gms") {
            return looksLikeTransaction(combined)
        }
        return false
    }

    private fun looksLikeTransaction(combined: String): Boolean {
        return combined.contains("₪") ||
            combined.contains("בסך") ||
            combined.contains("עסקה") ||
            combined.contains("Paid", ignoreCase = true) ||
            combined.contains("בכרטיס")
    }

    private fun ensureDebugChannel(): String {
        val channelId = "wallet_debug"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "MoneyBoss Debug Wallet",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        return channelId
    }

    private fun notifyDebug(title: String, body: String) {
        val channelId = ensureDebugChannel()
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
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
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.apps.wallet",
            "com.google.android.apps.nbu.googlewallet"
            // com.google.android.gms handled separately — only if text looks like a transaction
        )
    }
}

object WalletParser {
    /**
     * Real Google Wallet (HE) example:
     *   title: vr BAKERY
     *   text:  עסקה בסך 127.00 ₪ בכרטיס CashCal Pro 2098..
     */
    private val amountPatterns = listOf(
        Regex("""בסך\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([\d]+(?:[.,]\d{1,2})?)\s*(?:₪|NIS|ILS|ש["״]?ח)""", RegexOption.IGNORE_CASE),
        Regex("""(?:₪|NIS|ILS)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:\$|USD)\s*([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Paid|spent|עסקה)\s*(?:of\s*)?([\d]+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    private val merchantPatterns = listOf(
        Regex("""(?:at|אצל)\s+(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
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

        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

        var merchant = lines.firstOrNull { line -> looksLikeMerchantTitle(line) }

        if (merchant.isNullOrBlank()) {
            merchant = merchantPatterns.firstNotNullOfOrNull { re ->
                re.find(raw)?.groupValues?.getOrNull(1)?.trim()
            }
        }

        if (merchant.isNullOrBlank()) {
            merchant = lines.firstOrNull { line ->
                !line.contains("₪") &&
                    !line.contains("Paid", ignoreCase = true) &&
                    !line.contains("תשלום") &&
                    !line.contains("עסקה") &&
                    !line.contains("בכרטיס")
            }
        }

        merchant = merchant
            ?.replace(Regex("""\s+"""), " ")
            ?.take(80)
            ?.ifBlank { null }
            ?: "Wallet"

        return Parsed(amount = amount, merchant = merchant)
    }

    private fun looksLikeMerchantTitle(line: String): Boolean {
        if (line.length < 2) return false
        if (line.contains("₪")) return false
        if (line.contains("עסקה")) return false
        if (line.contains("בכרטיס")) return false
        if (line.contains("בוצעה")) return false
        if (line.contains("Paid", ignoreCase = true)) return false
        if (line.contains("תשלום")) return false
        if (line == "Google Wallet" || line == "ארנק Google" || line == "Wallet") return false
        return true
    }
}
