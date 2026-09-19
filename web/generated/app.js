import { createDiscoveryUI } from './discover.js';
import { diagnosePlaybackFailure } from './playback-errors.js';
import { apiUrl, mediaUrl, apiMode, getSessionToken, setSessionToken, clearSessionToken, credentialsMode, applicationStorage, flushRuntimeState, assertRuntimeReady, runtimeCapabilities, imageUrl } from './runtime.js';
import { rememberApiKey, loadRememberedApiKey, forgetApiKey } from './vault.js';
import { listRecent, recordRecent, removeRecent, recentForContext, resumePosition, formatResumeTime } from './history.js';
import { getSettings, saveSettings, resetSettings } from './settings.js';
import { rememberSourceSuccess, setAudioFeedback, setSourceBad, clearSourceMemory } from './source-memory.js';
import { clearSearchHistory } from './search-history.js';
import { hasParentPin, setParentPin, verifyParentPin, getKidProfile, updateKidProfile, resetKidAllowance, grantKidExtension, canStartKidPlayback, consumeKidPlayback, formatKidUsage } from './parental-controls.js';
assertRuntimeReady();
const $ = id => document.getElementById(id);
for(const control of document.querySelectorAll('[data-runtime-capability]'))control.hidden=!runtimeCapabilities[control.dataset.runtimeCapability];
let csrf = '', sessionToken = getSessionToken(), playGeneration = 0, active = null, recentRenderTimer, guestMode = false, driveSelected = null, driveRunId = '', drivePollTimer = null, driveConfigured = false, driveOauthUrl = '', torboxStatusCache = null, nextCountdownTimer = null, wakeLock = null, deferredInstallPrompt = null, stillWatchingTimer = null, stillWatchingDue = false, stillWatchingPromptActive = false, parentPinCallback = null, pendingKidPlayback = null, kidLimitReason = '';
let discoveryUI;
let viewer = 'viewer-1';
try { const saved = applicationStorage()?.getItem('tw-viewer') || (!runtimeCapabilities.phonePersistence ? sessionStorage.getItem('tw-viewer') : ''); if (['viewer-1', 'viewer-2'].includes(saved)) viewer = saved; } catch {}
$('viewer').value = viewer;
function kidEnabled(){return !guestMode&&getKidProfile(viewer).enabled===true;}
function applyInterfaceMode(){
  const kid=kidEnabled(),full=!kid&&getSettings().interfaceMode==='full';
  document.body.classList.toggle('mode-full',full);document.body.classList.toggle('mode-simple',!full);document.body.classList.toggle('kid-mode',kid);
  const badge=$('kid-mode-badge');if(badge){badge.hidden=!kid;badge.title=kid?formatKidUsage(getKidProfile(viewer)):'';}
}
applyInterfaceMode();
function setPlaybackHealth(state,detail=''){
  const panel=$('playback-health');if(!panel)return;
  panel.hidden=!getSettings().showPlaybackHealth;$('health-state').textContent=state;$('health-detail').textContent=detail||'';panel.dataset.state=String(state||'').toLowerCase().replace(/[^a-z]+/g,'-');
}
function setHealthSource(context=active){
  const source=context?.playbackContext?.sourceInfo;if(!$('health-source'))return;
  $('health-source').textContent=source?[source.resolution,source.provider,source.videoCodec,(source.audioCodecs||[]).slice(0,2).join('/')].filter(Boolean).join(' · '):'';
  for(const id of ['health-sound-good','health-sound-bad','health-source-bad'])$(id).disabled=!source;
}
function text(id, value, error = false) { $(id).textContent = value; $(id).classList.toggle('error', error); }
function requestParentPin(message,onSuccess){
  if(!hasParentPin()){if(typeof onSuccess==='function')onSuccess();return;}
  parentPinCallback=onSuccess;$('parent-pin-prompt').textContent=message||'Enter the Parent PIN.';
  $('parent-pin-input').value='';text('parent-pin-message','');
  if(!$('parent-pin-dialog').open)$('parent-pin-dialog').showModal();
  setTimeout(()=>$('parent-pin-input').focus(),0);
}
$('close-parent-pin').addEventListener('click',()=>{parentPinCallback=null;$('parent-pin-dialog').close();});
$('parent-pin-form').addEventListener('submit',async event=>{
  event.preventDefault();const button=event.submitter;button.disabled=true;text('parent-pin-message','Checking…');
  try{
    const ok=await verifyParentPin($('parent-pin-input').value);$('parent-pin-input').value='';
    if(!ok){text('parent-pin-message','Incorrect PIN.',true);return;}
    const callback=parentPinCallback;parentPinCallback=null;$('parent-pin-dialog').close();text('parent-pin-message','');
    if(typeof callback==='function')await callback();
  }catch(error){text('parent-pin-message',error.message,true);}
  finally{button.disabled=false;}
});
function formatDriveSize(bytes) { return Number.isFinite(bytes) && bytes > 0 ? (bytes / 1024 ** 3).toFixed(2) + ' GB' : 'size unknown'; }
function formatDriveElapsed(ms) {
  const seconds = Math.max(0, Math.round((ms || 0) / 1000));
  return seconds < 60 ? seconds + 's' : Math.floor(seconds / 60) + 'm ' + String(seconds % 60).padStart(2, '0') + 's';
}
async function openDriveTest(file, context = {}) {
  if (guestMode || !file?.id) return;
  driveSelected = { file, context };
  $('drive-block-download').checked = getSettings().driveWatchOnly;
  $('drive-delete-after').value = String(getSettings().driveDeleteMinutes);
  driveRunId = '';
  $('drive-selected').textContent = [context.title || file.title, context.episodeName || '', formatDriveSize(file.size)].filter(Boolean).join(' · ');
  $('drive-progress').hidden = true; $('drive-ready-actions').hidden = true; $('drive-status').textContent = ''; $('drive-timing').textContent = ''; $('drive-delete-note').textContent = '';
  if (!$('drive-dialog').open) $('drive-dialog').showModal();
  try {
    const config = await api('/api/drive/config');
    driveConfigured = config.configured === true;
    driveOauthUrl = config.oauthUrl || 'https://api.torbox.app/v1/api/integration/oauth/google';
    $('drive-connected-until').textContent = config.expiresAt ? 'Connected until ' + new Date(config.expiresAt).toLocaleTimeString([], {hour:'numeric', minute:'2-digit'}) + '.' : '';
  } catch { driveConfigured = false; driveOauthUrl = 'https://api.torbox.app/v1/api/integration/oauth/google'; $('drive-connected-until').textContent = ''; }
  $('drive-setup').hidden = driveConfigured;
  $('drive-test-controls').hidden = !driveConfigured;
}
function stopDrivePolling() { if (drivePollTimer) clearTimeout(drivePollTimer); drivePollTimer = null; }
function scheduleDrivePoll(delay = 1800) { stopDrivePolling(); drivePollTimer = setTimeout(pollDriveTest, delay); }
function updateDriveStatus(result) {
  const pct = Math.round((Number(result.progress) || 0) * 100);
  $('drive-progress-bar').value = Number(result.progress) || 0;
  const labels = {
    queued: 'Waiting for TorBox to queue the Google Drive transfer…',
    pending: 'TorBox queued the Google Drive transfer…',
    uploading: 'TorBox → Google Drive · ' + pct + '%',
    drive_locating: 'Upload finished · locating the file in Drive…',
    drive_processing: 'Upload finished · Google Drive is processing the video…',
    ready: 'Ready to watch from Google Drive.',
    failed: result.detail || 'Drive transfer failed.',
    deleted: 'The temporary Drive copy was deleted.'
  };
  text('drive-status', labels[result.status] || result.detail || result.status, result.status === 'failed');
  const timing = ['Elapsed ' + formatDriveElapsed(result.elapsedMs)];
  if (Number.isFinite(result.uploadMs)) timing.push('TorBox → Drive ' + formatDriveElapsed(result.uploadMs));
  if (Number.isFinite(result.playbackMs)) timing.push('Playable ' + formatDriveElapsed(result.playbackMs));
  $('drive-timing').textContent = timing.join(' · ');
  if (result.previewUrl) {
    $('drive-open').href = result.previewUrl; $('drive-ready-actions').hidden = false;
    $('drive-delete-note').textContent = (result.downloadRestricted ? 'Viewer download/copy is disabled. ' : '') + (result.deleteAt ? 'Deletion scheduled for ' + new Date(result.deleteAt).toLocaleTimeString([], {hour:'numeric', minute:'2-digit'}) + '. Keep this test open until it deletes; this easy OAuth test is not yet restart-proof.' : '');
  }
  if (result.deleteError) $('drive-delete-note').textContent += ' Cleanup warning: ' + result.deleteError;
}
async function pollDriveTest() {
  if (!driveRunId) return;
  try {
    const result = await api('/api/drive/test/status?id=' + encodeURIComponent(driveRunId));
    updateDriveStatus(result);
    if (['failed', 'deleted'].includes(result.status)) { stopDrivePolling(); return; }
    scheduleDrivePoll(result.status === 'drive_processing' || result.status === 'ready' ? 3000 : 1800);
  } catch (error) {
    text('drive-status', error.message, true);
    if (error.code !== 'DRIVE_AUTH_EXPIRED') scheduleDrivePoll(5000);
  }
}

function recentLabel(item) {
  const episode = item.type === 'series' ? `S${item.season}E${item.episode}${item.episodeName ? ' · ' + item.episodeName : ''}` : 'Movie';
  const time = item.completed ? 'Finished' : item.position > 0 ? `Resume ${formatResumeTime(item.position)}` : 'Start';
  return `${episode} · ${time}`;
}
function renderRecent() {
  const settings=getSettings(),all=listRecent(),rows=(settings.showCompletedRecent?all:all.filter(item=>!item.completed)).slice(0,settings.recentLimit), section = $('recent-section'), list = $('recent-list');
  $('recent-heading').textContent=settings.showCompletedRecent?'Recently played':'Continue watching';
  section.hidden = !rows.length || $('discover-panel').hidden;
  const fragment = document.createDocumentFragment();
  for (const item of rows) {
    const entry = document.createElement('div'); entry.className = 'recent-entry';
    const card = document.createElement('button'); card.type = 'button'; card.className = 'recent-card';
    if (item.poster) { const img = document.createElement('img'); img.className = 'recent-thumb'; img.src = imageUrl(item.poster); img.alt = ''; img.referrerPolicy = 'no-referrer'; card.append(img); }
    const copy = document.createElement('span'); copy.className = 'recent-copy';
    const title = document.createElement('strong'); title.className = 'recent-title'; title.textContent = item.title;
    const meta = document.createElement('span'); meta.className = 'recent-meta'; meta.textContent = recentLabel(item); copy.append(title, meta);
    if (item.duration > 0 && !item.completed) { const track=document.createElement('span');track.className='recent-progress';const fill=document.createElement('span');fill.style.width=`${Math.min(100,Math.max(0,item.position/item.duration*100))}%`;track.append(fill);copy.append(track); }
    card.append(copy); card.addEventListener('click', () => discoveryUI.resumeRecent(item));
    const remove=document.createElement('button');remove.type='button';remove.className='recent-remove';remove.textContent='×';remove.setAttribute('aria-label',`Remove ${item.title} from Recently played`);
    remove.addEventListener('click',event=>{event.stopPropagation();if(confirm(`Remove "${item.title}" from Recently played?`)){removeRecent(item.key);renderRecent();discoveryUI.historyChanged();}});
    if(item.position>0||item.completed){const start=document.createElement('button');start.type='button';start.className='recent-start-over';start.textContent='Start over';start.addEventListener('click',event=>{event.stopPropagation();discoveryUI.startOverRecent(item);});entry.append(card,remove,start);}else entry.append(card,remove);fragment.append(entry);
  }
  list.replaceChildren(fragment);
}
function scheduleRecentRender(refreshHome=false) { clearTimeout(recentRenderTimer); recentRenderTimer = setTimeout(()=>{renderRecent();if(refreshHome)discoveryUI?.historyChanged();},250); }

function renderTorBoxStatus(result) {
  torboxStatusCache = result ? { ...result, localAt: Date.now() } : null;
  const banner=$('torbox-status-banner'),label=$('torbox-status-text');
  if (!result) { banner.hidden=true; return; }
  if (result.ok && result.official !== 'issue') { banner.hidden=true; label.textContent=''; return; }
  banner.hidden=false;
  banner.classList.toggle('error',!result.ok);
  label.textContent=result.message || (result.ok ? 'TorBox reports a service issue, but its API is reachable.' : 'TorBox is currently unavailable.');
}
async function checkTorBoxStatus(force=false) {
  if (guestMode) return true;
  if (!force && torboxStatusCache && Date.now()-torboxStatusCache.localAt < 60000) return torboxStatusCache.ok === true;
  try {
    const result=await api('/api/torbox-status');
    renderTorBoxStatus(result);
    return result.ok === true;
  } catch (error) {
    const result={ok:false,official:'unknown',message:error.message,localAt:Date.now()};
    renderTorBoxStatus(result);return false;
  }
}
async function ensureTorBoxReady() {
  const ok=await checkTorBoxStatus(false);
  if (!ok) throw new Error(torboxStatusCache?.message || 'TorBox is currently unavailable. Try again after the outage clears.');
  return true;
}
function show(section) {
  if(section==='login'&&runtimeCapabilities.phoneCredentials){section='loading';$('loading').querySelector('p').textContent='Scan Open Player in CarStream on the phone to reconnect.';} if (section !== 'workspace') discoveryUI?.suspend(); for (const id of ['loading', 'setup-needed', 'login', 'workspace']) $(id).hidden = id !== section; }
async function api(path, { method = 'GET', data, signal, keepalive = false } = {}) {
  if(!['GET','HEAD'].includes(method))await flushRuntimeState();
  const headers = {}; if (data !== undefined) headers['Content-Type'] = 'application/json';
  if (sessionToken) headers.Authorization = `Bearer ${sessionToken}`;
  if (method !== 'GET') headers['X-CSRF-Token'] = csrf;
  let response;
  try { response = await fetch(apiUrl(path), { method, mode: apiMode(), headers, body: data === undefined ? undefined : JSON.stringify(data), credentials: credentialsMode(), cache: 'no-store', signal: signal || AbortSignal.timeout(22000), keepalive }); }
  catch (error) { if (error.name === 'AbortError') throw error; throw new Error('The connection was interrupted or timed out. Please try again.'); }
  let result; try { result = await response.json(); } catch { throw new Error('The service is starting or could not answer. Reload the page and try again.'); }
  if (!response.ok) {
    if (result.error === 'LOGIN_REQUIRED') { clearSessionToken(); sessionToken = ''; csrf = ''; stopPlayback(); if ($('player').open) $('player').close(); show('login'); }
    const error = new Error(result.message || 'The request failed.'); error.code = result.error; throw error;
  }
  return result;
}
function guestTokenFromHash() {
  try {
    const match = /^#guest=([A-Za-z0-9_-]{43})$/.exec(location.hash || '');
    return match ? match[1] : '';
  } catch { return ''; }
}
async function acceptGuestInvite(token) {
  if (sessionToken && !guestMode) {
    try { sessionStorage.setItem('torbox-owner-session-backup', sessionToken); } catch {}
  }
  const previous = sessionToken;
  sessionToken = ''; csrf = '';
  try {
    const result = await api('/api/guest/accept', { method: 'POST', data: { token } });
    sessionToken = result.sessionToken || '';
    if (!sessionToken || !setSessionToken(sessionToken)) throw new Error('This browser could not save the temporary session.');
    csrf = result.csrf; guestMode = true;
    try { history.replaceState(null, '', location.pathname + location.search); } catch {}
    return result;
  } catch (error) {
    sessionToken = previous;
    if (previous) setSessionToken(previous);
    throw error;
  }
}
function enterGuestUi() {
  guestMode = true;
  document.body.classList.add('guest-mode');
  $('logout').textContent = 'Leave';
}
function leaveGuestUi() {
  guestMode = false;
  document.body.classList.remove('guest-mode');
  $('logout').textContent = 'Sign out';
}
let autoLoginTried = false;
async function connectWithKey(apiKey, remember = false) {
  const result = await api('/api/login', { method: 'POST', data: { apiKey } });
  sessionToken = result.sessionToken || '';
  if (!sessionToken || !setSessionToken(sessionToken)) throw new Error('The browser could not save the private session.');
  csrf = result.csrf;
  if (remember) await rememberApiKey(apiKey);
  return result;
}
async function bootstrap() {
  try {
    const invite = guestTokenFromHash();
    if (invite && !guestMode) { await acceptGuestInvite(invite); return await bootstrap(); }
    const session = await api('/api/session');
    if (session.setupRequired) return show('setup-needed');
    if (!session.authenticated) {
      clearSessionToken(); sessionToken = ''; csrf = ''; leaveGuestUi();
      if (!autoLoginTried && runtimeCapabilities.credentialVault) {
        autoLoginTried = true;
        const remembered = await loadRememberedApiKey();
        if (remembered) {
          try { await connectWithKey(remembered, false); return await bootstrap(); }
          catch { clearSessionToken(); sessionToken = ''; csrf = ''; }
        }
      }
      return show('login');
    }
    csrf = session.csrf; show('workspace');
    if (session.guest) {
      enterGuestUi();
      $('recent-section').hidden = true;
      await discoveryUI.activateGuest(session.scope);
      return;
    }
    leaveGuestUi(); await checkTorBoxStatus(true); renderRecent(); await discoveryUI.activate();
  } catch (error) { show('loading'); $('loading').querySelector('p').textContent = error.message; }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; text('login-message', 'Checking TorBox…');
  const apiKey = $('api-key').value; $('api-key').value = '';
  try { await connectWithKey(apiKey, $('remember-key').checked); text('login-message', ''); await bootstrap(); }
  catch (error) { clearSessionToken(); sessionToken = ''; csrf = ''; text('login-message', error.message, true); }
  finally { button.disabled = false; }
});
$('forget-key').addEventListener('click', async () => {
  await forgetApiKey(); $('remember-key').checked = false; text('login-message', 'Saved key removed from this device.');
});
async function performLogout(){
  await stopPlayback();
  try {
    await api('/api/logout', { method: 'POST', data: {} });
    clearSessionToken(); sessionToken = ''; csrf = ''; torboxStatusCache = null;
    if (guestMode) {
      let backup = ''; try { backup = sessionStorage.getItem('torbox-owner-session-backup') || ''; sessionStorage.removeItem('torbox-owner-session-backup'); } catch {}
      leaveGuestUi();
      if (/^[A-Za-z0-9_-]{43}$/.test(backup)) { sessionToken = backup; setSessionToken(backup); return await bootstrap(); }
      autoLoginTried = true; show('login'); text('login-message', 'Temporary access ended.');
      return;
    }
    autoLoginTried = true; show('login');
  } catch (error) { text('login-message', error.message, true); }
}
$('logout').addEventListener('click',()=>{if(kidEnabled()&&hasParentPin())requestParentPin('Enter the Parent PIN to sign out.',performLogout);else performLogout();});
async function switchViewer(next){
  if(!['viewer-1','viewer-2'].includes(next)||next===viewer)return;
  await stopPlayback();if($('player').open)$('player').close();
  try{applicationStorage()?.setItem('tw-viewer',next);await flushRuntimeState();}catch(error){if(runtimeCapabilities.phonePersistence){text('catalog-message',error.message||'The phone could not change viewers.',true);return;}}
  viewer=next;$('viewer').value=viewer;
  try{if(!runtimeCapabilities.phonePersistence)sessionStorage.setItem('tw-viewer',viewer);}catch{}
  applyInterfaceMode();renderRecent();discoveryUI?.settingsChanged();
}
$('viewer').addEventListener('change',()=>{
  const target=$('viewer').value;$('viewer').value=viewer;
  const change=()=>switchViewer(target);
  if(kidEnabled()&&hasParentPin())requestParentPin('Enter the Parent PIN to change viewers.',change);else change();
});
function hidePauseCard(){ $('pause-card').hidden=true; }
function updatePauseCard(context=active){
  if(!context||!getSettings().pauseOverlay||!context.video?.paused||context.video.ended||!context.started){hidePauseCard();return;}
  const p=context.playbackContext||{},video=context.video;
  $('pause-title').textContent=p.title||context.file?.title||'Paused';
  $('pause-subtitle').textContent=p.current?.type==='series'
    ? `S${p.current.season}E${p.current.episode}${p.episodeName?' · '+p.episodeName:''}`
    : 'Movie';
  const current=Number.isFinite(video.currentTime)?video.currentTime:0,duration=Number.isFinite(video.duration)?video.duration:0;
  $('pause-time').textContent=duration?`${formatResumeTime(current)} / ${formatResumeTime(duration)} · ${formatResumeTime(Math.max(0,duration-current))} remaining`:`Paused at ${formatResumeTime(current)}`;
  $('pause-card').hidden=false;
}
async function saveProgress(context = active, keepalive = false) {
  if (!context || !context.ready || !Number.isFinite(context.video.duration) || !context.video.duration) return;
  const position = Number.isFinite(context.video.currentTime) ? context.video.currentTime : 0, duration = context.video.duration;
  if (context.playbackContext) { recordRecent(context.playbackContext, position, duration); scheduleRecentRender(); }
  if (!context.started) return;
  const seq = ++context.seq;
  try {
    const result = await api('/api/progress', { method: 'PUT', keepalive, data: { viewer: context.viewer, videoId: context.file.id, leaseId: context.leaseId, seq, position, duration } });
    if (!result.saved && active === context) text('player-message', 'Local resume is saved, but the temporary server progress lease was replaced.', true);
  } catch { if (active === context) text('player-message', 'Playback continues. Resume is saved on this device.', false); }
}
function clearNextCountdown(){if(nextCountdownTimer)clearInterval(nextCountdownTimer);nextCountdownTimer=null;$('up-next-card').hidden=true;}
async function releaseWakeLock(){try{await wakeLock?.release();}catch{}wakeLock=null;}
async function acquireWakeLock(){
  if(!runtimeCapabilities.screenWakeLock||!getSettings().keepAwake||!('wakeLock' in navigator)||document.visibilityState!=='visible')return;
  try{await releaseWakeLock();wakeLock=await navigator.wakeLock.request('screen');wakeLock.addEventListener('release',()=>{wakeLock=null;},{once:true});}catch{}
}
function armSleepTimer(context){
  clearTimeout(context.sleepTimer);context.sleepTimer=null;
  const minutes=getSettings().sleepTimerMinutes;if(!minutes)return;
  context.sleepTimer=setTimeout(()=>{if(active!==context)return;context.video.pause();text('player-message',`Sleep timer · paused after ${minutes} minutes`);},minutes*60000);
}
function clearStillWatchingTimer(clearDue=true){
  if(stillWatchingTimer)clearTimeout(stillWatchingTimer);stillWatchingTimer=null;
  if(clearDue)stillWatchingDue=false;
}
function hideStillWatchingPrompt(){
  stillWatchingPromptActive=false;
  if($('still-watching-card'))$('still-watching-card').hidden=true;
}
function triggerStillWatchingPrompt(){
  const context=active,minutes=getSettings().stillWatchingMinutes;
  if(!context||!minutes){clearStillWatchingTimer();hideStillWatchingPrompt();return;}
  clearStillWatchingTimer();stillWatchingPromptActive=true;
  context.video.pause();
  $('still-watching-card').hidden=false;
  setPlaybackHealth('Still watching?',`Paused after ${minutes} minutes without confirmation.`);
  text('player-message',`Still watching? Playback paused after ${minutes} minutes.`);
}
function armStillWatchingTimer(){
  const minutes=getSettings().stillWatchingMinutes;
  if(!minutes){clearStillWatchingTimer();hideStillWatchingPrompt();return;}
  if(stillWatchingPromptActive)return;
  if(stillWatchingDue){triggerStillWatchingPrompt();return;}
  if(stillWatchingTimer)return;
  stillWatchingTimer=setTimeout(()=>{
    stillWatchingTimer=null;
    if(!active||active.video.paused){stillWatchingDue=true;return;}
    triggerStillWatchingPrompt();
  },minutes*60000);
}
function resetStillWatchingTimer(){
  clearStillWatchingTimer();hideStillWatchingPrompt();
  if(active&&!active.video.paused)armStillWatchingTimer();
}
function scheduleNextEpisode(context){
  const playbackContext=context.playbackContext;if(!getSettings().autoNext||!playbackContext?.queue?.length){text('player-message','Finished');return;}
  const next=playbackContext.queue[0],delay=getSettings().autoNextDelaySeconds;
  if(delay===0){text('player-message',`Next · S${next.season}E${next.episode} ${next.name||''}`);discoveryUI.playNext(playbackContext).then(moved=>{if(!moved&&active===context)text('player-message','Next episode could not be selected automatically.',true);});return;}
  let remaining=delay;$('up-next-title').textContent=`Next · S${next.season}E${next.episode} ${next.name||''}`;clearNextCountdown();$('up-next-card').hidden=false;$('up-next-time').textContent=`Playing in ${remaining}s`;
  nextCountdownTimer=setInterval(async()=>{if(active!==context){clearNextCountdown();return;}remaining-=1;if(remaining>0){$('up-next-time').textContent=`Playing in ${remaining}s`;return;}clearNextCountdown();text('player-message','Opening next episode…');const moved=await discoveryUI.playNext(playbackContext);if(!moved&&active===context)text('player-message','Next episode could not be selected automatically.',true);},1000);
}
function updateKidLimitDialog(reason=kidLimitReason){
  const profile=getKidProfile(viewer),titles={time:'Watching time is finished',episodes:'Episode limit reached',movies:'Movie limit reached'};
  $('kid-limit-title').textContent=titles[reason]||'Watching limit reached';
  $('kid-limit-message').textContent='Ask a parent to continue.';
  $('kid-limit-summary').textContent=formatKidUsage(profile);
}
function showKidLimit(block,retry=null){
  kidLimitReason=block?.reason||'limit';pendingKidPlayback=retry;
  updateKidLimitDialog();$('kid-parent-actions').hidden=true;$('kid-limit-pin-form').hidden=true;$('kid-limit-pin').value='';text('kid-limit-pin-message','');
  if(!$('kid-limit-dialog').open)$('kid-limit-dialog').showModal();
  if(active?.video&&!active.video.paused)active.video.pause();
  releaseWakeLock();
}
function tickKidUsage(context,force=false){
  if(!context||guestMode||!context.playbackContext)return;
  const now=Date.now(),video=context.video,position=Number.isFinite(video.currentTime)?video.currentTime:0;
  if(!context.kidLastAt){context.kidLastAt=now;context.kidLastPosition=position;return;}
  const wall=Math.max(0,(now-context.kidLastAt)/1000),media=position-context.kidLastPosition,rate=Math.max(.25,Number(video.playbackRate)||1);
  context.kidLastAt=now;context.kidLastPosition=position;
  if((!force&&(video.paused||video.ended))||wall<=0||media<=.05)return;
  if(media>Math.max(15,wall*rate*3+5))return;
  const seconds=Math.min(wall,media/rate*1.15);if(seconds<=.05)return;
  const result=consumeKidPlayback(context.viewer,context.playbackContext,seconds,Number.isFinite(video.duration)?video.duration:0);
  const badge=$('kid-mode-badge');if(badge&&!badge.hidden)badge.title=formatKidUsage(result.profile);
  if(result.timeBlocked&&active===context&&!$('kid-limit-dialog').open)showKidLimit({reason:'time'});
}
async function resumeAfterKidParentAction(){
  try{await flushRuntimeState();}catch(error){text('kid-limit-pin-message',error.message||'The phone could not save the allowance.',true);return false;}
  updateKidLimitDialog();applyInterfaceMode();
  const retry=pendingKidPlayback;pendingKidPlayback=null;kidLimitReason='';
  if($('kid-limit-dialog').open)$('kid-limit-dialog').close();
  if(retry)return startPlayback(retry.file,retry.playbackContext,retry.retryCount||0);
  if(active?.video?.paused){
    const block=canStartKidPlayback(viewer,active.playbackContext);
    if(!block.allowed){showKidLimit(block);return false;}
    active.video.play().catch(()=>text('player-message','Tap play to continue.'));
  }
  return true;
}

async function detachPlayback() {
  const old = active;if(old)tickKidUsage(old,true); active = null; hidePauseCard();clearNextCountdown();await releaseWakeLock();
  if (old) { clearInterval(old.timer);clearInterval(old.kidTimer);clearTimeout(old.bufferTimer);clearTimeout(old.sleepTimer);clearTimeout(old.healthyTimer); const saving = saveProgress(old, true); old.video.pause(); old.video.removeAttribute('src'); old.video.load(); old.video.remove(); await saving; }
}
async function stopPlayback() { playGeneration++; clearStillWatchingTimer(); hideStillWatchingPrompt(); await detachPlayback(); }
async function startPlayback(file, playbackContext = null, retryCount = 0) {
  if(!guestMode){
    const block=canStartKidPlayback(viewer,playbackContext);
    if(!block.allowed){showKidLimit(block,{file,playbackContext,retryCount});return false;}
  }
  const generation = ++playGeneration, selectedViewer = viewer;
  await detachPlayback(); if (generation !== playGeneration) return false;
  $('playing-title').textContent = file.title; text('player-message', 'Opening…'); setPlaybackHealth('Opening','Requesting a fresh TorBox link…'); $('video-slot').replaceChildren();
  if (!$('player').open) $('player').showModal();
  try {
    const result = await api('/api/playback', { method: 'POST', data: { viewer: selectedViewer, videoId: file.id, startOver: playbackContext?.forceStartOver===true } });
    if (generation !== playGeneration || !$('player').open || selectedViewer !== viewer) return false;
    const video = document.createElement('video'); video.controls = true; video.playsInline = true; video.preload = 'metadata'; video.playbackRate=getSettings().playbackRate;
    const context = { file, viewer:selectedViewer, leaseId:result.leaseId, seq:0, video, mediaUrl:mediaUrl(result.mediaUrl), playbackContext, retryCount, diagnosing:false, recovering:false, ready:false, started:false, timer:null, kidTimer:null, kidLastAt:0, kidLastPosition:0, bufferTimer:null, sleepTimer:null, healthyTimer:null, sourceLearned:false, lastTime:0 };
    active = context; $('video-slot').replaceChildren(video);setHealthSource(context);
    const clearBuffer = () => { clearTimeout(context.bufferTimer); context.bufferTimer = null; };
    const recover = async reason => {
      if (active !== context || context.recovering || video.ended || (reason === 'buffer' && (!context.started || video.paused))) return;
      if (!getSettings().autoRecovery) { setPlaybackHealth(reason==='buffer'?'Buffering':'Playback problem','Automatic recovery is off.'); text('player-message', reason === 'buffer' ? 'Playback is buffering. Automatic recovery is off in Settings.' : 'Playback failed. Automatic recovery is off in Settings.', true); return; }
      context.recovering = true; clearBuffer();clearTimeout(context.healthyTimer); hidePauseCard(); await saveProgress(context); video.pause();
      setPlaybackHealth('Recovering',reason==='buffer'?'Switching to a lower-resolution source…':'Finding another source…');
      text('player-message', reason === 'buffer' ? 'Buffering · switching to a lower resolution…' : 'Stream failed · finding a lower-resolution source…');
      const moved = playbackContext ? await discoveryUI.recoverPlayback(playbackContext) : false;
      if (moved) return;
      if (active !== context) return;
      if (retryCount < 1) { text('player-message', 'Refreshing the TorBox link…'); await startPlayback(file, playbackContext, retryCount + 1); return; }
      context.recovering = false; setPlaybackHealth('Likely failed','Automatic recovery could not find a working alternative.'); text('player-message', 'Playback could not recover automatically. Close the player and choose another source.', true);
    };
    const armBuffer = () => {
      if (!context.started || video.paused || video.ended || context.recovering) return;
      setPlaybackHealth('Buffering','Waiting for the current stream…');clearBuffer(); context.bufferTimer = setTimeout(() => recover('buffer'), getSettings().bufferSeconds * 1000);
    };
    video.addEventListener('loadedmetadata', () => {
      if (active !== context) return;
      const local = playbackContext ? recentForContext(playbackContext) : null;
      let position = playbackContext?.forceStartOver ? 0 : Math.max(result.progress.position || 0, resumePosition(local));
      const rewind = Number(playbackContext?.rewindOnResumeSeconds) || 0;
      if (rewind > 0 && position > 0) position = Math.max(0, position - rewind);
      if (Number.isFinite(video.duration) && position > 0) video.currentTime = Math.min(position, Math.max(0, video.duration - .25));
      context.ready = true;setPlaybackHealth('Ready',position>0?'Resume point loaded.':'Ready to play.');
      if (playbackContext) { recordRecent(playbackContext, position, video.duration || 0); scheduleRecentRender(true); }
      text('player-message', position > 0 ? `Resuming ${formatResumeTime(position)}` : '');
      video.play().catch(error => { if (active === context && error.name === 'NotAllowedError') text('player-message', 'Tap play'); });
    });
    video.addEventListener('playing', () => { if (active === context) {
      context.started = true; context.recovering = false; clearBuffer(); hidePauseCard(); clearNextCountdown(); acquireWakeLock(); if(!context.sleepTimer)armSleepTimer(context); armStillWatchingTimer(); context.kidLastAt=Date.now();context.kidLastPosition=Number.isFinite(video.currentTime)?video.currentTime:0;if(!context.kidTimer)context.kidTimer=setInterval(()=>tickKidUsage(context),5000); setPlaybackHealth('Playing','Stream is advancing normally.'); text('player-message', '');
      clearTimeout(context.healthyTimer);if(getSettings().autoLearnSources&&playbackContext?.sourceInfo&&!context.sourceLearned)context.healthyTimer=setTimeout(()=>{if(active===context&&!video.paused&&video.currentTime>5){rememberSourceSuccess(playbackContext.current,playbackContext.sourceInfo);context.sourceLearned=true;setHealthSource(context);}},15000);
    } });
    video.addEventListener('canplay',()=>{clearBuffer();if(active===context&&context.started&&!video.paused)setPlaybackHealth('Playing','Stream is ready.');});
    video.addEventListener('timeupdate', () => { if (active !== context || !context.ready) return; if (Math.abs(video.currentTime - context.lastTime) > .2) { context.lastTime = video.currentTime; clearBuffer(); } if(video.paused)updatePauseCard(context); });
    video.addEventListener('waiting', armBuffer); video.addEventListener('stalled',()=>{setPlaybackHealth('Stalled','The browser reports that data stopped arriving.');armBuffer();});
    video.addEventListener('pause', () => { if(active===context)tickKidUsage(context,true); if (active === context && !context.recovering) { clearTimeout(context.healthyTimer);releaseWakeLock(); if(!stillWatchingPromptActive&&!video.ended)clearStillWatchingTimer(); saveProgress(context); if(stillWatchingPromptActive){hidePauseCard();setPlaybackHealth('Still watching?','Tap Keep watching to continue.');}else if(!$('kid-limit-dialog').open){setPlaybackHealth('Paused','Playback is paused.');updatePauseCard(context);} } });
    video.addEventListener('seeked', () => { if (active === context && context.ready && context.started) saveProgress(context); });
    video.addEventListener('ended', async () => {
      if (active !== context) return; tickKidUsage(context,true); clearBuffer(); hidePauseCard(); await saveProgress(context);
      if (playbackContext) { recordRecent(playbackContext, video.duration || video.currentTime, video.duration || 0, { completed:true }); scheduleRecentRender(true); }
      clearTimeout(context.healthyTimer);setPlaybackHealth('Finished','Playback completed.');await releaseWakeLock();scheduleNextEpisode(context);
    });
    video.addEventListener('error', async () => {
      if (active !== context || context.diagnosing) return; context.diagnosing = true; clearBuffer();
      setPlaybackHealth('Playback error','Diagnosing the failed stream…');const diagnosis = await diagnosePlaybackFailure(context.mediaUrl, video.error?.code);
      if (active !== context || generation !== playGeneration) return;
      if (diagnosis.kind !== 'cancelled') { await recover('error'); return; }
      text('player-message', diagnosis.message, true);
    });
    context.timer=setInterval(()=>{if(active===context&&!video.paused)saveProgress(context);},10000);
    video.src=context.mediaUrl; return true;
  } catch (error) { if (generation===playGeneration) {setPlaybackHealth('Could not start',error.message);text('player-message',error.message,true);} return false; }
}
$('cancel-auto-next').addEventListener('click',()=>{clearNextCountdown();text('player-message','Finished');});
async function rejectCurrentSource(kind){
  const context=active,playback=context?.playbackContext,source=playback?.sourceInfo;if(!context||!playback?.current||!source)return;
  if(kind==='audio')setAudioFeedback(playback.current,source,'bad');else setSourceBad(playback.current,source,true);
  setPlaybackHealth('Recovering',kind==='audio'?'Remembered: no sound. Finding another source…':'Source blocked on this device. Finding another source…');
  await saveProgress(context);context.video.pause();const moved=await discoveryUI.recoverPlayback(playback);
  if(!moved&&active===context){setPlaybackHealth('Needs attention','No acceptable alternative source was found.');text('player-message','That source is now avoided, but no automatic alternative was available.',true);}
}
$('health-sound-good').addEventListener('click',()=>{const p=active?.playbackContext;if(!p?.sourceInfo)return;setAudioFeedback(p.current,p.sourceInfo,'good');rememberSourceSuccess(p.current,p.sourceInfo);active.sourceLearned=true;setPlaybackHealth('Playing','Sound confirmed on this device.');});
$('health-sound-bad').addEventListener('click',()=>rejectCurrentSource('audio'));
$('health-source-bad').addEventListener('click',()=>rejectCurrentSource('source'));
$('keep-watching').addEventListener('click',()=>{
  if(!active)return;
  const block=canStartKidPlayback(viewer,active.playbackContext);if(!block.allowed){hideStillWatchingPrompt();showKidLimit(block);return;}
  clearStillWatchingTimer();hideStillWatchingPrompt();text('player-message','');
  active.video.play().catch(()=>text('player-message','Tap play to continue.'));
});
$('kid-limit-dialog').addEventListener('cancel',event=>event.preventDefault());
$('kid-parent-options').addEventListener('click',()=>{
  $('kid-limit-pin-form').hidden=false;$('kid-limit-pin').value='';text('kid-limit-pin-message','');
  setTimeout(()=>$('kid-limit-pin').focus(),0);
});
$('kid-limit-pin-form').addEventListener('submit',async event=>{
  event.preventDefault();const button=event.submitter;button.disabled=true;text('kid-limit-pin-message','Checking…');
  try{
    const ok=await verifyParentPin($('kid-limit-pin').value);$('kid-limit-pin').value='';
    if(!ok){text('kid-limit-pin-message','Incorrect PIN.',true);return;}
    $('kid-limit-pin-form').hidden=true;$('kid-parent-actions').hidden=false;text('kid-limit-pin-message','');
  }catch(error){text('kid-limit-pin-message',error.message,true);}
  finally{button.disabled=false;}
});
$('kid-add-15').addEventListener('click',()=>{grantKidExtension(viewer,{minutes:15});resumeAfterKidParentAction();});
$('kid-add-30').addEventListener('click',()=>{grantKidExtension(viewer,{minutes:30});resumeAfterKidParentAction();});
$('kid-add-episode').addEventListener('click',()=>{grantKidExtension(viewer,{episodes:1});resumeAfterKidParentAction();});
$('kid-add-movie').addEventListener('click',()=>{grantKidExtension(viewer,{movies:1});resumeAfterKidParentAction();});
$('kid-reset-limit').addEventListener('click',()=>{resetKidAllowance(viewer);resumeAfterKidParentAction();});
$('kid-turn-off').addEventListener('click',()=>{updateKidProfile(viewer,{enabled:false});resumeAfterKidParentAction();});
$('player').addEventListener('pointerdown',()=>{
  if(active&&!stillWatchingPromptActive&&!active.video.paused)resetStillWatchingTimer();
});
$('close-player').addEventListener('click', () => $('player').close());
$('player').addEventListener('close', () => stopPlayback());
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') { tickKidUsage(active,true);saveProgress(active, true); releaseWakeLock(); } else if(active&&!active.video.paused){active.kidLastAt=Date.now();active.kidLastPosition=Number.isFinite(active.video.currentTime)?active.video.currentTime:0;acquireWakeLock();} });
document.addEventListener('keydown',event=>{
  if(!$('player').open||!active||!getSettings().keyboardShortcuts||event.altKey||event.ctrlKey||event.metaKey)return;
  const tag=event.target?.tagName;if(['INPUT','TEXTAREA','SELECT','BUTTON'].includes(tag))return;
  const step=getSettings().seekSeconds,key=event.key.toLowerCase();
  if(key==='j'||key==='arrowleft'){event.preventDefault();resetStillWatchingTimer();active.video.currentTime=Math.max(0,active.video.currentTime-step);}
  else if(key==='l'||key==='arrowright'){event.preventDefault();resetStillWatchingTimer();const end=Number.isFinite(active.video.duration)?active.video.duration:active.video.currentTime+step;active.video.currentTime=Math.min(end,active.video.currentTime+step);}
});
window.addEventListener('pagehide', () => { tickKidUsage(active,true);saveProgress(active, true); });
$('open-drive-oauth').addEventListener('click', () => {
  const url = driveOauthUrl || 'https://api.torbox.app/v1/api/integration/oauth/google';
  window.open(url, '_blank', 'noopener,noreferrer');
  text('drive-connect-message', 'Authorize Google Drive in the new tab. When TorBox says it succeeded, copy that page address, come back here, and tap Finish connection. The site will read your clipboard when Chrome permits it; the box below is the fallback.');
});
$('finish-drive-connect').addEventListener('click', async () => {
  const button = $('finish-drive-connect'); button.disabled = true; text('drive-connect-message', 'Checking Google Drive…');
  let successUrl = $('drive-success-url').value.trim();
  if (!successUrl && navigator.clipboard?.readText) {
    try { successUrl = (await navigator.clipboard.readText()).trim(); } catch {}
  }
  $('drive-success-url').value = '';
  try {
    if (!successUrl) throw new Error('Copy the TorBox Google success-page address first, or paste it into the box.');
    const result = await api('/api/drive/connect', { method: 'POST', data: { successUrl } });
    driveConfigured = true; driveOauthUrl = result.oauthUrl || driveOauthUrl;
    $('drive-setup').hidden = true; $('drive-test-controls').hidden = false;
    $('drive-connected-until').textContent = result.expiresAt ? 'Connected until ' + new Date(result.expiresAt).toLocaleTimeString([], {hour:'numeric', minute:'2-digit'}) + '.' : '';
    text('drive-connect-message', '');
  } catch (error) { text('drive-connect-message', error.message, true); }
  finally { button.disabled = false; }
});
$('reconnect-drive').addEventListener('click', () => {
  driveConfigured = false; $('drive-setup').hidden = false; $('drive-test-controls').hidden = true; $('drive-connected-until').textContent = '';
});
$('start-drive-test').addEventListener('click', async () => {
  if (!driveSelected?.file?.id) return;
  const button = $('start-drive-test'); button.disabled = true; $('drive-progress').hidden = false; $('drive-ready-actions').hidden = true;
  text('drive-status', 'Starting TorBox → Google Drive transfer…'); $('drive-progress-bar').value = 0; $('drive-timing').textContent = '';
  try {
    const result = await api('/api/drive/test/start', { method: 'POST', data: { videoId: driveSelected.file.id, deleteMinutes: Number($('drive-delete-after').value), blockDownload: $('drive-block-download').checked } });
    driveRunId = result.id; updateDriveStatus(result); scheduleDrivePoll(800);
  } catch (error) { text('drive-status', error.message, true); }
  finally { button.disabled = false; }
});
$('delete-drive-now').addEventListener('click', async () => {
  if (!driveRunId) return;
  const button = $('delete-drive-now'); button.disabled = true;
  try { const result = await api('/api/drive/test/delete', { method: 'POST', data: { id: driveRunId } }); updateDriveStatus(result); stopDrivePolling(); }
  catch (error) { text('drive-status', error.message, true); }
  finally { button.disabled = false; }
});
$('copy-drive-link').addEventListener('click', async () => {
  const url = $('drive-open').href;
  try { await navigator.clipboard.writeText(url); $('drive-delete-note').textContent = 'Drive link copied. ' + $('drive-delete-note').textContent; }
  catch { text('drive-status', 'Could not copy automatically. Open the Drive player and copy its URL.', true); }
});
$('close-drive').addEventListener('click', () => $('drive-dialog').close());
function updateInstallButton(){
  const button=$('settings-install-app');if(!button)return;
  button.disabled=!deferredInstallPrompt;button.textContent=window.matchMedia?.('(display-mode: standalone)').matches?'Installed':'Install app';
}
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();deferredInstallPrompt=event;updateInstallButton();});
window.addEventListener('appinstalled',()=>{deferredInstallPrompt=null;updateInstallButton();text('settings-message','Installed.');});
function loadKidSettings(){
  const profile=getKidProfile(viewer),label=$('viewer').selectedOptions?.[0]?.textContent||viewer;
  $('kid-viewer-label').textContent=label;$('setting-kid-mode').checked=profile.enabled;
  const useHours=profile.timeLimitMinutes>=60&&profile.timeLimitMinutes%60===0;
  $('setting-kid-time-unit').value=useHours?'hours':'minutes';
  $('setting-kid-time').value=String(useHours?profile.timeLimitMinutes/60:profile.timeLimitMinutes);
  $('setting-kid-episodes').value=String(profile.episodeLimit);$('setting-kid-movies').value=String(profile.movieLimit);$('setting-kid-reset').value=profile.resetMode;
  $('kid-usage-summary').textContent=formatKidUsage(profile);
  $('parent-pin-change').textContent=hasParentPin()?'Change Parent PIN':'Set Parent PIN';
}
function loadSettingsForm(){
  const settings=getSettings();loadKidSettings();
  $('setting-interface-mode').value=settings.interfaceMode;
  $('setting-resolution').value=settings.resolution;
  $('setting-rewind').value=String(settings.resumeRewindSeconds);
  $('setting-buffer').value=String(settings.bufferSeconds);
  $('setting-recent-limit').value=String(settings.recentLimit);
  $('setting-auto-next-delay').value=String(settings.autoNextDelaySeconds);
  $('setting-playback-rate').value=String(settings.playbackRate);
  $('setting-sleep-timer').value=String(settings.sleepTimerMinutes);
  $('setting-still-watching').value=String(settings.stillWatchingMinutes);
  $('setting-seek').value=String(settings.seekSeconds);
  $('setting-size-profile').value=settings.sourceSizeProfile;
  $('setting-watchlist-limit').value=String(settings.watchlistLimit);
  $('setting-drive-delete').value=String(settings.driveDeleteMinutes);
  $('setting-next-up-limit').value=String(settings.nextUpLimit);
  $('setting-search-history-limit').value=String(settings.searchHistoryLimit);
  $('setting-auto-next').checked=settings.autoNext;
  $('setting-auto-recovery').checked=settings.autoRecovery;
  $('setting-pause-overlay').checked=settings.pauseOverlay;
  $('setting-drive-watch-only').checked=settings.driveWatchOnly;
  $('setting-show-completed-recent').checked=settings.showCompletedRecent;
  $('setting-keep-awake').checked=settings.keepAwake;
  $('setting-keyboard-shortcuts').checked=settings.keyboardShortcuts;
  $('setting-show-episode-progress').checked=settings.showEpisodeProgress;
  $('setting-remember-browse').checked=settings.rememberBrowse;
  $('setting-show-watchlist').checked=settings.showWatchlist;
  $('setting-cleanup-completed').checked=settings.cleanupCompletedEpisodes;
  $('setting-show-next-up').checked=settings.showNextUp;
  $('setting-show-search-history').checked=settings.showSearchHistory;
  $('setting-long-press').checked=settings.longPressShortcuts;
  $('setting-playback-health').checked=settings.showPlaybackHealth;
  $('setting-auto-learn-sources').checked=settings.autoLearnSources;
  updateInstallButton();text('settings-message','');
}
function openSettingsDialog(){loadSettingsForm();if(!$('settings-dialog').open)$('settings-dialog').showModal();}
$('open-settings').addEventListener('click',()=>{if(kidEnabled()&&hasParentPin())requestParentPin('Enter the Parent PIN to open Settings.',openSettingsDialog);else openSettingsDialog();});
$('close-settings').addEventListener('click',()=>$('settings-dialog').close());
$('settings-form').addEventListener('submit',async event=>{
  event.preventDefault();
  const enableKid=$('setting-kid-mode').checked;
  if(enableKid&&!hasParentPin()){text('settings-message','Set a Parent PIN before enabling Kid Mode.',true);return;}
  const timeValue=Math.max(0,Number($('setting-kid-time').value)||0),timeMinutes=$('setting-kid-time-unit').value==='hours'?Math.round(timeValue*60):Math.round(timeValue);
  updateKidProfile(viewer,{
    enabled:enableKid,timeLimitMinutes:timeMinutes,
    episodeLimit:Math.max(0,Math.round(Number($('setting-kid-episodes').value)||0)),
    movieLimit:Math.max(0,Math.round(Number($('setting-kid-movies').value)||0)),
    resetMode:$('setting-kid-reset').value
  });
  saveSettings({
    interfaceMode:$('setting-interface-mode').value,
    resolution:$('setting-resolution').value,
    resumeRewindSeconds:Number($('setting-rewind').value),
    bufferSeconds:Number($('setting-buffer').value),
    recentLimit:Number($('setting-recent-limit').value),
    autoNext:$('setting-auto-next').checked,
    autoNextDelaySeconds:Number($('setting-auto-next-delay').value),
    autoRecovery:$('setting-auto-recovery').checked,
    pauseOverlay:$('setting-pause-overlay').checked,
    driveWatchOnly:$('setting-drive-watch-only').checked,
    driveDeleteMinutes:Number($('setting-drive-delete').value),
    showCompletedRecent:$('setting-show-completed-recent').checked,
    playbackRate:Number($('setting-playback-rate').value),
    sleepTimerMinutes:Number($('setting-sleep-timer').value),
    stillWatchingMinutes:Number($('setting-still-watching').value),
    keepAwake:$('setting-keep-awake').checked,
    keyboardShortcuts:$('setting-keyboard-shortcuts').checked,
    seekSeconds:Number($('setting-seek').value),
    showEpisodeProgress:$('setting-show-episode-progress').checked,
    rememberBrowse:$('setting-remember-browse').checked,
    sourceSizeProfile:$('setting-size-profile').value,
    showWatchlist:$('setting-show-watchlist').checked,
    watchlistLimit:Number($('setting-watchlist-limit').value),
    cleanupCompletedEpisodes:$('setting-cleanup-completed').checked,
    showNextUp:$('setting-show-next-up').checked,
    nextUpLimit:Number($('setting-next-up-limit').value),
    showSearchHistory:$('setting-show-search-history').checked,
    searchHistoryLimit:Number($('setting-search-history-limit').value),
    longPressShortcuts:$('setting-long-press').checked,
    showPlaybackHealth:$('setting-playback-health').checked,
    autoLearnSources:$('setting-auto-learn-sources').checked
  });
  try{await flushRuntimeState();}catch(error){text('settings-message',error.message||'The phone could not save settings.',true);return;}
  try{sessionStorage.setItem('tw-source-resolution',$('setting-resolution').value)}catch{}
  applyInterfaceMode();loadKidSettings();renderRecent();discoveryUI.settingsChanged();resetStillWatchingTimer();if(active?.video){active.video.playbackRate=getSettings().playbackRate;if(active.video.paused)updatePauseCard(active);else hidePauseCard();setHealthSource(active);setPlaybackHealth(active.video.paused?'Paused':'Playing','Settings updated.');} text('settings-message','Saved.');
});
$('reset-settings').addEventListener('click',async()=>{resetSettings();try{await flushRuntimeState();}catch(error){text('settings-message',error.message||'The phone could not reset settings.',true);return;}try{sessionStorage.removeItem('tw-source-resolution')}catch{}applyInterfaceMode();loadSettingsForm();renderRecent();discoveryUI.settingsChanged();resetStillWatchingTimer();});
$('kid-reset-allowance').addEventListener('click',async()=>{resetKidAllowance(viewer);try{await flushRuntimeState();}catch(error){text('settings-message',error.message||'The phone could not reset the allowance.',true);return;}loadKidSettings();applyInterfaceMode();text('settings-message','Kid allowance reset.');});
function openParentPinChange(){
  const existing=hasParentPin();$('parent-pin-change-title').textContent=existing?'Change Parent PIN':'Set Parent PIN';
  $('parent-pin-current-wrap').hidden=!existing;$('parent-pin-current').required=existing;
  $('parent-pin-current').value='';$('parent-pin-new').value='';$('parent-pin-confirm').value='';text('parent-pin-change-message','');
  if(!$('parent-pin-change-dialog').open)$('parent-pin-change-dialog').showModal();
}
$('parent-pin-change').addEventListener('click',openParentPinChange);
$('close-parent-pin-change').addEventListener('click',()=>$('parent-pin-change-dialog').close());
$('parent-pin-change-form').addEventListener('submit',async event=>{
  event.preventDefault();const button=event.submitter;button.disabled=true;text('parent-pin-change-message','Saving…');
  try{
    const existing=hasParentPin(),current=$('parent-pin-current').value,next=$('parent-pin-new').value,confirm=$('parent-pin-confirm').value;
    if(existing&&!(await verifyParentPin(current))){text('parent-pin-change-message','Current PIN is incorrect.',true);return;}
    if(!/^\d{4,8}$/.test(next)){text('parent-pin-change-message','Use a 4 to 8 digit PIN.',true);return;}
    if(next!==confirm){text('parent-pin-change-message','The new PIN entries do not match.',true);return;}
    await setParentPin(next);$('parent-pin-change-dialog').close();loadKidSettings();text('settings-message',existing?'Parent PIN changed.':'Parent PIN set.');
  }catch(error){text('parent-pin-change-message',error.message,true);}
  finally{button.disabled=false;}
});
$('settings-clear-learning').addEventListener('click',()=>{if(confirm('Clear learned source preferences, audio feedback, bad-source blocks, and per-title quality choices on this device?')){clearSourceMemory();text('settings-message','Source learning cleared.');}});
$('settings-clear-searches').addEventListener('click',()=>{if(confirm('Clear search history for this viewer on this device?')){clearSearchHistory(viewer);discoveryUI.settingsChanged();text('settings-message','Search history cleared.');}});
$('settings-install-app').addEventListener('click',async()=>{if(!deferredInstallPrompt){text('settings-message',window.matchMedia?.('(display-mode: standalone)').matches?'The app is already installed.':'Use Chrome’s Add to Home screen / Install app command if the install prompt is not available.');return;}const prompt=deferredInstallPrompt;deferredInstallPrompt=null;await prompt.prompt();await prompt.userChoice.catch(()=>{});updateInstallButton();});
$('settings-check-status').addEventListener('click',async()=>{text('settings-message','Checking TorBox…');const ok=await checkTorBoxStatus(true);text('settings-message',ok?(torboxStatusCache?.official==='issue'?'TorBox API is reachable, but its status page reports an issue.':'TorBox API is reachable.'):(torboxStatusCache?.message||'TorBox is unavailable.'),!ok);});
$('retry-torbox-status').addEventListener('click',()=>checkTorBoxStatus(true));
discoveryUI = createDiscoveryUI({ api, play: startPlayback, driveTest: runtimeCapabilities.driveSharing ? openDriveTest : null, guard: ensureTorBoxReady });
if(runtimeCapabilities.serviceWorker&&'serviceWorker' in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
bootstrap();
