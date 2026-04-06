// Service Worker — מערכת מבצעים v5.2
const CACHE = 'tac-v5-2';            // ← bumped: forces old cache to be wiped
const BASE  = '/tactical-command-center/';
const SHELL = [BASE, BASE + 'index.html'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL).catch(() => {}))
      .then(() => self.skipWaiting())   // activate immediately, don't wait for old SW to die
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const url = e.request.url;

  // ── Always network-first for external APIs ──
  if (url.includes('googleapis.com') || url.includes('gstatic.com') ||
      url.includes('firebaseio.com') || url.includes('fonts.')) {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
    return;
  }

  // ── Network-first for HTML pages (index.html) ──
  // This ensures updates always appear on next open.
  // Falls back to cached version only when truly offline.
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

  // ── Cache-first for everything else (icons, fonts, etc.) ──
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

// Allow the page to tell a waiting SW to activate immediately
self.addEventListener('message', e => {
  if(e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

// Push notifications (future use)
self.addEventListener('push', e => {
  if (!e.data) return;
  const d = e.data.json();
  self.registration.showNotification(d.title || 'מערכת מבצעים', {
    body: d.body || '',
    icon: BASE + 'icon-192.png',
    badge: BASE + 'icon-192.png',
    dir: 'rtl',
    lang: 'he',
    vibrate: [200, 100, 200],
    data: d
  });
});
