/**
 * Native Web Audio Engine (Zero-Asset Sound System)
 * Strictly follows docs/ui-ux-spec.md Section 2.
 * Generates all sound effects procedurally via AudioContext without downloading external audio files.
 */

class SoundEngine {
  private ctx: AudioContext | null = null;
  private isUnlocked: boolean = false;

  constructor() {
    this.setupUnlockListeners();
  }

  private getContext(): AudioContext | null {
    if (!this.ctx && (typeof window !== 'undefined' || typeof (window as any).webkitAudioContext !== 'undefined')) {
      const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
      if (AudioCtx) {
        this.ctx = new AudioCtx();
      }
    }
    if (this.ctx && this.ctx.state === 'suspended') {
      this.ctx.resume().catch(() => {});
    }
    return this.ctx;
  }

  private setupUnlockListeners() {
    if (typeof window === 'undefined') return;

    const unlock = () => {
      const ctx = this.getContext();
      if (ctx) {
        if (ctx.state === 'suspended') {
          ctx.resume().then(() => {
            this.isUnlocked = true;
          }).catch(() => {});
        } else {
          this.isUnlocked = true;
        }
      }
      window.removeEventListener('touchstart', unlock);
      window.removeEventListener('mousedown', unlock);
      window.removeEventListener('click', unlock);
      window.removeEventListener('keydown', unlock);
    };

    window.addEventListener('touchstart', unlock, { passive: true });
    window.addEventListener('mousedown', unlock, { passive: true });
    window.addEventListener('click', unlock, { passive: true });
    window.addEventListener('keydown', unlock, { passive: true });
  }

  /**
   * Helper: Generate a White Noise AudioBuffer
   */
  private createNoiseBuffer(durationSeconds: number = 0.5): AudioBuffer | null {
    const ctx = this.getContext();
    if (!ctx) return null;
    const bufferSize = Math.floor(ctx.sampleRate * durationSeconds);
    const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }
    return buffer;
  }

  /**
   * 1. Card Select (Tap)
   * Triangle wave, pitch decay 600Hz -> 150Hz over 20ms, gain envelope peak 0.2.
   */
  public playCardSelectTick() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'triangle';
      osc.frequency.setValueAtTime(600, now);
      osc.frequency.exponentialRampToValueAtTime(150, now + 0.02);

      gain.gain.setValueAtTime(0.2, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.025);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + 0.03);
    } catch (e) {}
  }

  /**
   * 2. Multi-Select (Double-Tap)
   * Arpeggiated double-flick: Two staggered tick pulses (15ms offset) with second pulse pitched +3 semitones higher.
   */
  public playMultiSelectTick() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      
      // Pulse 1
      const osc1 = ctx.createOscillator();
      const gain1 = ctx.createGain();
      osc1.type = 'triangle';
      osc1.frequency.setValueAtTime(600, now);
      osc1.frequency.exponentialRampToValueAtTime(150, now + 0.02);
      gain1.gain.setValueAtTime(0.2, now);
      gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.025);
      osc1.connect(gain1);
      gain1.connect(ctx.destination);
      osc1.start(now);
      osc1.stop(now + 0.03);

      // Pulse 2: +3 semitones (600 * 2^(3/12) ≈ 713.5Hz -> 178.4Hz)
      const offset = 0.015;
      const osc2 = ctx.createOscillator();
      const gain2 = ctx.createGain();
      osc2.type = 'triangle';
      osc2.frequency.setValueAtTime(713.5, now + offset);
      osc2.frequency.exponentialRampToValueAtTime(178.4, now + offset + 0.02);
      gain2.gain.setValueAtTime(0.22, now + offset);
      gain2.gain.exponentialRampToValueAtTime(0.001, now + offset + 0.025);
      osc2.connect(gain2);
      gain2.connect(ctx.destination);
      osc2.start(now + offset);
      osc2.stop(now + offset + 0.03);
    } catch (e) {}
  }

  /**
   * 3. Staging Drop (Valid)
   * Heavy felt impact thud: Dual node:
   * - Low-pass filtered noise burst (400Hz cutoff, 30ms)
   * - Sine wave thud (120Hz -> 40Hz)
   */
  public playValidStagingDrop() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;

      // Noise burst
      const noiseBuffer = this.createNoiseBuffer(0.05);
      if (noiseBuffer) {
        const noiseSource = ctx.createBufferSource();
        noiseSource.buffer = noiseBuffer;
        const filter = ctx.createBiquadFilter();
        filter.type = 'lowpass';
        filter.frequency.setValueAtTime(400, now);

        const noiseGain = ctx.createGain();
        noiseGain.gain.setValueAtTime(0.3, now);
        noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.03);

        noiseSource.connect(filter);
        filter.connect(noiseGain);
        noiseGain.connect(ctx.destination);

        noiseSource.start(now);
        noiseSource.stop(now + 0.035);
      }

      // Sine thud
      const sineOsc = ctx.createOscillator();
      const sineGain = ctx.createGain();
      sineOsc.type = 'sine';
      sineOsc.frequency.setValueAtTime(120, now);
      sineOsc.frequency.exponentialRampToValueAtTime(40, now + 0.07);

      sineGain.gain.setValueAtTime(0.4, now);
      sineGain.gain.exponentialRampToValueAtTime(0.001, now + 0.08);

      sineOsc.connect(sineGain);
      sineGain.connect(ctx.destination);

      sineOsc.start(now);
      sineOsc.stop(now + 0.09);
    } catch (e) {}
  }

  /**
   * 4. Staging Drop / Ineligible Tap (Invalid)
   * Elastic rejection spring: Sawtooth pitch sweep 180Hz -> 90Hz over 150ms with rapid amplitude tremor.
   */
  public playInvalidRejection() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      const tremoloOsc = ctx.createOscillator();
      const tremoloGain = ctx.createGain();

      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(180, now);
      osc.frequency.exponentialRampToValueAtTime(90, now + 0.15);

      tremoloOsc.type = 'sine';
      tremoloOsc.frequency.setValueAtTime(25, now);
      tremoloGain.gain.setValueAtTime(0.08, now);

      gain.gain.setValueAtTime(0.18, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.16);

      tremoloOsc.connect(gain.gain);
      osc.connect(gain);
      gain.connect(ctx.destination);

      tremoloOsc.start(now);
      osc.start(now);
      tremoloOsc.stop(now + 0.17);
      osc.stop(now + 0.17);
    } catch (e) {}
  }

  /**
   * 5. Felt Slide / Drag
   * Dynamic White Noise buffer connected to Biquad Bandpass Filter;
   * frequency scales with drag velocity vector.
   */
  public playFeltSlide(intensity: number = 0.5) {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      const noiseBuffer = this.createNoiseBuffer(0.1);
      if (!noiseBuffer) return;

      const source = ctx.createBufferSource();
      source.buffer = noiseBuffer;

      const filter = ctx.createBiquadFilter();
      filter.type = 'bandpass';
      const centerFreq = 500 + Math.min(Math.max(intensity, 0), 1) * 1300;
      filter.frequency.setValueAtTime(centerFreq, now);
      filter.Q.setValueAtTime(1.5, now);

      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.08 * Math.min(Math.max(intensity, 0.2), 1), now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.08);

      source.connect(filter);
      filter.connect(gain);
      gain.connect(ctx.destination);

      source.start(now);
      source.stop(now + 0.09);
    } catch (e) {}
  }

  /**
   * 6. Dealer Round-Robin
   * Air-cushion card flick: Fast noise burst envelope (15ms, high-pass 1200Hz) coupled with a soft thud on landing.
   */
  public playDealerFlick() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;

      const noiseBuffer = this.createNoiseBuffer(0.03);
      if (noiseBuffer) {
        const source = ctx.createBufferSource();
        source.buffer = noiseBuffer;

        const hpFilter = ctx.createBiquadFilter();
        hpFilter.type = 'highpass';
        hpFilter.frequency.setValueAtTime(1200, now);

        const noiseGain = ctx.createGain();
        noiseGain.gain.setValueAtTime(0.18, now);
        noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.018);

        source.connect(hpFilter);
        hpFilter.connect(noiseGain);
        noiseGain.connect(ctx.destination);

        source.start(now);
        source.stop(now + 0.02);
      }

      const landingNow = now + 0.03;
      const thudOsc = ctx.createOscillator();
      const thudGain = ctx.createGain();
      thudOsc.type = 'sine';
      thudOsc.frequency.setValueAtTime(100, landingNow);
      thudOsc.frequency.exponentialRampToValueAtTime(45, landingNow + 0.04);

      thudGain.gain.setValueAtTime(0.15, landingNow);
      thudGain.gain.exponentialRampToValueAtTime(0.001, landingNow + 0.045);

      thudOsc.connect(thudGain);
      thudGain.connect(ctx.destination);

      thudOsc.start(landingNow);
      thudOsc.stop(landingNow + 0.05);
    } catch (e) {}
  }

  /**
   * 7. Yaniv Call Bell
   * Resonant metallic chime: Dual Sine harmonics (880Hz A5 & 1760Hz A6) with long exponential decay (1.2s) + vibrato.
   */
  public playYanivBell() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;

      const osc1 = ctx.createOscillator();
      const gain1 = ctx.createGain();
      osc1.type = 'sine';
      osc1.frequency.setValueAtTime(880, now);

      const osc2 = ctx.createOscillator();
      const gain2 = ctx.createGain();
      osc2.type = 'sine';
      osc2.frequency.setValueAtTime(1760, now);

      const vibrato = ctx.createOscillator();
      const vibratoGain = ctx.createGain();
      vibrato.frequency.setValueAtTime(5, now);
      vibratoGain.gain.setValueAtTime(4, now);
      vibrato.connect(osc1.frequency);
      vibrato.connect(osc2.frequency);

      gain1.gain.setValueAtTime(0.3, now);
      gain1.gain.exponentialRampToValueAtTime(0.0001, now + 1.2);

      gain2.gain.setValueAtTime(0.18, now);
      gain2.gain.exponentialRampToValueAtTime(0.0001, now + 1.0);

      osc1.connect(gain1);
      osc2.connect(gain2);
      gain1.connect(ctx.destination);
      gain2.connect(ctx.destination);

      vibrato.start(now);
      osc1.start(now);
      osc2.start(now);

      vibrato.stop(now + 1.25);
      osc1.stop(now + 1.25);
      osc2.stop(now + 1.25);
    } catch (e) {}
  }

  /**
   * 8. Asaf Penalty
   * Distorted crimson crash: Square wave low-end rumble (80Hz) + overdrive distortion node fading over 600ms.
   */
  public playAsafCrash() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'square';
      osc.frequency.setValueAtTime(80, now);
      osc.frequency.exponentialRampToValueAtTime(35, now + 0.5);

      const shaper = ctx.createWaveShaper();
      const curve = new Float32Array(256);
      for (let i = 0; i < 256; i++) {
        const x = (i * 2) / 256 - 1;
        curve[i] = ((Math.PI + 4) * x) / (Math.PI + 4 * Math.abs(x));
      }
      shaper.curve = curve;
      shaper.oversample = '2x';

      gain.gain.setValueAtTime(0.35, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.6);

      osc.connect(shaper);
      shaper.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + 0.65);
    } catch (e) {}
  }

  /**
   * 9. Timer Warning (<= 5s)
   * Ticking clock heartbeat: Low Sine pulse (100Hz / 200Hz, 10ms).
   */
  public playTimerTick(isUrgent: boolean = false) {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(isUrgent ? 200 : 100, now);

      gain.gain.setValueAtTime(0.2, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.012);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + 0.015);
    } catch (e) {}
  }

  /**
   * 10. Hand Sorting Cascade
   * Rapid 5-card cascade tick audio effect.
   */
  public playSortCascade() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const baseFreq = 400;
      for (let i = 0; i < 5; i++) {
        setTimeout(() => {
          if (!this.ctx) return;
          const now = this.ctx.currentTime;
          const osc = this.ctx.createOscillator();
          const gain = this.ctx.createGain();
          osc.type = 'triangle';
          osc.frequency.setValueAtTime(baseFreq + i * 50, now);
          gain.gain.setValueAtTime(0.12, now);
          gain.gain.exponentialRampToValueAtTime(0.001, now + 0.018);
          osc.connect(gain);
          gain.connect(this.ctx.destination);
          osc.start(now);
          osc.stop(now + 0.02);
        }, i * 25);
      }
    } catch (e) {}
  }

  /**
   * 11. Yaniv Ready Chime
   * Soft high-pitched golden chime played once when total points <= 7.
   */
  public playYanivReadyChime() {
    const ctx = this.getContext();
    if (!ctx) return;
    try {
      const now = ctx.currentTime;
      const osc1 = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      const gain = ctx.createGain();

      osc1.type = 'sine';
      osc1.frequency.setValueAtTime(1046.5, now); // C6
      osc2.type = 'sine';
      osc2.frequency.setValueAtTime(1318.5, now); // E6

      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.4);

      osc1.connect(gain);
      osc2.connect(gain);
      gain.connect(ctx.destination);

      osc1.start(now);
      osc2.start(now);
      osc1.stop(now + 0.45);
      osc2.stop(now + 0.45);
    } catch (e) {}
  }
}

export const soundEngine = new SoundEngine();
export default soundEngine;
