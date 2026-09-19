import { createRuntime, DEFAULT_API_ORIGIN } from './runtime-core.js';
export { createRuntime, isCarStreamApiAction, CARSTREAM_API_ACTIONS, TORBOX_MEDIA_SUFFIXES, isTrustedDirectMediaUrl } from './runtime-core.js';
const browser = typeof location !== 'undefined';
const meta = name => typeof document !== 'undefined' ? document.querySelector(`meta[name="${name}"]`)?.content?.trim() || '' : '';
const store = name => { try { return globalThis[name] || null; } catch { return null; } };
const runtime = createRuntime({
  mode:meta('player-runtime') || 'internet',
  pageOrigin:browser ? location.origin : DEFAULT_API_ORIGIN,
  apiOrigin:meta('api-origin') || (browser ? location.origin : DEFAULT_API_ORIGIN),
  apiPrefix:meta('api-prefix') || '/tw', browser, secureContext:globalThis.isSecureContext === true,
  localStorage:() => store('localStorage'), sessionStorage:() => store('sessionStorage')
});
export const API_ORIGIN = runtime.apiOrigin;
export const apiUrl = runtime.apiUrl;
export const mediaUrl = runtime.mediaUrl;
export const imageUrl = runtime.imageUrl;
export const apiMode = runtime.apiMode;
export const credentialsMode = runtime.credentialsMode;
export const getSessionToken = runtime.getSessionToken;
export const setSessionToken = runtime.setSessionToken;
export const clearSessionToken = runtime.clearSessionToken;
export const runtimeMode = runtime.mode;
export const runtimeCapabilities = runtime.capabilities;
export const isTrustedPlaybackUrl = runtime.isTrustedPlaybackUrl;
export const applicationStorage = runtime.applicationStorage;
export const flushRuntimeState = runtime.flushState;
export const parentPinService = runtime.parentPinService;
export const installRuntimeServices = runtime.installServices;
export function assertRuntimeReady() { runtime.applicationStorage(); runtime.parentPinService(); }
export function storedImageReference(value) {
  if(runtime.mode!=='carstream')return typeof value==='string'&&/^https:\/\//.test(value)?value:'';
  try{return value?new URL(runtime.imageUrl(value)).pathname:'';}catch{return '';}
}
