const DB_NAME = 'torbox-browser-key-vault';
const STORE = 'vault';
const KEY_ID = 'device-key';
const VALUE_ID = 'torbox-api-key';

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function get(id) {
  const db = await openDb();
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(id);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally { db.close(); }
}
async function put(id, value) {
  const db = await openDb();
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite');
      tx.objectStore(STORE).put(value, id);
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
export async function rememberApiKey(apiKey) {
  if (!crypto?.subtle || typeof apiKey !== 'string' || !apiKey) return false;
  let key = await get(KEY_ID);
  if (!key) {
    key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt','decrypt']);
    await put(KEY_ID, key);
  }
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = new TextEncoder().encode(apiKey);
  const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plain));
  await put(VALUE_ID, { iv: Array.from(iv), data: Array.from(encrypted) });
  return true;
}
export async function loadRememberedApiKey() {
  if (!crypto?.subtle) return '';
  try {
    const [key, saved] = await Promise.all([get(KEY_ID), get(VALUE_ID)]);
    if (!key || !saved?.iv || !saved?.data) return '';
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: new Uint8Array(saved.iv) }, key, new Uint8Array(saved.data));
    return new TextDecoder().decode(plain);
  } catch { return ''; }
}
export async function forgetApiKey() {
  const db = await openDb();
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite');
      tx.objectStore(STORE).delete(KEY_ID); tx.objectStore(STORE).delete(VALUE_ID);
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
