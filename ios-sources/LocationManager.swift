// LocationManager.swift — CoreLocation wrapper with optional background updates.
import Foundation
import CoreLocation
import UIKit

class LocationManager: NSObject, CLLocationManagerDelegate {

    let clm = CLLocationManager()
    /// Called on each position update with lat, lng, accuracy (m), speed (m/s), heading (deg).
    var onUpdate: ((Double, Double, Double, Double, Double) -> Void)?

    override init() {
        super.init()
        clm.delegate = self
        clm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        clm.distanceFilter = 5  // meters
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
        let spd = max(0, loc.speed) // clamp negatives (means "unknown")
        let hdg = max(0, loc.course)
        onUpdate?(loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy, spd, hdg)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("[Loc] error: \(error.localizedDescription)")
    }
}
