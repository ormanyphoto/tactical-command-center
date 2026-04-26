package com.tacops.commandcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    // ── Background Location Tracking ──
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var lastLocPublishTime = 0L

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
        stopLocationTracking()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildPersistentNotification()
        val started = tryStartForeground(notif)
        if (!started) {
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

    /**
     * Call startForeground with a cascade of service type fallbacks so the
     * service survives even if one type is denied. On Android 14+ we prefer
     * SPECIAL_USE (matches our use case best) and fall back to DATA_SYNC if
     * that fails. On older Android any positive call is fine.
     * Each step is logged to Firebase so we can see the exact failure reason
     * in the debug panel.
     */
    private fun tryStartForeground(notif: Notification): Boolean {
        // Attempt 1: SPECIAL_USE + LOCATION combined (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(FG_NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                Log.d(TAG, "startForeground OK (SPECIAL_USE|LOCATION)")
                writeDebugLog("fg.startForegroundOK", "SPECIAL_USE|LOCATION")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "SPECIAL_USE|LOCATION failed: ${e.javaClass.simpleName} ${e.message}")
                writeDebugLog("fg.fgCombinedFail", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
            // Fallback: SPECIAL_USE only
            try {
                startForeground(FG_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Log.d(TAG, "startForeground OK (SPECIAL_USE only)")
                writeDebugLog("fg.startForegroundOK", "SPECIAL_USE")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "SPECIAL_USE failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        // Attempt 2: LOCATION only (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(FG_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                Log.d(TAG, "startForeground OK (LOCATION)")
                writeDebugLog("fg.startForegroundOK", "LOCATION")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "LOCATION failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        // Attempt 3: DATA_SYNC (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(FG_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                Log.d(TAG, "startForeground OK (DATA_SYNC)")
                writeDebugLog("fg.startForegroundOK", "DATA_SYNC")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "DATA_SYNC failed: ${e.javaClass.simpleName} ${e.message}")
                writeDebugLog("fg.fgDataSyncFail", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
        // Attempt 3: no-type (pre-Q or legacy)
        try {
            @Suppress("DEPRECATION")
            startForeground(FG_NOTIF_ID, notif)
            Log.d(TAG, "startForeground OK (no type)")
            writeDebugLog("fg.startForegroundOK", "legacy")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All startForeground attempts failed", e)
            writeDebugLog("fg.startForegroundFAIL", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            return false
        }
    }

    private fun waitForAuthThenAttach(pid: String, attempt: Int) {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            currentPid = pid
            attachListener(pid)
            startLocationTracking(pid)
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
            startLocationTracking(pid)
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
            .setContentText("מאזין להתראות חירום + מיקום חי ברקע")
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

    // ── Background Location Tracking ──
    // Publishes GPS to Firebase tac_locs/{uid} every 15 seconds while the
    // app is in background. Uses the same path as the WebView JS publisher
    // so the commander sees live locations seamlessly.
    private fun startLocationTracking(pid: String) {
        if (locationListener != null) return // already tracking

        // Check permission at runtime
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No FINE_LOCATION permission — skip background location")
            writeDebugLog("fg.locNoPerm", "skip background location")
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) return

        // Read the user profile from SharedPreferences (set by NativeBridge)
        val prefs = getSharedPreferences("tac_prefs", Context_MODE_PRIVATE)
        // Read user profile: try native bridge prefs first, then field-mode keys
        var name = prefs.getString("userName", null)
        if (name.isNullOrEmpty()) {
            // Field mode stores name under a different key via localStorage bridge
            name = prefs.getString("tac_field_name", null) ?: "שדה"
        }
        var role = prefs.getString("userRole", null)
        if (role.isNullOrEmpty()) {
            role = prefs.getString("tac_field_role", null) ?: "לוחם"
        }
        val uid = prefs.getString("deviceId", null) ?: return

        // Check if location publishing is enabled (user toggled ON in the app)
        val locEnabled = prefs.getBoolean("locPublishEnabled", true)
        if (!locEnabled) {
            Log.d(TAG, "Location publish disabled by user preference")
            return
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val now = System.currentTimeMillis()
                // Throttle to every 15 seconds
                if (now - lastLocPublishTime < 5_000L) return
                lastLocPublishTime = now

                val data = hashMapOf<String, Any>(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "acc" to loc.accuracy.toDouble(),
                    "spd" to (loc.speed * 3.6).toDouble(), // m/s → km/h
                    "ts" to now,
                    "name" to name,
                    "role" to role,
                    "status" to "active",
                    "personId" to pid,
                    "platform" to "android",
                    "src" to "bg" // "bg" = background service (vs "fg" = foreground WebView)
                )
                try {
                    FirebaseDatabase.getInstance()
                        .getReference("tac_locs/$uid")
                        .setValue(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Location publish failed", e)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // Request updates every 3s / 5m — fast enough for smooth map tracking
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                3_000L,  // minTime 3s
                5f,      // minDistance 5m
                locationListener!!
            )
            // Also request from network provider as fallback
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5_000L, 20f,
                    locationListener!!
                )
            } catch (_: Exception) {}

            Log.d(TAG, "Background location tracking started for $pid")
            writeDebugLog("fg.locStarted", "uid=$uid pid=$pid")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission denied at runtime", e)
            writeDebugLog("fg.locSecurityEx", e.message ?: "")
        }
    }

    private fun stopLocationTracking() {
        locationListener?.let {
            locationManager?.removeUpdates(it)
        }
        locationListener = null
        locationManager = null
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
