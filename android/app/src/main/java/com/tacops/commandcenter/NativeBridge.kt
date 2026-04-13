package com.tacops.commandcenter

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/**
 * JavaScript bridge exposed to the web app running in the WebView as
 * `window.Android`. The web code calls these methods to:
 *   - detect that it's running inside the native APK
 *   - obtain a stable device ID (so FCM token linking is deterministic)
 *   - register the logged-in personId with the native side so our
 *     FCM token is written under tac_fcm_tokens_native/<personId>/<deviceId>
 *   - trigger a fresh FCM token fetch + upload on demand
 *
 * All methods are @JavascriptInterface annotated so they can be called
 * from JS. Kotlin receives them on a background thread — we must not
 * touch the WebView from inside them.
 */
class NativeBridge(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun getVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    /** Returns a stable UUID that survives across app launches until reinstall. */
    @JavascriptInterface
    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    /**
     * Web app calls this after successful login to tell the native side
     * who the current user is. We store it in SharedPreferences (so
     * MyFirebaseMessagingService.onNewToken can use it), then immediately
     * fetch the current FCM token and write it to Firebase under
     * tac_fcm_tokens_native/<personId>/<deviceId> so sendPush can target it.
     */
    @JavascriptInterface
    @android.webkit.JavascriptInterface
    fun setUserProfile(name: String, role: String) {
        prefs.edit()
            .putString("userName", name)
            .putString("userRole", role)
            .apply()
        Log.d(TAG, "setUserProfile(name=$name, role=$role)")
    }

    @android.webkit.JavascriptInterface
    fun setLocPublishEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("locPublishEnabled", enabled).apply()
        Log.d(TAG, "setLocPublishEnabled($enabled)")
    }

    fun setPersonId(personId: String) {
        if (personId.isEmpty()) return
        val prev = prefs.getString(KEY_PERSON_ID, null)
        prefs.edit().putString(KEY_PERSON_ID, personId).apply()
        Log.d(TAG, "setPersonId($personId) — previous=$prev")
        registerTokenForPersonId(personId)
        // Kick the foreground service to re-attach its Firebase listener
        // to the new personId (or attach for the first time on fresh login).
        try {
            val svc = android.content.Intent(context, TacticalForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start FG service from setPersonId", e)
        }
    }

    /** Clears the person link on logout so we don't keep sending to a stale pid. */
    @JavascriptInterface
    fun clearPersonId() {
        val prev = prefs.getString(KEY_PERSON_ID, null)
        prefs.edit().remove(KEY_PERSON_ID).apply()
        Log.d(TAG, "clearPersonId — was $prev")
        // Remove the linked-tokens entry for this device
        if (prev != null) {
            val deviceId = getDeviceId()
            try {
                FirebaseDatabase.getInstance()
                    .getReference("tac_fcm_tokens_native/$prev/$deviceId")
                    .removeValue()
            } catch (e: Exception) { Log.w(TAG, "clearPersonId remove failed", e) }
        }
    }

    /** On-demand re-fetch and re-register. Called from JS if the link dot goes yellow. */
    @JavascriptInterface
    fun refreshToken() {
        val pid = prefs.getString(KEY_PERSON_ID, null) ?: return
        registerTokenForPersonId(pid)
    }

    /**
     * Opens Android Settings → Full-screen notifications for this app.
     * Android 14+ requires explicit per-app permission to launch a
     * FullScreenIntent Activity over the lock screen. Without this grant
     * our LockScreenAlertActivity will not fire even though the manifest
     * declares USE_FULL_SCREEN_INTENT.
     */
    @JavascriptInterface
    fun openFullScreenIntentSettings() {
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
            } else {
                // Older: open the app's notification settings page
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openFullScreenIntentSettings failed", e)
        }
    }

    /**
     * Returns whether this app can currently use FullScreenIntent — on
     * Android 14+ this reflects the per-app toggle the user grants via
     * openFullScreenIntentSettings(). On older Android it always returns true.
     */
    @JavascriptInterface
    fun canUseFullScreenIntent(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.canUseFullScreenIntent()
        } catch (e: Exception) { false }
    }

    /** Opens the app-level notification settings page (for channel importance). */
    @JavascriptInterface
    fun openAppNotificationSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openAppNotificationSettings failed", e)
        }
    }

    /**
     * Opens the system battery optimization settings for our app. On Samsung,
     * "sleeping apps" will drop FCM delivery when the screen is off unless
     * the app is whitelisted here. This is the #1 reason lock-screen alerts
     * fail on Samsung devices.
     */
    @JavascriptInterface
    fun requestBatteryExemption() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "requestBatteryExemption failed", e)
            // Fall back to opening the app's battery settings page
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks whether the app is whitelisted from battery optimizations.
     * Returns true on Android <23 (no battery optimization) or if the app
     * is in the whitelist.
     */
    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) { false }
    }

    /**
     * Directly launches LockScreenAlertActivity with a test payload. Useful
     * to prove the activity + channel + permissions all work end-to-end
     * WITHOUT going through FCM. If this button works but real FCM pushes
     * don't, the issue is in FCM delivery (battery optimization, network).
     */
    @JavascriptInterface
    fun testLockScreenAlert() {
        try {
            val intent = android.content.Intent(context, LockScreenAlertActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("title", "🚨 בדיקת LockScreen")
                putExtra("body", "אם אתה רואה את זה — המסך הנעול + Activity + הרשאות הכל עובד")
                putExtra("notifId", "test_${System.currentTimeMillis()}")
                putExtra("type", "alert")
                putExtra("typeLabel", "בדיקה")
                putExtra("senderName", "מערכת")
                putExtra("threat", "בדיקה")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "testLockScreenAlert failed", e)
        }
    }

    /** Debug: current Android version + manufacturer */
    @JavascriptInterface
    fun getDeviceInfo(): String = try {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
    } catch (_: Exception) { "unknown" }

    /**
     * Opens Samsung's "Sleeping apps" settings so the user can REMOVE
     * מבצעים from the list (or check it's not in it). This is SEPARATE
     * from battery optimization exemption and Samsung enforces it
     * aggressively — apps in the sleeping list have their foreground
     * services killed when the screen is off.
     */
    @JavascriptInterface
    fun openSamsungSleepingApps() {
        // Try multiple Samsung-specific intent paths — the activity name
        // varies by One UI version.
        val candidates = listOf(
            "com.samsung.android.lool/.deviceidle.ui.DeviceIdleActivity",
            "com.samsung.android.sm/.ui.battery.BatteryActivity",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        for (name in candidates) {
            try {
                val intent = android.content.Intent().setClassName(
                    name.substringBefore("/"),
                    name.substringAfter("/")
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        // Fall back to the standard battery optimization settings
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openSamsungSleepingApps failed", e)
        }
    }

    /**
     * Returns whether the TacticalForegroundService is currently running.
     * Used by the diagnostic UI to tell the user if the listener is alive.
     */
    @JavascriptInterface
    fun isForegroundServiceRunning(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val mgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            mgr?.getRunningServices(Integer.MAX_VALUE)?.any {
                it.service.className == TacticalForegroundService::class.java.name
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun registerTokenForPersonId(personId: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Failed to fetch FCM token for registration", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result ?: return@addOnCompleteListener
            val deviceId = getDeviceId()
            Log.d(TAG, "Registering token for $personId / $deviceId → ${token.take(20)}...")
            try {
                val db = FirebaseDatabase.getInstance()
                db.getReference("tac_fcm_tokens_native/$personId/$deviceId")
                    .setValue(token)
                    .addOnSuccessListener { Log.d(TAG, "Token linked to $personId") }
                    .addOnFailureListener { e -> Log.w(TAG, "Link failed", e) }
                db.getReference("tac_fcm_tokens_native_devices/$deviceId").setValue(
                    hashMapOf(
                        "token" to token,
                        "personId" to personId,
                        "ts" to System.currentTimeMillis(),
                        "platform" to "android"
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Token write exception", e)
            }
        }
    }

    companion object {
        const val TAG = "NativeBridge"
        const val PREFS_NAME = "tac_prefs"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_PERSON_ID = "personId"
    }
}
