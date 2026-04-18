# Tactical Command Center — iOS / iPadOS

Native WKWebView shell around the TCC web app, with Firebase Cloud Messaging, Core Location background tracking, and Critical Alerts for SOS.

**Parity with the Android build:** same web app URL, same FCM sender, same Firebase project, same push-notification payload format.

## Structure

```
ios/
├── TacticalCommandCenter/
│   ├── Sources/                   # 6 Swift files
│   │   ├── AppConfig.swift           • web URL, FCM sender id, build metadata
│   │   ├── AppDelegate.swift         • launch, Firebase init, push registration
│   │   ├── RootViewController.swift  • WKWebView + JS↔native bridge
│   │   ├── LocationManager.swift     • Core Location background tracking
│   │   ├── PushTokenManager.swift    • FCM token → web app
│   │   ├── NativeBridge.swift        • central post-to-web dispatcher
│   │   └── HapticsHelper.swift       • vibrate() polyfill
│   ├── SupportingFiles/
│   │   ├── Info.plist                        • permissions, background modes, RTL
│   │   ├── TacticalCommandCenter.entitlements • aps-environment, critical-alerts
│   │   └── GoogleService-Info.plist          • YOU ADD from Firebase Console
│   └── Resources/                   # Assets (app icon, launch screen assets)
├── Podfile                          # Alternative to SPM for Firebase
├── SETUP.md                         # Step-by-step Xcode setup
└── README.md                        # This file
```

## Build flow

1. **One-time**: follow [`SETUP.md`](./SETUP.md) to create the Xcode project, drop in these files, install Firebase SDK, and configure signing.
2. **Iterate**: edit web app → push to Firebase Hosting → the iOS app picks up the new version on next launch (since the WKWebView always fetches fresh).
3. **Native changes** (e.g., add a new JS↔native bridge method) require an Xcode build and TestFlight release.

## What the web app can call

The WKWebView injects a `TCC_NATIVE` global:

```js
TCC_NATIVE.platform          // 'ios'
TCC_NATIVE.startLocation()   // begin GPS streaming (fires 'tcc-native-loc')
TCC_NATIVE.stopLocation()
TCC_NATIVE.setStatus('busy') // persisted in UserDefaults
TCC_NATIVE.requestPushToken()// triggers 'tcc-fcm-token' event with the FCM token
TCC_NATIVE.vibrate([400,150,400])
TCC_NATIVE.openExternal(url) // open in Safari / native app
TCC_NATIVE.log('message')    // forward to NSLog
```

Events the web app can listen for:

```js
window.addEventListener('tcc-fcm-token',      e => e.detail.token)
window.addEventListener('tcc-native-loc',     e => e.detail) // {lat,lng,acc,spd,hdg,ts}
window.addEventListener('tcc-show_incoming_notif', e => e.detail.notifId)
```

## Version

Match the web app version. Update `CFBundleShortVersionString` in `Info.plist` alongside each web release.

Current: **v6.8.40** (matching web app).
