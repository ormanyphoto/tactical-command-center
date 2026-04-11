package com.tacops.commandcenter

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen emergency alert activity — launched automatically by the FCM
 * service via setFullScreenIntent. Wakes the screen, shows over the lock
 * screen, plays alarm sound, vibrates, and offers buttons to open the full
 * app (launches the TWA) or acknowledge the alert.
 */
class LockScreenAlertActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen + wake screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Acquire a wake lock that forces the screen on for a limited time.
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "tac:emergency_alert"
            )
            wakeLock?.acquire(30 * 1000L /* 30 seconds */)
        } catch (_: Exception) {}

        setContentView(R.layout.activity_lock_screen_alert)

        val title = intent.getStringExtra("title") ?: "התראת חירום"
        val body = intent.getStringExtra("body") ?: ""
        val typeLabel = intent.getStringExtra("typeLabel") ?: ""
        val threat = intent.getStringExtra("threat") ?: ""
        val senderName = intent.getStringExtra("senderName") ?: ""
        val address = intent.getStringExtra("address") ?: ""

        findViewById<TextView>(R.id.alert_title).text = title
        findViewById<TextView>(R.id.alert_body).text =
            if (typeLabel.isNotEmpty()) "[$typeLabel] $body" else body

        val threatView = findViewById<TextView>(R.id.alert_threat)
        if (threat.isNotEmpty()) {
            threatView.visibility = View.VISIBLE
            threatView.text = "⚡ $threat"
        } else {
            threatView.visibility = View.GONE
        }

        val addrView = findViewById<TextView>(R.id.alert_address)
        if (address.isNotEmpty()) {
            addrView.visibility = View.VISIBLE
            addrView.text = "📍 $address"
        } else {
            addrView.visibility = View.GONE
        }

        findViewById<TextView>(R.id.alert_sender).text =
            if (senderName.isNotEmpty()) "נשלח על ידי: $senderName" else ""

        // Open full app — launches the TWA LauncherActivity which opens the web app
        findViewById<Button>(R.id.btn_open_app).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (launch != null) startActivity(launch)
            stopAlertSound()
            finish()
        }

        // Acknowledge — dismiss
        findViewById<Button>(R.id.btn_acknowledge).setOnClickListener {
            stopAlertSound()
            finish()
        }

        playAlertSound()
        vibrate()
    }

    private fun playAlertSound() {
        try {
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@LockScreenAlertActivity, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlertSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = longArrayOf(0, 400, 150, 400, 150, 800, 150, 400, 150, 400, 150, 800)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                v.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopAlertSound()
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.cancel()
        } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
