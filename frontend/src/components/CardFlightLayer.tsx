import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { motion } from 'framer-motion';
import './CardFlightLayer.css';

/**
 * One measured endpoint of a card flight, in viewport (client) coordinates.
 * Measured with getBoundingClientRect at flight start so the overlay is
 * independent of the table's responsive layout — the flight is painted in a
 * fixed-position layer and never participates in flow.
 */
export interface FlightPoint {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface CardFlightSpec {
  /** Stable key for this flight (card id + timestamp). */
  key: string;
  from: FlightPoint;
  to: FlightPoint;
  /** false → card back (draw pile). true → revealed face (discard pickup). */
  faceUp: boolean;
  /** Resolved face image URL, required when faceUp. */
  faceImageSrc?: string;
  faceAlt?: string;
  /** End rotation in degrees (fan tilt). Defaults to 0. */
  rotation?: number;
  /** Stagger delay before the flight starts, in seconds. */
  delay?: number;
}

interface CardFlightLayerProps {
  flights: CardFlightSpec[];
  /** Fired once per flight when its motion completes (or is skipped). */
  onFlightComplete: (key: string) => void;
  /** Seconds for a single flight. Defaults to 0.5. */
  duration?: number;
}

const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Organic in-travel tilt, deterministic per flight: a stable hash of the
 * flight key mapped to [-5deg, +5deg], added to the landing rotation. Stable
 * matters — Math.random() in render would re-roll on every re-render (and
 * StrictMode double-render), visibly jittering the flight mid-travel.
 */
export const travelTiltFor = (key: string, endRotation: number): number => {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) | 0;
  return endRotation + ((Math.abs(h) % 1000) / 1000) * 10 - 5;
};

function SingleFlight({
  flight,
  duration,
  zIndex,
  onFlightComplete,
}: {
  flight: CardFlightSpec;
  duration: number;
  /** Paint order within the overlay: later-started flights glide above. */
  zIndex: number;
  onFlightComplete: (key: string) => void;
}) {
  const { from, to } = flight;
  const rotation = flight.rotation ?? 0;
  // Stable for the flight's lifetime (key never changes for a mounted flight).
  const tilt = travelTiltFor(flight.key, rotation);
  const endScale = to.width / from.width;

  // Reduced motion: no one sees the flight, so the state handoff it gates
  // must not wait on it. Report completion on mount. Deps are complete, and
  // the parent's completion handlers are idempotent (a repeat key is a no-op),
  // so re-runs are safe.
  useEffect(() => {
    if (prefersReducedMotion()) {
      onFlightComplete(flight.key);
    }
  }, [flight.key, onFlightComplete]);

  if (prefersReducedMotion()) {
    return null;
  }

  const dx = to.x + to.width / 2 - (from.x + from.width / 2);
  const dy = to.y + to.height / 2 - (from.y + from.height / 2);

  return (
    <motion.div
      className="card-flight"
      data-testid="card-flight"
      data-flight-key={flight.key}
      data-face={flight.faceUp ? 'up' : 'down'}
      style={{ width: from.width, height: from.height, left: from.x, top: from.y, zIndex }}
      initial={{ x: 0, y: 0, rotate: 0, scale: 1, opacity: 1 }}
      animate={{
        x: dx,
        y: dy,
        // Travel tilt mid-flight, settling into the landing rotation —
        // organic rather than rigidly programmatic.
        rotate: [0, tilt, rotation],
        // Picked-up lift, docking back to rest on landing. Discard-pickup
        // cards are already face-up, so there is deliberately no flip here:
        // flipping would snap orientation instead of preserving it.
        scale: [1, 1.08, endScale],
        // Visual persistence: a breath of transparency at peak velocity,
        // back to solid on settle.
        opacity: [1, 0.95, 1],
      }}
      transition={{
        // Weighted travel, softly damped so the card settles without
        // high-frequency dock jitter.
        type: 'spring',
        stiffness: 300,
        damping: 34,
        delay: flight.delay ?? 0,
        // Micro-interactions ease out over the flight duration.
        scale: { duration, ease: [0.25, 1, 0.5, 1] },
        opacity: { duration, ease: 'easeOut' },
      }}
      onAnimationComplete={() => onFlightComplete(flight.key)}
    >
      {flight.faceUp && flight.faceImageSrc ? (
        <img src={flight.faceImageSrc} alt={flight.faceAlt ?? 'card'} className="card-img" draggable={false} />
      ) : (
        <div className="card-flight-back" aria-hidden="true">
          <div className="card-back-pattern">
            <div className="card-back-emblem">♠</div>
          </div>
        </div>
      )}
    </motion.div>
  );
}

/**
 * Dedicated top-level mount for the flight overlay. Portalled here (not left
 * in the table tree) so no ancestor — its perspective, overflow, filters, or
 * stacking context — can clip, trap, or displace the flights. A plain body
 * child: fixed descendants position to the viewport exactly as measured.
 */
const getAnimationRoot = (): HTMLElement | null => {
  if (typeof document === 'undefined' || !document.body) return null;
  let root = document.getElementById('animation-root');
  if (!root) {
    root = document.createElement('div');
    root.id = 'animation-root';
    document.body.appendChild(root);
  }
  return root;
};

/**
 * Fixed-position overlay that flies card visuals between two measured points.
 *
 * Purely cosmetic: it never reads game state and never mutates it. Callers
 * start a flight, keep their rendered state frozen, and apply the real update
 * in onFlightComplete — so hands/piles change only after (or in sync with)
 * the animation landing, with no layout jump and no premature render.
 *
 * Portalled to #animation-root on purpose: `.table-canvas-root` carries
 * `perspective: 1200px`, and a non-none perspective makes an ancestor the
 * containing block for `position: fixed` descendants. Rendered inside the
 * table, `inset: 0` would mean the root's box while every flight coordinate
 * is measured in viewport coordinates — displacing every flight. On the
 * top-level mount, fixed truly means viewport and the coordinates line up.
 */
export default function CardFlightLayer({ flights, onFlightComplete, duration = 0.5 }: CardFlightLayerProps) {
  if (flights.length === 0) return null;
  const mount = getAnimationRoot();
  if (!mount) return null;
  return createPortal(
    <div className="card-flight-layer" aria-hidden="true">
      {flights.map((flight, i) => (
        <SingleFlight
          key={flight.key}
          flight={flight}
          duration={duration}
          // Explicit paint order: a later-started flight always glides above
          // earlier ones, hands, piles and table chrome (the layer itself
          // already sits above the whole UI at z-200 in #animation-root).
          zIndex={10 + i}
          onFlightComplete={onFlightComplete}
        />
      ))}
    </div>,
    mount
  );
}
