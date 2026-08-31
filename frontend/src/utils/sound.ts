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
