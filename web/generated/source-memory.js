import { applicationStorage } from './runtime.js';
const KEY='torbox-source-memory-v1';
const QUALITY=new Set(['480p','720p','1080p','2160p']);
const clean=(value,max=240)=>typeof value==='string'?value.replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,max):'';
const storage=applicationStorage;
export function titleKey(target){
  return target&&['movie','series'].includes(target.type)&&/^tt[0-9]{5,12}$/.test(target.id||'')?`${target.type}:${target.id}`:'';
}
export function releaseGroup(source){
  const text=clean(source?.filename||source?.title||'',350).replace(/\.[a-z0-9]{2,5}$/i,'');
  const match=/-([A-Za-z0-9][A-Za-z0-9._]{1,24})$/.exec(text);
  return match?match[1].toLowerCase():'';
}
export function sourceIdentity(source){
  if(!source||typeof source!=='object')return null;
  const hash=/^[a-f0-9]{40}$/i.test(source.hash||'')?source.hash.toLowerCase():'';
  const id=clean(source.id,160),group=releaseGroup(source),provider=clean(source.provider,60).toLowerCase(),resolution=clean(source.resolution||source.quality,20).toLowerCase();
  if(!hash&&!id&&!group)return null;
  return {hash,id,group,provider,resolution,label:clean(source.filename||source.title||source.label,180)};
}
function sourceKey(source){const i=sourceIdentity(source);return i?(i.hash||i.id||[i.group,i.provider,i.resolution].join(':')):'';}
function load(store=storage()){if(!store)return{titles:{}};try{const d=JSON.parse(store.getItem(KEY)||'{}');return d&&typeof d==='object'&&d.titles&&typeof d.titles==='object'?d:{titles:{}};}catch{return{titles:{}};}}
function save(data,store=storage()){try{store?.setItem(KEY,JSON.stringify(data));}catch{}}
function titleRow(data,target,create=false){const key=titleKey(target);if(!key)return null;if(!data.titles[key]&&create)data.titles[key]={quality:'',sources:{}};return data.titles[key]||null;}
export function getTitleQuality(target,store=storage()){const row=titleRow(load(store),target);return QUALITY.has(row?.quality)?row.quality:'';}
export function setTitleQuality(target,quality,store=storage()){
  if(!store)return false;const data=load(store),row=titleRow(data,target,true);if(!row)return false;row.quality=QUALITY.has(quality)?quality:'';save(data,store);return true;
}
export function sourceMemory(target,source,store=storage()){
  const data=load(store),row=titleRow(data,target),key=sourceKey(source),identity=sourceIdentity(source);if(!identity)return{bad:false,audio:'unknown',successes:0,bonus:0};
  const exact=row?.sources?.[key]||null;let bonus=0;
  if(exact?.successes)bonus+=Math.min(260,100+exact.successes*35);
  if(exact?.audio==='good')bonus+=180;
  const peers=Object.values(row?.sources||{});
  if(identity.group&&peers.some(item=>item.group===identity.group&&item.successes>0))bonus+=90;
  if(identity.provider&&peers.some(item=>item.provider===identity.provider&&item.successes>1))bonus+=20;
  return {bad:exact?.bad===true,audio:exact?.audio||'unknown',successes:Number(exact?.successes)||0,bonus};
}
export function applySourceMemory(target,sources,store=storage()){
  return (Array.isArray(sources)?sources:[]).map(source=>{const memory=sourceMemory(target,source,store);return{...source,memoryBad:memory.bad,memoryAudio:memory.audio,memoryBonus:memory.bonus};});
}
function updateSource(target,source,patch,store=storage()){
  if(!store)return false;const identity=sourceIdentity(source),key=sourceKey(source);if(!identity||!key)return false;
  const data=load(store),row=titleRow(data,target,true);if(!row)return false;const old=row.sources[key]||{};
  row.sources[key]={...old,...identity,...patch,updatedAt:Date.now()};save(data,store);return true;
}
export function rememberSourceSuccess(target,source,store=storage()){
  const current=sourceMemory(target,source,store);return updateSource(target,source,{successes:current.successes+1,bad:false},store);
}
export function setAudioFeedback(target,source,status,store=storage()){
  const audio=status==='good'||status==='bad'?status:'unknown';return updateSource(target,source,{audio,bad:audio==='bad'},store);
}
export function setSourceBad(target,source,bad=true,store=storage()){return updateSource(target,source,{bad:bad===true},store);}
export function clearSourceMemory(store=storage()){try{store?.removeItem(KEY);return true;}catch{return false;}}
