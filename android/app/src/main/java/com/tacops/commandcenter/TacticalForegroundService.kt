package com.tacops.commandcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot

/**
 * Foreground service that keeps the app alive 24/7 to receive emergency
 * alerts. Uses Firebase Realtime Database's persistent socket connection
 * (not FCM) — this bypasses:
 *   - Android Doze / App Standby (foreground service is exempt)
 *   - Samsung's "Sleeping apps" list
 *   - FCM delivery delays
 *   - onMessageReceived not firing for notification-payload FCM
 *
 * Whenever a new entry appears at tac_notifications/<personId>, the service
 * directly launches LockScreenAlertActivity with the payload — which wakes
 * the screen and shows the full-screen emergency UI over the lock screen.
 *
 * This is the same architecture that Pikud HaOref / Red Alert use on
 * Android: a persistent foreground service as the delivery channel.
 *
 * The tradeoff is a persistent low-importance notification in the status
 * bar saying "מבצעים פעיל — מאזין להתראות חירום". For an emergency-response
 * app this is the right tradeoff — reliability over UI cleanliness.
 */
class TacticalForegroundService : Service() {

    private var childListener: ChildEventListener? = null
    private var notifRef: DatabaseReference? = null
    private var currentPid: String? = null
    private val handledIds = HashSet<String>()
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            writeDebugLog("fg.heartbeat", "alive pid=${currentPid ?: "-"}")
            heartbeatHandler.postDelayed(this, 60_000L) // every 60s
        }
    }

    override fun onCreate() {
        super.onCreate()
        createForegroundChannel()
        Log.d(TAG, "Service created")
        writeDebugLog("fg.onCreate", "Service created")
        // Start the heartbeat — if this stops in the debug log, we know the
        // service was killed by the OS (Samsung battery management, etc.)
        heartbeatHandler.postDelayed(heartbeatRunnable, 30_000L)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        writeDebugLog("fg.onDestroy", "Service killed by OS")
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        childListener?.let { notifRef?.removeEventListener(it) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildPersistentNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: declare foregroundServiceType
                startForeground(
                    FG_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FG_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(FG_NOTIF_ID, notif)
            }
            Log.d(TAG, "startForeground OK")
            writeDebugLog("fg.startForegroundOK", "persistent notif id=$FG_NOTIF_ID")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            writeDebugLog("fg.startForegroundFAIL", e.message ?: "unknown")
            stopSelf()
            return START_NOT_STICKY
        }

        // Attach/refresh the Firebase listener based on the current personId.
        // Wait for Firebase Auth to complete before attaching — the DB rules
        // require authentication and attaching too early results in a silent
        // PERMISSION_DENIED that never fires onChildAdded.
        val prefs = getSharedPreferences("tac_prefs", Context_MODE_PRIVATE)
        val pid = prefs.getString("personId", null)
        if (pid != null && pid != currentPid) {
            waitForAuthThenAttach(pid, 0)
        } else if (pid == null) {
            Log.d(TAG, "No personId yet — service running but no listener attached")
            writeDebugLog("fg.noPid", "waiting for login")
        }

        return START_STICKY
    }

    private fun waitForAuthThenAttach(pid: String, attempt: Int) {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            currentPid = pid
            attachListener(pid)
            writeDebugLog("fg.authReady", "uid=${auth.currentUser?.uid?.take(8)}")
        } else if (attempt < 20) {
            // Retry every 500ms for up to 10 seconds waiting for signInAnonymously
            android.os.Handler(mainLooper).postDelayed({
                waitForAuthThenAttach(pid, attempt + 1)
            }, 500)
        } else {
            Log.w(TAG, "Auth never completed — attaching listener anyway")
            writeDebugLog("fg.authTimeout", "attach without auth")
            currentPid = pid
            attachListener(pid)
        }
    }

    private val Context_MODE_PRIVATE: Int get() = android.content.Context.MODE_PRIVATE

    private fun attachListener(pid: String) {
        // Detach previous if any
        childListener?.let { l ->
            notifRef?.removeEventListener(l)
        }
        Log.d(TAG, "Attaching Firebase listener for $pid")
        val db = FirebaseDatabase.getInstance()
        notifRef = db.getReference("tac_notifications/$pid")
        childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val data = snapshot.value
                    if (data !is Map<*, *>) return
                    val notifId = snapshot.key ?: return
                    if (handledIds.contains(notifId)) return

                    val sentAt = (data["sentAt"] as? Number)?.toLong() ?: 0L
                    val age = System.currentTimeMillis() - sentAt
                    // Only trigger for recent notifications (last 5 minutes).
                    // Older ones are stale — we don't want the alarm to fire
                    // on historic notifications when the service first attaches.
                    if (age > 5 * 60 * 1000) {
                        handledIds.add(notifId) // mark so we don't re-evaluate
                        return
                    }

                    handledIds.add(notifId)
                    val title = (data["title"] as? String) ?: "מערכת מבצעים"
                    val body = (data["body"] as? String) ?: ""
                    val threat = (data["threat"] as? String) ?: ""
                    val senderName = (data["senderName"] as? String) ?: ""
                    val address = (data["address"] as? String) ?: ""
                    val typeLabel = (data["typeLabel"] as? String) ?: ""

                    Log.d(TAG, "New notification: $notifId — $title")
                    writeDebugLog("fg.onChildAdded", title)
                    fireEmergencyAlert(notifId, title, body, threat, senderName, address, typeLabel)
                } catch (e: Exception) {
                    Log.w(TAG, "onChildAdded error", e)
                }
            }

            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {
                // If the notification was deleted (user acknowledged it on another
                // device), clean up our handled-ids so memory doesn't grow forever.
                s.key?.let { handledIds.remove(it) }
            }
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Firebase listener cancelled: ${error.message}")
            }
        }
        notifRef?.addChildEventListener(childListener as ChildEventListener)
    }

    private fun fireEmergencyAlert(
        notifId: String,
        title: String,
        body: String,
        threat: String,
        senderName: String,
        address: String,
        typeLabel: String
    ) {
        // Build the Intent targeting LockScreenAlertActivity
        val lockIntent = Intent(this, LockScreenAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("title", title)
            putExtra("body", body)
            putExtra("notifId", notifId)
            putExtra("threat", threat)
            putExtra("senderName", senderName)
            putExtra("address", address)
            putExtra("typeLabel", typeLabel)
        }

        // 1. Direct launch: foreground services are allowed to launch activities
        //    from background on Android 12+ as an exception to the usual rules.
        //    This is the MOST reliable path for full-screen lock-screen wake.
        try {
            startActivity(lockIntent)
            Log.d(TAG, "Directly started LockScreenAlertActivity from FG service")
            writeDebugLog("fg.startActivityOK", title)
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity start failed: $e")
            writeDebugLog("fg.startActivityFAIL", e.message ?: "unknown")
        }

        // 2. Also post a NotificationCompat with setFullScreenIntent as a backup.
        //    If the OS blocks the direct launch, Android should fall back to
        //    launching via the full-screen intent pending intent.
        val pending = PendingIntent.getActivity(
            this,
            notifId.hashCode(),
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (typeLabel.isNotEmpty()) "[$typeLabel] $body" else body
        val notif = NotificationCompat.Builder(this, TacticalApplication.EMERGENCY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notifId.hashCode(), notif)
            Log.d(TAG, "Posted backup notification id=${notifId.hashCode()}")
        } catch (e: Exception) {
            Log.w(TAG, "notify() failed", e)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🟢 מבצעים פעיל")
            .setContentText("מאזין להתראות חירום ברקע")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .setShowWhen(false)
            .build()
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(FG_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            FG_CHANNEL_ID,
            "שירות האזנה",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "שומר את האפליקציה פעילה לקבלת התראות חירום בזמן אמת"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun writeDebugLog(event: String, detail: String) {
        try {
            val prefs = getSharedPreferences("tac_prefs", Context_MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", "unknown") ?: "unknown"
            FirebaseDatabase.getInstance()
                .getReference("tac_debug_fcm/$deviceId").push().setValue(
                    hashMapOf(
                        "ts" to System.currentTimeMillis(),
                        "event" to event,
                        "title" to detail
                    )
                )
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "TacFgSvc"
        const val FG_NOTIF_ID = 1337
        const val FG_CHANNEL_ID = "tac_foreground_v1"
    }
}
