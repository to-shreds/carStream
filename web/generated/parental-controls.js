import { applicationStorage, parentPinService } from './runtime.js';
const STORAGE_KEY='torbox-parental-controls-v1';
const PIN_ITERATIONS=150000;
const VIEWERS=new Set(['viewer-1','viewer-2']);
const MAX_CONTENT_ROWS=240;

const storage=applicationStorage;
function cleanInt(value,min,max,fallback=0){
  const n=Number(value);return Number.isFinite(n)?Math.min(max,Math.max(min,Math.round(n))):fallback;
}
function cleanSeconds(value,max=172800){
  const n=Number(value);return Number.isFinite(n)?Math.min(max,Math.max(0,n)):0;
}
function dateKey(now=Date.now()){
  const d=new Date(now);
  return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');
}
function validViewer(viewer){return VIEWERS.has(viewer)?viewer:'viewer-1'}
function validContentKey(value){return /^(?:movie:tt[0-9]{5,12}|series:tt[0-9]{5,12}:\d{1,3}:\d{1,4})$/.test(value||'')}
function defaultProfile(now=Date.now()){
  return {
    enabled:false,
    timeLimitMinutes:0,
    episodeLimit:0,
    movieLimit:0,
    resetMode:'daily',
    periodKey:dateKey(now),
    usedSeconds:0,
    episodesUsed:0,
    moviesUsed:0,
    bonusSeconds:0,
    bonusEpisodes:0,
    bonusMovies:0,
    charged:[],
    contentSeconds:{}
  };
}
function normalizeProfile(raw,now=Date.now()){
  const source=raw&&typeof raw==='object'?raw:{};
  const charged=Array.isArray(source.charged)?[...new Set(source.charged.filter(validContentKey))].slice(-MAX_CONTENT_ROWS):[];
  const contentSeconds={};
  if(source.contentSeconds&&typeof source.contentSeconds==='object'){
    for(const [key,value] of Object.entries(source.contentSeconds)){
      if(validContentKey(key)&&Object.keys(contentSeconds).length<MAX_CONTENT_ROWS)contentSeconds[key]=cleanSeconds(value);
    }
  }
  return {
    enabled:source.enabled===true,
    timeLimitMinutes:cleanInt(source.timeLimitMinutes,0,1440),
    episodeLimit:cleanInt(source.episodeLimit,0,100),
    movieLimit:cleanInt(source.movieLimit,0,50),
    resetMode:source.resetMode==='manual'?'manual':'daily',
    periodKey:typeof source.periodKey==='string'&&source.periodKey.length<80?source.periodKey:dateKey(now),
    usedSeconds:cleanSeconds(source.usedSeconds,7*86400),
    episodesUsed:cleanInt(source.episodesUsed,0,1000),
    moviesUsed:cleanInt(source.moviesUsed,0,1000),
    bonusSeconds:cleanSeconds(source.bonusSeconds,86400),
    bonusEpisodes:cleanInt(source.bonusEpisodes,0,100),
    bonusMovies:cleanInt(source.bonusMovies,0,50),
    charged,
    contentSeconds
  };
}
function normalizePin(raw){
  if(!raw||typeof raw!=='object')return null;
  const salt=typeof raw.salt==='string'?raw.salt:'',hash=typeof raw.hash==='string'?raw.hash:'',iterations=cleanInt(raw.iterations,100000,1000000,0);
  return /^[0-9a-f]{32}$/i.test(salt)&&/^[0-9a-f]{64}$/i.test(hash)&&iterations?{salt:salt.toLowerCase(),hash:hash.toLowerCase(),iterations}:null;
}
function normalizeState(raw,now=Date.now()){
  const source=raw&&typeof raw==='object'?raw:{};
  return {
    version:1,
    pin:normalizePin(source.pin),
    viewers:{
      'viewer-1':normalizeProfile(source.viewers?.['viewer-1'],now),
      'viewer-2':normalizeProfile(source.viewers?.['viewer-2'],now)
    }
  };
}
function resetUsage(profile,now=Date.now()){
  profile.periodKey=profile.resetMode==='daily'?dateKey(now):`manual:${now}`;
  profile.usedSeconds=0;profile.episodesUsed=0;profile.moviesUsed=0;
  profile.bonusSeconds=0;profile.bonusEpisodes=0;profile.bonusMovies=0;
  profile.charged=[];profile.contentSeconds={};
}
function readState(store=storage(),now=Date.now()){
  let parsed={};
  try{parsed=JSON.parse(store?.getItem(STORAGE_KEY)||'{}')}catch{}
  const state=normalizeState(parsed,now);
  let changed=false;
  for(const profile of Object.values(state.viewers)){
    if(profile.resetMode==='daily'&&profile.periodKey!==dateKey(now)){resetUsage(profile,now);changed=true;}
  }
  if(changed)writeState(state,store);
  return state;
}
function writeState(state,store=storage()){
  try{store?.setItem(STORAGE_KEY,JSON.stringify(state));return true}catch{return false}
}
function clone(value){return JSON.parse(JSON.stringify(value))}
function toHex(bytes){return [...bytes].map(value=>value.toString(16).padStart(2,'0')).join('')}
function fromHex(value){
  const out=new Uint8Array(value.length/2);
  for(let i=0;i<out.length;i++)out[i]=parseInt(value.slice(i*2,i*2+2),16);
  return out;
}
async function pinHash(pin,salt,iterations,cryptoObj=globalThis.crypto){
  if(!cryptoObj?.subtle)throw new Error('Secure browser cryptography is not available on this device.');
  const material=await cryptoObj.subtle.importKey('raw',new TextEncoder().encode(pin),'PBKDF2',false,['deriveBits']);
  const bits=await cryptoObj.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt,iterations},material,256);
  return new Uint8Array(bits);
}
function equalHex(a,b){
  if(typeof a!=='string'||typeof b!=='string'||a.length!==b.length)return false;
  let diff=0;for(let i=0;i<a.length;i++)diff|=a.charCodeAt(i)^b.charCodeAt(i);return diff===0;
}
export function hasParentPin(store=storage(),now=Date.now()){const phone=parentPinService();return phone?phone.hasPin():!!readState(store,now).pin}
export async function setParentPin(pin,store=storage(),cryptoObj=globalThis.crypto,now=Date.now()){
  const phone=parentPinService();if(phone)return await phone.setPin(pin);
  if(!/^\d{4,8}$/.test(String(pin||'')))throw new Error('Parent PIN must be 4 to 8 digits.');
  if(!cryptoObj?.getRandomValues)throw new Error('Secure browser cryptography is not available on this device.');
  const state=readState(store,now),salt=cryptoObj.getRandomValues(new Uint8Array(16));
  const hash=await pinHash(String(pin),salt,PIN_ITERATIONS,cryptoObj);
  state.pin={salt:toHex(salt),hash:toHex(hash),iterations:PIN_ITERATIONS};
  writeState(state,store);return true;
}
export async function verifyParentPin(pin,store=storage(),cryptoObj=globalThis.crypto,now=Date.now()){
  const phone=parentPinService();if(phone)return await phone.verifyPin(pin);
  if(!/^\d{4,8}$/.test(String(pin||'')))return false;
  const state=readState(store,now);if(!state.pin)return false;
  const hash=await pinHash(String(pin),fromHex(state.pin.salt),state.pin.iterations,cryptoObj);
  return equalHex(toHex(hash),state.pin.hash);
}
export function getKidProfile(viewer,store=storage(),now=Date.now()){
  const state=readState(store,now);return clone(state.viewers[validViewer(viewer)]);
}
export function updateKidProfile(viewer,patch={},store=storage(),now=Date.now()){
  const state=readState(store,now),key=validViewer(viewer),current=state.viewers[key];
  const next=normalizeProfile({...current,...patch},now);
  if(next.resetMode==='daily'&&next.periodKey!==dateKey(now))resetUsage(next,now);
  state.viewers[key]=next;writeState(state,store);return clone(next);
}
export function resetKidAllowance(viewer,store=storage(),now=Date.now()){
  const state=readState(store,now),profile=state.viewers[validViewer(viewer)];
  resetUsage(profile,now);writeState(state,store);return clone(profile);
}
export function grantKidExtension(viewer,{minutes=0,episodes=0,movies=0}={},store=storage(),now=Date.now()){
  const state=readState(store,now),profile=state.viewers[validViewer(viewer)];
  profile.bonusSeconds=Math.min(86400,profile.bonusSeconds+cleanInt(minutes,0,1440)*60);
  profile.bonusEpisodes=Math.min(100,profile.bonusEpisodes+cleanInt(episodes,0,100));
  profile.bonusMovies=Math.min(50,profile.bonusMovies+cleanInt(movies,0,50));
  writeState(state,store);return clone(profile);
}
export function kidContentKey(context){
  const target=context?.current;
  if(!target||!/^tt[0-9]{5,12}$/.test(target.id||''))return '';
  if(target.type==='movie')return `movie:${target.id}`;
  const season=Number(target.season),episode=Number(target.episode);
  if(target.type==='series'&&Number.isSafeInteger(season)&&season>=0&&Number.isSafeInteger(episode)&&episode>=0)return `series:${target.id}:${season}:${episode}`;
  return '';
}
function totals(profile){
  return {
    seconds:profile.timeLimitMinutes*60+profile.bonusSeconds,
    episodes:profile.episodeLimit+profile.bonusEpisodes,
    movies:profile.movieLimit+profile.bonusMovies
  };
}
export function canStartKidPlayback(viewer,context,store=storage(),now=Date.now()){
  const state=readState(store,now),profile=state.viewers[validViewer(viewer)],limit=totals(profile);
  if(!profile.enabled)return {allowed:true,reason:'',profile:clone(profile)};
  if(limit.seconds>0&&profile.usedSeconds>=limit.seconds)return {allowed:false,reason:'time',profile:clone(profile)};
  const key=kidContentKey(context),alreadyCharged=key&&profile.charged.includes(key);
  if(key.startsWith('series:')&&limit.episodes>0&&profile.episodesUsed>=limit.episodes&&!alreadyCharged)return {allowed:false,reason:'episodes',profile:clone(profile)};
  if(key.startsWith('movie:')&&limit.movies>0&&profile.moviesUsed>=limit.movies&&!alreadyCharged)return {allowed:false,reason:'movies',profile:clone(profile)};
  return {allowed:true,reason:'',profile:clone(profile)};
}
export function consumeKidPlayback(viewer,context,deltaSeconds,duration=0,store=storage(),now=Date.now()){
  const state=readState(store,now),profile=state.viewers[validViewer(viewer)];
  if(!profile.enabled)return {profile:clone(profile),newlyCharged:false,timeBlocked:false};
  const delta=Math.min(3600,Math.max(0,Number(deltaSeconds)||0));
  if(delta<=0)return {profile:clone(profile),newlyCharged:false,timeBlocked:false};
  profile.usedSeconds=Math.min(7*86400,profile.usedSeconds+delta);
  const key=kidContentKey(context);let newlyCharged=false;
  if(key){
    profile.contentSeconds[key]=Math.min(172800,(profile.contentSeconds[key]||0)+delta);
    if(!profile.charged.includes(key)){
      const series=key.startsWith('series:'),knownDuration=Number.isFinite(duration)&&duration>0;
      const threshold=series?Math.min(300,knownDuration?duration*.2:300):600;
      if(profile.contentSeconds[key]>=threshold){
        profile.charged.push(key);if(profile.charged.length>MAX_CONTENT_ROWS)profile.charged=profile.charged.slice(-MAX_CONTENT_ROWS);
        if(series)profile.episodesUsed+=1;else profile.moviesUsed+=1;
        newlyCharged=true;
      }
    }
  }
  writeState(state,store);
  const limit=totals(profile);
  return {profile:clone(profile),newlyCharged,timeBlocked:limit.seconds>0&&profile.usedSeconds>=limit.seconds};
}
export function formatKidUsage(profile){
  const p=normalizeProfile(profile),limit=totals(p),parts=[];
  if(limit.seconds>0)parts.push(`${Math.floor(p.usedSeconds/60)} / ${Math.round(limit.seconds/60)} min`);
  if(limit.episodes>0)parts.push(`${p.episodesUsed} / ${limit.episodes} episodes`);
  if(limit.movies>0)parts.push(`${p.moviesUsed} / ${limit.movies} movies`);
  return parts.length?parts.join(' · '):'No allowance limits';
}
