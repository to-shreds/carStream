/** Transport contract shared by the Internet player and a paired local host.
 * A local host must rewrite upstream media/artwork into opaque local references.
 * This module never turns a remote URL into an arbitrary proxy URL.
 */
export const DEFAULT_API_ORIGIN = 'https://torbox-web-player-key.onrender.com';
export const TORBOX_MEDIA_SUFFIXES = Object.freeze(['torbox.app','tb-cdn.cx','tb-cdn.io','tb-cdn.pw','tb-cdn.sh','tb-cdn.st','tb-cdn.to','tb-cdn.earth']);
export const CARSTREAM_API_ACTIONS = Object.freeze({
  '/api/session': Object.freeze(['GET']),
  '/api/logout': Object.freeze(['POST']),
  '/api/torbox-status': Object.freeze(['GET']),
  '/api/discover/catalog': Object.freeze(['GET']),
  '/api/discover/meta': Object.freeze(['GET']),
  '/api/discover/lookup': Object.freeze(['GET']),
  '/api/discover/sources': Object.freeze(['POST']),
  '/api/discover/prepare': Object.freeze(['POST']),
  '/api/discover/status': Object.freeze(['GET']),
  '/api/playback': Object.freeze(['POST']),
  '/api/progress': Object.freeze(['PUT'])
});
const SESSION_KEY = 'torbox-web-session';
const SESSION_PATTERN = /^[A-Za-z0-9_-]{43}$/;
export function isTrustedDirectMediaUrl(value) {
  try {
    const url = new URL(value), host = url.hostname.toLowerCase();
    return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443')
      && TORBOX_MEDIA_SUFFIXES.some(suffix => host === suffix || host.endsWith('.' + suffix));
  } catch { return false; }
}
function localReference(value, origin, kind) {
  if (typeof value !== 'string' || !value || /[\\\s]/.test(value)) throw new TypeError('Invalid local resource reference.');
  const url = new URL(value, origin);
  if (url.origin !== origin || url.username || url.password || url.search || url.hash
      || !new RegExp(`^/${kind}/[A-Za-z0-9_-]{43}$`).test(url.pathname)) {
    throw new TypeError('The local player requires an opaque same-origin resource.');
  }
  return url.href;
}
export function isCarStreamApiAction(path, method = 'GET') {
  if (typeof path !== 'string' || !/^\/api\/[a-z/-]+(?:\?[^#\\]*)?$/.test(path)) return false;
  const pathname = path.split('?')[0];
  return Object.hasOwn(CARSTREAM_API_ACTIONS, pathname) && CARSTREAM_API_ACTIONS[pathname].includes(method);
}
export function createRuntime({
  mode = 'internet', pageOrigin = DEFAULT_API_ORIGIN, apiOrigin = pageOrigin,
  apiPrefix = '/tw', browser = true, secureContext = false,
  localStorage = null, sessionStorage = null
} = {}) {
  if (!['internet','carstream'].includes(mode)) throw new TypeError('Unknown player runtime.');
  const local = mode === 'carstream';
  const page = new URL(pageOrigin).origin;
  if (!['http:','https:'].includes(new URL(page).protocol)) throw new TypeError('An HTTP player origin is required.');
  const remote = new URL(apiOrigin || page, DEFAULT_API_ORIGIN).origin;
  if (local && !/^\/[A-Za-z][A-Za-z0-9_-]*$/.test(apiPrefix)) throw new TypeError('Invalid local API prefix.');
  // Deliberately ignore a configured remote API origin in the local runtime.
  const effectiveOrigin = local ? page : remote;
  let services = null;
  const capabilities = Object.freeze({
    serviceWorker: !local && secureContext,
    installApp: !local && secureContext,
    driveSharing: !local,
    credentialVault: !local && secureContext,
    screenWakeLock: secureContext,
    phoneCredentials: local,
    phonePersistence: local,
    phoneParentPin: local
  });
  function apiUrl(path) {
    if (!local) return browser ? new URL(path, effectiveOrigin).href : path;
    const pathname = typeof path === 'string' ? path.split('?')[0] : '';
    if (!Object.hasOwn(CARSTREAM_API_ACTIONS, pathname)
        || !CARSTREAM_API_ACTIONS[pathname].some(method => isCarStreamApiAction(path, method))) {
      throw new TypeError('This API action is not supported by the local runtime.');
    }
    return new URL(apiPrefix + path, page).href;
  }
  function mediaUrl(value) { return local ? localReference(value, page, 'media') : new URL(value, effectiveOrigin).href; }
  function imageUrl(value) {
    if (!value) return '';
    return local ? localReference(value, page, 'image') : value;
  }
  function getSessionToken() {
    if (local) return ''; // Phone credentials never enter browser session storage.
    try { const target = typeof sessionStorage === 'function' ? sessionStorage() : sessionStorage; const value = target?.getItem(SESSION_KEY) || ''; return SESSION_PATTERN.test(value) ? value : ''; } catch { return ''; }
  }
  function setSessionToken(value) {
    if (local || !SESSION_PATTERN.test(value || '')) return false;
    try { const target=typeof sessionStorage === 'function' ? sessionStorage() : sessionStorage;if(!target)return false;target.setItem(SESSION_KEY, value);return true; } catch { return false; }
  }
  function clearSessionToken() { if (!local) try { const target=typeof sessionStorage === 'function' ? sessionStorage() : sessionStorage;target?.removeItem(SESSION_KEY); } catch {} }
  function installServices(value) {
    if (!local) throw new Error('Phone services are only valid in the local runtime.');
    if (services) throw new Error('Runtime services are already installed.');
    if (!value || !['getItem','setItem','removeItem','flush'].every(name => typeof value.storage?.[name] === 'function')
        || !['hasPin','verifyPin','setPin'].every(name => typeof value.parentPin?.[name] === 'function')) {
      throw new TypeError('Phone storage and Parent PIN services are both required.');
    }
    services = Object.freeze({ storage:value.storage, parentPin:value.parentPin });
  }
  function applicationStorage() {
    if (!local) return typeof localStorage === 'function' ? localStorage() : localStorage;
    if (!services) throw new Error('Phone profile is not ready. Do not fall back to browser storage.');
    return services.storage;
  }
  function parentPinService() {
    if (!local) return null;
    if (!services) throw new Error('Phone Parent PIN is not ready.');
    return services.parentPin;
  }
  return Object.freeze({
    mode, apiOrigin:effectiveOrigin, capabilities, apiUrl, mediaUrl, imageUrl,
    apiMode:() => browser && page !== effectiveOrigin ? 'cors' : 'same-origin',
    credentialsMode:() => browser && page !== effectiveOrigin ? 'omit' : 'same-origin',
    getSessionToken, setSessionToken, clearSessionToken, installServices, applicationStorage, parentPinService,
    async flushState() { if(local)await applicationStorage().flush(); },
    isTrustedPlaybackUrl(value) {
      if (!local) return isTrustedDirectMediaUrl(value);
      try { mediaUrl(value); return true; } catch { return false; }
    }
  });
}
