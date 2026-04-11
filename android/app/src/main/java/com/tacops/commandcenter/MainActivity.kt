package com.tacops.commandcenter

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main Activity — hosts a WebView that renders the tactical-command-center.web.app
 * web app. The WebView is ONLY used for UI rendering. All notification flow
 * (FCM reception, full-screen lock-screen alerts) is handled independently by
 * MyFirebaseMessagingService + LockScreenAlertActivity, which are pure native
 * Kotlin components that do NOT go through the WebView. That's what lets us
 * wake the screen and show full-screen alerts even when the app is locked —
 * the exact model used by Pikud HaOref / Red Alert.
 *
 * Runtime permissions (POST_NOTIFICATIONS on Android 13+, ACCESS_FINE_LOCATION)
 * are requested here and enforced at the Android process level, so the WebView
 * inherits them via our WebChromeClient geolocation override.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val REQ_PERMISSIONS = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        configureWebView()
        loadAppUrl()
        requestRuntimePermissions()

        // Modern back-button handling — WebView.goBack if possible, else finish
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // Check critical permissions every time the activity comes to front.
        // This handles the case where the user just came back from the
        // settings page after flipping the Full-Screen Intent or Battery
        // toggle — we detect the change and proceed with the next step.
        checkCriticalPermissionsAndPrompt()
    }

    /**
     * One-time guided setup for new installs. Checks the two non-standard
     * permissions that make emergency alerts actually work on modern Android
     * / Samsung devices, and walks the user through granting each one.
     *   1. USE_FULL_SCREEN_INTENT — required for LockScreenAlertActivity to
     *      launch over the lock screen on Android 14+
     *   2. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — prevents Samsung from
     *      dropping FCM messages while the device is in Doze mode
     * Each is gated by a SharedPreferences flag so we don't nag the user
     * on every app launch — but we DO re-check in onResume so if they
     * just flipped a toggle, we advance to the next step.
     */
    private fun checkCriticalPermissionsAndPrompt() {
        // Avoid nagging more than once per minute
        val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastPrompt = prefs.getLong("lastPermissionPrompt", 0L)
        if (now - lastPrompt < 60_000L) return

        // Step 1: Full-Screen Intent permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                prefs.edit().putLong("lastPermissionPrompt", now).apply()
                AlertDialog.Builder(this)
                    .setTitle("🚨 הרשאת התראות חירום")
                    .setMessage("כדי שהתראות יקפצו על המסך הנעול ויעירו את הטלפון, יש לאשר לאפליקציה \"Full-Screen notifications\".\n\nכפתור הבא → מצא \"מבצעים\" ברשימה → הפעל את המתג → חזור לאפליקציה.")
                    .setPositiveButton("פתח הגדרות") { _, _ ->
                        try {
                            val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:$packageName"))
                            startActivity(intent)
                        } catch (e: Exception) { Log.w(TAG, "FSI settings open failed", e) }
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        // Step 2: Battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                prefs.edit().putLong("lastPermissionPrompt", now).apply()
                AlertDialog.Builder(this)
                    .setTitle("🔋 הסרת הגבלת סוללה")
                    .setMessage("Samsung אגרסיבית במיוחד והיא מרדימה אפליקציות במסך נעול. כדי לקבל התראות חירום בזמן אמת, יש להחריג את \"מבצעים\" ממיטוב הסוללה.\n\nכפתור הבא → אישור → חזור לאפליקציה.")
                    .setPositiveButton("פתח הגדרות") { _, _ ->
                        try {
                            val intent = android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                            startActivity(intent)
                        } catch (e: Exception) { Log.w(TAG, "Battery exemption open failed", e) }
                    }
                    .setNegativeButton("דלג") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = "$userAgentString TacticalCommandCenterAndroid/${getVersionName()}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Inject the native bridge so the web JS can call window.Android.setPersonId etc.
        webView.addJavascriptInterface(NativeBridge(applicationContext), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasLoc = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Geolocation permission requested for $origin → granted=$hasLoc")
                callback.invoke(origin, hasLoc, true /* retain */)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // The web app only needs geolocation and notifications, both of
                // which we handle natively. Camera / mic / MIDI are not used,
                // so deny anything else to prevent unwanted prompts.
                request.deny()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val host = request.url.host ?: ""
                // Keep tactical-command-center.web.app links inside the WebView,
                // open all other links in an external browser.
                return if (host.contains("tactical-command-center")) false
                else {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) { Log.w(TAG, "External launch failed: $e") }
                    true
                }
            }
        }
    }

    private fun loadAppUrl() {
        // Pass ?native_app=1 so the web code can detect that it's running inside
        // the native WebView wrapper and adjust behavior (e.g. skip web FCM token
        // registration in favor of native FCM via MyFirebaseMessagingService).
        val url = "https://tactical-command-center.web.app/?native_app=1"
        Log.d(TAG, "Loading $url")
        webView.loadUrl(url)
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $needed")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        Log.d(TAG, "Permissions result: " + permissions.zip(grantResults.toList()).joinToString())
        // No reload needed — the WebView will ask again as needed and the
        // geolocation override uses the CURRENT permission state.
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    private fun getVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    companion object {
        const val TAG = "TacMainActivity"
    }
}
