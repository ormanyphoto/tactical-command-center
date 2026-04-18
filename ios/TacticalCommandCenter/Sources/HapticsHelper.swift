// HapticsHelper.swift — approximates the web app's vibrate() API using
// UIImpactFeedbackGenerator. Real device-wide vibration isn't available to
// third-party apps on iOS, so we chain taptic pulses to approximate a pattern.
import UIKit

enum HapticsHelper {
    static func play(pattern: [Int]) {
        // pattern alternates [vibrate_ms, pause_ms, vibrate_ms, ...]
        var delay: Double = 0
        for (idx, ms) in pattern.enumerated() {
            let isVibrate = idx % 2 == 0
            if isVibrate {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    let gen = UIImpactFeedbackGenerator(style: .heavy)
                    gen.prepare()
                    gen.impactOccurred()
                }
            }
            delay += Double(ms) / 1000.0
        }
    }
}
