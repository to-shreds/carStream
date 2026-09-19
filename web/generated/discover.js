import { imageUrl } from './runtime.js';
import { loadPublicSources } from './source-client.js';
import { getSettings, updateSettings } from './settings.js';
import { listRecent, formatResumeTime } from './history.js';
import { listWatchlist, isWatchlisted, toggleWatchlist } from './watchlist.js';
import { listSearchHistory, recordSearch, removeSearch } from './search-history.js';
import { applySourceMemory, getTitleQuality, setTitleQuality, setSourceBad, setAudioFeedback } from './source-memory.js';
const $ = id => document.getElementById(id);
const GB = 1024 ** 3;
const element = (tag, text = '', className = '') => { const el = document.createElement(tag); if (text) el.textContent = text; if (className) el.className = className; return el; };
const button = (label, fn, primary = false) => { const b = element('button', label, primary ? 'primary' : ''); b.type = 'button'; b.addEventListener('click', fn); return b; };
const image = (url, label, className) => { const i = element('img', '', className); i.alt = label; i.loading = 'lazy'; i.referrerPolicy = 'no-referrer'; i.src = imageUrl(url); i.addEventListener('error', () => { i.hidden = true; }); return i; };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const resolutionOf = s => String(s?.resolution || s?.quality || '').toLowerCase();
export function sourceMatchesResolution(source, resolution = 'auto') {
  if (!resolution || resolution === 'auto') return true;
  const value = resolutionOf(source);
  if (resolution === '2160p') return value.includes('2160') || value.includes('4k');
  return value.includes(resolution.replace('p', ''));
}
export const filterSourcesByResolution = (list, resolution = 'auto') => list.filter(s => sourceMatchesResolution(s, resolution));
export function recommendSource(list, type = 'movie', resolution = 'auto', sizeProfile = getSettings().sourceSizeProfile) {
  const visible = filterSourcesByResolution(list, resolution).filter(source=>source.memoryBad!==true&&source.memoryAudio!=='bad'); if (!visible.length) return null;
  const cachedFriendly = visible.filter(s => s.cached === true && s.browserFriendly && !s.audioRisk && !s.videoRisk);
  const cachedSafe = visible.filter(s => s.cached === true && !s.audioRisk && !s.videoRisk);
  const browserSafe = visible.filter(s => s.browserFriendly && !s.audioRisk);
  const cached = visible.filter(s => s.cached === true && !s.audioRisk);
  const pool = cachedFriendly.length ? cachedFriendly : cachedSafe.length ? cachedSafe : browserSafe.length ? browserSafe : cached.length ? cached : visible;
  const profile = ['data','balanced','quality'].includes(sizeProfile) ? sizeProfile : 'balanced';
  const limits = profile === 'data' ? { movie:1.5 * GB, series:.6 * GB } : profile === 'quality' ? { movie:6 * GB, series:2 * GB } : { movie:3 * GB, series:1 * GB };
  const limit = limits[type === 'series' ? 'series' : 'movie'];
  const rank = s => {
    const r = resolutionOf(s), sizeScore = s.size == null ? 15 : s.size <= limit ? 110 : -Math.min(140, (s.size / limit - 1) * 90);
    const seedScore = Number.isSafeInteger(s.seeders) ? Math.min(35, Math.log2(s.seeders + 1) * 5) : 0;
    return (s.score || 0) + (s.memoryBonus || 0) + (s.cached === true ? 260 : 0) + (s.browserFriendly ? 100 : 0) - (s.audioRisk ? 180 : 0) - (s.videoRisk ? 100 : 0)
      + (r.includes('720') ? 135 : r.includes('1080') ? 55 : 0) + sizeScore + seedScore;
  };
  return [...pool].sort((a,b)=>rank(b)-rank(a)||(a.size??Infinity)-(b.size??Infinity))[0];
}
export const preferredSource = list => recommendSource(list, 'movie', 'auto');
export function episodeQueue(meta, target, now = Date.now()) {
  if (!meta || target?.type !== 'series' || !Array.isArray(meta.episodes)) return [];
  const released = meta.episodes.filter(e => !e.released || Date.parse(e.released) <= now).sort((a,b)=>a.season-b.season||a.episode-b.episode);
  const index = released.findIndex(e => e.season === target.season && e.episode === target.episode);
  if (index < 0) return [];
  return released.slice(index + 1).map(e => ({ type:'series', id:meta.id, season:e.season, episode:e.episode, name:e.name || `Episode ${e.episode}` }));
}
export function lowerResolutionOrder(current, selected = 'auto') {
  const value = String(current || selected || '').toLowerCase();
  const tier = value.includes('2160') || value.includes('4k') ? '2160p' : value.includes('1080') ? '1080p' : value.includes('720') ? '720p' : value.includes('480') ? '480p' : selected !== 'auto' ? selected : '720p';
  const order = ['2160p','1080p','720p','480p']; const index = order.indexOf(tier);
  return index >= 0 ? order.slice(index + 1) : ['720p','480p'];
}
const formatBytes = value => Number.isFinite(value) && value > 0 ? (value >= GB ? `${(value / GB).toFixed(value >= 10*GB ? 1 : 2)} GB` : `${Math.max(1,Math.round(value/1024**2))} MB`) : '—';
const qualityText = s => [s.resolution || s.quality, s.releaseQuality, s.videoCodec, ...(s.audioCodecs || []).slice(0,2), s.container].filter(Boolean).join(' · ') || 'Unknown';
const feedNames = { popular:'Popular', featured:'Featured', new:'New' };
const getResolution = target => getTitleQuality(target) || getSettings().resolution;
const setResolution = value => updateSettings({ resolution: value });
function createResolutionSelect(value = getResolution()) {
  const select = element('select');
  for (const [v,t] of [['auto','Auto · 720p'],['480p','480p'],['720p','720p'],['1080p','1080p'],['2160p','4K']]) { const o=element('option',t);o.value=v;select.append(o); }
  select.value = value; select.addEventListener('change',()=>setResolution(select.value)); return select;
}

export function createDiscoveryUI({ api, play, driveTest, guard }) {
  let active=false, guestMode=false, catalogGeneration=0, titleGeneration=0, sourceGeneration=0, preparationGeneration=0, nextUpGeneration=0;
  let catalogAbort,titleAbort,sourceAbort,searchTimer,pollTimer,metas=[],nextSkip=null,currentMeta;

  function cancelSource(){++preparationGeneration;++sourceGeneration;sourceAbort?.abort();clearTimeout(pollTimer);}
  function cancelTitle(){++titleGeneration;titleAbort?.abort();cancelSource();}
  function message(id,text,error=false){$(id).textContent=text;$(id).classList.toggle('error',error);}
  function applyBrowsePreferences(){
    const settings=getSettings();if(!settings.rememberBrowse)return;
    $('catalog-type').value=settings.catalogType;$('catalog-feed').value=settings.catalogFeed;$('catalog-genre').value=settings.catalogGenre;
  }
  function persistBrowsePreferences(){
    if(!getSettings().rememberBrowse)return;
    updateSettings({catalogType:$('catalog-type').value,catalogFeed:$('catalog-feed').value,catalogGenre:$('catalog-genre').value});
  }
  function applySearchMode(){
    const query=$('search').value.trim(),searching=!!query&&!guestMode;
    document.body.classList.toggle('search-mode',searching);
    const heading=$('search-results-heading');
    if(heading){heading.hidden=!searching;heading.textContent=searching?`Search results for “${query}”`:'Search results';}
  }
  function renderSearchHistory(){
    const section=$('search-history-section'),list=$('search-history-list'),settings=getSettings(),query=$('search').value.trim();
    if(!section||!list||guestMode||!settings.showSearchHistory||query){if(section)section.hidden=true;return;}
    const rows=listSearchHistory($('viewer').value).slice(0,settings.searchHistoryLimit);section.hidden=!rows.length;
    const fragment=document.createDocumentFragment();
    for(const row of rows){
      const chip=element('span','','search-history-chip'),open=button(row.query,()=>{$('search').value=row.query;applySearchMode();section.hidden=true;browse();}),remove=button('×',event=>{event.stopPropagation();removeSearch($('viewer').value,row.query);renderSearchHistory();});
      remove.className='search-history-remove';remove.setAttribute('aria-label',`Remove search ${row.query}`);chip.append(open,remove);fragment.append(chip);
    }
    list.replaceChildren(fragment);
  }
  async function renderNextUp(){
    const generation=++nextUpGeneration,section=$('next-up-section'),list=$('next-up-list'),settings=getSettings();
    if(!section||!list||guestMode||!settings.showNextUp){if(section)section.hidden=true;return;}
    const latest=new Map();
    for(const item of listRecent())if(item.type==='series'&&!latest.has(item.id))latest.set(item.id,item);
    const candidates=[...latest.values()].filter(item=>item.completed).slice(0,Math.max(settings.nextUpLimit,3));
    if(!candidates.length){section.hidden=true;list.replaceChildren();return;}
    section.hidden=false;list.replaceChildren(element('span','Finding next episodes…','muted'));
    const cards=[];
    for(const item of candidates){
      if(generation!==nextUpGeneration||!active||guestMode)return;
      try{
        const data=await api(`/api/discover/meta?type=series&id=${item.id}`,{signal:AbortSignal.timeout(12000)}),meta=data.meta;
        const next=episodeQueue(meta,{type:'series',id:item.id,season:item.season,episode:item.episode})[0];if(!next)continue;
        const episode=meta.episodes.find(e=>e.season===next.season&&e.episode===next.episode),card=element('article','','next-up-card');
        if(meta.poster)card.append(image(meta.poster,'','next-up-thumb'));
        const copy=element('div','','next-up-copy');copy.append(element('strong',meta.name),element('span',`S${next.season}E${next.episode} · ${episode?.name||next.name||'Next episode'}`,'muted'));
        const playButton=button('Play',()=>quickPlay(meta,next,episode?.name||next.name||'',playButton),true);card.append(copy,playButton);cards.push(card);
        if(cards.length>=settings.nextUpLimit)break;
      }catch{}
    }
    if(generation!==nextUpGeneration)return;
    if(!cards.length){section.hidden=true;list.replaceChildren();return;}section.hidden=false;list.replaceChildren(...cards);
  }
  async function resolveQuickTarget(meta){
    const data=meta?.episodes?{meta}:await api(`/api/discover/meta?type=${meta.type}&id=${meta.id}`,{signal:AbortSignal.timeout(15000)}),full=data.meta;
    if(full.type==='movie')return{meta:full,target:{type:'movie',id:full.id},episodeName:''};
    const history=listRecent().filter(item=>item.type==='series'&&item.id===full.id),latest=history[0];
    if(latest&&!latest.completed)return{meta:full,target:{type:'series',id:full.id,season:latest.season,episode:latest.episode},episodeName:full.episodes.find(e=>e.season===latest.season&&e.episode===latest.episode)?.name||latest.episodeName||''};
    if(latest?.completed){
      const next=episodeQueue(full,{type:'series',id:full.id,season:latest.season,episode:latest.episode})[0];
      if(next)return{meta:full,target:next,episodeName:full.episodes.find(e=>e.season===next.season&&e.episode===next.episode)?.name||next.name||''};
    }
    const released=full.episodes.filter(e=>!e.released||Date.parse(e.released)<=Date.now()).sort((a,b)=>(a.season===0)-(b.season===0)||a.season-b.season||a.episode-b.episode),first=released[0];
    if(!first)throw new Error('No released episode is available.');
    return{meta:full,target:{type:'series',id:full.id,season:first.season,episode:first.episode},episodeName:first.name||''};
  }
  function openQuickActions(meta){
    if(!meta||guestMode)return;const dialog=$('quick-actions-dialog'),area=$('quick-actions-content');$('quick-actions-title').textContent=meta.name||'Title';area.replaceChildren();
    const favorite=button(isWatchlisted($('viewer').value,meta)?'Remove from My list':'Add to My list',()=>{toggleWatchlist($('viewer').value,meta);renderWatchlist();favorite.textContent=isWatchlisted($('viewer').value,meta)?'Remove from My list':'Add to My list';});
    const playButton=button('Play',async()=>{try{const resolved=await resolveQuickTarget(meta);dialog.close();await quickPlay(resolved.meta,resolved.target,resolved.episodeName,playButton);}catch(e){message('catalog-message',e.message,true);}},true);
    const shareButton=button('Share',async()=>{try{const resolved=await resolveQuickTarget(meta);dialog.close();await quickDriveShare(resolved.meta,resolved.target,resolved.episodeName,shareButton);}catch(e){message('catalog-message',e.message,true);}});shareButton.classList.add('advanced-only');shareButton.hidden=typeof driveTest!=='function';
    const details=button('Details',()=>{dialog.close();showTitle(meta);});
    area.append(playButton,favorite,shareButton,details);if(!dialog.open)dialog.showModal();
  }
  function posterCard(meta){
    let longPressed=false,timer=null;
    const card=button('',()=>{if(longPressed){longPressed=false;return;}showTitle(meta)});card.className='poster-card';card.setAttribute('aria-label',`Open ${meta.name}`);
    const art=element('div','','poster-art');if(meta.poster)art.append(image(meta.poster,'','poster-image'));art.append(element('span',meta.name,'poster-fallback'));
    card.append(art,element('strong',meta.name),element('span',meta.year||(meta.type==='series'?'Show':'Movie'),'muted'));
    const cancel=()=>{if(timer)clearTimeout(timer);timer=null;};
    card.addEventListener('pointerdown',event=>{if(!getSettings().longPressShortcuts||event.button>0)return;cancel();timer=setTimeout(()=>{longPressed=true;openQuickActions(meta);},550);});
    for(const name of ['pointerup','pointercancel','pointerleave'])card.addEventListener(name,cancel);
    card.addEventListener('contextmenu',event=>{if(!getSettings().longPressShortcuts)return;event.preventDefault();cancel();longPressed=true;openQuickActions(meta);});
    return card;
  }
  function updateWatchlistButton(){
    const control=$('toggle-watchlist');if(!control)return;
    control.hidden=guestMode||!currentMeta;if(control.hidden)return;
    control.textContent=isWatchlisted($('viewer').value,currentMeta)?'Remove from My list':'Add to My list';
  }
  function renderWatchlist(){
    const section=$('watchlist-section'),list=$('watchlist-list'),settings=getSettings();
    if(!section||!list||guestMode||!settings.showWatchlist){if(section)section.hidden=true;return;}
    const rows=listWatchlist($('viewer').value).slice(0,settings.watchlistLimit);
    section.hidden=!rows.length;$('watchlist-count').textContent=rows.length?String(rows.length):'';
    const fragment=document.createDocumentFragment();
    for(const meta of rows){
      fragment.append(posterCard(meta));
    }
    list.replaceChildren(fragment);
  }
  async function openDiscover(){guestMode=false;$('page-title').textContent='Discover';$('search').placeholder='Search movies and shows';applyBrowsePreferences();applySearchMode();renderWatchlist();renderSearchHistory();renderNextUp();await browse();}

  async function browse(more=false){
    if(!active||guestMode)return;const offset=more?nextSkip:0;if(offset===null)return;
    const generation=++catalogGeneration;catalogAbort?.abort();catalogAbort=new AbortController();$('catalog-more').disabled=true;$('catalog-retry').hidden=true;
    if(!more){metas=[];nextSkip=null;$('catalog-grid').replaceChildren();$('catalog-more').hidden=true;}
    const query=$('search').value.trim();applySearchMode();message('catalog-message',query?'Searching…':'Loading browse…');
    try{
      const params=new URLSearchParams({type:$('catalog-type').value,q:query,skip:String(offset),genre:$('catalog-genre').value,feed:$('catalog-feed').value});
      const data=await api('/api/discover/catalog?'+params,{signal:AbortSignal.any([catalogAbort.signal,AbortSignal.timeout(20000)])});
      if(generation!==catalogGeneration||!active||guestMode)return;
      metas=more?[...new Map([...metas,...data.metas].map(m=>[m.id,m])).values()]:data.metas;nextSkip=data.nextSkip;
      const fragment=document.createDocumentFragment();
      for(const meta of metas){
        fragment.append(posterCard(meta));
      }
      $('catalog-grid').replaceChildren(fragment);$('catalog-more').hidden=nextSkip===null;
      if(query){if(!more)recordSearch($('viewer').value,query);message('catalog-message',metas.length?`${metas.length} results`:'No matches. Try a shorter title or IMDb ID.');}
      else {const shown=feedNames[data.feed]||'Browse';message('catalog-message',data.fallback?`${feedNames[data.requestedFeed]||'Browse'} unavailable · showing ${shown}`:metas.length?shown:'No titles in this browse view.');}
    }catch(e){
      if(generation===catalogGeneration&&active&&!guestMode){message('catalog-message',query?(e.name==='TimeoutError'?'Search timed out.':e.message):'Browse is temporarily unavailable. Search still works.',true);$('catalog-retry').hidden=false;}
    }finally{if(generation===catalogGeneration)$('catalog-more').disabled=false;}
  }

  function buildContext(meta,target,episodeName,resolution,source){
    return {title:meta?.name||'',poster:meta?.poster||'',episodeName:episodeName||'',resolution,current:{...target,name:episodeName||''},sourceResolution:source?.resolution||source?.quality||'',sourceInfo:source?{id:source.id||'',hash:source.hash||'',filename:source.filename||'',title:source.title||'',provider:source.provider||'',resolution:source.resolution||source.quality||'',videoCodec:source.videoCodec||'',audioCodecs:Array.isArray(source.audioCodecs)?source.audioCodecs.slice(0,6):[]}:null,queue:target.type==='series'?episodeQueue(meta,target):[]};
  }
  const closeTitleForPlayback = () => { if (!guestMode && $('title-dialog').open) $('title-dialog').close(); };

  async function ensureService(){ if(typeof guard==='function') await guard(); }
  async function registeredSources(target, signal){
    const sources=await loadPublicSources(target,{signal});if(!sources.length)throw new Error('No source found.');
    const result=await api('/api/discover/sources',{method:'POST',data:{target,sources},signal:signal?AbortSignal.any([signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000)});
    if(!result.sources?.length)throw new Error('No supported source found.');return {...result,sources:applySourceMemory(target,result.sources)};
  }

  async function readyFile(source, { signal, unattended=false } = {}){
    let result=await api('/api/discover/prepare',{method:'POST',data:{source:source.id,onlyCached:source.cached===true},signal:signal?AbortSignal.any([signal,AbortSignal.timeout(60000)]):AbortSignal.timeout(60000)});
    const deadline=Date.now()+300000;
    while(Date.now()<deadline){
      if(result.state==='ready')return result;
      if(result.state==='choose_file'){
        if(!result.files?.length)throw new Error('No matching video file was found.');
        if(unattended&&result.files.length!==1)throw new Error('This package has multiple possible files and cannot be selected automatically.');
        result=await api('/api/discover/status?'+new URLSearchParams({source:source.id,file:result.files[0].id}),{signal:signal?AbortSignal.any([signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000)});continue;
      }
      if(result.state!=='preparing')throw new Error(result.message||'This source is unavailable.');
      await delay(3500);result=await api('/api/discover/status?'+new URLSearchParams({source:source.id}),{signal:signal?AbortSignal.any([signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000)});
    }
    throw new Error('TorBox is still preparing this source. Try again shortly.');
  }

  async function quickPlay(meta,target,episodeName='',trigger){
    const original=trigger?.textContent;if(trigger){trigger.disabled=true;trigger.textContent='Opening…';}
    message('detail-message','Finding the recommended source…');
    try{
      await ensureService();
      const resolution=getResolution(target),registered=await registeredSources(target,AbortSignal.timeout(45000));
      const best=recommendSource(registered.sources,target.type,resolution)||recommendSource(registered.sources,target.type,'auto');
      if(!best)throw new Error('No source matches this resolution.');
      message('detail-message',best.cached?'Opening cached source…':'Preparing recommended source…');
      const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});
      const context=buildContext(meta,target,episodeName,resolution,best);
      if($('source-dialog').open)$('source-dialog').close();closeTitleForPlayback();
      await play(result.file,context);return true;
    }catch(e){message('detail-message',e.message,true);return false;}
    finally{if(trigger&&trigger.isConnected){trigger.disabled=false;trigger.textContent=original;}}
  }

  async function quickDriveShare(meta,target,episodeName='',trigger){
    if(typeof driveTest!=='function')return false;
    const original=trigger?.textContent;if(trigger){trigger.disabled=true;trigger.textContent='Preparing…';}
    message('detail-message','Finding the recommended source for Drive…');
    try{
      await ensureService();
      const resolution=getResolution(target),registered=await registeredSources(target,AbortSignal.timeout(45000));
      const best=recommendSource(registered.sources,target.type,resolution)||recommendSource(registered.sources,target.type,'auto');
      if(!best)throw new Error('No source matches this resolution.');
      message('detail-message',best.cached?'Preparing Drive export…':'Preparing the recommended source in TorBox…');
      const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});
      const context=buildContext(meta,target,episodeName,resolution,best);
      if($('source-dialog').open)$('source-dialog').close();
      if($('title-dialog').open)$('title-dialog').close();
      await driveTest(result.file,context);return true;
    }catch(e){message('detail-message',e.message,true);return false;}
    finally{if(trigger&&trigger.isConnected){trigger.disabled=false;trigger.textContent=original;}}
  }

  function titleResolutionControl(meta){
    const target={type:meta.type,id:meta.id},label=element('label','This title quality','compact-select advanced-only'),select=element('select');
    for(const [value,text] of [['','Use default'],['480p','480p'],['720p','720p'],['1080p','1080p'],['2160p','4K']]){const option=element('option',text);option.value=value;select.append(option);}
    select.value=getTitleQuality(target);select.addEventListener('change',()=>setTitleQuality(target,select.value));label.append(select);return label;
  }

  async function showTitle(meta){
    cancelTitle();currentMeta=null;const generation=titleGeneration;titleAbort=new AbortController();$('title-content').replaceChildren();$('episode-area').replaceChildren();
    $('detail-title').textContent=meta.name;$('toggle-watchlist').hidden=true;message('detail-message','Loading…');if(!$('title-dialog').open)$('title-dialog').showModal();
    try{
      const data=await api(`/api/discover/meta?type=${meta.type}&id=${meta.id}`,{signal:AbortSignal.any([titleAbort.signal,AbortSignal.timeout(20000)])});
      if(generation!==titleGeneration||!$('title-dialog').open||!active)return;currentMeta=data.meta;updateWatchlistButton();
      const summary=element('div','','title-summary');if(currentMeta.poster)summary.append(image(currentMeta.poster,currentMeta.name,'detail-poster'));
      const copy=element('div','','title-copy');copy.append(element('p',[currentMeta.year,currentMeta.runtime,...currentMeta.genres].filter(Boolean).join(' · '),'muted'),element('p',currentMeta.description||'','title-description'));summary.append(copy);
      $('title-content').replaceChildren(summary);message('detail-message','');
      if(currentMeta.type==='movie')renderMovieActions(currentMeta);else renderEpisodes(currentMeta);
    }catch(e){if(generation===titleGeneration&&active)message('detail-message',e.name==='AbortError'?'Cancelled.':e.message,true);}
  }

  function renderMovieActions(meta){
    const bar=element('div','','movie-action-bar');bar.append(titleResolutionControl(meta));
    const target={type:'movie',id:meta.id},actions=element('div','','row-actions');
    const playButton=button('Play',()=>quickPlay(meta,target,'',playButton),true);
    const shareButton=button('Share',()=>quickDriveShare(meta,target,'',shareButton));shareButton.hidden=guestMode||typeof driveTest!=='function';shareButton.classList.add('advanced-only');
    const options=button('Options',()=>openOptions(meta,target));options.classList.add('advanced-only');actions.append(playButton,shareButton,options);bar.append(actions);$('episode-area').replaceChildren(bar);
  }

  function renderEpisodes(meta){
    const settings=getSettings(),progressRows=settings.showEpisodeProgress?listRecent().filter(item=>item.type==='series'&&item.id===meta.id):[];
    const controls=element('div','','episode-controls');const seasonLabel=element('label','Season','compact-select');const seasonSelect=element('select');
    const seasons=[...new Set(meta.episodes.map(e=>e.season))];for(const n of seasons){const o=element('option',n===0?'Specials':`Season ${n}`);o.value=String(n);seasonSelect.append(o);}
    if(seasons.some(n=>n>0))seasonSelect.value=String(seasons.find(n=>n>0));seasonLabel.append(seasonSelect);controls.append(seasonLabel,titleResolutionControl(meta));
    const list=element('div','','episode-list');
    const render=()=>{
      list.replaceChildren();
      for(const episode of meta.episodes.filter(e=>e.season===+seasonSelect.value)){
        const row=element('article','','episode-row');const number=element('strong',String(episode.episode).padStart(2,'0'),'episode-number');
        const info=element('div','','episode-info');info.append(element('strong',episode.name));if(episode.description)info.append(element('span',episode.description,'episode-overview'));
        const progress=progressRows.find(item=>item.season===episode.season&&item.episode===episode.episode);
        if(progress){const label=progress.completed?'Watched':progress.position>0?`Resume ${formatResumeTime(progress.position)}`:'';if(label)info.append(element('span',label,'episode-progress-text'));}
        const actions=element('div','','episode-actions');const target={type:'series',id:meta.id,season:episode.season,episode:episode.episode};
        const playButton=button('Play',()=>quickPlay(meta,target,episode.name,playButton),true);
        const shareButton=button('Share',()=>quickDriveShare(meta,target,episode.name,shareButton));shareButton.hidden=guestMode||typeof driveTest!=='function';shareButton.classList.add('advanced-only');
        const more=button('Options',()=>openOptions(meta,target,episode.name));more.setAttribute('aria-label',`More options for ${episode.name}`);more.classList.add('advanced-only');
        actions.append(playButton,shareButton,more);row.append(number,info,actions);
        if(episode.released&&Date.parse(episode.released)>Date.now()){playButton.disabled=true;shareButton.disabled=true;more.disabled=true;row.classList.add('future');}
        list.append(row);
      }
    };
    seasonSelect.addEventListener('change',render);$('episode-area').replaceChildren(controls,list);
    if(!seasons.length){$('episode-area').append(element('p','No episode list is available.'));return;}render();
  }

  async function openOptions(meta,target,episodeName=''){
    currentMeta=meta;if(!$('source-dialog').open)$('source-dialog').showModal();$('source-title').textContent=episodeName?`${meta.name} · S${target.season}E${target.episode}`:meta.name;
    await findSources(meta,target,episodeName);
  }

  function renderSourceTable(meta,list,target,episodeName,generation,area,status,resolution,page=0){
    area.replaceChildren(status);
    const controls=element('div','','source-controls'),label=element('label','Resolution'),select=createResolutionSelect(resolution);label.append(select);controls.append(label);area.append(controls);
    const visible=filterSourcesByResolution(list,resolution);
    if(!visible.length){
      area.append(element('p','No sources match this resolution.','source-note'));
      select.addEventListener('change',()=>renderSourceTable(meta,list,target,episodeName,generation,area,status,select.value,0));
      return;
    }
    const best=recommendSource(visible,target.type,resolution),context=best?buildContext(meta,target,episodeName,resolution,best):null;
    if(best){
      const box=element('section','','recommended'),copy=element('div','','recommended-copy');
      copy.append(element('div','Recommended','recommended-kicker'),element('div',best.title,'recommended-title'),element('p',[best.cached?'Cached':'Not cached',qualityText(best),formatBytes(best.size),Number.isSafeInteger(best.seeders)?best.seeders+' seeders':''].filter(Boolean).join(' · '),'recommended-meta'));
      const recommendedActions=element('div','','recommended-actions');
      recommendedActions.append(button(best.cached?'Play':'Prepare',async()=>{try{await ensureService();const result=await readyFile(best,{signal:AbortSignal.timeout(330000)});$('source-dialog').close();closeTitleForPlayback();await play(result.file,context);}catch(e){status.textContent=e.message;status.classList.add('error');}},true));
      if(!guestMode&&typeof driveTest==='function')recommendedActions.append(button('Share',async()=>{try{await ensureService();const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});$('source-dialog').close();if($('title-dialog').open)$('title-dialog').close();await driveTest(result.file,context);}catch(e){status.textContent=e.message;status.classList.add('error');}}));
      box.append(copy,recommendedActions);area.append(box);
    }

    const alternates=visible.filter(source=>source.id!==best?.id);
    if(alternates.length){
      const mobile=typeof matchMedia==='function'&&matchMedia('(max-width: 620px)').matches,pageSize=mobile?3:6,pages=Math.max(1,Math.ceil(alternates.length/pageSize)),safePage=Math.min(Math.max(0,page),pages-1);
      const rows=alternates.slice(safePage*pageSize,(safePage+1)*pageSize);
      const wrap=element('div','','source-table-wrap'),table=element('table','','source-table'),head=document.createElement('thead'),hr=document.createElement('tr');
      for(const title of ['Filename','Size','Seeders','Quality','TorBox',''])hr.append(element('th',title));head.append(hr);table.append(head);const body=document.createElement('tbody');
      for(const source of rows){
        const row=document.createElement('tr'),nameCell=element('td','','filename');
        nameCell.append(element('span',source.title,'source-title-cell'),element('small',[formatBytes(source.size),Number.isSafeInteger(source.seeders)?source.seeders+' seeders':'seeders —',qualityText(source),source.cached?'Cached':source.cached===false?'Not cached':'Cache ?'].join(' · '),'source-mobile-meta'));
        row.append(nameCell,element('td',formatBytes(source.size)),element('td',Number.isSafeInteger(source.seeders)?String(source.seeders):'—'),element('td',qualityText(source)),element('td',source.cached?'Cached':source.cached===false?'Not cached':'Unknown',source.cached?'cache-yes':'cache-no'));
        const action=document.createElement('td');
        if(source.memoryBad||source.memoryAudio==='bad'){
          row.classList.add('source-blocked');const allow=button('Allow',()=>{setSourceBad(target,source,false);setAudioFeedback(target,source,'unknown');findSources(meta,target,episodeName);});allow.title='Remove this source from the local bad-source list';action.append(allow);
        }else action.append(button(source.cached?'Play':'Prepare',async()=>{try{await ensureService();const result=await readyFile(source,{signal:AbortSignal.timeout(330000)});$('source-dialog').close();closeTitleForPlayback();await play(result.file,buildContext(meta,target,episodeName,resolution,source));}catch(e){status.textContent=e.message;status.classList.add('error');}}));
        row.append(action);body.append(row);
      }
      table.append(body);wrap.append(table);area.append(wrap);
      if(pages>1){
        const pager=element('nav','','source-pager'),prev=button('‹',()=>renderSourceTable(meta,list,target,episodeName,generation,area,status,resolution,safePage-1)),next=button('›',()=>renderSourceTable(meta,list,target,episodeName,generation,area,status,resolution,safePage+1));
        prev.disabled=safePage===0;next.disabled=safePage===pages-1;pager.append(prev,element('span',`${safePage+1} / ${pages}`),next);area.append(pager);
      }
    }
    select.addEventListener('change',()=>{setResolution(select.value);renderSourceTable(meta,list,target,episodeName,generation,area,status,select.value,0);});
  }

  async function findSources(meta,target,episodeName=''){
    cancelSource();const generation=sourceGeneration;sourceAbort=new AbortController();const area=$('source-options'),status=element('p','Finding sources…','status subtle-status');area.replaceChildren(status);
    const alive=()=>active&&generation===sourceGeneration&&$('source-dialog').open;
    try{
      const result=await registeredSources(target,sourceAbort.signal);if(!alive())return;status.textContent=result.warning||'';renderSourceTable(meta,result.sources,target,episodeName,generation,area,status,getResolution(target));
    }catch(e){if(alive()){status.textContent=e.message;status.classList.add('error');area.append(button('Retry',()=>findSources(meta,target,episodeName)));}}
  }

  async function playNext(context){
    if(!active||!context?.queue?.length)return false;const next=context.queue[0],rest=context.queue.slice(1);
    try{
      const meta=await api(`/api/discover/meta?type=series&id=${next.id}`,{signal:AbortSignal.timeout(20000)});
      const registered=await registeredSources(next,AbortSignal.timeout(45000));
      let best=recommendSource(registered.sources,'series',context.resolution||'auto')||recommendSource(registered.sources,'series','auto');if(!best||best.audioRisk)return false;
      const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});
      await play(result.file,{...buildContext(meta.meta,next,next.name||'',context.resolution||getResolution(next),best),poster:meta.meta.poster||context.poster,queue:rest});return true;
    }catch{return false;}
  }

  async function recoverPlayback(context){
    if(!active||!context?.current)return false;
    try{
      const registered=await registeredSources(context.current,AbortSignal.timeout(45000));
      for(const resolution of lowerResolutionOrder(context.sourceResolution,context.resolution)){
        const best=recommendSource(registered.sources,context.current.type,resolution);if(!best||best.audioRisk||best.videoRisk)continue;
        try{
          const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});
          await play(result.file,{...context,...buildContext({name:context.title,poster:context.poster,episodes:[]},context.current,context.episodeName||'',resolution,best),queue:context.queue||[]});return true;
        }catch{}
      }
    }catch{}
    return false;
  }

  async function resumeRecent(entry,startOver=false){
    if(!entry||!['movie','series'].includes(entry.type))return false;
    message('catalog-message',startOver?'Starting over…':'Resuming…');
    try{
      await ensureService();
      const data=await api(`/api/discover/meta?type=${entry.type}&id=${entry.id}`,{signal:AbortSignal.timeout(20000)}),meta=data.meta;
      const target=entry.type==='series'?{type:'series',id:entry.id,season:entry.season,episode:entry.episode}:{type:'movie',id:entry.id};
      const name=entry.type==='series'?(meta.episodes.find(e=>e.season===entry.season&&e.episode===entry.episode)?.name||entry.episodeName):'';
      const registered=await registeredSources(target,AbortSignal.timeout(45000)),resolution=entry.resolution||getResolution(target);
      const best=recommendSource(registered.sources,target.type,resolution)||recommendSource(registered.sources,target.type,'auto');if(!best)throw new Error('No source is available to resume.');
      const result=await readyFile(best,{signal:AbortSignal.timeout(330000),unattended:true});
      const context=buildContext(meta,target,name,resolution,best);context.forceStartOver=startOver;context.rewindOnResumeSeconds=startOver?0:getSettings().resumeRewindSeconds;
      await play(result.file,context);message('catalog-message','');return true;
    }catch(e){message('catalog-message',e.message,true);return false;}
  }

  for(const id of ['catalog-type','catalog-feed','catalog-genre'])$(id).addEventListener('change',()=>{persistBrowsePreferences();browse();});
  $('catalog-more').addEventListener('click',()=>browse(true));$('catalog-retry').addEventListener('click',()=>browse());
  $('search').addEventListener('input',()=>{
    clearTimeout(searchTimer);applySearchMode();renderSearchHistory();if(guestMode)return;++catalogGeneration;catalogAbort?.abort();
    if(!$('search').value.trim()){browse();return;}
    searchTimer=setTimeout(()=>browse(),350);
  });
  $('search').addEventListener('keydown',event=>{
    if(event.key!=='Enter')return;
    event.preventDefault();clearTimeout(searchTimer);applySearchMode();$('search').blur();
    if(guestMode)return;++catalogGeneration;catalogAbort?.abort();browse();
  });
  $('search').addEventListener('focus',renderSearchHistory);
  $('close-title').addEventListener('click',()=>$('title-dialog').close());$('title-dialog').addEventListener('close',()=>{++titleGeneration;titleAbort?.abort();});
  $('close-source').addEventListener('click',()=>$('source-dialog').close());$('source-dialog').addEventListener('close',cancelSource);
  $('close-quick-actions').addEventListener('click',()=>$('quick-actions-dialog').close());
  $('toggle-watchlist').addEventListener('click',()=>{if(guestMode||!currentMeta)return;toggleWatchlist($('viewer').value,currentMeta);updateWatchlistButton();renderWatchlist();});
  $('viewer').addEventListener('change',()=>{cancelTitle();if($('title-dialog').open)$('title-dialog').close();if($('source-dialog').open)$('source-dialog').close();renderWatchlist();renderSearchHistory();renderNextUp();});
  return {
    async activate(){guestMode=false;active=true;await openDiscover();},
    async activateGuest(scope){
      guestMode=true;active=true;
      await showTitle({type:scope.type,id:scope.id,name:scope.name||'Shared title',poster:scope.poster||''});
    },
    playNext,recoverPlayback,resumeRecent,startOverRecent:entry=>resumeRecent(entry,true),historyChanged(){renderNextUp();},settingsChanged(){renderWatchlist();renderSearchHistory();renderNextUp();if(currentMeta&&$('title-dialog').open){updateWatchlistButton();if(currentMeta.type==='series')renderEpisodes(currentMeta);else renderMovieActions(currentMeta);}},
    suspend(){active=false;guestMode=false;document.body.classList.remove('search-mode');++catalogGeneration;catalogAbort?.abort();cancelTitle();clearTimeout(searchTimer);metas=[];nextSkip=null;currentMeta=null;$('catalog-grid').replaceChildren();$('title-content').replaceChildren();$('episode-area').replaceChildren();$('source-options').replaceChildren();if($('title-dialog').open)$('title-dialog').close();if($('source-dialog').open)$('source-dialog').close();}
  };
}