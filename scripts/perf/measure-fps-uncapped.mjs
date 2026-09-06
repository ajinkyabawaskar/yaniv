// Uncapped-FPS probe: same 30-card transform/opacity burst as perf-fps.spec.ts,
// but launched with vsync/frame-rate-limit disabled so headless Chromium reports
// what the compositor can actually do instead of the 60Hz BeginFrame cap.
// Usage: npm run start & sleep 60; node scripts/perf/measure-fps-uncapped.mjs; kill %1
import { createRequire } from 'module';
// Resolve from the caller's cwd (run this from frontend/): the script lives in
// scripts/perf, which has no node_modules of its own.
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('@playwright/test');

const URL = process.env.PERF_URL || 'http://localhost:3000/';
const browser = await chromium.launch({
  headless: true,
  args: [
    '--disable-frame-rate-limit',
    '--disable-gpu-vsync',
    '--disable-software-rasterizer',
    '--use-gl=angle',
    '--use-angle=swiftshader',
  ],
});
const page = await browser.newPage();
await page.goto(URL, { waitUntil: 'load' }).catch(() => {});
const m = await page.evaluate(async () => {
  const samples = [];
  const stage = document.createElement('div');
  stage.style.cssText = 'position:fixed;inset:0;pointer-events:none;overflow:hidden;';
  document.body.appendChild(stage);
  const cards = [];
  for (let i = 0; i < 30; i++) {
    const el = document.createElement('div');
    el.style.cssText =
      'position:absolute;left:50%;top:50%;width:64px;height:90px;border-radius:8px;' +
      'background:linear-gradient(135deg,#1e40af,#7c3aed);will-change:transform,opacity;';
    stage.appendChild(el);
    cards.push(el);
  }
  await new Promise((resolve) => {
    const start = performance.now();
    let last = start;
    let frame = 0;
    const tick = (now) => {
      const dt = now - last;
      last = now;
      if (now - start > 300) samples.push(1000 / Math.max(dt, 0.01));
      for (let i = 0; i < cards.length; i++) {
        const phase = (i / cards.length) * Math.PI * 2;
        const t = (now - start) / 1000;
        const x = Math.cos(phase + t * 2.4) * (120 + 40 * Math.sin(t + phase));
        const y = Math.sin(phase * 2 + t * 3.1) * 160 - 60;
        const r = ((frame * 2 + i * 12) % 40) - 20;
        cards[i].style.transform = `translate3d(${x.toFixed(1)}px,${y.toFixed(1)}px,0) rotate(${r.toFixed(1)}deg)`;
        cards[i].style.opacity = (0.55 + 0.45 * Math.abs(Math.sin(t + phase))).toFixed(3);
      }
      frame++;
      if (now - start < 5000) requestAnimationFrame(tick);
      else resolve();
    };
    requestAnimationFrame(tick);
  });
  stage.remove();
  samples.sort((a, b) => a - b);
  const avg = samples.reduce((s, v) => s + v, 0) / Math.max(samples.length, 1);
  return { avg: avg.toFixed(1), p1: (samples[Math.floor(samples.length * 0.01)] ?? avg).toFixed(1), frames: samples.length };
});
console.log(`[uncapped] avg=${m.avg} p1=${m.p1} frames=${m.frames}`);
await browser.close();
