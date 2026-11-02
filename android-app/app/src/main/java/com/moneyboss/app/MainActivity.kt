package com.moneyboss.app

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Boots a native panel first. WebView is created only afterwards.
 * If WebView/system component is broken, we show an error instead of crashing.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var bootPanel: LinearLayout
    private lateinit var bootStatus: TextView
    private lateinit var bootError: TextView
    private lateinit var bootRetry: Button
    private lateinit var webContainer: FrameLayout

    private var webView: WebView? = null
    private val store by lazy { TokenStore(this) }
    private var askedNotificationAccess = false
    private var pendingWebRefresh = false
    private var webStarted = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bootPanel = findViewById(R.id.bootPanel)
        bootStatus = findViewById(R.id.bootStatus)
        bootError = findViewById(R.id.bootError)
        bootRetry = findViewById(R.id.bootRetry)
        webContainer = findViewById(R.id.webContainer)

        bootRetry.setOnClickListener { startWebViewSafe() }

        if (intent.getBooleanExtra(EXTRA_REFRESH_WEB, false)) {
            pendingWebRefresh = true
        }

        // Prove Activity can open without WebView
        bootStatus.text = "UI OK — loading site..."

        // Start WebView on next frame
        window.decorView.post {
            startWebViewSafe()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebViewSafe() {
        if (webStarted && webView != null) {
            showWeb()
            return
        }
        bootError.visibility = View.GONE
        bootRetry.visibility = View.GONE
        bootStatus.text = "Creating WebView..."

        try {
            val web = WebView(this@MainActivity)
            webView = web

            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    syncTokenFromUrl(request?.url?.toString())
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    syncTokenFromUrl(url)
                    if (pendingWebRefresh) {
                        pendingWebRefresh = false
                        refreshWebData()
                    }
                    showWeb()
                    window.decorView.post {
                        try {
                            requestPostNotificationsIfNeeded()
                            maybePromptNotificationAccessOnce()
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    syncTokenFromUrl(url)
                }
            }
            web.webChromeClient = WebChromeClient()

            val settings: WebSettings = web.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            try {
                settings.userAgentString =
                    settings.userAgentString.replace("; wv", "") + " Mobile"
            } catch (_: Exception) {
            }

            webContainer.removeAllViews()
            webContainer.addView(
                web,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val token = store.getToken()
            val startUrl = if (token != null) {
                "${Config.WEB_BASE_URL}?token=$token"
            } else {
                Config.WEB_BASE_URL
            }
            bootStatus.text = "Loading $startUrl"
            web.loadUrl(startUrl)
            webStarted = true

            // If page never finishes, still reveal after timeout
            window.decorView.postDelayed({ showWeb() }, 4000)
        } catch (e: Exception) {
            bootStatus.text = "WebView failed"
            bootError.visibility = View.VISIBLE
            bootError.text =
                "Android System WebView error:\n${e.javaClass.simpleName}: ${e.message}\n\n" +
                    "Update/disable Android System WebView in Play Store, or clear its storage."
            bootRetry.visibility = View.VISIBLE
        }
    }

    private fun showWeb() {
        if (webView == null) return
        bootPanel.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REFRESH_WEB, false)) {
            pendingWebRefresh = true
            refreshWebData()
            showWeb()
        }
    }

    private fun refreshWebData() {
        try {
            webView?.evaluateJavascript(
                "(function(){try{if(window.fetchTransactionsOnly)window.fetchTransactionsOnly(true);}catch(e){}})();",
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun syncTokenFromUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return
        }
        if (uri.host != null &&
            !uri.host!!.contains("moneyboss", ignoreCase = true) &&
            !uri.host!!.contains("netlify", ignoreCase = true)
        ) {
            return
        }

        val tokenParam = uri.getQueryParameter("token")?.trim()?.uppercase().orEmpty()
        val current = store.getToken()
        if (tokenParam.length == Config.TOKEN_LEN) {
            if (current != tokenParam) {
                store.saveToken(tokenParam)
                refreshWidgets()
            }
        } else if (current != null && !url.contains("token=", ignoreCase = true)) {
            store.clear()
            refreshWidgets()
        }
    }

    private fun refreshWidgets() {
        try {
            val mgr = AppWidgetManager.getInstance(this)
            val ids = mgr.getAppWidgetIds(ComponentName(this, BalanceWidgetProvider::class.java))
            if (ids.isNotEmpty()) BalanceWidgetProvider.requestUpdate(this, ids)
        } catch (_: Exception) {
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
            }
        }
    }

    private fun maybePromptNotificationAccessOnce() {
        if (askedNotificationAccess || isFinishing) return
        if (isNotificationServiceEnabled()) return
        askedNotificationAccess = true
        try {
            AlertDialog.Builder(this)
                .setTitle("Notification access")
                .setMessage(
                    "Enable Notification access for MoneyBoss to capture Google Wallet expenses.\n\n" +
                        "After each APK update: turn it OFF, then ON again.\n\n" +
                        "Also disable \"Pause app activity if unused\"."
                )
                .setPositiveButton("Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        } catch (_: Exception) {
            askedNotificationAccess = false
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        return try {
            val cn = ComponentName(this, WalletNotificationListener::class.java)
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?: return false
            flat.split(":").any {
                ComponentName.unflattenFromString(it) == cn
            }
        } catch (_: Exception) {
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack()
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_REFRESH_WEB = "com.moneyboss.app.EXTRA_REFRESH_WEB"
    }
}
