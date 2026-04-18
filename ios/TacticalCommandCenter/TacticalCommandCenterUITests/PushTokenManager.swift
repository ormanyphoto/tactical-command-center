// PushTokenManager.swift — holds the current FCM token and dispatches it to
// the web app via a JS custom event. The web app already listens for
// `tcc-fcm-token` to register the device in /tac_fcm_tokens/{personId}.
import Foundation
import WebKit

class PushTokenManager {
    static let shared = PushTokenManager()
    private init() {}

    private(set) var currentToken: String?

    func setCurrentToken(_ token: String) {
        currentToken = token
        UserDefaults.standard.set(token, forKey: "tcc_fcm_token")
        // If there's an active web view already, push immediately.
        if let wv = NativeBridge.shared.currentWebView {
            dispatchToWeb(webView: wv, token: token)
        }
    }

    func dispatchToWeb(webView: WKWebView, token: String) {
        let js = """
        (function(){
          try {
            window.__tcc_fcm_token = \(quoted(token));
            window.dispatchEvent(new CustomEvent('tcc-fcm-token',{detail:{token:\(quoted(token))}}));
          } catch(e){}
        })();
        """
        DispatchQueue.main.async {
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    private func quoted(_ s: String) -> String {
        let escaped = s
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "\"\(escaped)\""
    }
}
