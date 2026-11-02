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
import java.io.File
import java.security.MessageDigest

class WalletNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = SupabaseClient()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener CONNECTED")
        try {
            File(filesDir, "listener_connected.txt").writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName.orEmpty()
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val subText = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val combined = listOf(title, text, bigText, subText)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        if (!isMonitoredPackage(pkg, combined)) return
        if (combined.isBlank()) return

        Log.i(TAG, "READ ok pkg=$pkg\n$combined")

        val preview = combined.replace("\n", " | ").take(140)
        val parsed = WalletParser.parse(combined)
        if (parsed == null) {
            notifyDebug("Wallet parse FAIL", "$pkg\n$preview")
            return
        }

        val token = TokenStore(this).getToken()
        if (token == null) {
            notifyDebug(
                "Wallet token MISSING",
                "parse OK: ${parsed.merchant} ${parsed.amount}\n$pkg"
            )
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
                Log.i(TAG, "Recorded: ${parsed.merchant} ${parsed.amount}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record wallet expense", e)
                notifyDebug(
                    "Wallet insert FAIL",
                    "${e.message}\n${parsed.merchant} ${parsed.amount}"
                )
            }
        }
    }

    private fun isMonitoredPackage(pkg: String, combined: String): Boolean {
        if (pkg in WALLET_PACKAGES) return true
        val lower = pkg.lowercase()
        if (lower.contains("wallet")) return true
        if (lower.contains("google.android.apps.nbu")) return true
        if (lower == "com.google.android.gms") {
            return looksLikeTransaction(combined)
        }
        return false
    }

    private fun looksLikeTransaction(combined: String): Boolean {
        val t = combined.uppercase()
        return combined.contains("\u20AA") ||
            t.contains("NIS") ||
            t.contains("ILS") ||
            combined.contains("\u05D1\u05E1\u05DA") ||
            combined.contains("\u05E2\u05E1\u05E7\u05D4") ||
            combined.contains("Paid", ignoreCase = true) ||
            combined.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1")
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
                .setPriority(NotificationCompat.PRIORITY_HIGH)
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
    /**
     * Formats seen on device:
     * - "vr BAKERY" + "עסקה בסך 127.00 ₪ בכרטיס..."
     * - "MACABI TIVEE | ₪ 499.35 ..."
     * - "NETO HISACHON" + "עסקה בסך 300.27 NIS בכרטיס ... 2098••"
     */
    data class Parsed(val amount: Double, val merchant: String)

    fun parse(raw: String): Parsed? {
        val text = normalize(raw)
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null
        return Parsed(amount = amount, merchant = extractMerchant(text))
    }

    private fun normalize(raw: String): String {
        return raw
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .replace(Regex("[\\u00A0\\u202F\\u2007\\u2009\\u200A\\u200B]"), " ")
            // Normalize spaced currency letters: "N I S" / "n i s"
            .replace(Regex("""(?i)\bN\s*I\s*S\b"""), "NIS")
            .replace(Regex("""(?i)\bI\s*L\s*S\b"""), "ILS")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractAmount(text: String): Double? {
        val patterns = listOf(
            // "בסך 300.27" (before NIS/₪)
            Regex("""\u05D1\u05E1\u05DA\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // "300.27 NIS" / "300.27 ₪" / "300.27ILS"
            Regex("""(\d+(?:[.,]\d{1,2})?)\s*(?:NIS|ILS|\u20AA|\$)""", RegexOption.IGNORE_CASE),
            // "NIS 300.27" / "₪ 300.27"
            Regex("""(?:NIS|ILS|\u20AA|\$)\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // "| 300.27"
            Regex("""\|\s*(?:NIS|ILS|\u20AA|\$)?\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // Fallback: decimal money amount
            Regex("""(?<![\d.,])(\d+[.,]\d{2})(?![\d])""")
        )

        for (re in patterns) {
            val m = re.find(text) ?: continue
            val rawNum = m.groupValues.getOrNull(1) ?: continue
            val value = parseNumber(rawNum) ?: continue
            if (!rawNum.contains('.') && !rawNum.contains(',') && value in 1000.0..9999.0) {
                val idx = m.range.first
                val window = text.substring(maxOf(0, idx - 24), minOf(text.length, idx + 36))
                if (window.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") ||
                    window.contains("CashCal", ignoreCase = true) ||
                    window.contains("••") ||
                    window.contains("**")
                ) {
                    continue
                }
            }
            return value
        }
        return null
    }

    private fun parseNumber(raw: String): Double? {
        return raw.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()
    }

    private fun extractMerchant(text: String): String {
        val pipe = text.indexOf('|')
        if (pipe > 0) {
            val before = text.substring(0, pipe).trim()
            if (before.length >= 2) return before.take(80)
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fromLine = lines.firstOrNull { line ->
            val upper = line.uppercase()
            line.length >= 2 &&
                !line.contains('\u20AA') &&
                !upper.contains("NIS") &&
                !upper.contains("ILS") &&
                !Regex("""\d+[.,]\d{2}""").containsMatchIn(line) &&
                !line.contains("\u05E2\u05E1\u05E7\u05D4") &&
                !line.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") &&
                !line.contains("Paid", ignoreCase = true) &&
                !line.equals("Google Wallet", ignoreCase = true) &&
                !line.equals("Wallet", ignoreCase = true)
        }
        return fromLine?.take(80) ?: "Wallet"
    }
}
