// Playback diagnostics use the selected runtime's trusted media boundary.
import { isTrustedPlaybackUrl, runtimeCapabilities } from './runtime.js';
export function matchesFormat(file, format = 'all') {
  return format !== 'mp4' || /\.mp4$/i.test(file.title || '');
}
export async function diagnosePlaybackFailure(mediaUrl, browserCode) {
  const result = (kind, message, retry = true) => ({ kind, message, retry });
  if (!isTrustedPlaybackUrl(mediaUrl)) return result('session', runtimeCapabilities.phoneCredentials?'This local playback link is invalid. Close and reopen the file.':'This playback link is not a trusted TorBox media URL. Close and reopen the file.');
  if (browserCode === 1) return result('cancelled', 'Playback was cancelled. Close and reopen the file to try again.');
  if (browserCode === 2) return result('network', runtimeCapabilities.phoneCredentials?'The phone stream was interrupted or its source expired. Try New playback link once.':'The direct TorBox stream was interrupted or its temporary link expired. Try New playback link once.');
  if ([3, 4].includes(browserCode)) return result('codec', `${runtimeCapabilities.phoneCredentials?'The phone delivered the file':'TorBox delivered the file directly'}, but this browser rejected its format or codec (browser error ${browserCode}). Choose another version. This build does not convert video or audio.`, false);
  return result('network', runtimeCapabilities.phoneCredentials?'Phone playback failed. Try New playback link once or choose another version.':'Direct TorBox playback failed. Try New playback link once or choose another version.');
}
