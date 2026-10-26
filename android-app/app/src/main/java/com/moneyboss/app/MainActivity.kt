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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.moneyboss.app.databinding.ActivityMainBinding

/**
 * Startup path restored to the stable Oct-21 version (0c16d99):
 * no onResume permission loops. Permission prompts only from onCreate.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { TokenStore(this) }
    private var askedNotificationAccess = false
    private var pendingWebRefresh = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // After post-notification grant, offer listener access once
            window.decorView.post { maybePromptNotificationAccessOnce() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val web = binding.webView
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                syncTokenFromUrl(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                syncTokenFromUrl(url)
                injectViewportFix(view)
                if (pendingWebRefresh) {
                    pendingWebRefresh = false
                    refreshWebData()
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
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        settings.userAgentString =
            settings.userAgentString.replace("; wv", "") + " Mobile"

        web.setInitialScale(100)

        val token = store.getToken()
        val startUrl = if (token != null) {
            "${Config.WEB_BASE_URL}?token=$token"
        } else {
            Config.WEB_BASE_URL
        }
        web.loadUrl(startUrl)

        if (intent.getBooleanExtra(EXTRA_REFRESH_WEB, false)) {
            pendingWebRefresh = true
        }

        // Same as stable Oct-21: only from onCreate, not onResume
        requestPostNotificationsIfNeeded()
        window.decorView.post { maybePromptNotificationAccessOnce() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REFRESH_WEB, false)) {
            refreshWebData()
        }
    }

    private fun refreshWebData() {
        if (!::binding.isInitialized) return
        binding.webView.evaluateJavascript(
            """
            (function() {
              try {
                if (typeof window.fetchTransactionsOnly === 'function') {
                  window.fetchTransactionsOnly(true);
                }
              } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun injectViewportFix(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
              var m = document.querySelector('meta[name=viewport]');
              if (!m) {
                m = document.createElement('meta');
                m.name = 'viewport';
                document.head.appendChild(m);
              }
              m.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
              document.documentElement.style.width = '100%';
              document.body && (document.body.style.width = '100%');
            })();
            """.trimIndent(),
            null
        )
    }

    private fun syncTokenFromUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return
        }
        if (uri.host != null && !uri.host!!.contains("moneyboss", ignoreCase = true) &&
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
                maybePromptNotificationAccessOnce()
            }
        } else if (current != null && !url.contains("token=", ignoreCase = true)) {
            store.clear()
            refreshWidgets()
        }
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, BalanceWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            BalanceWidgetProvider.requestUpdate(this, ids)
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
        if (askedNotificationAccess) return
        if (isFinishing || isDestroyed) return
        if (isNotificationServiceEnabled()) return
        askedNotificationAccess = true
        try {
            AlertDialog.Builder(this)
                .setTitle("\u05D2\u05D9\u05E9\u05D4 \u05DC\u05E7\u05E8\u05D9\u05D0\u05EA \u05D4\u05EA\u05E8\u05D0\u05D5\u05EA")
                .setMessage(
                    "\u05DB\u05D3\u05D9 \u05DC\u05E8\u05E9\u05D5\u05DD \u05D4\u05D5\u05E6\u05D0\u05D5\u05EA \u05DE-Google Wallet \u05E6\u05E8\u05D9\u05DA \u05DC\u05D0\u05E4\u05E9\u05E8 \u05DC-MoneyBoss " +
                        "Notification access / \u05D2\u05D9\u05E9\u05D4 \u05DC\u05D4\u05EA\u05E8\u05D0\u05D5\u05EA.\n\n" +
                        "\u05D2\u05DD \u05DB\u05D1\u05D4 \"\u05D4\u05D2\u05D1\u05DC\u05EA \u05D4\u05D0\u05E4\u05DC\u05D9\u05E7\u05E6\u05D9\u05D4 \u05DB\u05E9\u05D4\u05D9\u05D0 \u05DC\u05D0 \u05D1\u05E9\u05D9\u05DE\u05D5\u05E9\"."
                )
                .setPositiveButton("\u05E4\u05EA\u05D7 \u05D4\u05D2\u05D3\u05E8\u05D5\u05EA") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton("\u05DE\u05D0\u05D5\u05D7\u05E8 \u05D9\u05D5\u05EA\u05E8", null)
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
                ComponentName.unflattenFromString(it)?.equals(cn) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::binding.isInitialized && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_REFRESH_WEB = "com.moneyboss.app.EXTRA_REFRESH_WEB"
    }
}
