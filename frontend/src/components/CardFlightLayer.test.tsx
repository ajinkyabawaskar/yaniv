/**
 * Card flight animations: discards fly hand → discard pile before the server
 * send goes out, so the real hand/pile arrays only change after the animation
 * lands. These pin both halves of that contract:
 *  - with motion allowed, starting a discard renders a flight overlay and
 *    defers onDiscard (no premature state change);
 *  - with prefers-reduced-motion, there is no flight and the send goes out
 *    at once (the overlay must never gate the game).
 */
import React, { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import TableCanvas, { fanLandingSlots } from './TableCanvas';
import CardFlightLayer from './CardFlightLayer';
import { travelTiltFor } from './CardFlightLayer';

(global as any).IS_REACT_ACT_ENVIRONMENT = true;

const card = { id: 'c1', rank: 'FIVE', suit: 'HEARTS' };

const baseProps = {
  hand: [card],
  topCard: null,
  topDiscardCards: [],
  isPlayerTurn: true,
  currentTurnPlayerId: 'u1',
  deckCount: 30,
  onCallYaniv: () => {},
  currentUserId: 'u1',
  playerNames: { u1: 'Ari', u2: 'Bob' },
  opponents: [],
};

const mockMatchMedia = (matches: boolean) => {
  window.matchMedia = ((query: string) => ({
    matches,
    media: query,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as any;
};

// jsdom lays nothing out, so every rect is zero and the table would take its
// no-measurable-anchor fallback (send at once). Give the anchors real rects
// so the flight path itself is what runs.
const mockRects = () => {
  const rect = {
    left: 100,
    top: 200,
    width: 88,
    height: 124,
    right: 188,
    bottom: 324,
    x: 100,
    y: 200,
    toJSON: () => {},
  };
  return jest.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(rect as DOMRect);
};

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => {
    root.unmount();
  });
  container.remove();
  jest.restoreAllMocks();
});

const selectFirstHandCard = () => {
  const handCard = container.querySelector('.hand-card') as HTMLElement;
  expect(handCard).not.toBeNull();
  act(() => {
    handCard.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
};

const clickDrawPile = () => {
  const drawPile = container.querySelector('.draw-pile-column') as HTMLElement;
  expect(drawPile).not.toBeNull();
  act(() => {
    drawPile.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
};

test('discard flies hand → discard pile and defers the server send until landing', () => {
  mockMatchMedia(false);
  mockRects();
  const onDiscard = jest.fn();
  act(() => {
    root.render(<TableCanvas {...baseProps} onDiscard={onDiscard} />);
  });

  selectFirstHandCard();
  clickDrawPile();

  // The flight overlay is portalled to document.body (outside the table's
  // perspective container), and face-up: the discarded card's face shows...
  const flight = document.body.querySelector('[data-testid="card-flight"]') as HTMLElement | null;
  expect(flight).not.toBeNull();
  expect(flight?.getAttribute('data-face')).toBe('up');
  // ...explicitly layered above the table chrome...
  expect(flight?.style.zIndex).toBe('10');
  // ...and the real state change waits for it to land.
  expect(onDiscard).not.toHaveBeenCalled();
});

test('deck-draw reveal flight turns over mid-travel instead of snapping on landing', () => {
  mockMatchMedia(false);
  const onFlightComplete = jest.fn();
  const box = { x: 100, y: 200, width: 88, height: 124 };
  act(() => {
    root.render(
      <CardFlightLayer
        flights={[
          {
            key: 'draw-c9-123',
            from: box,
            to: { x: 300, y: 500, width: 88, height: 124 },
            faceUp: false,
            faceImageSrc: '/cards/5_of_hearts.svg',
            faceAlt: 'FIVE of HEARTS',
            revealFace: true,
          },
        ]}
        onFlightComplete={onFlightComplete}
      />
    );
  });

  // Starts as a reveal (not a plain back): both faces ride along so the
  // crossfade at the edge-on point has something to show.
  const flight = document.body.querySelector('[data-testid="card-flight"]') as HTMLElement | null;
  expect(flight).not.toBeNull();
  expect(flight?.getAttribute('data-face')).toBe('reveal');
  expect(flight?.querySelector('.card-flip-inner')).not.toBeNull();
  expect(flight?.querySelector('.card-flight-back')).not.toBeNull();
  expect(flight?.querySelector('img[alt="FIVE of HEARTS"]')).not.toBeNull();
  // The state handoff still waits for the landing, not the flip.
  expect(onFlightComplete).not.toHaveBeenCalled();
});

test('discard sets land fanned on the pile: one slot per card, centered, fan-matched rotation', () => {
  const pileBox = { x: 100, y: 200, width: 176, height: 124 };
  const size = { width: 88, height: 124 };

  // Single discard: dead center, no tilt — same as before.
  const single = fanLandingSlots(pileBox, size, 1);
  expect(single).toHaveLength(1);
  expect(single[0].x).toBe(100 + 176 / 2 - 88 / 2);
  expect(single[0].rotation).toBe(0);

  // Three discards: spread around the pile center with the fan's own
  // 3°-per-offset rotation, so each flight docks where its pile card renders.
  const three = fanLandingSlots(pileBox, size, 3);
  expect(three).toHaveLength(3);
  const centerX = 100 + 176 / 2;
  expect(three[0].x + 44).toBeLessThan(three[1].x + 44);
  expect(three[1].x + 44).toBeLessThan(three[2].x + 44);
  // Symmetric about the pile center…
  expect(three[0].x + three[2].x).toBeCloseTo(2 * (centerX - 44), 5);
  expect(three[1].x).toBeCloseTo(centerX - 44, 5);
  // …with matching fan tilt (-3°, 0, 3°).
  expect(three.map((s) => s.rotation)).toEqual([-3, 0, 3]);

  // Five discards: near-flat fan (1° per offset), all card-sized.
  const five = fanLandingSlots(pileBox, size, 5);
  expect(five.map((s) => s.rotation)).toEqual([-2, -1, 0, 1, 2]);
  for (const slot of five) {
    expect(slot.width).toBe(88);
    expect(slot.height).toBe(124);
  }
});

test('travel tilt is deterministic per flight and stays within ±5° of landing rotation', () => {  for (const key of ['discard-c1-123', 'opp-draw-u1-456', 'draw-x-0']) {
    const first = travelTiltFor(key, 8);
    expect(travelTiltFor(key, 8)).toBe(first);
    expect(first).toBeGreaterThanOrEqual(3);
    expect(first).toBeLessThanOrEqual(13);
  }
});

test('prefers-reduced-motion skips the flight and sends at once', () => {
  mockMatchMedia(true);
  mockRects();
  const onDiscard = jest.fn();
  act(() => {
    root.render(<TableCanvas {...baseProps} onDiscard={onDiscard} />);
  });

  selectFirstHandCard();
  clickDrawPile();

  expect(document.body.querySelector('[data-testid="card-flight"]')).toBeNull();
  expect(onDiscard).toHaveBeenCalledWith(['c1'], 'DECK', undefined);
});

/**
 * Table visibility: every player watches every turn. An opponent's action
 * arrives as a server push (no actor/draw-source fields), and the table
 * reconstructs it from diffs — discards fly seat → pile face-up while the
 * pile holds its previous render, deck draws fly card-back deck → seat.
 */
const oppSeat = (userId: string, name: string, isTurn: boolean, isMe = false) => ({
  userId,
  displayName: name,
  score: 0,
  isHost: false,
  isCurrentTurn: isTurn,
  isEliminated: false,
  cardCount: 5,
  isCurrentPlayer: isMe,
});

const watcherProps = {
  hand: [card],
  topCard: null,
  topDiscardCards: [] as typeof card[],
  drawableDiscardCards: [] as typeof card[],
  isPlayerTurn: false,
  currentTurnPlayerId: 'u1',
  deckCount: 30,
  onDiscard: () => {},
  onCallYaniv: () => {},
  currentUserId: 'u2',
  playerNames: { u1: 'Ari', u2: 'Bob' },
  opponents: [oppSeat('u1', 'Ari', true), oppSeat('u2', 'Bob', false, true)],
};

const flights = () => Array.from(document.body.querySelectorAll('[data-testid="card-flight"]'));

test("opponent's discard + deck draw is visible: discards fly seat → pile face-up, draw flies card-back", () => {
  mockMatchMedia(false);
  mockRects();
  act(() => {
    root.render(<TableCanvas {...watcherProps} />);
  });
  expect(flights()).toHaveLength(0);

  const discarded = { id: 'd1', rank: 'KING', suit: 'SPADES' };
  act(() => {
    root.render(
      <TableCanvas
        {...watcherProps}
        topDiscardCards={[discarded]}
        drawableDiscardCards={[discarded]}
        currentTurnPlayerId="u2"
        isPlayerTurn={true}
        deckCount={29}
        opponents={[oppSeat('u1', 'Ari', false), oppSeat('u2', 'Bob', true, true)]}
      />
    );
  });

  // Two flights: the discard (face-up) and the deck draw (card-back down).
  const seen = flights();
  expect(seen).toHaveLength(2);
  expect(seen.map((f) => f.getAttribute('data-face')).sort()).toEqual(['down', 'up']);
  // The pile holds its previous render until the discards land.
  expect(container.querySelector('.discard-empty-box')).not.toBeNull();
});

test("opponent's pile pickup is visible and revealed: the exact picked card flies face-up", () => {
  mockMatchMedia(false);
  mockRects();
  const onPile = { id: 'p1', rank: 'FIVE', suit: 'HEARTS' };
  act(() => {
    root.render(
      <TableCanvas {...watcherProps} topDiscardCards={[onPile]} drawableDiscardCards={[onPile]} />
    );
  });
  expect(flights()).toHaveLength(0);

  const freshDiscard = { id: 'd2', rank: 'QUEEN', suit: 'DIAMONDS' };
  act(() => {
    root.render(
      <TableCanvas
        {...watcherProps}
        topDiscardCards={[freshDiscard]}
        drawableDiscardCards={[freshDiscard]}
        currentTurnPlayerId="u2"
        isPlayerTurn={true}
      />
    );
  });

  // No card-back flight (deck untouched): the picked card travels revealed.
  const seen = flights();
  expect(seen).toHaveLength(2);
  expect(seen.every((f) => f.getAttribute('data-face') === 'up')).toBe(true);
  expect(document.body.querySelector('img[alt="FIVE of HEARTS"]')).not.toBeNull();
  expect(document.body.querySelector('img[alt="QUEEN of DIAMONDS"]')).not.toBeNull();
});

test('multi-card discards still fly when the new combo buries a multi-drawable top', () => {
  mockMatchMedia(false);
  mockRects();
  // Previous top is a pair: BOTH cards drawable. The new 3-discard buries
  // them, so the push cannot say which (if any) was picked up — the ambiguous
  // draw is skipped, but the three discards must still fly seat → pile.
  const prevTop = [
    { id: 'p1', rank: 'FIVE', suit: 'HEARTS' },
    { id: 'p2', rank: 'FIVE', suit: 'SPADES' },
  ];
  act(() => {
    root.render(
      <TableCanvas {...watcherProps} topDiscardCards={prevTop} drawableDiscardCards={prevTop} />
    );
  });
  expect(flights()).toHaveLength(0);

  const fresh = [
    { id: 'd1', rank: 'KING', suit: 'SPADES' },
    { id: 'd2', rank: 'KING', suit: 'HEARTS' },
    { id: 'd3', rank: 'KING', suit: 'DIAMONDS' },
  ];
  act(() => {
    root.render(
      <TableCanvas
        {...watcherProps}
        topDiscardCards={fresh}
        drawableDiscardCards={fresh}
        currentTurnPlayerId="u2"
        isPlayerTurn={true}
      />
    );
  });

  // Deck untouched and pickup ambiguous → no draw flight; three face-up
  // discards fly and the pile holds its previous render until they land.
  const seen = flights();
  expect(seen).toHaveLength(3);
  expect(seen.every((f) => f.getAttribute('data-face') === 'up')).toBe(true);
  expect(container.querySelectorAll('.discard-fan-card')).toHaveLength(2);
});
