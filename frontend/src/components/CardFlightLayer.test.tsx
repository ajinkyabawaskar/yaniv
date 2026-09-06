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
import TableCanvas from './TableCanvas';
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

test('travel tilt is deterministic per flight and stays within ±5° of landing rotation', () => {
  for (const key of ['discard-c1-123', 'opp-draw-u1-456', 'draw-x-0']) {
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
