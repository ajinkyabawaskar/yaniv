/**
 * Pins the emote handoff GameView owns: a broadcast arriving on the room topic must
 * reach the table's playReaction and draw a banner. TableCanvas's own rendering is
 * covered in TableCanvas.reactions.test.tsx; this covers the socket -> ref wiring
 * (subscription topic, JSON parsing, ref availability) that only exists here.
 */
import React, { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import GameView from './GameView';

(global as any).IS_REACT_ACT_ENVIRONMENT = true;

type MsgCallback = (message: { body: string }) => void;
const subs: Record<string, MsgCallback> = {};
const sent: Array<{ destination: string; body: any }> = [];

jest.mock('../contexts/StompContext', () => ({
  useStomp: () => ({
    isConnected: true,
    client: null,
    send: (destination: string, body: any) => {
      sent.push({ destination, body });
    },
    subscribe: (destination: string, callback: MsgCallback) => {
      subs[destination] = callback;
      return { unsubscribe: jest.fn() };
    },
    flushPending: jest.fn(),
  }),
}));

jest.mock('../utils/api', () => ({
  gameApi: {
    getGameDetails: () =>
      Promise.resolve({
        gameId: 'g1',
        roomCode: 'ABC',
        status: 'WAIT_FOR_TURN',
        players: [
          { userId: 'u1', displayName: 'Ari', isHost: true },
          { userId: 'u2', displayName: 'Bob', isHost: false },
        ],
        maxPlayers: 6,
        hostUserId: 'u1',
      }),
  },
}));

jest.mock('../utils/sound', () => ({
  playTurnChangeSound: jest.fn(),
  playYourTurnSound: jest.fn(),
  isSoundEnabled: () => false,
  setSoundEnabled: jest.fn(),
  setupAudioUnlock: jest.fn(),
}));

jest.mock('../utils/backgroundMusic', () => ({
  isBgMusicEnabled: () => false,
  setBgMusicEnabled: jest.fn(),
  setupBgMusicUnlock: jest.fn(),
  preloadBgMusic: jest.fn(),
}));

beforeAll(() => {
  window.matchMedia =
    window.matchMedia ||
    ((query: string) =>
      ({
        matches: false,
        media: query,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      } as any));
  (global as any).ResizeObserver =
    (global as any).ResizeObserver ||
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
});

beforeEach(() => {
  localStorage.setItem('userId', 'u1');
  for (const k of Object.keys(subs)) delete subs[k];
  sent.length = 0;
});

afterEach(() => {
  document.body.innerHTML = '';
  localStorage.clear();
});

let container: HTMLDivElement;
let root: Root;

const mountView = async () => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root.render(<GameView gameId="g1" roomCode="ABC" onExit={() => {}} />);
  });
};

const pushGameState = (overrides: Record<string, unknown> = {}) => {
  const cb = subs['/user/queue/room/g1/game-state'];
  if (!cb) throw new Error('game-state subscription was never created');
  act(() => {
    cb({
      body: JSON.stringify({
        gameId: 'g1',
        roomCode: 'ABC',
        currentState: 'WAIT_FOR_TURN',
        currentTurnPlayerId: 'u2',
        roundNumber: 1,
        scores: { u1: 0, u2: 0 },
        playerNames: { u1: 'Ari', u2: 'Bob' },
        players: [
          { userId: 'u1', displayName: 'Ari', isHost: true },
          { userId: 'u2', displayName: 'Bob', isHost: false },
        ],
        eliminatedPlayers: [],
        deckCount: 30,
        topDiscardCard: null,
        topDiscardCards: [],
        hand: [],
        drawableDiscardCards: [],
        opponentCounts: { u2: 5 },
        ...overrides,
      }),
    });
  });
};

const pushReaction = (event: Record<string, unknown>) => {
  const cb = subs['/topic/room/g1/reactions'];
  if (!cb) throw new Error('reactions subscription was never created');
  act(() => {
    cb({ body: JSON.stringify(event) });
  });
};

const banners = () => Array.from(container.querySelectorAll('.emote-log-row'));

test('a broadcast on the room topic draws a banner on the table', async () => {
  await mountView();
  pushGameState();
  expect(banners()).toHaveLength(0);

  pushReaction({
    id: 'rct_1',
    type: 'TAUNT',
    fromUserId: 'u2',
    fromDisplayName: 'Bob',
    targetUserId: 'u2',
    text: 'halke ho jao',
  });

  expect(banners()).toHaveLength(1);
  expect(container.querySelector('.emote-msg')!.textContent).toBe('halke ho jao');
  act(() => root.unmount());
});

test('the strip sends love at the turn holder over the socket', async () => {
  await mountView();
  pushGameState({ currentTurnPlayerId: 'u2' });

  const love = container.querySelector('.reaction-strip .reaction-love') as HTMLButtonElement;
  expect(love).not.toBeNull();
  expect(love.disabled).toBe(false);
  act(() => {
    love.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });

  expect(sent).toContainEqual({
    destination: '/app/room/g1/reaction',
    body: { type: 'LOVE', targetUserId: 'u2' },
  });

  const mock = container.querySelector('.reaction-strip .reaction-mock') as HTMLButtonElement;
  expect(mock).not.toBeNull();
  expect(mock.disabled).toBe(false);
  act(() => {
    mock.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });

  expect(sent).toContainEqual({
    destination: '/app/room/g1/reaction',
    body: { type: 'MOCK', targetUserId: 'u2' },
  });

  for (const [cls, type] of [
    ['.reaction-shock', 'SHOCK'],
    ['.reaction-flex', 'FLEX'],
  ] as const) {
    const btn = container.querySelector(`.reaction-strip ${cls}`) as HTMLButtonElement;
    expect(btn).not.toBeNull();
    expect(btn.disabled).toBe(false);
    act(() => {
      btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(sent).toContainEqual({
      destination: '/app/room/g1/reaction',
      body: { type, targetUserId: 'u2' },
    });
  }
  act(() => root.unmount());
});
