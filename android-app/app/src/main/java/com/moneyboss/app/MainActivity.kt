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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { TokenStore(this) }
    private var pendingWebRefresh = false
    private var permissionDialogShowing = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // After POST_NOTIFICATIONS result, still ensure listener access
        ensureRequiredPermissions()
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
    }

    override fun onResume() {
        super.onResume()
        ensureRequiredPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REFRESH_WEB, false)) {
            refreshWebData()
        }
    }

    /** Called on every app open — ask for anything still missing. */
    private fun ensureRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!isNotificationServiceEnabled()) {
            promptNotificationListenerAccess()
        }
    }

    /** Pulls latest transactions/balance from the live site (same as the sync button). */
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

    private fun promptNotificationListenerAccess() {
        if (permissionDialogShowing) return
        permissionDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("גישה לקריאת התראות")
            .setMessage(
                "כדי לרשום הוצאות מ-Google Wallet צריך לאפשר ל-MoneyBoss " +
                    "\"גישה להתראות\" / Notification access " +
                    "(זה שונה מהרשאת הצגת התראות של האפליקציה).\n\n" +
                    "גם כבה \"הגבלת האפליקציה כשהיא לא בשימוש\" בהגדרות MoneyBoss."
            )
            .setPositiveButton("פתח הגדרות") { _, _ ->
                permissionDialogShowing = false
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("מאוחר יותר") { _, _ ->
                permissionDialogShowing = false
            }
            .setOnCancelListener {
                permissionDialogShowing = false
            }
            .show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, WalletNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.equals(cn) == true
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
