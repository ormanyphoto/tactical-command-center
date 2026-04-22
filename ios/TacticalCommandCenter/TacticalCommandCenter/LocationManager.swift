// LocationManager.swift — CoreLocation wrapper with optional background updates.
import Foundation
import CoreLocation
import UIKit

class LocationManager: NSObject, CLLocationManagerDelegate {

    let clm = CLLocationManager()
    /// Called on each position update with lat, lng, accuracy (m), speed (m/s), heading (deg).
    var onUpdate: ((Double, Double, Double, Double, Double) -> Void)?
    /// Tracks whether we've emitted at least one fix since start() — we
    /// accept the very first fix regardless of accuracy so the web app
    /// can render an immediate marker; later fixes get the strict filter.
    private var firstFixEmitted = false

    override init() {
        super.init()
        clm.delegate = self
        clm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        // Deliver every fix Core Location produces. distanceFilter=5m
        // caused the map marker to feel stuck: when the operator was
        // stationary or moving slowly (walking indoors), no delegate
        // callbacks fired, so the web app never re-published. Using
        // kCLDistanceFilterNone makes "live" actually live — the filter
        // in didUpdateLocations (accuracy/age) is what guards quality.
        clm.distanceFilter = kCLDistanceFilterNone
        clm.pausesLocationUpdatesAutomatically = false

        // Background location updates require UIBackgroundModes → "location"
        // in Info.plist. If that's missing (Personal Team without entitlements)
        // setting this property throws an exception. Check the plist first.
        if Self.hasBackgroundLocationMode() {
            clm.allowsBackgroundLocationUpdates = true
            clm.showsBackgroundLocationIndicator = true
        }
        clm.activityType = .otherNavigation
    }

    private static func hasBackgroundLocationMode() -> Bool {
        guard let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] else {
            return false
        }
        return modes.contains("location")
    }

    func start() {
        switch clm.authorizationStatus {
        case .notDetermined:
            clm.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            // Only request Always if we can actually use background location
            if Self.hasBackgroundLocationMode() {
                clm.requestAlwaysAuthorization()
            }
            clm.startUpdatingLocation()
            clm.startUpdatingHeading()
        case .authorizedAlways:
            clm.startUpdatingLocation()
            clm.startUpdatingHeading()
        case .denied, .restricted:
            NSLog("[Loc] authorization denied/restricted")
        @unknown default: break
        }
    }

    func stop() {
        clm.stopUpdatingLocation()
        clm.stopUpdatingHeading()
        // Reset so next start() re-accepts its first fix unfiltered.
        firstFixEmitted = false
    }

    // ─── Delegate ──────────────────────────────────────────────────────────
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        NSLog("[Loc] auth = \(manager.authorizationStatus.rawValue)")
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
            manager.startUpdatingLocation()
            manager.startUpdatingHeading()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }

        let age = -loc.timestamp.timeIntervalSinceNow

        // ── First fix: relaxed but NOT unfiltered ────────────────────────────
        // On cold-start iOS delivers a cached cellular-tower fix before GPS
        // locks, typically 500–2000 m accuracy. That's acceptable to render
        // "roughly where I am." But iOS will also hand us CACHED fixes from
        // previous sessions — hours old and sometimes in a different city
        // (seen 14 km off in the field). Those must be rejected.
        //
        // Rules for fix #1:
        //   • accuracy in 0–3000 m (cellular / rough WiFi, not city-fallback)
        //   • age < 120 s (fresh-enough, not a stale session cache)
        if !firstFixEmitted {
            if loc.horizontalAccuracy < 0 || loc.horizontalAccuracy > 3000 {
                NSLog("[Loc] reject first fix — acc=\(loc.horizontalAccuracy)m (too coarse)")
                return
            }
            if age > 120 {
                NSLog("[Loc] reject first fix — age=\(Int(age))s (stale cache)")
                return
            }
            firstFixEmitted = true
            NSLog("[Loc] FIRST fix acc=\(Int(loc.horizontalAccuracy))m age=\(Int(age))s (accepted relaxed)")
            let spd = max(0, loc.speed)
            let hdg = max(0, loc.course)
            onUpdate?(loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy, spd, hdg)
            return
        }

        // ── Subsequent fixes: strict filter ──────────────────────────────────
        if loc.horizontalAccuracy < 0 || loc.horizontalAccuracy > 500 {
            NSLog("[Loc] reject acc=\(loc.horizontalAccuracy)m (out of range)")
            return
        }
        if age > 30 {
            NSLog("[Loc] reject stale fix age=\(age)s")
            return
        }
        NSLog("[Loc] fix acc=\(Int(loc.horizontalAccuracy))m age=\(Int(age))s")

        let spd = max(0, loc.speed) // clamp negatives (means "unknown")
        let hdg = max(0, loc.course)
        onUpdate?(loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy, spd, hdg)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("[Loc] error: \(error.localizedDescription)")
    }
}
