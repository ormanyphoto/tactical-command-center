// Service Worker — מערכת מבצעים v5.3 + Instant Push via Firebase SSE
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

const DB_URL  = 'https://tactical-command-center-default-rtdb.firebaseio.com';
const CFG_CACHE = 'tac-sw-cfg-v1';

// ── Persistent cache helpers ──
async function swSet(key, val){
  const c = await caches.open(CFG_CACHE);
  await c.put('/sw/'+key, new Response(String(val)));
}
async function swGet(key){
  const c = await caches.open(CFG_CACHE);
  const r = await c.match('/sw/'+key);
  return r ? r.text() : null;
}

// ── State ──
let _personId   = null;
let _listenFrom = 0;
let _sseAbort   = null;
let _reconnectTimer = null;

// ════════════════════════════════════════════════
//  INSTANT SSE — Firebase streams changes in real-time
//  No polling. Notification arrives in < 1 second.
// ════════════════════════════════════════════════
async function startSSE(personId, listenFrom){
  // Cancel any existing connection
  if(_sseAbort){ _sseAbort.abort(); _sseAbort = null; }
  if(_reconnectTimer){ clearTimeout(_reconnectTimer); _reconnectTimer = null; }
  if(!personId) return;

  _personId   = personId;
  _listenFrom = listenFrom || (Date.now() - 5000);

  async function connect(){
    _sseAbort = new AbortController();
    // Listen to entire user notification node as a stream
    const url = `${DB_URL}/tac_notifications/${_personId}.json`;
    try {
      const resp = await fetch(url, {
        headers: { 'Accept': 'text/event-stream' },
        signal:  _sseAbort.signal
      });
      if(!resp.ok || !resp.body){ scheduleReconnect(5000); return; }

      const reader  = resp.body.getReader();
      const decoder = new TextDecoder();
      let   buf = '', eventType = null, isFirst = true;

      while(true){
        const { done, value } = await reader.read();
        if(done){ scheduleReconnect(3000); break; }

        buf += decoder.decode(value, { stream: true });
        // Firebase SSE lines end with \n
        const lines = buf.split('\n');
        buf = lines.pop(); // keep incomplete last line

        for(const line of lines){
          if(line.startsWith('event: ')){
            eventType = line.slice(7).trim();
          } else if(line.startsWith('data: ') && eventType === 'put'){
            try {
              const ev = JSON.parse(line.slice(6));
              if(isFirst){
                // First event = full dump of all existing data — update baseline only
                isFirst = false;
                if(ev.data && typeof ev.data === 'object'){
                  const times = Object.values(ev.data)
                    .map(n => n && n.sentAt ? n.sentAt : 0);
                  if(times.length) _listenFrom = Math.max(_listenFrom, ...times);
                }
              } else if(ev.data && typeof ev.data === 'object' && ev.path !== '/'){
                // Incremental update — a single new notification object
                const notif = ev.data;
                if(notif.sentAt && notif.sentAt > _listenFrom){
                  _listenFrom = notif.sentAt;
                  await swSet('lastSeenAt', _listenFrom);
                  await _showNotif(notif);
                }
              } else if(ev.data && typeof ev.data === 'object' && ev.path === '/'){
                // Full replace — find entries newer than baseline
                const entries = Object.values(ev.data)
                  .filter(n => n && n.sentAt && n.sentAt > _listenFrom)
                  .sort((a,b) => a.sentAt - b.sentAt);
                for(const notif of entries){
                  _listenFrom = notif.sentAt;
                  await swSet('lastSeenAt', _listenFrom);
                  await _showNotif(notif);
                }
              }
            } catch(e){ /* bad JSON */ }
            eventType = null;
          } else if(line === ''){
            eventType = null; // blank line = end of event block
          }
        }
      }
    } catch(e){
      if(e.name !== 'AbortError') scheduleReconnect(5000);
    }
  }

  function scheduleReconnect(ms){
    if(_sseAbort && _sseAbort.signal.aborted) return;
    _reconnectTimer = setTimeout(connect, ms);
  }

  connect();
}

async function _showNotif(notif){
  const icons = { mission:'📋', message:'💬', alert:'⚠️' };
  const icon  = icons[notif.type] || '📢';
  const typeLabel = notif.typeLabel || notif.type || '';
  await self.registration.showNotification(
    icon + ' ' + (notif.title || 'מערכת מבצעים'),
    {
      body:    (typeLabel ? '['+typeLabel+'] ' : '') + (notif.body || ''),
      icon:    '/tactical-command-center/icon-192.png',
      badge:   '/tactical-command-center/icon-192.png',
      dir:     'rtl', lang: 'he',
      tag:     notif.id || 'tac-notif',
      renotify: true,
      vibrate: [200, 100, 200, 100, 200],
      data:    notif,
      actions: [
        { action: 'open',   title: '📂 פתח' },
        { action: 'ack',    title: '✓ קיבלתי' }
      ]
    }
  );
}

// ── Notification click → Waze navigation or open app ──
self.addEventListener('notificationclick', e => {
  e.notification.close();
  if(e.action === 'ack') return; // just dismiss

  const notif = e.notification.data || {};
  const lat   = notif.lat;
  const lng   = notif.lng;
  const addr  = notif.address;

  // If there's a location → open Waze directly
  if(addr || (lat && lng)){
    let wazeUrl;
    if(lat && lng){
      wazeUrl = `https://waze.com/ul?ll=${lat},${lng}&navigate=yes`;
    } else {
      wazeUrl = `https://waze.com/ul?q=${encodeURIComponent(addr)}&navigate=yes`;
    }
    e.waitUntil(clients.openWindow(wazeUrl));
    return;
  }

  // No address → focus / open app
  e.waitUntil(
    clients.matchAll({ type:'window', includeUncontrolled:true }).then(list => {
      const win = list.find(c => c.url.includes('tactical-command-center'));
      return win ? win.focus() : clients.openWindow('/tactical-command-center/');
    })
  );
});

// ── Page → SW messages ──
self.addEventListener('message', async e => {
  if(!e.data) return;
  switch(e.data.type){
    case 'SKIP_WAITING':
      self.skipWaiting();
      break;
    case 'SET_USER':
      await swSet('personId',   e.data.personId);
      await swSet('lastSeenAt', e.data.listenFrom || Date.now());
      startSSE(e.data.personId, e.data.listenFrom || Date.now());
      break;
    case 'CLEAR_USER':
      if(_sseAbort) _sseAbort.abort();
      _personId = null;
      await swSet('personId', '');
      break;
  }
});

// ── Periodic Background Sync — wakes SW when app fully closed (Android Chrome) ──
self.addEventListener('periodicsync', async e => {
  if(e.tag === 'tac-notif-check'){
    e.waitUntil((async () => {
      // Restore state from cache
      const pid = await swGet('personId');
      const ts  = Number(await swGet('lastSeenAt') || 0);
      if(pid && pid !== _personId){
        // SW was killed and restarted — reconnect SSE immediately
        startSSE(pid, ts);
      } else if(pid && !_sseAbort){
        startSSE(pid, ts);
      }
    })());
  }
});

// ── FCM background message (future/fallback) ──
messaging.onBackgroundMessage(payload => {
  const d = payload.data || {};
  const n = payload.notification || {};
  _showNotif({
    id: d.notifId, type: d.type, typeLabel: d.typeLabel,
    title: d.title || n.title, body: d.body || n.body,
    senderName: d.senderName, sentAt: Number(d.sentAt) || Date.now()
  });
});

// ══════════════════════════════════════════════════
//  Cache shell
// ══════════════════════════════════════════════════
const CACHE = 'tac-v5-3-' + '2026040807';
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
      .then(async ()=>{
        // Resume SSE after activation (e.g. after SW update)
        const pid = await swGet('personId');
        const ts  = Number(await swGet('lastSeenAt') || 0);
        if(pid) startSSE(pid, ts);
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
  if(e.request.mode === 'navigate' ||
     (e.request.headers.get('accept')||'').includes('text/html')){
    e.respondWith(
      fetch(e.request, {cache: 'no-store'})
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
