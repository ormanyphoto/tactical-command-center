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
    fun setPersonId(personId: String) {
        if (personId.isEmpty()) return
        val prev = prefs.getString(KEY_PERSON_ID, null)
        prefs.edit().putString(KEY_PERSON_ID, personId).apply()
        Log.d(TAG, "setPersonId($personId) — previous=$prev")
        registerTokenForPersonId(personId)
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
