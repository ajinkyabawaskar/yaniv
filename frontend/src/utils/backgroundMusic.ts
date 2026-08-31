// Background music — hidden HTMLAudio, low volume so turn sounds stay audible.
// Loads /background.mp3 in background, plays only after user interaction.

let bgAudio: HTMLAudioElement | null = null;
const STORAGE_KEY = 'yanif_bg_music_enabled';
const VOLUME = 0.15; // low so game action audio is clearly audible

function getBgAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined') return null;
  if (!bgAudio) {
    bgAudio = new Audio('/background.mp3');
    bgAudio.loop = true;
    bgAudio.preload = 'auto';
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
  // Create element early so file starts loading in background
  getBgAudio();
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

// Preload hint for browser — hidden, background
export function preloadBgMusic() {
  const audio = getBgAudio();
  if (audio) {
    // Trigger load without playing
    audio.load();
  }
  // Also add link preload for early fetch (no UI)
  if (typeof document !== 'undefined' && !document.querySelector('link[href="/background.mp3"]')) {
    const link = document.createElement('link');
    link.rel = 'preload';
    link.as = 'audio';
    link.href = '/background.mp3';
    document.head.appendChild(link);
  }
}
