import { applicationStorage, storedImageReference } from './runtime.js';
import { getSettings } from './settings.js';
const STORAGE_KEY = 'torbox-recent-v1';
const MAX_ITEMS = 20;
const clean = (value, max = 240) => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, max) : '';
const num = value => Number.isFinite(value) && value >= 0 ? value : 0;
export function recentKey(context) {
  const target = context?.current;
  if (!target || !['movie','series'].includes(target.type) || !/^tt[0-9]{5,12}$/.test(target.id || '')) return '';
  return target.type === 'series' ? `series:${target.id}:${target.season}:${target.episode}` : `movie:${target.id}`;
}
export function normalizeRecent(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const key = typeof raw.key === 'string' ? raw.key : '';
  const movie = /^movie:(tt[0-9]{5,12})$/.exec(key), series = /^series:(tt[0-9]{5,12}):(\d{1,3}):(\d{1,4})$/.exec(key);
  if (!movie && !series) return null;
  const type = movie ? 'movie' : 'series', id = (movie || series)[1];
  const season = series ? Number(series[2]) : null, episode = series ? Number(series[3]) : null;
  if (series && (!Number.isSafeInteger(season) || !Number.isSafeInteger(episode))) return null;
  const duration = num(raw.duration), position = Math.min(num(raw.position), duration || Number.MAX_SAFE_INTEGER);
  return {
    key, type, id, season, episode, title: clean(raw.title, 160) || 'Untitled',
    episodeName: clean(raw.episodeName, 160), poster: storedImageReference(clean(raw.poster, 500)),
    resolution: clean(raw.resolution, 20) || 'auto', position, duration, completed: raw.completed === true,
    updatedAt: Number.isFinite(raw.updatedAt) && raw.updatedAt > 0 ? raw.updatedAt : Date.now()
  };
}
const storage = applicationStorage;
export function listRecent(store = storage()) {
  if (!store) return [];
  try {
    const parsed = JSON.parse(store.getItem(STORAGE_KEY) || '[]');
    if (!Array.isArray(parsed)) return [];
    return parsed.map(normalizeRecent).filter(Boolean).sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,MAX_ITEMS);
  } catch { return []; }
}
export function recentForContext(context, store = storage()) {
  const key = recentKey(context); return key ? listRecent(store).find(item => item.key === key) || null : null;
}
export function resumePosition(entry) {
  const row = normalizeRecent(entry); if (!row || row.completed) return 0;
  if (row.duration > 0 && row.duration - row.position < 20) return 0;
  return row.position;
}
export function recordRecent(context, position = 0, duration = 0, { completed = false, store = storage() } = {}) {
  if (!store) return null;
  const key = recentKey(context); if (!key) return null;
  const target = context.current;
  const row = normalizeRecent({
    key, title: context.title, episodeName: context.episodeName || target.name || '', poster: context.poster || '',
    resolution: context.resolution || 'auto', position, duration, completed, updatedAt: Date.now()
  });
  if (!row) return null;
  let rest = listRecent(store).filter(item => item.key !== key);
  if(row.type==='series'&&position>0&&getSettings(store).cleanupCompletedEpisodes){
    const order=item=>Number(item.season||0)*10000+Number(item.episode||0),currentOrder=order(row);
    rest=rest.filter(item=>!(item.type==='series'&&item.id===row.id&&item.completed&&order(item)<currentOrder));
  }
  const rows = [row, ...rest].slice(0,MAX_ITEMS);
  try { store.setItem(STORAGE_KEY, JSON.stringify(rows)); } catch {}
  return row;
}
export function clearRecent(store = storage()) { try { store?.removeItem(STORAGE_KEY); } catch {} }
export function removeRecent(key, store = storage()) {
  if (!store || typeof key !== 'string') return false;
  const rows = listRecent(store).filter(item => item.key !== key);
  try { store.setItem(STORAGE_KEY, JSON.stringify(rows)); return true; } catch { return false; }
}
export function formatResumeTime(seconds) {
  const value = Math.max(0, Math.floor(num(seconds))); const h = Math.floor(value / 3600), m = Math.floor(value % 3600 / 60), s = value % 60;
  return h ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}` : `${m}:${String(s).padStart(2,'0')}`;
}
