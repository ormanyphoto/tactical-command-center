// AppDelegate.swift — app lifecycle, push registration, Firebase init.
import UIKit
import UserNotifications
import FirebaseCore
import FirebaseMessaging

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {

    var window: UIWindow?

    // ─── Launch ────────────────────────────────────────────────────────────
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {

        // Firebase — reads GoogleService-Info.plist from the bundle.
        FirebaseApp.configure()

        // Push notifications — ask for permission including Critical Alerts
        // (needed so SOS/emergency bypass silent mode and DND). Critical
        // Alerts requires the com.apple.developer.usernotifications.critical-alerts
        // entitlement which Apple grants on request — see SETUP.md.
        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self

        let options: UNAuthorizationOptions = [.alert, .sound, .badge, .criticalAlert, .providesAppNotificationSettings]
        UNUserNotificationCenter.current().requestAuthorization(options: options) { granted, error in
            if let error = error {
                NSLog("[Push] auth error: \(error.localizedDescription)")
            } else {
                NSLog("[Push] permission granted: \(granted)")
            }
            DispatchQueue.main.async {
                application.registerForRemoteNotifications()
            }
        }

        // Register the emergency notification category so the OS knows the
        // action buttons when an SOS notification arrives.
        let ackAction = UNNotificationAction(identifier: "ACK", title: "✓ קיבלתי", options: [.foreground])
        let openAction = UNNotificationAction(identifier: "OPEN", title: "📂 פתח", options: [.foreground])
        let emergencyCategory = UNNotificationCategory(
            identifier: "EMERGENCY",
            actions: [openAction, ackAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        UNUserNotificationCenter.current().setNotificationCategories([emergencyCategory])

        // Root view hosting the WKWebView.
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = RootViewController()
        window?.makeKeyAndVisible()

        return true
    }

    // ─── APNs token → FCM ──────────────────────────────────────────────────
    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
        NSLog("[Push] APNs token registered (\(deviceToken.count) bytes)")
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("[Push] APNs registration failed: \(error.localizedDescription)")
    }

    // ─── FCM token → send to our Firebase RTDB keyed by person id ──────────
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        NSLog("[FCM] token: \(token.prefix(20))...")
        PushTokenManager.shared.setCurrentToken(token)
    }

    // ─── Foreground notification presentation ──────────────────────────────
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Show even in foreground for emergency notifications
        completionHandler([.banner, .sound, .list, .badge])
    }

    // ─── Tap on notification → forward to the web app via JS bridge ────────
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        NSLog("[Push] tapped (\(action)) payload=\(userInfo)")

        // Post to the web app so it can open the emergency modal for the notif id.
        if let notifId = userInfo["notifId"] as? String ?? userInfo["id"] as? String {
            NativeBridge.shared.postToWeb(event: "SHOW_INCOMING_NOTIF", data: [
                "notifId": notifId,
                "userInfo": userInfo,
                "action": action
            ])
        }
        completionHandler()
    }
}
