// Sound utility for turn notifications — Web Audio API, no external assets needed.
// Two distinct sounds:
// 1. playTurnChangeSound — soft tick when turn passes to another player
// 2. playYourTurnSound  — bright ding when it's your turn (don't play #1 in this case)

let audioCtx: AudioContext | null = null;
const STORAGE_KEY = 'yanif_sound_enabled';

function getAudioContext(): AudioContext | null {
  try {
    if (!audioCtx) {
      const Ctx = (window as any).AudioContext || (window as any).webkitAudioContext;
      if (!Ctx) return null;
      audioCtx = new Ctx();
    }
    const ctx = audioCtx as AudioContext;
    if (ctx.state === 'suspended') {
      ctx.resume().catch(() => {});
    }
    return ctx;
  } catch {
    return null;
  }
}

export function isSoundEnabled(): boolean {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === null ? true : v === 'true';
  } catch {
    return true;
  }
}

export function setSoundEnabled(enabled: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {}
  window.dispatchEvent(new CustomEvent('yanif:sound-toggled', { detail: enabled }));
}

export function unlockAudio() {
  getAudioContext();
}

function playTone(freq: number, duration: number, type: OscillatorType, gain: number, delay = 0) {
  const ctx = getAudioContext();
  if (!ctx) return;
  const start = ctx.currentTime + delay;
  const osc = ctx.createOscillator();
  const g = ctx.createGain();
  osc.type = type;
  osc.frequency.value = freq;
  osc.connect(g);
  g.connect(ctx.destination);
  // Envelope to avoid click
  g.gain.setValueAtTime(0, start);
  g.gain.linearRampToValueAtTime(gain, start + 0.01);
  g.gain.exponentialRampToValueAtTime(0.001, start + duration);
  osc.start(start);
  osc.stop(start + duration + 0.02);
}

// 1. Turn switches to another player — soft, low tick
export function playTurnChangeSound() {
  if (!isSoundEnabled()) return;
  // Short 620Hz sine, quick decay — unobtrusive
  playTone(620, 0.12, 'sine', 0.25);
  // faint second harmonic for warmth
  playTone(1240, 0.08, 'sine', 0.08, 0.015);
}

// 2. It's your turn — bright, attention-grabbing double ding (not playing #1)
export function playYourTurnSound() {
  if (!isSoundEnabled()) return;
  // Two-note chime: 880Hz -> 1318Hz (A5 -> E6), sine with slight triangle overtone
  playTone(880, 0.18, 'sine', 0.32);
  playTone(880, 0.18, 'triangle', 0.08);
  playTone(1318.5, 0.22, 'sine', 0.28, 0.14);
  playTone(1318.5, 0.22, 'triangle', 0.07, 0.14);
}

// 3. Asaf — plays the bundled /asaf.mp3 file (served from static root).
// Respects the same sound toggle as the turn sounds. The track is long, so
// callers must call stopAsafSound() when the room moves on (next round).
let asafAudio: HTMLAudioElement | null = null;

function getAsafAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;
  if (!asafAudio) {
    asafAudio = new Audio('/asaf.mp3');
    asafAudio.preload = 'auto';
  }
  return asafAudio;
}

export function preloadAsafSound() {
  try {
    getAsafAudio()?.load();
  } catch {}
}

export function playAsafSound() {
  if (!isSoundEnabled()) return;
  try {
    const audio = getAsafAudio();
    if (!audio) return;
    audio.currentTime = 0;
    const p = audio.play();
    if (p) p.catch(() => {});
  } catch {}
}

export function stopAsafSound() {
  try {
    if (!asafAudio) return;
    asafAudio.pause();
    asafAudio.currentTime = 0;
  } catch {}
}

// 4. Waiting-for-Asaf — plays the bundled /waiting-for-asaf.mp3 file while every
// player watches the 5s Yaniv reveal countdown. Stopped when the countdown ends
// and the result screen lands (see TableCanvas contest effect).
let waitingForAsafAudio: HTMLAudioElement | null = null;

function getWaitingForAsafAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;
  if (!waitingForAsafAudio) {
    waitingForAsafAudio = new Audio('/waiting-for-asaf.mp3');
    waitingForAsafAudio.preload = 'auto';
  }
  return waitingForAsafAudio;
}

export function preloadWaitingForAsafSound() {
  try {
    getWaitingForAsafAudio()?.load();
  } catch {}
}

export function playWaitingForAsafSound() {
  if (!isSoundEnabled()) return;
  try {
    const audio = getWaitingForAsafAudio();
    if (!audio) return;
    audio.currentTime = 0;
    const p = audio.play();
    if (p) p.catch(() => {});
  } catch {}
}

export function stopWaitingForAsafSound() {
  try {
    if (!waitingForAsafAudio) return;
    waitingForAsafAudio.pause();
    waitingForAsafAudio.currentTime = 0;
  } catch {}
}

// 5. All-cards-discarded — plays the bundled /all-cards-discarded.mp3 file when any
// player throws their whole hand in one discard. Short clip, no stop needed.
let allCardsDiscardedAudio: HTMLAudioElement | null = null;

function getAllCardsDiscardedAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;
  if (!allCardsDiscardedAudio) {
    allCardsDiscardedAudio = new Audio('/all-cards-discarded.mp3');
    allCardsDiscardedAudio.preload = 'auto';
  }
  return allCardsDiscardedAudio;
}

export function preloadAllCardsDiscardedSound() {
  try {
    getAllCardsDiscardedAudio()?.load();
  } catch {}
}

export function playAllCardsDiscardedSound() {
  if (!isSoundEnabled()) return;
  try {
    const audio = getAllCardsDiscardedAudio();
    if (!audio) return;
    audio.currentTime = 0;
    const p = audio.play();
    if (p) p.catch(() => {});
  } catch {}
}

// 6. Ace-picked — plays the first 3 seconds of the bundled /ace-picked.mp3 file
// when any player draws an ace from the discard pile (pile draws are public;
// deck draws stay silent).
let acePickedAudio: HTMLAudioElement | null = null;
let acePickedTimer: ReturnType<typeof setTimeout> | null = null;
const ACE_PICKED_PLAY_MS = 3000;

function getAcePickedAudio(): HTMLAudioElement | null {
  if (typeof window === 'undefined' || typeof Audio === 'undefined') return null;
  if (!acePickedAudio) {
    acePickedAudio = new Audio('/ace-picked.mp3');
    acePickedAudio.preload = 'auto';
  }
  return acePickedAudio;
}

export function preloadAcePickedSound() {
  try {
    getAcePickedAudio()?.load();
  } catch {}
}

export function playAcePickedSound() {
  if (!isSoundEnabled()) return;
  try {
    const audio = getAcePickedAudio();
    if (!audio) return;
    if (acePickedTimer) {
      clearTimeout(acePickedTimer);
      acePickedTimer = null;
    }
    audio.currentTime = 0;
    const p = audio.play();
    if (p) p.catch(() => {});
    // Only the opening 3 seconds — the track is longer.
    acePickedTimer = setTimeout(() => {
      acePickedTimer = null;
      try {
        audio.pause();
      } catch {}
    }, ACE_PICKED_PLAY_MS);
  } catch {}
}

// Call once on app mount to unlock on first user gesture
export function setupAudioUnlock() {
  const handler = () => {
    unlockAudio();
    document.removeEventListener('click', handler);
    document.removeEventListener('keydown', handler);
    document.removeEventListener('touchstart', handler);
  };
  document.addEventListener('click', handler, { once: true });
  document.addEventListener('keydown', handler, { once: true });
  document.addEventListener('touchstart', handler, { once: true });
}
