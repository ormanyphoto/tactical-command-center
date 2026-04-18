// LocationManager.swift — CoreLocation wrapper with background updates.
// Needs:
//   - NSLocationWhenInUseUsageDescription  (Info.plist)
//   - NSLocationAlwaysAndWhenInUseUsageDescription (Info.plist)
//   - UIBackgroundModes → location  (Info.plist)
import Foundation
import CoreLocation

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
        clm.allowsBackgroundLocationUpdates = true
        clm.showsBackgroundLocationIndicator = true  // blue bar when background
        clm.activityType = .otherNavigation
    }

    func start() {
        switch clm.authorizationStatus {
        case .notDetermined:
            clm.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            clm.requestAlwaysAuthorization()  // prompt for always so background works
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
        let spd = max(0, loc.speed) // m/s, clamp negatives (means "unknown")
        let hdg = max(0, loc.course)
        onUpdate?(loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy, spd, hdg)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("[Loc] error: \(error.localizedDescription)")
    }
}
