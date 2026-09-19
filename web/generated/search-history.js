import { applicationStorage } from './runtime.js';
const KEY='torbox-search-history-v1';
const MAX_ITEMS=30;
const validViewer=value=>/^viewer-[12]$/.test(value||'')?value:'viewer-1';
const clean=value=>typeof value==='string'?value.replace(/[\u0000-\u001f\u007f]/g,' ').replace(/\s+/g,' ').trim().slice(0,150):'';
const storage=applicationStorage;
function load(store=storage()){if(!store)return{};try{const v=JSON.parse(store.getItem(KEY)||'{}');return v&&typeof v==='object'&&!Array.isArray(v)?v:{};}catch{return{};}}
function save(data,store=storage()){try{store?.setItem(KEY,JSON.stringify(data));}catch{}}
export function listSearchHistory(viewer='viewer-1',store=storage()){
  const rows=load(store)[validViewer(viewer)];
  if(!Array.isArray(rows))return[];
  return rows.map(row=>({query:clean(row?.query),updatedAt:Number(row?.updatedAt)||0})).filter(row=>row.query).sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,MAX_ITEMS);
}
export function recordSearch(viewer,query,store=storage()){
  const value=clean(query);if(!store||value.length<2)return false;
  const key=validViewer(viewer),data=load(store),rows=listSearchHistory(key,store).filter(row=>row.query.toLowerCase()!==value.toLowerCase());
  data[key]=[{query:value,updatedAt:Date.now()},...rows].slice(0,MAX_ITEMS);save(data,store);return true;
}
export function removeSearch(viewer,query,store=storage()){
  const value=clean(query);if(!store||!value)return false;
  const key=validViewer(viewer),data=load(store);data[key]=listSearchHistory(key,store).filter(row=>row.query!==value);save(data,store);return true;
}
export function clearSearchHistory(viewer,store=storage()){
  if(!store)return false;const key=validViewer(viewer),data=load(store);data[key]=[];save(data,store);return true;
}
