const CACHE_NAME = 'stellar-grooves-v1';
const STATIC_ASSETS = [
  '/offline.html',
  '/css/main.css',
  '/css/theme.css',
  '/css/components.css',
  '/css/jukebox.css',
  '/vendor/bootstrap/css/bootstrap.min.css',
  '/vendor/bootstrap/js/bootstrap.bundle.min.js',
  '/images/stellar-grooves-logo.svg',
  '/images/stellar-grooves-logo-nav.svg',
  '/images/logo.png',
  '/images/icon-192.png',
  '/images/icon-512.png'
];

// Install: pre-cache static shell
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(STATIC_ASSETS))
      .then(() => self.skipWaiting())
  );
});

// Activate: clean old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Fetch: network-first for navigation, cache-first for static assets
self.addEventListener('fetch', event => {
  const { request } = event;

  // Skip non-GET requests
  if (request.method !== 'GET') return;

  // Skip API calls and WebSocket connections
  if (request.url.includes('/api/') || request.url.includes('/ws/')) return;

  // Navigation requests: network-first with offline fallback
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .catch(() => caches.match('/offline.html'))
    );
    return;
  }

  // Static assets: cache-first, fall back to network
  event.respondWith(
    caches.match(request).then(cached => {
      if (cached) return cached;
      return fetch(request).then(response => {
        if (response.ok && isStaticAsset(request.url)) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(request, clone));
        }
        return response;
      });
    })
  );
});

function isStaticAsset(url) {
  return /\.(css|js|png|svg|woff2?|ico)(\?.*)?$/.test(url);
}
