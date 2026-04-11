package com.tacops.commandcenter

import android.net.Uri
import com.google.androidbrowserhelper.trusted.LauncherActivity
import java.util.UUID

/**
 * Custom TWA launcher that injects a stable device UUID into the web app URL
 * as a query parameter (?native_device=<uuid>). The web JavaScript reads this
 * parameter on boot, stores it in localStorage, and uses it to link the native
 * FCM token (registered by MyFirebaseMessagingService) to the logged-in
 * personId via the tac_fcm_tokens_native/<personId>/<deviceId> Firebase path.
 *
 * Without this bridge the native FCM token would never be associated with the
 * web-side user identity, and sendPush would have nothing to target → native
 * full-screen lock-screen alerts would never fire.
 */
class CustomLauncherActivity : LauncherActivity() {

    override fun getLaunchingUrl(): Uri {
        // Get or create a stable device UUID stored in SharedPreferences. The
        // UUID persists across reinstalls only if the user keeps "App data"
        // — that's fine, a new install just gets a fresh UUID and re-links.
        val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", deviceId).apply()
        }

        // Base URL (default_url from strings.xml) with native_device appended
        val base = super.getLaunchingUrl()
        val separator = if (base.query.isNullOrEmpty()) "?" else "&"
        return Uri.parse("$base${separator}native_device=$deviceId&native_app=1")
    }
}
