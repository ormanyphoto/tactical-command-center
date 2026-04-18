// AppConfig.swift — central config for the TCC iOS app.
// Change these values when pointing the app at a different environment.
import Foundation

enum AppConfig {
    /// The web app URL loaded by the WKWebView.
    static let webAppURL = URL(string: "https://tactical-command-center.web.app")!

    /// Firebase Cloud Messaging project ID — matches web + android.
    static let fcmSenderID = "31609493041"

    /// Realtime Database base URL for REST-based fallback writes
    /// (parity with web app's REST-primary pattern).
    static let databaseRestBase = "https://tactical-command-center-default-rtdb.firebaseio.com"

    /// App display name shown in settings/status bar contexts.
    static let appDisplayName = "מערכת מבצעים"

    /// User-Agent suffix so the web app can detect the native iOS wrapper.
    /// The server-side `isNativeApp` detection checks for this substring.
    static let userAgentSuffix = "TCC-iOS/1.0"
}
