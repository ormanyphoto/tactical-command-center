// BackgroundPublisher.swift — publishes location fixes directly to
// Firebase Realtime Database from Swift when the app is backgrounded.
//
// Why: when the app enters background, iOS suspends WKWebView JavaScript
// execution within a few seconds. CoreLocation keeps delivering fixes to
// the Swift delegate (UIBackgroundModes:location + allowsBackgroundLocationUpdates),
// but the `tcc-native-loc` CustomEvent that the bridge dispatches into the
// webview is processed by JS that is effectively paused — so nothing ever
// reaches `tac_locs/{uid}` in Firebase. Remote viewers see the operator
// frozen in place for the entire duration the app is not in the foreground.
//
// Fix: while the app is in the background, publish each accepted location
// fix via Firebase RTDB REST PATCH ourselves. Requires the web layer to
// cache the user's uid, a valid ID token, and their profile metadata (via
// TCC_NATIVE.cacheAuthForBackground — see RootViewController.swift) so we
// can author the same payload shape as the web's watchPosition callback.
//
// Token lifecycle: Firebase ID tokens expire after 1 hour. The web layer
// re-caches the token every ~30 min while foregrounded, which covers the
// majority of "pocket during a mission" cases. Once the token expires
// and the app hasn't been foregrounded, background writes will fail with
// 401 — at which point the only recovery is the user opening the app
// again (which refreshes the token).

import Foundation
import UIKit

final class BackgroundPublisher {

    static let shared = BackgroundPublisher()
    private init() {}

    // Keys stored in UserDefaults (not Keychain — ID tokens are short-lived
    // and non-transferable; worst case of UserDefaults leak is an hour of
    // extra database write capability for an attacker who already has
    // physical+unlock access to the device).
    private let kUid      = "tcc.bg.uid"
    private let kToken    = "tcc.bg.idToken"
    private let kTokenAt  = "tcc.bg.idTokenSavedAt"
    private let kPersonId = "tcc.bg.personId"
    private let kName     = "tcc.bg.name"
    private let kRole     = "tcc.bg.role"
    private let kColor    = "tcc.bg.color"
    private let kStatus   = "tcc.bg.status"

    private let dbHost = "tactical-command-center-default-rtdb.firebaseio.com"

    // MARK: — called from RootViewController.userContentController

    func cacheAuth(_ body: [String: Any]) {
        let d = UserDefaults.standard
        if let v = body["uid"]      as? String, !v.isEmpty { d.set(v, forKey: kUid) }
        if let v = body["idToken"]  as? String, !v.isEmpty {
            d.set(v, forKey: kToken)
            d.set(Date().timeIntervalSince1970, forKey: kTokenAt)
        }
        if let v = body["personId"] as? String { d.set(v, forKey: kPersonId) }
        if let v = body["name"]     as? String { d.set(v, forKey: kName) }
        if let v = body["role"]     as? String { d.set(v, forKey: kRole) }
        if let v = body["color"]    as? String { d.set(v, forKey: kColor) }
        if let v = body["status"]   as? String { d.set(v, forKey: kStatus) }
        NSLog("[BgPub] cached auth for uid=\(body["uid"] ?? "?")")
    }

    func updateStatus(_ status: String) {
        UserDefaults.standard.set(status, forKey: kStatus)
    }

    // MARK: — called from LocationManager's onUpdate

    /// Publish a fix to Firebase via REST. Returns immediately; HTTP
    /// request runs on URLSession's default background queue. Only
    /// publishes when the app is in the background — when foregrounded,
    /// the web JS path handles publishing to avoid double-writes.
    func publishIfBackgrounded(lat: Double, lng: Double, acc: Double, spd: Double, hdg: Double) {
        let state = UIApplication.shared.applicationState
        guard state == .background || state == .inactive else {
            // Foregrounded — let the WKWebView JS do it (it's already hooked
            // via tcc-native-loc and will run real-time).
            return
        }
        let d = UserDefaults.standard
        guard let uid = d.string(forKey: kUid), !uid.isEmpty else {
            NSLog("[BgPub] skip — no cached uid"); return
        }
        guard let token = d.string(forKey: kToken), !token.isEmpty else {
            NSLog("[BgPub] skip — no cached idToken"); return
        }
        // Token age sanity check — Firebase ID tokens expire at 1 h; skip
        // once we know it's staler than that, rather than firing requests
        // that all fail with 401.
        let tokenAge = Date().timeIntervalSince1970 - d.double(forKey: kTokenAt)
        if tokenAge > 55 * 60 {
            NSLog("[BgPub] skip — token age=\(Int(tokenAge))s > 55min"); return
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
        guard let url = URL(string: "https://\(dbHost)/tac_locs/\(uid).json?auth=\(token)") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        URLSession.shared.dataTask(with: req) { _, resp, err in
            if let err = err {
                NSLog("[BgPub] PUT error: \(err.localizedDescription)")
                return
            }
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                NSLog("[BgPub] PUT HTTP \(http.statusCode)")
            }
        }.resume()
    }
}
