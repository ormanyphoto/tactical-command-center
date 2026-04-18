# TCC iOS — Setup & Build Guide

Native iOS/iPadOS shell for the Tactical Command Center web app. Architecture mirrors the Android build: a custom WKWebView hosting the web app at `https://tactical-command-center.web.app`, with native bridges for location, FCM push, haptics, and SOS critical alerts.

**Targets:** iPhone + iPad, iOS 15.0+, universal.

---

## 1. Prerequisites

- Mac with **Xcode 15+**
- Apple Developer account (paid: $99/yr) — needed for push notifications + App Store
- The Firebase project `tactical-command-center` (already set up for web + android)

---

## 2. One-time Firebase setup

1. Go to **[Firebase Console](https://console.firebase.google.com/project/tactical-command-center/settings/general)** → "Add app" → **iOS**.
2. Bundle ID: `com.tacops.commandcenter` (must match Xcode target).
3. Download `GoogleService-Info.plist` and drop it into `ios/TacticalCommandCenter/SupportingFiles/` (same folder as `Info.plist`).
   - Add it to the Xcode project (drag into the *SupportingFiles* group, check "Copy items if needed", check the target).
4. In Firebase Console → **Cloud Messaging** → upload your **APNs Authentication Key** (`.p8` file) from [Apple Developer → Keys](https://developer.apple.com/account/resources/authkeys/list). This is what lets Firebase send push notifications to iOS devices.

---

## 3. Create the Xcode project

Apple's project file format (`.xcodeproj/project.pbxproj`) is fragile, so rather than committing a hand-crafted one, create the project through Xcode once and then the provided source files slot in.

```bash
cd ios
open -a Xcode
```

In Xcode:

1. **File → New → Project → iOS → App**
2. Product Name: `TacticalCommandCenter`
3. Team: select your team
4. Organization Identifier: `com.tacops`
5. Bundle Identifier (auto): `com.tacops.commandcenter`
6. Interface: **Storyboard** (even though we host WKWebView, a LaunchScreen.storyboard is still required)
7. Language: **Swift**
8. Save location: the existing `ios/` folder (so `ios/TacticalCommandCenter.xcodeproj` is created alongside the provided Sources/)

After Xcode creates the project:

1. **Delete** the auto-generated files (`ViewController.swift`, `SceneDelegate.swift`, `Main.storyboard`, `AppDelegate.swift` that Xcode made for you).
2. **Drag the provided folders into the Navigator**:
   - `TacticalCommandCenter/Sources/` — all 6 Swift files
   - `TacticalCommandCenter/SupportingFiles/Info.plist` — replace the auto-generated one
   - `TacticalCommandCenter/SupportingFiles/TacticalCommandCenter.entitlements`
   - `TacticalCommandCenter/Resources/` (add your icon assets here — see §5)
3. In **target settings → General**:
   - Deployment Info: iOS 15.0
   - Supported Destinations: iPhone, iPad
   - Main Interface: **clear it out** (we use code-based UI from `AppDelegate.swift`)
   - Launch Screen File: `LaunchScreen`
4. In **target settings → Signing & Capabilities**:
   - Add capability: **Push Notifications**
   - Add capability: **Background Modes** → check *Location updates*, *Remote notifications*, *Background fetch*, *Background processing*
   - Set the entitlements file path to `TacticalCommandCenter/SupportingFiles/TacticalCommandCenter.entitlements`
5. In **target settings → Build Settings**:
   - Remove `SceneDelegate` references — we use pure `AppDelegate`-driven UIWindow
   - Search `UIApplicationSceneManifest` in Info.plist and delete that key if it exists (Xcode may have added it)

---

## 4. Install Firebase SDK

### Option A: Swift Package Manager (recommended)

Xcode → **File → Add Package Dependencies** → `https://github.com/firebase/firebase-ios-sdk` → choose latest version → add products: **FirebaseMessaging**, **FirebaseCore**.

### Option B: CocoaPods

```bash
cd ios
sudo gem install cocoapods   # first time only
pod install
```

Then open `ios/TacticalCommandCenter.xcworkspace` (NOT the `.xcodeproj`) in Xcode.

---

## 5. App icons & launch screen

Minimum set:

- **App icon** — put a 1024×1024 PNG at `ios/TacticalCommandCenter/Resources/AppIcon.png`, then Xcode → Assets.xcassets → AppIcon → drag it in. Xcode 14+ auto-generates all sizes from the 1024.
- **Launch screen** — open `LaunchScreen.storyboard`, set the background to `#07090a` (dark navy), add a centered `UIImageView` with a hexagon logo and the text "מערכת מבצעים".

Easiest: copy the `icon-512.png` from the repo root into `AppIcon.appiconset/` and Xcode will take care of the rest.

---

## 6. Build & run

1. Plug in an iPhone/iPad, or use the simulator.
2. **Product → Run** (⌘R).
3. On first launch the app will ask for:
   - Notifications permission
   - Location permission (While Using → then Always)
4. The web app should load. If you see a white screen, check the Xcode console for `[Web] did fail` messages.

---

## 7. Critical Alerts entitlement (for SOS)

For SOS notifications to bypass silent mode and Do Not Disturb (like they do on Android), request the **Critical Alerts** entitlement from Apple:

1. Go to [Critical Alerts Request](https://developer.apple.com/contact/request/notifications-critical-alerts-entitlement/).
2. Describe the use case: *"Tactical command & control system for emergency services. SOS notifications must bypass silent mode so field operatives receive life-safety alerts."*
3. Apple usually approves in 1–3 business days for legitimate tactical/emergency apps.
4. Once approved, the entitlements file's `com.apple.developer.usernotifications.critical-alerts = true` will activate.

Without this entitlement, the key is ignored and emergency notifications behave like regular ones.

---

## 8. Release to TestFlight / App Store

1. **Product → Archive**
2. **Window → Organizer** → Distribute App → App Store Connect.
3. Wait ~15 min for Apple to process.
4. Invite testers via TestFlight.
5. For App Store release: fill out the App Store Connect listing (description, screenshots for iPhone 6.5" + iPad 12.9", review notes mentioning tactical use-case).

---

## 9. Troubleshooting

- **Push token not arriving**: check that `GoogleService-Info.plist` is in the target (Target Membership checkbox); verify APNs key is uploaded in Firebase Console.
- **Location updates stop in background**: verify `UIBackgroundModes` → `location` in Info.plist AND `allowsBackgroundLocationUpdates = true` in `LocationManager.swift`. The user must have granted "Always" permission (not just "While Using").
- **White screen on launch**: likely a TLS/CSP issue — test that `https://tactical-command-center.web.app` loads in mobile Safari first.
- **Web app shows "broken image" placeholders on markers**: that was a mobile WebView bug fixed in web v6.8.27 — hard-reload the webview (Settings → Safari → Advanced → Website Data → remove site) and relaunch.
