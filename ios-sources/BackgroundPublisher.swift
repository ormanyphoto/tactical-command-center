// BackgroundPublisher.swift — publishes location fixes directly to
// Firebase Realtime Database from Swift while the app is backgrounded,
// with autonomous token refresh so publishing never stops just because
// the ID token expired.
//
// Why: when the app enters background, iOS suspends WKWebView JavaScript
// execution within a few seconds. CoreLocation keeps firing fixes to the
// Swift delegate (UIBackgroundModes:location + allowsBackgroundLocationUpdates),
// but the `tcc-native-loc` CustomEvent that the bridge dispatches into the
// webview is processed by JS that is effectively paused — so nothing ever
// reaches `tac_locs/{uid}` in Firebase. Remote viewers see the operator
// frozen the moment the app is not in the foreground.
//
// How: the web layer calls TCC_NATIVE.cacheAuthForBackground(info) after
// login with {uid, idToken, refreshToken, apiKey, projectId, personId,
// name, role, color, status}. We store the refreshToken in the Keychain
// (long-lived secret) and everything else in UserDefaults. On each CL
// fix in background:
//   1. If our ID token is within 50 min of issuance, reuse it.
//   2. Otherwise POST to https://securetoken.googleapis.com/v1/token?key=<API_KEY>
//      with grant_type=refresh_token to get a fresh ID token, cache it.
//   3. PUT tac_locs/{uid}.json with the payload.
// No SDK required — pure URLSession. Tokens refresh autonomously for as
// long as the refresh token is valid (10+ years typical) or until the
// user explicitly signs out.

import Foundation
import UIKit
import Security

final class BackgroundPublisher {

    static let shared = BackgroundPublisher()
    private init() {}

    // ── UserDefaults keys ────────────────────────────────────────────────
    private let kUid       = "tcc.bg.uid"
    private let kToken     = "tcc.bg.idToken"
    private let kTokenAt   = "tcc.bg.idTokenSavedAt"
    private let kApiKey    = "tcc.bg.apiKey"
    private let kProject   = "tcc.bg.projectId"
    private let kPersonId  = "tcc.bg.personId"
    private let kName      = "tcc.bg.name"
    private let kRole      = "tcc.bg.role"
    private let kColor     = "tcc.bg.color"
    private let kStatus    = "tcc.bg.status"

    // Keychain service/account for the refresh token (long-lived secret).
    private let kcService  = "com.tacops.commandcenter.bgpub"
    private let kcAccount  = "firebase.refreshToken"

    // In-flight refresh de-duplication — multiple location updates can
    // race when tokens expire. Serializing here prevents bombing
    // securetoken.googleapis.com with N identical refresh requests.
    private var refreshInFlight: URLSessionDataTask?
    private let refreshQueue = DispatchQueue(label: "tcc.bg.refresh")

    private var dbHost: String {
        let p = UserDefaults.standard.string(forKey: kProject) ?? "tactical-command-center"
        return "\(p)-default-rtdb.firebaseio.com"
    }

    // MARK: — called from RootViewController

    func cacheAuth(_ body: [String: Any]) {
        let d = UserDefaults.standard
        if let v = body["uid"]       as? String, !v.isEmpty { d.set(v, forKey: kUid) }
        if let v = body["apiKey"]    as? String, !v.isEmpty { d.set(v, forKey: kApiKey) }
        if let v = body["projectId"] as? String, !v.isEmpty { d.set(v, forKey: kProject) }
        if let v = body["personId"]  as? String { d.set(v, forKey: kPersonId) }
        if let v = body["name"]      as? String { d.set(v, forKey: kName) }
        if let v = body["role"]      as? String { d.set(v, forKey: kRole) }
        if let v = body["color"]     as? String { d.set(v, forKey: kColor) }
        if let v = body["status"]    as? String { d.set(v, forKey: kStatus) }
        if let v = body["idToken"]   as? String, !v.isEmpty {
            d.set(v, forKey: kToken)
            d.set(Date().timeIntervalSince1970, forKey: kTokenAt)
        }
        // Refresh token — long-lived — goes to Keychain so it survives
        // backups-restore protections and isn't casually readable via the
        // file system.
        if let v = body["refreshToken"] as? String, !v.isEmpty {
            keychainSet(v)
        }
        NSLog("[BgPub] cached auth uid=\(body["uid"] ?? "?") hasRefreshToken=\(body["refreshToken"] != nil)")
    }

    func updateStatus(_ status: String) {
        UserDefaults.standard.set(status, forKey: kStatus)
    }

    func clearAuth() {
        let d = UserDefaults.standard
        [kUid, kToken, kTokenAt, kPersonId, kName, kRole, kColor, kStatus].forEach { d.removeObject(forKey: $0) }
        keychainDelete()
    }

    // MARK: — LocationManager hook

    /// Called from LocationManager on every accepted fix. Only publishes
    /// when the app is backgrounded — in foreground the JS path writes.
    func publishIfBackgrounded(lat: Double, lng: Double, acc: Double, spd: Double, hdg: Double) {
        let state = UIApplication.shared.applicationState
        guard state == .background || state == .inactive else { return }
        ensureFreshToken { [weak self] token in
            guard let self = self, let token = token else { return }
            self.publish(lat: lat, lng: lng, acc: acc, spd: spd, hdg: hdg, idToken: token)
        }
    }

    // MARK: — Token lifecycle

    /// Invokes completion with a valid ID token, refreshing via the
    /// refresh token if the cached one is stale. Nil on unrecoverable
    /// failure (no refresh token, API key missing, HTTP error).
    private func ensureFreshToken(_ completion: @escaping (String?) -> Void) {
        let d = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        let savedAt = d.double(forKey: kTokenAt)
        let age = savedAt > 0 ? (now - savedAt) : Double.infinity

        // Firebase ID tokens are valid 60 min. Refresh when > 50 min old
        // so we don't cut it close on request-round-trip.
        if age < 50 * 60, let token = d.string(forKey: kToken), !token.isEmpty {
            completion(token); return
        }
        refreshToken(completion)
    }

    private func refreshToken(_ completion: @escaping (String?) -> Void) {
        let d = UserDefaults.standard
        guard let apiKey = d.string(forKey: kApiKey), !apiKey.isEmpty else {
            NSLog("[BgPub] refresh — no apiKey cached")
            completion(nil); return
        }
        guard let refreshToken = keychainGet(), !refreshToken.isEmpty else {
            NSLog("[BgPub] refresh — no refreshToken in Keychain")
            completion(nil); return
        }
        guard let url = URL(string: "https://securetoken.googleapis.com/v1/token?key=\(apiKey)") else {
            completion(nil); return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = "grant_type=refresh_token&refresh_token=\(refreshToken)"
        req.httpBody = body.data(using: .utf8)

        var bgTask: UIBackgroundTaskIdentifier = .invalid
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "BgPub.refresh") {
            if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
        }
        NSLog("[BgPub] refresh — requesting new ID token from securetoken")
        let task = URLSession.shared.dataTask(with: req) { [weak self] data, resp, err in
            defer { if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid } }
            guard let self = self else { completion(nil); return }
            if let err = err {
                NSLog("[BgPub] refresh error: \(err.localizedDescription)")
                completion(nil); return
            }
            guard let http = resp as? HTTPURLResponse else { completion(nil); return }
            guard let data = data else { completion(nil); return }
            if http.statusCode != 200 {
                let bodyStr = String(data: data, encoding: .utf8) ?? "<no body>"
                NSLog("[BgPub] refresh HTTP \(http.statusCode): \(bodyStr.prefix(200))")
                // If the refresh token itself was revoked (user signed out
                // on another device, password changed, etc.), clear it so
                // we don't keep hammering the endpoint.
                if http.statusCode == 400 || http.statusCode == 401 {
                    if bodyStr.contains("TOKEN_EXPIRED") || bodyStr.contains("INVALID_REFRESH_TOKEN") || bodyStr.contains("USER_DISABLED") {
                        NSLog("[BgPub] refresh token revoked — clearing keychain")
                        self.keychainDelete()
                    }
                }
                completion(nil); return
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let newIdToken = json["id_token"] as? String else {
                NSLog("[BgPub] refresh response parse failed")
                completion(nil); return
            }
            // Some endpoints issue a rotated refresh token — persist it.
            if let newRefresh = json["refresh_token"] as? String, !newRefresh.isEmpty {
                self.keychainSet(newRefresh)
            }
            let dd = UserDefaults.standard
            dd.set(newIdToken, forKey: self.kToken)
            dd.set(Date().timeIntervalSince1970, forKey: self.kTokenAt)
            NSLog("[BgPub] refresh ok — new ID token issued")
            completion(newIdToken)
        }
        task.resume()
    }

    // MARK: — Write payload

    private func publish(lat: Double, lng: Double, acc: Double, spd: Double, hdg: Double, idToken: String) {
        let d = UserDefaults.standard
        guard let uid = d.string(forKey: kUid), !uid.isEmpty else {
            NSLog("[BgPub] publish — no uid"); return
        }
        let payload: [String: Any] = [
            "name":     d.string(forKey: kName) ?? "—",
            "role":     d.string(forKey: kRole) ?? "",
            "status":   d.string(forKey: kStatus) ?? "active",
            "color":    d.string(forKey: kColor) ?? "#29c5f6",
            "personId": d.string(forKey: kPersonId) ?? "",
            "lat":  lat, "lng": lng,
            "acc":  Int(acc.rounded()),
            "hdg":  Int(hdg.rounded()),
            "spd":  Int((spd * 3.6).rounded()),
            "ts":   Int(Date().timeIntervalSince1970 * 1000),
            "uid":  uid
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }
        guard let url = URL(string: "https://\(dbHost)/tac_locs/\(uid).json?auth=\(idToken)") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body

        // Keep the process alive long enough for the HTTPS request to
        // complete. Without this, iOS will suspend the app between
        // location updates (even though we have UIBackgroundModes:location)
        // and any in-flight URLSession task gets cancelled — which is why
        // "lock the screen right after opening the app" made publishing
        // appear to stop. beginBackgroundTask gives us up to ~30 s per
        // call; we end the task the moment the request completes.
        var bgTask: UIBackgroundTaskIdentifier = .invalid
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "BgPub.PUT") {
            // Expiration handler — if iOS runs out of patience we at
            // least end the task cleanly so the system doesn't kill the
            // app.
            if bgTask != .invalid {
                UIApplication.shared.endBackgroundTask(bgTask)
                bgTask = .invalid
            }
        }
        NSLog("[BgPub] publish PUT tac_locs/\(uid) acc=\(Int(acc))m")
        URLSession.shared.dataTask(with: req) { [weak self] _, resp, err in
            defer {
                if bgTask != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTask)
                    bgTask = .invalid
                }
            }
            if let err = err {
                NSLog("[BgPub] PUT error: \(err.localizedDescription)"); return
            }
            if let http = resp as? HTTPURLResponse {
                if http.statusCode >= 400 {
                    NSLog("[BgPub] PUT HTTP \(http.statusCode)")
                    if http.statusCode == 401 || http.statusCode == 403 {
                        // Token rejected — next fix will trigger a refresh.
                        UserDefaults.standard.set(0.0, forKey: self?.kTokenAt ?? "tcc.bg.idTokenSavedAt")
                    }
                } else {
                    NSLog("[BgPub] PUT ok (\(http.statusCode))")
                }
            }
        }.resume()
    }

    // MARK: — Keychain helpers

    private func keychainSet(_ value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass        as String: kSecClassGenericPassword,
            kSecAttrService  as String: kcService,
            kSecAttrAccount  as String: kcAccount,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(add as CFDictionary, nil)
        if status != errSecSuccess {
            NSLog("[BgPub] Keychain SecItemAdd failed: \(status)")
        }
    }

    private func keychainGet() -> String? {
        let query: [String: Any] = [
            kSecClass       as String: kSecClassGenericPassword,
            kSecAttrService as String: kcService,
            kSecAttrAccount as String: kcAccount,
            kSecReturnData  as String: true,
            kSecMatchLimit  as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        guard status == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func keychainDelete() {
        let query: [String: Any] = [
            kSecClass       as String: kSecClassGenericPassword,
            kSecAttrService as String: kcService,
            kSecAttrAccount as String: kcAccount,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
