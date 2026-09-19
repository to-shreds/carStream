import { applicationStorage, storedImageReference } from './runtime.js';
const KEY='torbox-watchlist-v1';
const MAX_ITEMS=100;
const validViewer=value=>/^viewer-[12]$/.test(value||'')?value:'viewer-1';
const clean=(value,max=240)=>typeof value==='string'?value.replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,max):'';
const storage=applicationStorage;
export function normalizeWatchItem(raw){
  if(!raw||typeof raw!=='object'||!['movie','series'].includes(raw.type)||!/^tt[0-9]{5,12}$/.test(raw.id||''))return null;
  return {type:raw.type,id:raw.id,name:clean(raw.name,160)||'Untitled',poster:storedImageReference(clean(raw.poster,500)),year:clean(String(raw.year||''),12),addedAt:Number.isFinite(raw.addedAt)&&raw.addedAt>0?raw.addedAt:Date.now()};
}
function load(store=storage()){
  if(!store)return {};
  try{const parsed=JSON.parse(store.getItem(KEY)||'{}');return parsed&&typeof parsed==='object'&&!Array.isArray(parsed)?parsed:{};}catch{return {};}
}
function save(data,store=storage()){try{store?.setItem(KEY,JSON.stringify(data));}catch{}}
export function listWatchlist(viewer='viewer-1',store=storage()){
  const data=load(store),rows=Array.isArray(data[validViewer(viewer)])?data[validViewer(viewer)]:[];
  return rows.map(normalizeWatchItem).filter(Boolean).sort((a,b)=>b.addedAt-a.addedAt).slice(0,MAX_ITEMS);
}
export function isWatchlisted(viewer,item,store=storage()){
  const row=normalizeWatchItem(item);return !!row&&listWatchlist(viewer,store).some(x=>x.type===row.type&&x.id===row.id);
}
export function toggleWatchlist(viewer,item,store=storage()){
  const row=normalizeWatchItem(item);if(!row||!store)return false;
  const key=validViewer(viewer),data=load(store),rows=listWatchlist(key,store),index=rows.findIndex(x=>x.type===row.type&&x.id===row.id);
  if(index>=0)rows.splice(index,1);else rows.unshift({...row,addedAt:Date.now()});
  data[key]=rows.slice(0,MAX_ITEMS);save(data,store);return index<0;
}
