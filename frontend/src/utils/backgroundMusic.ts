// Background music — hidden HTMLAudio, low volume so turn sounds stay audible.
// Lazy: the 2.1MB track is NOT fetched at app mount. The <audio> element is
// created only on the first user interaction that actually plays it (or an
// explicit unmute), so muted/never-tapping players never pay the download.
import { assetUrl } from './api';

let bgAudio: HTMLAudioElement | null = null;
const STORAGE_KEY = 'yanif_bg_music_enabled';
const VOLUME = 0.15; // low so game action audio is clearly audible

/** Release-pinned background track URL (single place so <audio> and preload hint match). */
export function getBgMusicUrl(): string {
  return assetUrl('/background.mp3');
}

function getBgAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined') return null;
  if (!bgAudio) {
    bgAudio = new Audio(getBgMusicUrl());
    bgAudio.loop = true;
    // 'none' + first play(): the file's bytes only arrive once the user
    // actually hears it — never during app/tab load.
    bgAudio.preload = 'none';
    bgAudio.volume = VOLUME;
    // Hidden — no UI, background load
    bgAudio.style.display = 'none';
    // Ensure it doesn't show controls
    bgAudio.controls = false;
  }
  return bgAudio;
}

export function isBgMusicEnabled(): boolean {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === null ? true : v === 'true';
  } catch {
    return true;
  }
}

export function setBgMusicEnabled(enabled: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {}
  if (enabled) {
    playBgMusic();
  } else {
    pauseBgMusic();
  }
  window.dispatchEvent(new CustomEvent('yanif:bg-music-toggled', { detail: enabled }));
}

export function playBgMusic() {
  if (!isBgMusicEnabled()) return;
  const audio = getBgAudio();
  if (!audio) return;
  // Promote to auto so the browser buffers the full track once playback
  // starts. Kept as 'none' until this point to avoid fetching on load.
  if (audio.preload !== 'auto') audio.preload = 'auto';
  audio.volume = VOLUME;
  const p = audio.play();
  if (p) p.catch(() => {});
}

export function pauseBgMusic() {
  const audio = getBgAudio();
  if (!audio) return;
  audio.pause();
}

export function unlockBgMusic() {
  if (isBgMusicEnabled()) playBgMusic();
}

export function setupBgMusicUnlock() {
  // Deliberately does NOT touch the audio element: no file bytes before the
  // first attempt to play. The listeners below are what flip play on the
  // first click/keydown/touch for players who enabled music. Muted players
  // lose the whole 2.1MB download on every session.
  const handler = () => {
    unlockBgMusic();
    document.removeEventListener('click', handler);
    document.removeEventListener('keydown', handler);
    document.removeEventListener('touchstart', handler);
  };
  document.addEventListener('click', handler, { once: true });
  document.addEventListener('keydown', handler, { once: true });
  document.addEventListener('touchstart', handler, { once: true });
  // Also handle visibility — resume if was playing and got paused by browser
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && isBgMusicEnabled()) {
      const a = getBgAudio();
      if (a && a.paused) playBgMusic();
    }
  });
  // Pause when page hidden to save resources (optional — keep playing if you prefer)
  // We keep playing in background; uncomment to pause on hide:
  // document.addEventListener('visibilitychange', () => {
  //   if (document.hidden) pauseBgMusic();
  // });
}

// Legacy eager preload: now a no-op. The track loads on first play; the bytes
// are never spent before the user actually wants to hear the music. Kept as an
// export so existing call sites read naturally and a future preload policy can
// slot back in here without touching them.
export function preloadBgMusic() {
  // Intentionally empty — see header comment.
}
