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

        val title = data["title"] ?: data["notifTitle"] ?: "מערכת מבצעים"
        val body = data["body"] ?: ""
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

        val notifBuilder = NotificationCompat.Builder(this, TacticalApplication.EMERGENCY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)

        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(notifId.hashCode(), notifBuilder.build())
    }

    companion object {
        const val TAG = "TacFCM"
    }
}
