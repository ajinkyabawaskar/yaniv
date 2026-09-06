import { test, expect } from '@playwright/test';

/**
 * Frontend performance guardrails for the 120 FPS target.
 *
 * What it measures (no backend required — the stress animation is injected):
 *  - FPS stability: average + 1st percentile frame rate during a 5s
 *    multi-card transform/opacity animation burst (deal/discard/sort proxy).
 *  - Long tasks: JS blocks > 8.33ms (one 120Hz frame budget) via PerformanceObserver.
 *  - CLS: cumulative layout shift during rapid UI state updates.
 *
 * Guardrails (fail CI on breach):
 *  - average FPS >= 115, p1 FPS >= 90
 *  - long tasks (>8.33ms) <= 5 over the 5s window
 *  - CLS <= 0.05
 *
 * NOTE: headless CI renderers (SwiftShader) are slower than real GPUs. If this
 * spec fails only on `avgFps` in CI, raise CI workers' resources before
 * weakening the thresholds — the numbers below are the product requirement.
 */

const AVG_FPS_FLOOR = 115;
const P1_FPS_FLOOR = 90;
const LONG_TASK_BUDGET_MS = 8.33;
const LONG_TASK_MAX = 5;
const CLS_MAX = 0.05;

test.describe('perf: 120fps animation guardrails', () => {
  test('transform-only card burst holds frame budget', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle').catch(() => {});

    const metrics = await page.evaluate(
      async ({ budget }: { budget: number }) => {
        const samples: number[] = [];
        const longTasks: number[] = [];
        let cls = 0;

        const taskObserver = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if ((entry as any).duration > budget) longTasks.push((entry as any).duration);
          }
        });
        try {
          taskObserver.observe({ entryTypes: ['longtask'] });
        } catch {
          /* longtask unsupported — report empty */
        }
        const clsObserver = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (!(entry as any).hadRecentInput) cls += (entry as any).value;
          }
        });
        try {
          clsObserver.observe({ entryTypes: ['layout-shift'] });
        } catch {
          /* layout-shift unsupported — report zero */
        }

        // 30-card burst animated ONLY via transform + opacity (the GPU path
        // every TableCanvas animation must stay on).
        const stage = document.createElement('div');
        stage.setAttribute('data-testid', 'perf-stage');
        stage.style.cssText =
          'position:fixed;inset:0;pointer-events:none;overflow:hidden;';
        document.body.appendChild(stage);
        const cards: HTMLDivElement[] = [];
        for (let i = 0; i < 30; i++) {
          const el = document.createElement('div');
          el.style.cssText =
            'position:absolute;left:50%;top:50%;width:64px;height:90px;border-radius:8px;' +
            'background:linear-gradient(135deg,#1e40af,#7c3aed);will-change:transform,opacity;';
          stage.appendChild(el);
          cards.push(el);
        }

        const DURATION_MS = 5000;
        await new Promise<void>((resolve) => {
          const start = performance.now();
          let last = start;
          let frame = 0;
          const tick = (now: number) => {
            const dt = now - last;
            last = now;
            if (now - start > 300) samples.push(1000 / Math.max(dt, 0.01)); // warm-up skip
            // Fan-out + settle: staggered sine drift, mirroring discard flights.
            for (let i = 0; i < cards.length; i++) {
              const phase = (i / cards.length) * Math.PI * 2;
              const t = (now - start) / 1000;
              const x = Math.cos(phase + t * 2.4) * (120 + 40 * Math.sin(t + phase));
              const y = Math.sin(phase * 2 + t * 3.1) * 160 - 60;
              const r = ((frame * 2 + i * 12) % 40) - 20;
              cards[i].style.transform =
                `translate3d(${x.toFixed(1)}px,${y.toFixed(1)}px,0) rotate(${r.toFixed(1)}deg)`;
              cards[i].style.opacity = (0.55 + 0.45 * Math.abs(Math.sin(t + phase))).toFixed(3);
            }
            frame++;
            if (now - start < DURATION_MS) requestAnimationFrame(tick);
            else resolve();
          };
          requestAnimationFrame(tick);
        });

        // Rapid state churn WITHOUT layout: toggle a transform class 60x.
        for (let i = 0; i < 60; i++) {
          stage.style.transform = `translate3d(0,${i % 2 === 0 ? 0 : 1}px,0)`;
          // eslint-disable-next-line no-await-in-loop
          await new Promise((r) => requestAnimationFrame(r));
        }

        stage.remove();
        taskObserver.disconnect();
        clsObserver.disconnect();

        samples.sort((a, b) => a - b);
        const avg = samples.reduce((s, v) => s + v, 0) / Math.max(samples.length, 1);
        const p1 = samples[Math.floor(samples.length * 0.01)] ?? avg;
        return { avgFps: avg, p1Fps: p1, frames: samples.length, longTasks, cls };
      },
      { budget: LONG_TASK_BUDGET_MS },
    );

    // Attach raw numbers to the report for before/after tracking.
    console.log(
      `[perf-fps] avg=${metrics.avgFps.toFixed(1)} p1=${metrics.p1Fps.toFixed(1)} ` +
        `frames=${metrics.frames} longTasks=${metrics.longTasks.length} cls=${metrics.cls.toFixed(4)}`,
    );

    expect(metrics.frames).toBeGreaterThan(200);
    expect(metrics.avgFps, `avg FPS ${metrics.avgFps.toFixed(1)} < ${AVG_FPS_FLOOR}`).toBeGreaterThanOrEqual(
      AVG_FPS_FLOOR,
    );
    expect(metrics.p1Fps, `p1 FPS ${metrics.p1Fps.toFixed(1)} < ${P1_FPS_FLOOR}`).toBeGreaterThanOrEqual(
      P1_FPS_FLOOR,
    );
    expect(
      metrics.longTasks.length,
      `long tasks >${LONG_TASK_BUDGET_MS}ms: ${metrics.longTasks.length} (max ${LONG_TASK_MAX})`,
    ).toBeLessThanOrEqual(LONG_TASK_MAX);
    expect(metrics.cls, `CLS ${metrics.cls.toFixed(4)} > ${CLS_MAX}`).toBeLessThanOrEqual(CLS_MAX);
  });
});
