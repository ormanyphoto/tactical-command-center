// RootViewController.swift — hosts the WKWebView that loads the web app.
// Bridges between the web app and native iOS APIs (location, push token,
// haptics, SOS sound override).
import UIKit
import WebKit

class RootViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    private var webView: WKWebView!
    private var locationManager: LocationManager?
    private let userContentController = WKUserContentController()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0x07/255, green: 0x09/255, blue: 0x0a/255, alpha: 1)

        // ─── Register JS → native message handlers ────────────────────────
        // The web app calls window.webkit.messageHandlers.<name>.postMessage(obj).
        ["startLocation",
         "stopLocation",
         "setStatus",
         "requestPushToken",
         "vibrate",
         "openExternal",
         "nativeLog"
        ].forEach { name in
            userContentController.add(ScriptMessageDelegate(parent: self), name: name)
        }

        // ─── WKWebView config ─────────────────────────────────────────────
        let config = WKWebViewConfiguration()
        config.userContentController = userContentController
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        if #available(iOS 14.0, *) {
            config.defaultWebpagePreferences.allowsContentJavaScript = true
        }
        // Persistent cookies/localStorage across app launches
        config.websiteDataStore = .default()

        // Inject a tiny bootstrap script that exposes TCC_NATIVE to the web app
        // with helper functions that wrap postMessage.
        let bootstrap = WKUserScript(
            source: Self.bootstrapJS,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        config.userContentController.addUserScript(bootstrap)

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.customUserAgent = (webView.value(forKey: "userAgent") as? String ?? "") + " " + AppConfig.userAgentSuffix
        webView.allowsBackForwardNavigationGestures = false
        view.addSubview(webView)

        NativeBridge.shared.register(webView: webView)

        let request = URLRequest(url: AppConfig.webAppURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        webView.load(request)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // ─── JS → native dispatcher ────────────────────────────────────────────
    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        let name = message.name
        let body = message.body as? [String: Any] ?? [:]
        NSLog("[Bridge] ← \(name) \(body)")
        switch name {
        case "startLocation":
            if locationManager == nil { locationManager = LocationManager() }
            locationManager?.onUpdate = { [weak self] lat, lng, acc, spd, hdg in
                self?.webView.evaluateJavaScript(
                    "window.dispatchEvent(new CustomEvent('tcc-native-loc',{detail:{lat:\(lat),lng:\(lng),acc:\(acc),spd:\(spd),hdg:\(hdg),ts:Date.now()}}))"
                )
            }
            locationManager?.start()
        case "stopLocation":
            locationManager?.stop()
        case "setStatus":
            if let status = body["status"] as? String {
                // Forward to web app's SYNC.updateStatus via existing handler;
                // also remember so background foreground-service could use it.
                UserDefaults.standard.set(status, forKey: "tcc_status")
            }
        case "requestPushToken":
            if let token = PushTokenManager.shared.currentToken {
                PushTokenManager.shared.dispatchToWeb(webView: webView, token: token)
            }
        case "vibrate":
            let pattern = body["pattern"] as? [Int] ?? [400, 150, 400]
            HapticsHelper.play(pattern: pattern)
        case "openExternal":
            if let urlStr = body["url"] as? String, let url = URL(string: urlStr) {
                UIApplication.shared.open(url)
            }
        case "nativeLog":
            NSLog("[WebLog] \(body)")
        default: break
        }
    }

    // ─── Nav delegate — allow tel: / waze: / googlemaps: URL schemes ───────
    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.allow); return }
        let scheme = url.scheme?.lowercased() ?? ""
        if ["tel", "sms", "mailto", "waze", "googlemaps", "maps", "comgooglemaps", "whatsapp"].contains(scheme) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        NSLog("[Web] did finish \(webView.url?.absoluteString ?? "-")")
        // Push the FCM token into the web app on every page load so
        // registerFCMToken() picks it up.
        if let token = PushTokenManager.shared.currentToken {
            PushTokenManager.shared.dispatchToWeb(webView: webView, token: token)
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        NSLog("[Web] did fail: \(error.localizedDescription)")
    }

    // Open new-window requests (target="_blank") in-place so the app doesn't try to spawn Safari.
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url {
            UIApplication.shared.open(url)
        }
        return nil
    }

    // ─── Bootstrap JS injected on every page load ──────────────────────────
    private static let bootstrapJS = """
    (function(){
      if(window.TCC_NATIVE) return;
      function post(name,data){
        try { window.webkit.messageHandlers[name].postMessage(data||{}); } catch(e){}
      }
      window.TCC_NATIVE = {
        platform:'ios',
        startLocation: function(){ post('startLocation',{}); },
        stopLocation:  function(){ post('stopLocation',{}); },
        setStatus:     function(s){ post('setStatus',{status:s}); },
        requestPushToken: function(){ post('requestPushToken',{}); },
        vibrate: function(pat){ post('vibrate',{pattern: pat||[400,150,400]}); },
        openExternal: function(url){ post('openExternal',{url:url}); },
        log: function(msg){ post('nativeLog',{msg:msg}); }
      };
      // On load, ask for the token — the native side will dispatch it back
      // as a `tcc-fcm-token` event that the web app listens to.
      document.addEventListener('DOMContentLoaded', function(){
        try { window.TCC_NATIVE.requestPushToken(); } catch(_){}
      });
    })();
    """
}

// Small wrapper so WKScriptMessageHandler's retain cycle doesn't capture the
// controller — forwards to the parent via weak ref.
class ScriptMessageDelegate: NSObject, WKScriptMessageHandler {
    weak var parent: WKScriptMessageHandler?
    init(parent: WKScriptMessageHandler) { self.parent = parent }
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        parent?.userContentController(c, didReceive: m)
    }
}
