// NativeBridge.swift — central singleton that tracks the active WKWebView
// and posts events into it (used from AppDelegate + push handlers).
import Foundation
import WebKit

class NativeBridge {
    static let shared = NativeBridge()
    private init() {}

    weak var currentWebView: WKWebView?

    func register(webView: WKWebView) {
        currentWebView = webView
    }

    /// Post a DOM CustomEvent into the web app so it can react.
    /// Dispatches as `window.dispatchEvent(new CustomEvent('tcc-<event>', {detail: data}))`.
    func postToWeb(event: String, data: [String: Any]) {
        guard let webView = currentWebView else { return }
        let json: String
        if let d = try? JSONSerialization.data(withJSONObject: data, options: []),
           let s = String(data: d, encoding: .utf8) {
            json = s
        } else {
            json = "{}"
        }
        let js = "try { window.dispatchEvent(new CustomEvent('tcc-\(event.lowercased())',{detail:\(json)})); } catch(e){}"
        DispatchQueue.main.async {
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }
}
