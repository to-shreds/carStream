const CACHE='torbox-player-v1.0';
const SHELL=['./','./index.html','./style.css','./discover.css','./app.js','./runtime.js','./runtime-core.js','./history.js','./settings.js','./watchlist.js','./search-history.js','./source-memory.js','./parental-controls.js','./playback-errors.js','./discover.js','./source-client.js','./manifest.webmanifest','./icon.svg'];
const shellUrls=new Set(SHELL.map(path=>new URL(path,self.registration.scope).href));
self.addEventListener('install',event=>{event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting()));});
self.addEventListener('activate',event=>{event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  const url=new URL(event.request.url);if(url.origin!==self.location.origin||url.pathname.startsWith('/api/'))return;
  if(event.request.mode!=='navigate'&&!shellUrls.has(event.request.url))return;
  event.respondWith(fetch(event.request).then(response=>{if(response.ok&&shellUrls.has(event.request.url)){const copy=response.clone();caches.open(CACHE).then(cache=>cache.put(event.request,copy));}return response;}).catch(()=>event.request.mode==='navigate'?caches.match('./'):caches.match(event.request)));
});
