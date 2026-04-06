// Service Worker — מערכת מבצעים v5.2 + FCM Push
importScripts('https://www.gstatic.com/firebasejs/9.23.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/9.23.0/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey:            "AIzaSyCwh4z4eKevoprQ-vLDs6VOJowQuclSb-E",
  authDomain:        "tactical-command-center.firebaseapp.com",
  projectId:         "tactical-command-center",
  storageBucket:     "tactical-command-center.firebasestorage.app",
  messagingSenderId: "31609493041",
  appId:             "1:31609493041:web:abc7f59d9e8363a02f2710"
});

const messaging = firebase.messaging();

// ── Background push (app closed / hidden) ──
messaging.onBackgroundMessage(payload => {
  const d = payload.data || {};
  const n = payload.notification || {};
  const typeIcons = { mission: '📋', message: '💬', alert: '⚠️' };
  const title  = d.title  || n.title  || 'מערכת מבצעים';
  const body   = d.body   || n.body   || '';
  const icon   = '/tactical-command-center/icon-192.png';
  self.registration.showNotification(title, {
    body,
    icon,
    badge: icon,
    dir:   'rtl',
    lang:  'he',
    tag:   d.notifId || 'tac-notif',
    vibrate: [200, 100, 200],
    data: d,
    actions: [{ action: 'open', title: '📂 פתח' }]
  });
});

// ── Click on background notification → open / focus app ──
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = '/tactical-command-center/';
  e.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      const existing = list.find(c => c.url.includes('tactical-command-center'));
      if (existing) return existing.focus();
      return clients.openWindow(url);
    })
  );
});

// ══════════════════════════════════════════════════
//  Cache shell (unchanged from v5.2)
// ══════════════════════════════════════════════════
const CACHE = 'tac-v5-2-' + '2026040701';
const BASE  = '/tactical-command-center/';
const SHELL = [BASE, BASE + 'index.html'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL).catch(() => {}))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
      .then(() => {
        self.clients.matchAll({ type: 'window', includeUncontrolled: true })
          .then(cs => cs.forEach(c => c.postMessage({ type: 'SW_UPDATED' })));
      })
  );
});

self.addEventListener('fetch', e => {
  const url = e.request.url;
  if (url.includes('googleapis.com') || url.includes('gstatic.com') ||
      url.includes('firebaseio.com') || url.includes('fonts.')) {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
    return;
  }
  if (e.request.mode === 'navigate' ||
      e.request.headers.get('accept').includes('text/html')) {
    e.respondWith(
      fetch(e.request)
        .then(res => {
          if (res.ok) {
            const clone = res.clone();
            caches.open(CACHE).then(c => c.put(e.request, clone));
          }
          return res;
        })
        .catch(() => caches.match(e.request).then(r => r || caches.match(BASE + 'index.html')))
    );
    return;
  }
  e.respondWith(
    caches.match(e.request).then(r => r || fetch(e.request)
      .then(res => {
        if (res.ok) {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      }).catch(() => caches.match(BASE + 'index.html'))
    )
  );
});

self.addEventListener('message', e => {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});
