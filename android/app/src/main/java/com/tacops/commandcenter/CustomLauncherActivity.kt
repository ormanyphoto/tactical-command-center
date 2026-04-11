package com.tacops.commandcenter

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.trusted.LauncherActivity
import java.util.UUID

/**
 * Custom TWA launcher that:
 *   1. Requests runtime permissions required by the native FCM flow:
 *      POST_NOTIFICATIONS (Android 13+) and ACCESS_FINE_LOCATION. Without
 *      POST_NOTIFICATIONS the native MyFirebaseMessagingService cannot
 *      display any notification at all — it silently fails.
 *   2. Injects a stable device UUID into the web app URL
 *      (?native_device=<uuid>&native_app=1) so the JavaScript can link
 *      the native FCM token to the logged-in personId via Firebase.
 */
class CustomLauncherActivity : LauncherActivity() {

    private val REQ_PERMISSIONS = 1001
    private var deferredLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the TWA launches, make sure the Android-level permissions
        // we need are granted. Without POST_NOTIFICATIONS on Android 13+,
        // notifications shown by our own FCM service never appear.
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
            // Don't launch yet — wait for the user to respond to the prompt
            deferredLaunch = true
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
            // We still need to call super.onCreate with a null state so the
            // Activity lifecycle is valid, but we skip the TWA launch here.
            super.onCreate(null)
            return
        }

        super.onCreate(savedInstanceState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        Log.d(TAG, "Permissions result: " + permissions.zip(grantResults.toList()).joinToString())
        if (deferredLaunch) {
            deferredLaunch = false
            // Retry the TWA launch now that permissions have been decided.
            // launchTwa() is provided by the base class — it re-reads
            // getLaunchingUrl() and fires the intent.
            try {
                launchTwa()
            } catch (e: Exception) {
                Log.w(TAG, "launchTwa after permissions failed", e)
                // Fallback: recreate the activity so onCreate runs again with
                // permissions now granted.
                recreate()
            }
        }
    }

    override fun getLaunchingUrl(): Uri {
        // Get or create a stable device UUID stored in SharedPreferences.
        // MyFirebaseMessagingService reads the same key so the UUID is
        // consistent between the FCM service and the launcher.
        val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", deviceId).apply()
        }

        val base = super.getLaunchingUrl()
        val separator = if (base.query.isNullOrEmpty()) "?" else "&"
        return Uri.parse("$base${separator}native_device=$deviceId&native_app=1")
    }

    companion object {
        const val TAG = "TacLauncher"
    }
}
