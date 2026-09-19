import { applicationStorage } from './runtime.js';
const KEY='torbox-settings-v1';
export const DEFAULT_SETTINGS=Object.freeze({
  interfaceMode:'simple',
  resolution:'auto',
  resumeRewindSeconds:10,
  autoNext:true,
  autoNextDelaySeconds:8,
  autoRecovery:true,
  bufferSeconds:12,
  driveWatchOnly:true,
  driveDeleteMinutes:10,
  recentLimit:6,
  showCompletedRecent:false,
  cleanupCompletedEpisodes:true,
  pauseOverlay:true,
  playbackRate:1,
  sleepTimerMinutes:0,
  stillWatchingMinutes:90,
  keepAwake:true,
  keyboardShortcuts:true,
  seekSeconds:10,
  showEpisodeProgress:true,
  rememberBrowse:true,
  catalogType:'movie',
  catalogFeed:'popular',
  catalogGenre:'',
  sourceSizeProfile:'balanced',
  showWatchlist:true,
  watchlistLimit:12,
  showNextUp:true,
  nextUpLimit:6,
  showSearchHistory:true,
  searchHistoryLimit:6,
  longPressShortcuts:true,
  showPlaybackHealth:true,
  autoLearnSources:true
});
const interfaceModes=new Set(['simple','full']);
const resolutions=new Set(['auto','480p','720p','1080p','2160p']);
const rewind=new Set([0,5,10,15,30]);
const buffer=new Set([8,12,20,30]);
const recent=new Set([4,6,8,10,12,16]);
const nextDelay=new Set([0,5,8,10,15]);
const rates=new Set([0.75,1,1.25,1.5,1.75,2]);
const sleep=new Set([0,15,30,45,60,90]);
const stillWatching=new Set([0,60,75,90,120]);
const seeks=new Set([5,10,15,30]);
const driveDelete=new Set([10,20,30,45]);
const watchlistLimit=new Set([6,12,18,24]);
const nextUpLimit=new Set([3,6,9,12]);
const searchHistoryLimit=new Set([3,6,9,12]);
const catalogTypes=new Set(['movie','series']);
const catalogFeeds=new Set(['popular','featured','new']);
const sizeProfiles=new Set(['data','balanced','quality']);
const genres=new Set(['','Action','Adventure','Animation','Biography','Comedy','Crime','Documentary','Drama','Family','Fantasy','History','Horror','Mystery','Romance','Sci-Fi','Sport','Thriller','War','Western']);
const store=applicationStorage;
export function normalizeSettings(raw){
  const s=raw&&typeof raw==='object'?raw:{};
  return {
    interfaceMode:interfaceModes.has(s.interfaceMode)?s.interfaceMode:DEFAULT_SETTINGS.interfaceMode,
    resolution:resolutions.has(s.resolution)?s.resolution:DEFAULT_SETTINGS.resolution,
    resumeRewindSeconds:rewind.has(Number(s.resumeRewindSeconds))?Number(s.resumeRewindSeconds):DEFAULT_SETTINGS.resumeRewindSeconds,
    autoNext:s.autoNext!==false,
    autoNextDelaySeconds:nextDelay.has(Number(s.autoNextDelaySeconds))?Number(s.autoNextDelaySeconds):DEFAULT_SETTINGS.autoNextDelaySeconds,
    autoRecovery:s.autoRecovery!==false,
    bufferSeconds:buffer.has(Number(s.bufferSeconds))?Number(s.bufferSeconds):DEFAULT_SETTINGS.bufferSeconds,
    driveWatchOnly:s.driveWatchOnly!==false,
    driveDeleteMinutes:driveDelete.has(Number(s.driveDeleteMinutes))?Number(s.driveDeleteMinutes):DEFAULT_SETTINGS.driveDeleteMinutes,
    recentLimit:recent.has(Number(s.recentLimit))?Number(s.recentLimit):DEFAULT_SETTINGS.recentLimit,
    showCompletedRecent:s.showCompletedRecent===true,
    cleanupCompletedEpisodes:s.cleanupCompletedEpisodes!==false,
    pauseOverlay:s.pauseOverlay!==false,
    playbackRate:rates.has(Number(s.playbackRate))?Number(s.playbackRate):DEFAULT_SETTINGS.playbackRate,
    sleepTimerMinutes:sleep.has(Number(s.sleepTimerMinutes))?Number(s.sleepTimerMinutes):DEFAULT_SETTINGS.sleepTimerMinutes,
    stillWatchingMinutes:stillWatching.has(Number(s.stillWatchingMinutes))?Number(s.stillWatchingMinutes):DEFAULT_SETTINGS.stillWatchingMinutes,
    keepAwake:s.keepAwake!==false,
    keyboardShortcuts:s.keyboardShortcuts!==false,
    seekSeconds:seeks.has(Number(s.seekSeconds))?Number(s.seekSeconds):DEFAULT_SETTINGS.seekSeconds,
    showEpisodeProgress:s.showEpisodeProgress!==false,
    rememberBrowse:s.rememberBrowse!==false,
    catalogType:catalogTypes.has(s.catalogType)?s.catalogType:DEFAULT_SETTINGS.catalogType,
    catalogFeed:catalogFeeds.has(s.catalogFeed)?s.catalogFeed:DEFAULT_SETTINGS.catalogFeed,
    catalogGenre:genres.has(s.catalogGenre)?s.catalogGenre:DEFAULT_SETTINGS.catalogGenre,
    sourceSizeProfile:sizeProfiles.has(s.sourceSizeProfile)?s.sourceSizeProfile:DEFAULT_SETTINGS.sourceSizeProfile,
    showWatchlist:s.showWatchlist!==false,
    watchlistLimit:watchlistLimit.has(Number(s.watchlistLimit))?Number(s.watchlistLimit):DEFAULT_SETTINGS.watchlistLimit,
    showNextUp:s.showNextUp!==false,
    nextUpLimit:nextUpLimit.has(Number(s.nextUpLimit))?Number(s.nextUpLimit):DEFAULT_SETTINGS.nextUpLimit,
    showSearchHistory:s.showSearchHistory!==false,
    searchHistoryLimit:searchHistoryLimit.has(Number(s.searchHistoryLimit))?Number(s.searchHistoryLimit):DEFAULT_SETTINGS.searchHistoryLimit,
    longPressShortcuts:s.longPressShortcuts!==false,
    showPlaybackHealth:s.showPlaybackHealth!==false,
    autoLearnSources:s.autoLearnSources!==false
  };
}
export function getSettings(storage=store()){
  if(!storage)return {...DEFAULT_SETTINGS};
  try{return normalizeSettings(JSON.parse(storage.getItem(KEY)||'{}'));}catch{return {...DEFAULT_SETTINGS};}
}
export function saveSettings(next,storage=store()){
  const value=normalizeSettings(next);
  try{storage?.setItem(KEY,JSON.stringify(value));}catch{}
  return value;
}
export function updateSettings(patch,storage=store()){return saveSettings({...getSettings(storage),...(patch||{})},storage);}
export function resetSettings(storage=store()){try{storage?.removeItem(KEY);}catch{}return {...DEFAULT_SETTINGS};}
