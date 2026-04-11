# Native Android App Setup

This is the NATIVE Android app (not just a TWA wrapper). It includes:

- **TacticalApplication**: Creates a high-importance notification channel (bypasses DND, plays alarm sound)
- **MyFirebaseMessagingService**: Receives FCM data messages directly and shows notifications with a `FullScreenIntent`
- **LockScreenAlertActivity**: Full-screen emergency alert that wakes the screen, shows over the lock screen, plays a looping alarm, and offers "open app" / "acknowledge" buttons.

## One-Time Firebase Console Setup (required)

The `google-services.json` file in this directory has a placeholder `mobilesdk_app_id`. You need to register the Android app with Firebase Console once:

1. Go to <https://console.firebase.google.com/project/tactical-command-center/settings/general>
2. In the **"Your apps"** section, click **"Add app"** and choose the **Android** icon
3. Fill in:
   - **Android package name**: `com.tacops.commandcenter`
   - **App nickname**: `Tactical Command Center Android`
   - **Debug signing certificate SHA-1**: *(leave blank — we use FCM, not Google Sign-In)*
4. Click **"Register app"**
5. **Download `google-services.json`**
6. Replace `android/app/google-services.json` in this repo with the downloaded file
7. Commit and push — GitHub Actions will build the APK automatically
8. Download the APK from the GitHub Release and install on your device

The downloaded `google-services.json` contains only public API keys that are safe to commit to the repo.

## How the native notification pipeline works

```
 Firebase FCM
     │  (data-only message, android.priority=HIGH)
     ▼
 MyFirebaseMessagingService.onMessageReceived
     │  Build NotificationCompat with setFullScreenIntent(pi, true)
     ▼
 Android system sees FSI + IMPORTANCE_HIGH channel
     │  Phone LOCKED → launches PendingIntent directly
     ▼
 LockScreenAlertActivity
     │  • setShowWhenLocked(true)
     │  • setTurnScreenOn(true)
     │  • WakeLock FULL_WAKE_LOCK
     │  • MediaPlayer plays alarm sound (USAGE_ALARM)
     │  • Vibrator pattern
     ▼
 User taps "פתח באפליקציה" → launches TWA LauncherActivity → web app
                       or "קיבלתי ✓" → dismisses
```

## Permissions used

- `INTERNET` — Firebase
- `WAKE_LOCK` — keep screen on during alert
- `USE_FULL_SCREEN_INTENT` — launch LockScreenAlertActivity over the lock screen
- `POST_NOTIFICATIONS` — Android 13+ notification permission
- `VIBRATE` — alert vibration pattern
- `DISABLE_KEYGUARD` — dismiss keyguard when presenting alert
- `SCHEDULE_EXACT_ALARM` — reserved for future alarm scheduling

## Testing

1. Install the APK on your device
2. Open the app once so it registers the FCM token (visible in Firebase under `tac_fcm_tokens_native/<personId>/<deviceId>`)
3. Lock the phone
4. From another device (or the web app) send an emergency notification
5. The phone screen should wake and show `LockScreenAlertActivity` with alarm sound
