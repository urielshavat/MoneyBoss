package com.moneyboss.app

import android.app.Notification
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

        // Group summaries are generic ("Wallet") — the real title lives on the child notif.
        val flags = sbn.notification?.flags ?: 0
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification?.extras ?: return
        // Single Wallet notification (expanded vs compact is UI-only):
        //   title = merchant (e.g. "NETO HISACHON")
        //   text  = amount line + optional "בכרטיס של …" + masked PAN
        //   Card brand (CashCal / etc.) is incidental — not required.
        //   "Google Wallet" is the app label, not title/text.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            .ifBlank { extras.getCharSequence("android.title")?.toString().orEmpty() }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val ticker = sbn.notification?.tickerText?.toString().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()

        val combined = (listOf(title, text, bigText, subText, infoText, summary, ticker) + textLines)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

        if (!isMonitoredPackage(pkg, combined)) return
        if (combined.isBlank()) return

        Log.i(TAG, "READ ok pkg=$pkg title='$title'\n$combined")

        val preview = "title='$title' | " + combined.replace("\n", " | ").take(120)
        val parsed = WalletParser.parse(combined, titleHint = title)
        if (parsed == null) {
            notifyDebug("Wallet parse FAIL", "$pkg\n$preview")
            return
        }

        if (parsed.merchant.equals("Wallet", ignoreCase = true)) {
            notifyDebug(
                "Wallet merchant missing",
                "amount=${parsed.amount}\n$pkg\n$preview"
            )
        }

        val token = TokenStore(this).getToken()
        if (token == null) {
            notifyDebug(
                "Wallet token MISSING",
                "parse OK: ${parsed.merchant} ${parsed.amount}\n$pkg"
            )
            return
        }

        val merchant = parsed.merchant
        val amount = parsed.amount
        val externalId = "wallet_" + sha1(
            "${pkg}|${sbn.key}|$amount|$merchant"
        )

        scope.launch {
            try {
                api.insertExpense(
                    token = token,
                    amount = amount,
                    name = merchant,
                    externalId = externalId
                )
                notifyUser(merchant, amount)
                refreshWidgets()
                Log.i(TAG, "Recorded: $merchant $amount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record wallet expense", e)
                notifyDebug(
                    "Wallet insert FAIL",
                    "${e.message}\n$merchant $amount"
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
            combined.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") ||
            combined.contains("\u05E9\u05F4\u05D7") || // ש״ח
            combined.contains("\u05E9\"\u05D7") // ש"ח
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
                .setContentTitle("הוצאה תועדה")
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
     * One Google Wallet notification — only amount + merchant matter.
     * - EXTRA_TITLE = merchant (e.g. "NETO HISACHON")
     * - EXTRA_TEXT = "עסקה בסך … ש״ח/₪ …" (+ optional card line)
     * Card brand text is irrelevant and not required.
     */
    data class Parsed(val amount: Double, val merchant: String)

    fun parse(raw: String, titleHint: String = ""): Parsed? {
        val text = normalize(raw)
        val title = normalize(titleHint)
        val amount = extractAmount(text) ?: return null
        if (amount <= 0) return null
        return Parsed(amount = amount, merchant = extractMerchant(text, title))
    }

    private fun normalize(raw: String): String {
        return raw
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .replace(Regex("[\\u00A0\\u202F\\u2007\\u2009\\u200A\\u200B]"), " ")
            .replace(Regex("""(?i)\bN\s*I\s*S\b"""), "NIS")
            .replace(Regex("""(?i)\bI\s*L\s*S\b"""), "ILS")
            .replace(Regex("[^\\S\\n]+"), " ")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    }

    private fun extractAmount(text: String): Double? {
        // ₪ $ NIS ILS ש"ח ש״ח שח
        val cur = """(?:NIS|ILS|\u20AA|\$|\u05E9\u05F4\u05D7|\u05E9\"\u05D7|\u05E9\u05D7)"""
        val num = """(\d+(?:[.,]\d{1,2})?)"""
        val patterns = listOf(
            Regex("""\u05D1\u05E1\u05DA\s*$cur?\s*$num""", RegexOption.IGNORE_CASE),
            Regex("""$num\s*$cur""", RegexOption.IGNORE_CASE),
            Regex("""$cur\s*$num""", RegexOption.IGNORE_CASE),
            Regex("""\|\s*$cur?\s*$num""", RegexOption.IGNORE_CASE),
            Regex("""(?<![\d.,])(\d+[.,]\d{1,2})(?![\d])""")
        )

        for (re in patterns) {
            val m = re.find(text) ?: continue
            val rawNum = m.groupValues.lastOrNull { it.isNotBlank() && it[0].isDigit() }
                ?: m.groupValues.getOrNull(1)
                ?: continue
            val value = parseNumber(rawNum) ?: continue
            // Skip bare 4-digit hits that sit on the masked PAN / "בכרטיס" line
            if (!rawNum.contains('.') && !rawNum.contains(',') && value in 1000.0..9999.0) {
                val idx = m.range.first
                val window = text.substring(maxOf(0, idx - 24), minOf(text.length, idx + 36))
                if (window.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") ||
                    window.contains("••") ||
                    window.contains("**") ||
                    window.contains("•")
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

    private fun extractMerchant(text: String, titleHint: String): String {
        // Title is the merchant — never the app label "Google Wallet"
        if (isUsableMerchant(titleHint)) return titleHint.take(80)

        val pipe = text.indexOf('|')
        if (pipe > 0) {
            val before = text.substring(0, pipe).trim()
            if (isUsableMerchant(before)) return before.take(80)
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fromLine = lines.firstOrNull { isUsableMerchant(it) && !looksLikeAmountOrCardLine(it) }
        if (fromLine != null) return fromLine.take(80)

        return "Wallet"
    }

    private fun looksLikeAmountOrCardLine(line: String): Boolean {
        val upper = line.uppercase()
        return line.contains('\u20AA') ||
            upper.contains("NIS") ||
            upper.contains("ILS") ||
            line.contains("\u05D1\u05E1\u05DA") || // בסך
            line.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1") || // בכרטיס
            line.contains("\u05E9\u05F4\u05D7") ||
            line.contains("\u05E9\"\u05D7") ||
            line.contains("••") ||
            line.contains("•") ||
            Regex("""\d+[.,]\d{1,2}""").containsMatchIn(line)
    }

    private fun isUsableMerchant(value: String): Boolean {
        val s = value.trim()
        if (s.length < 2) return false
        val upper = s.uppercase()
        if (upper == "WALLET" || upper == "GOOGLE WALLET" || upper == "GOOGLE PAY") return false
        if (upper.contains("GOOGLE WALLET")) return false
        if (upper == "PAYMENT" || upper == "PURCHASE" || upper == "TRANSACTION") return false
        if (s == "\u05E2\u05E1\u05E7\u05D4" || s.startsWith("\u05E2\u05E1\u05E7\u05D4 \u05D1\u05E1\u05DA")) return false
        // Card descriptor line ("בכרטיס של …"), not a merchant
        if (s.contains("\u05D1\u05DB\u05E8\u05D8\u05D9\u05E1")) return false
        if (upper.replace(" ", "") == "\u05E2\u05E1\u05E7\u05D4".uppercase()) return false
        return true
    }
}
