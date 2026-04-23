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
         "nativeLog",
         "cacheAuthForBackground"
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
                // Use %.7f for lat/lng (≈1 cm resolution) to avoid Swift
                // default Double→String ever producing scientific notation
                // or locale-specific decimal separators that would break
                // JS JSON parsing. accuracy/speed/heading get 2 decimals.
                let js = String(
                    format: "window.dispatchEvent(new CustomEvent('tcc-native-loc',{detail:{lat:%.7f,lng:%.7f,acc:%.2f,spd:%.2f,hdg:%.2f,ts:Date.now()}}))",
                    lat, lng, acc, spd, hdg
                )
                DispatchQueue.main.async {
                    self?.webView.evaluateJavaScript(js, completionHandler: nil)
                }
            }
            locationManager?.start()
        case "stopLocation":
            locationManager?.stop()
        case "setStatus":
            if let status = body["status"] as? String {
                // Forward to web app's SYNC.updateStatus via existing handler;
                // also remember so background foreground-service could use it.
                UserDefaults.standard.set(status, forKey: "tcc_status")
                BackgroundPublisher.shared.updateStatus(status)
            }
        case "cacheAuthForBackground":
            // Web layer hands us the uid + Firebase ID token + profile fields
            // so BackgroundPublisher can write tac_locs/{uid} directly to the
            // RTDB REST API while WKWebView JS is suspended. Token refreshed
            // every ~30 min by the web layer.
            BackgroundPublisher.shared.cacheAuth(body)
        case "requestPushToken":
            if let token = PushTokenManager.shared.currentToken {
                PushTokenManager.shared.dispatchToWeb(webView: webView, token: token)
            }
        case "vibrate":
            let pattern = body["pattern"] as? [Int] ?? [400, 150, 400]
            HapticsHelper.play(pattern: pattern)
        case "openExternal":
            // Allowlist schemes so a malicious or compromised web payload
            // can't drive the native app to open arbitrary URLs
            // (javascript:, file:, custom deep-links, etc.). Anything
            // outside this list is dropped and logged.
            if let urlStr = body["url"] as? String,
               let url = URL(string: urlStr),
               let scheme = url.scheme?.lowercased(),
               ["https","http","tel","sms","mailto",
                "waze","googlemaps","comgooglemaps","maps","whatsapp"].contains(scheme) {
                UIApplication.shared.open(url)
            } else {
                NSLog("[Bridge] openExternal rejected: \(body["url"] ?? "nil")")
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
    // Exposes TCC_NATIVE + overrides navigator.geolocation with a native-backed
    // shim so the web app's existing watchPosition / getCurrentPosition calls
    // are transparently routed through Core Location.
    private static let bootstrapJS = """
    (function(){
      if(window.TCC_NATIVE) return;
      // Signal to the web app that it's running inside the iOS native wrapper.
      // Matches the Android flag set in index.html so _ipLocate etc. can
      // take native-aware decisions.
      window._runningInNativeApp = true;
      try { document.documentElement.classList.add('native-app'); } catch(_){}
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
        log: function(msg){ post('nativeLog',{msg:msg}); },
        // Hands native Swift the Firebase uid + ID token + profile so the
        // BackgroundPublisher can PUT tac_locs/{uid} from Swift while
        // WKWebView's JS is suspended in the background. Call this after
        // login and then re-call every ~30 min with a fresh ID token.
        cacheAuthForBackground: function(info){ post('cacheAuthForBackground', info||{}); }
      };

      // ─── Geolocation shim ────────────────────────────────────────────────
      // WKWebView on iOS 16+ exposes a frozen `navigator.geolocation` object
      // whose built-in implementation often returns cached / coarse Apple
      // location-service fixes that don't match Core Location's high-accuracy
      // positions (causing "live" pin offset from real location on map).
      // Fix: REPLACE the entire navigator.geolocation object atomically with
      // our native-bridge shim — the WKWebView implementation is then
      // unreachable from web code. If the property itself is non-configurable
      // (iOS sometimes freezes navigator too) we fall back to per-method
      // patching with defineProperty.
      var _lastNativePos = null;
      var _watchers = {}; // id → callback object
      var _nextWatchId = 1;
      function _nativePosToGeoPosition(p){
        return {
          coords: {
            latitude: p.lat, longitude: p.lng,
            accuracy: (p.acc>0?p.acc:10),
            altitude: null, altitudeAccuracy: null,
            heading: (p.hdg >= 0 ? p.hdg : null),
            speed: (p.spd >= 0 ? p.spd : null)
          },
          timestamp: p.ts || Date.now()
        };
      }
      window.addEventListener('tcc-native-loc', function(e){
        var p = e.detail; if(!p) return;
        _lastNativePos = p;
        // Mirror every native fix onto window globals so fallback code paths
        // (startPublishing seed, SOS, manual-exit map restore) always have
        // fresh data even when no watcher is registered.
        try { window._lastLat = p.lat; window._lastLng = p.lng; } catch(_){}
        var pos = _nativePosToGeoPosition(p);
        Object.keys(_watchers).forEach(function(id){
          try{ _watchers[id].success && _watchers[id].success(pos); }catch(err){}
        });
      });

      var _nativeGeo = {
        getCurrentPosition: function(success, error, options){
          // Always kick Core Location so the next real fix arrives ASAP in
          // parallel, even if we return a cached one below.
          try{ post('startLocation',{}); }catch(_){}
          // If we have ANY cached fix, return it immediately — iOS BestForNav
          // cold-start can take 30 s, and making the caller wait for fresh data
          // is what caused "can't locate" errors and disappearing avatars after
          // toggle/manual flows. The live watchPosition (and any parallel
          // getCurrentPosition awaiting the event) will deliver the real fix
          // separately.
          if(_lastNativePos){
            try{ success(_nativePosToGeoPosition(_lastNativePos)); }catch(_){}
            return;
          }
          // No cache at all — wait for first event
          var resolved = false;
          var handler = function(e){
            if(resolved) return;
            resolved = true;
            window.removeEventListener('tcc-native-loc', handler);
            try{ success(_nativePosToGeoPosition(e.detail)); }catch(_){}
          };
          window.addEventListener('tcc-native-loc', handler);
          var timeout = (options && options.timeout) || 25000;
          setTimeout(function(){
            if(resolved) return;
            resolved = true;
            window.removeEventListener('tcc-native-loc', handler);
            if(_lastNativePos){ try{ success(_nativePosToGeoPosition(_lastNativePos)); }catch(_){} }
            else if(error){ try{ error({code:3, message:'Timeout (native shim)'}); }catch(_){} }
          }, timeout);
        },
        watchPosition: function(success, error, options){
          var id = _nextWatchId++;
          _watchers[id] = { success: success, error: error };
          post('nativeLog',{msg:'[geo-shim] watchPosition id='+id});
          try{ post('startLocation',{}); }catch(_){}
          if(_lastNativePos){
            try{ success(_nativePosToGeoPosition(_lastNativePos)); }catch(_){}
          }
          return id;
        },
        clearWatch: function(id){
          delete _watchers[id];
          // NOTE: We intentionally do NOT post('stopLocation') here anymore.
          // Every clearWatch used to stop CLLocationManager, and re-starting
          // it caused a 10–30 s cold-start on the next watchPosition call —
          // that's what made the avatar vanish after toggle-off/on and after
          // _resetToGPS (manual → GPS). Keeping Core Location warm costs a
          // small amount of battery while the app is foregrounded, but
          // guarantees the very next watchPosition delivers an instant fix.
          // The app lifecycle in RootViewController handles stopping CL when
          // the app actually backgrounds.
        },
        // Exposed for explicit "fully stop" cases (app background, logout)
        _forceStop: function(){
          try{ post('stopLocation',{}); }catch(_){}
        }
      };

      // Atomically replace the whole object — survives WKWebView freezing
      // individual methods. Try navigator.geolocation = _nativeGeo first
      // (most reliable when it works), then defineProperty on navigator,
      // then per-method defineProperty as last resort.
      var _installed = false;
      try { navigator.geolocation = _nativeGeo; _installed = (navigator.geolocation === _nativeGeo); } catch(_){}
      if(!_installed){
        try {
          Object.defineProperty(navigator, 'geolocation', {
            value: _nativeGeo, writable: false, configurable: true, enumerable: true
          });
          _installed = (navigator.geolocation === _nativeGeo);
        } catch(_){}
      }
      if(!_installed && navigator.geolocation){
        // Fallback: patch each method individually
        function _installGeoMethod(name, fn){
          try { navigator.geolocation[name] = fn; } catch(_){}
          try {
            Object.defineProperty(navigator.geolocation, name, {
              value: fn, writable: true, configurable: true, enumerable: true
            });
          } catch(_){}
        }
        _installGeoMethod('getCurrentPosition', _nativeGeo.getCurrentPosition);
        _installGeoMethod('watchPosition',      _nativeGeo.watchPosition);
        _installGeoMethod('clearWatch',         _nativeGeo.clearWatch);
      }
      post('nativeLog',{msg:'[geo-shim] install='+(_installed?'whole-object':'method-patch')});

      document.addEventListener('DOMContentLoaded', function(){
        try { window.TCC_NATIVE.requestPushToken(); } catch(_){}
        // Kick off location publishing on page load — the web app will also
        // call watchPosition via its SYNC module which activates the shim.
        try { window.TCC_NATIVE.startLocation(); } catch(_){}
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
