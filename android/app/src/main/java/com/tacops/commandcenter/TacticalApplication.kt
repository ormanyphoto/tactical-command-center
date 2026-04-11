package com.tacops.commandcenter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/**
 * Application class — creates the high-importance emergency notification
 * channel, signs in anonymously to Firebase so database writes succeed, and
 * proactively fetches the FCM token and writes it to Firebase under
 * tac_fcm_tokens_native_devices/<deviceId>. This runs on EVERY app launch
 * (not just on token rotation) so a fresh install always has its token
 * registered as soon as the app opens.
 */
class TacticalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createEmergencyChannel()
        // Sign in anonymously — required before writing to the database
        // because the Realtime DB rules expect an authenticated user.
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Anonymous auth ok: ${FirebaseAuth.getInstance().currentUser?.uid}")
                } else {
                    Log.w(TAG, "Anonymous auth failed", task.exception)
                }
                // Regardless of auth result, try to fetch + write the token.
                // If auth failed the write will fail too but we log it clearly.
                fetchAndRegisterFCMToken()
            }
    }

    private fun createEmergencyChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            EMERGENCY_CHANNEL_ID,
            "התראות חירום",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "התראות חירום של מערכת מבצעים — נפתחות אוטומטית על מסך נעול"
            enableLights(true)
            lightColor = 0xFF3DFF52.toInt()
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800, 150, 400)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setShowBadge(true)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                attrs
            )
        }
        mgr.createNotificationChannel(channel)
    }

    private fun fetchAndRegisterFCMToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "FCM token fetch failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result ?: return@addOnCompleteListener
                Log.d(TAG, "FCM token fetched: ${token.take(20)}... (len=${token.length})")
                writeTokenToFirebase(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "FirebaseMessaging init failed", e)
        }
    }

    private fun writeTokenToFirebase(token: String) {
        val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", deviceId).apply()
        }
        val pid = prefs.getString("personId", null)
        try {
            val db = FirebaseDatabase.getInstance()
            val entry = hashMapOf<String, Any>(
                "token" to token,
                "ts" to System.currentTimeMillis(),
                "platform" to "android",
                "versionName" to ("" + packageManager.getPackageInfo(packageName, 0).versionName)
            )
            if (pid != null) entry["personId"] = pid
            db.getReference("tac_fcm_tokens_native_devices/$deviceId").setValue(entry)
                .addOnSuccessListener { Log.d(TAG, "Token written to tac_fcm_tokens_native_devices/$deviceId") }
                .addOnFailureListener { e -> Log.w(TAG, "Token write failed", e) }
            if (pid != null) {
                db.getReference("tac_fcm_tokens_native/$pid/$deviceId").setValue(token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeTokenToFirebase exception", e)
        }
    }

    companion object {
        const val EMERGENCY_CHANNEL_ID = "tac_emergency_v1"
        const val TAG = "TacApp"
    }
}
