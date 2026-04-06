// Service Worker — מערכת מבצעים v5.2 + Background Polling
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

// Firebase Realtime DB REST base URL (public read)
const DB_URL = 'https://tactical-command-center-default-rtdb.firebaseio.com';

// ── In-memory state (survives tab close on Android when SW stays alive) ──
let _personId    = null;
let _lastSeenAt  = 0;
let _pollTimer   = null;

// ── Cache helpers (persistent across SW restarts) ──
const CFG_CACHE = 'tac-sw-cfg-v1';
async function swCacheSet(key, value){
  const c = await caches.open(CFG_CACHE);
  await c.put('/sw-cfg/'+key, new Response(String(value)));
}
async function swCacheGet(key){
  const c  = await caches.open(CFG_CACHE);
  const r  = await c.match('/sw-cfg/'+key);
  return r ? r.text() : null;
}

// ── Restore state from cache on SW startup ──
async function _restoreState(){
  _personId   = await swCacheGet('personId');
  const ts    = await swCacheGet('lastSeenAt');
  _lastSeenAt = ts ? Number(ts) : 0;
}

// ── Poll Firebase REST API for new notifications ──
async function pollNotifications(){
  if(!_personId) { _personId = await swCacheGet('personId'); }
  if(!_personId) return;

  const since = _lastSeenAt || (Date.now() - 60000); // last minute fallback
  const url   = `${DB_URL}/tac_notifications/${_personId}.json`
              + `?orderBy="sentAt"&startAt=${since + 1}&limitToLast=10`;
  try {
    const res  = await fetch(url);
    if(!res.ok) return;
    const data = await res.json();
    if(!data) return;

    const notifs = Object.values(data)
      .filter(n => n && n.sentAt && n.sentAt > _lastSeenAt)
      .sort((a,b) => a.sentAt - b.sentAt);

    for(const notif of notifs){
      const typeIcons = { mission:'📋', message:'💬', alert:'⚠️' };
      await self.registration.showNotification(
        (typeIcons[notif.type]||'📢') + ' ' + (notif.title || 'מערכת מבצעים'),
        {
          body:    notif.body    || '',
          icon:    '/tactical-command-center/icon-192.png',
          badge:   '/tactical-command-center/icon-192.png',
          dir:     'rtl',
          lang:    'he',
          tag:     notif.id     || 'tac-notif',
          vibrate: [200,100,200],
          data:    notif,
          actions: [{ action:'open', title:'פתח' }]
        }
      );
      if(notif.sentAt > _lastSeenAt){
        _lastSeenAt = notif.sentAt;
        await swCacheSet('lastSeenAt', _lastSeenAt);
      }
    }
  } catch(e){ /* network error — ignore */ }
}

// ── Start polling every 30 s ──
function startPolling(){
  if(_pollTimer) return;
  pollNotifications(); // immediate first check
  _pollTimer = setInterval(pollNotifications, 30000);
}

// ── Page → SW messages ──
self.addEventListener('message', async e => {
  if(!e.data) return;
  if(e.data.type === 'SKIP_WAITING') { self.skipWaiting(); return; }
  if(e.data.type === 'SET_USER'){
    _personId   = e.data.personId;
    _lastSeenAt = e.data.listenFrom || Date.now();
    await swCacheSet('personId',   _personId);
    await swCacheSet('lastSeenAt', _lastSeenAt);
    startPolling();
  }
  if(e.data.type === 'CLEAR_USER'){
    _personId = null; _lastSeenAt = 0; _pollTimer = null;
    await swCacheSet('personId',   '');
    await swCacheSet('lastSeenAt', '0');
  }
});

// ── Periodic Background Sync (Android Chrome — app fully closed) ──
self.addEventListener('periodicsync', e => {
  if(e.tag === 'tac-notif-check') e.waitUntil(pollNotifications());
});

// ── Push (FCM foreground messaging — future use) ──
messaging.onBackgroundMessage(payload => {
  const d = payload.data || {};
  const n = payload.notification || {};
  self.registration.showNotification(d.title || n.title || 'מערכת מבצעים', {
    body:    d.body   || n.body || '',
    icon:    '/tactical-command-center/icon-192.png',
    badge:   '/tactical-command-center/icon-192.png',
    dir:     'rtl', lang: 'he',
    tag:     d.notifId || 'tac-notif',
    vibrate: [200,100,200],
    data:    d
  });
});

// ── Notification click → open / focus app ──
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(
    clients.matchAll({ type:'window', includeUncontrolled:true }).then(list => {
      const win = list.find(c => c.url.includes('tactical-command-center'));
      return win ? win.focus() : clients.openWindow('/tactical-command-center/');
    })
  );
});

// ══════════════════════════════════════════════════
//  Cache shell
// ══════════════════════════════════════════════════
const CACHE = 'tac-v5-2-' + '2026040702';
const BASE  = '/tactical-command-center/';
const SHELL = [BASE, BASE + 'index.html'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL).catch(()=>{}))
      .then(()=> self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE && k !== CFG_CACHE).map(k => caches.delete(k))
      ))
      .then(()=> self.clients.claim())
      .then(()=>{
        _restoreState().then(startPolling); // resume polling after activation
        self.clients.matchAll({ type:'window', includeUncontrolled:true })
          .then(cs => cs.forEach(c => c.postMessage({ type:'SW_UPDATED' })));
      })
  );
});

self.addEventListener('fetch', e => {
  const url = e.request.url;
  if(url.includes('googleapis.com') || url.includes('gstatic.com') ||
     url.includes('firebaseio.com')  || url.includes('fonts.')){
    e.respondWith(fetch(e.request).catch(()=> caches.match(e.request)));
    return;
  }
  if(e.request.mode==='navigate' ||
     e.request.headers.get('accept').includes('text/html')){
    e.respondWith(
      fetch(e.request)
        .then(res => {
          if(res.ok){ const cl=res.clone(); caches.open(CACHE).then(c=>c.put(e.request,cl)); }
          return res;
        })
        .catch(()=> caches.match(e.request).then(r => r || caches.match(BASE+'index.html')))
    );
    return;
  }
  e.respondWith(
    caches.match(e.request).then(r => r || fetch(e.request)
      .then(res => {
        if(res.ok){ const cl=res.clone(); caches.open(CACHE).then(c=>c.put(e.request,cl)); }
        return res;
      }).catch(()=> caches.match(BASE+'index.html'))
    )
  );
});
