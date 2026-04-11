package com.tacops.commandcenter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * Application class — creates the high-importance emergency notification channel
 * once at startup. The channel bypasses Do Not Disturb, plays alarm-category sound,
 * vibrates, and lights the lock screen. These properties are IMMUTABLE after the
 * channel is created — to change them, bump the channel ID (append -v2 etc).
 */
class TacticalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createEmergencyChannel()
    }

    private fun createEmergencyChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = getSystemService(NotificationManager::class.java) ?: return

        // If the channel already exists with a prior version, it stays as-is.
        // Use a new channel ID (e.g. _v2) whenever we want to change sound/vibration/importance.
        val channel = NotificationChannel(
            EMERGENCY_CHANNEL_ID,
            "התראות חירום",
            NotificationManager.IMPORTANCE_HIGH // heads-up + sound + lock screen
        ).apply {
            description = "התראות חירום של מערכת מבצעים — נפתחות אוטומטית על מסך נעול"
            enableLights(true)
            lightColor = 0xFF3DFF52.toInt()
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800, 150, 400)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setShowBadge(true)

            // Play sound via the ALARM audio usage category so it is LOUD and
            // uses the user's alarm-volume stream rather than the (usually
            // muted) notification stream.
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

    companion object {
        const val EMERGENCY_CHANNEL_ID = "tac_emergency_v1"
    }
}
