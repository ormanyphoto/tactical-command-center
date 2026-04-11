package com.tacops.commandcenter

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Native FCM service — receives data-only FCM messages from the web backend
 * and shows them with a full-screen intent that launches LockScreenAlertActivity.
 * The full-screen intent is what makes the notification auto-wake the screen and
 * display over the lock screen without requiring user interaction.
 *
 * Tokens are uploaded to Firebase at tac_fcm_tokens_native/<personId> so the
 * web sendPush function can target native Android recipients separately from
 * the existing Chrome Web Push tokens stored at tac_fcm_tokens/<personId>.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: ${token.take(20)}...")

        // Read personId + deviceId from SharedPreferences (written by the TWA
        // web-side via a bridge, or we generate a stable deviceId here).
        val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
        val pid = prefs.getString("personId", null)
        var deviceId = prefs.getString("deviceId", null)
        if (deviceId == null) {
            deviceId = "dev_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
            prefs.edit().putString("deviceId", deviceId).apply()
        }

        try {
            val db = FirebaseDatabase.getInstance()
            // Store under deviceId so we don't overwrite tokens from other devices
            // when personId is null (pre-login). Once personId is known, we also
            // write under tac_fcm_tokens_native/<pid>/<deviceId> for targeted sends.
            db.getReference("tac_fcm_tokens_native_devices/$deviceId").setValue(
                mapOf(
                    "token" to token,
                    "personId" to pid,
                    "ts" to System.currentTimeMillis(),
                    "platform" to "android"
                )
            )
            if (pid != null) {
                db.getReference("tac_fcm_tokens_native/$pid/$deviceId").setValue(token)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save token: ${e.message}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.d(TAG, "FCM message received: keys=${data.keys}")

        // Debug log: write a trace entry to Firebase so the web UI can show
        // "yes, this message reached the device" even without adb access.
        try {
            val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", "unknown") ?: "unknown"
            val db = FirebaseDatabase.getInstance()
            db.getReference("tac_debug_fcm/$deviceId").push().setValue(
                hashMapOf(
                    "ts" to System.currentTimeMillis(),
                    "event" to "onMessageReceived",
                    "dataKeys" to data.keys.joinToString(","),
                    "title" to (data["title"] ?: ""),
                    "hasBody" to (data["body"]?.isNotEmpty() == true),
                    "priority" to message.priority,
                    "originalPriority" to message.originalPriority
                )
            )
            // Keep only last 20 entries per device
            db.getReference("tac_debug_fcm/$deviceId").limitToLast(20).get()
        } catch (e: Exception) {
            Log.w(TAG, "Debug log write failed", e)
        }

        // Fall back to the notification payload (when FCM sends `notification`
        // field alongside `data`, message.notification is populated in foreground).
        val title = data["title"] ?: data["notifTitle"] ?: message.notification?.title ?: "מערכת מבצעים"
        val body = data["body"] ?: message.notification?.body ?: ""
        val notifId = data["notifId"] ?: "emerg_${System.currentTimeMillis()}"
        val type = data["type"] ?: "alert"
        val typeLabel = data["typeLabel"] ?: ""
        val senderName = data["senderName"] ?: ""
        val address = data["address"] ?: ""
        val lat = data["lat"] ?: ""
        val lng = data["lng"] ?: ""
        val threat = data["threat"] ?: ""

        // Build a full-screen intent that launches LockScreenAlertActivity.
        // When the phone is locked and the channel importance is HIGH, Android
        // launches this activity directly instead of just showing a notification.
        val fullScreenIntent = Intent(this, LockScreenAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("title", title)
            putExtra("body", body)
            putExtra("notifId", notifId)
            putExtra("type", type)
            putExtra("typeLabel", typeLabel)
            putExtra("senderName", senderName)
            putExtra("address", address)
            putExtra("lat", lat)
            putExtra("lng", lng)
            putExtra("threat", threat)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            notifId.hashCode(),
            fullScreenIntent,
            flags
        )

        val contentText = if (typeLabel.isNotEmpty()) "[$typeLabel] $body" else body

        // Also directly launch the LockScreenAlertActivity — belt and suspenders.
        // If the app is allowed to launch activities from background (i.e. we
        // have a recent foreground use or the FSI permission), this bypasses
        // the FullScreenIntent restriction entirely. On Android 14+ without
        // special permission this will silently fail, in which case the
        // setFullScreenIntent on the notification is our fallback.
        try {
            startActivity(fullScreenIntent)
            Log.d(TAG, "Directly started LockScreenAlertActivity")
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity start failed (expected on bg-restricted OS): $e")
        }

        val notifBuilder = NotificationCompat.Builder(this, TacticalApplication.EMERGENCY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL) // CALL category gets priority lock-screen treatment
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 400, 150, 400, 150, 800, 150, 400))
            .setLights(0xFF3DFF52.toInt(), 1000, 500)
            .setDefaults(android.app.Notification.DEFAULT_LIGHTS or android.app.Notification.DEFAULT_VIBRATE)

        val mgr = getSystemService(NotificationManager::class.java)
        try {
            mgr?.notify(notifId.hashCode(), notifBuilder.build())
            Log.d(TAG, "Notification posted id=${notifId.hashCode()}")
            // Debug log: notification posted
            val prefs = getSharedPreferences("tac_prefs", MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", "unknown") ?: "unknown"
            FirebaseDatabase.getInstance()
                .getReference("tac_debug_fcm/$deviceId").push().setValue(
                    hashMapOf(
                        "ts" to System.currentTimeMillis(),
                        "event" to "notificationPosted",
                        "notifId" to notifId,
                        "title" to title
                    )
                )
        } catch (e: Exception) {
            Log.e(TAG, "Notification post failed", e)
        }
    }

    companion object {
        const val TAG = "TacFCM"
    }
}
