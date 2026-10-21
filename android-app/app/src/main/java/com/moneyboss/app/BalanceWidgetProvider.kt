package com.moneyboss.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Widget logic based on working GitHub commit a429578,
 * plus refresh-on-open and blue-on-white button colors.
 */
class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: mgr.getAppWidgetIds(
                    android.content.ComponentName(context, BalanceWidgetProvider::class.java)
                )
            ids.forEach { updateAppWidget(context, mgr, it) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.moneyboss.app.ACTION_REFRESH_WIDGET"

        fun requestUpdate(context: Context, appWidgetIds: IntArray) {
            val intent = Intent(context, BalanceWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun formatBalance(balance: Double): String {
            val whole = round(balance).toLong()
            val absFormatted = NumberFormat.getIntegerInstance(Locale.US).format(abs(whole))
            // Shekel via unicode escape — safe across uploads
            val shekel = "\u20AA"
            return if (whole < 0) "-$shekel$absFormatted" else "$shekel$absFormatted"
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_balance)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_REFRESH_WEB, true)
            }
            val openPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 3,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open_button, openPi)
            views.setOnClickPendingIntent(R.id.widget_label, openPi)

            val addIntent = Intent(context, AddExpenseActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val addPi = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_add_button, addPi)

            val refreshIntent = Intent(context, BalanceWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 2,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_balance_text, refreshPi)

            views.setTextViewText(R.id.widget_balance_text, "...")
            appWidgetManager.updateAppWidget(appWidgetId, views)

            val token = TokenStore(context).getToken()
            if (token == null) {
                views.setTextViewText(R.id.widget_balance_text, "\u05D4\u05EA\u05D7\u05D1\u05E8")
                views.setTextViewText(R.id.widget_label, "MoneyBoss")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            scope.launch(Dispatchers.IO) {
                val text = try {
                    formatBalance(SupabaseClient().getBalance(token))
                } catch (_: Exception) {
                    "\u05E9\u05D2\u05D9\u05D0\u05D4"
                }
                views.setTextViewText(R.id.widget_balance_text, text)
                views.setTextViewText(R.id.widget_label, "\u05DE\u05D0\u05D6\u05DF")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
