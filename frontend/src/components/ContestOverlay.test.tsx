/**
 * Yaniv reveal overlay: one shared popup for every seat, caller included.
 * There is no contest action — the 5-second window simply auto-reveals —
 * so the panel is an announcement (caller, countdown, waiting note), never
 * gated on who called.
 */
import React, { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import TableCanvas from './TableCanvas';

(global as any).IS_REACT_ACT_ENVIRONMENT = true;

// jsdom has no real audio (play/pause log "Not implemented" noise), so stub the
// file-based sound helpers TableCanvas drives during the contest window.
jest.mock('../utils/sound', () => ({
  playAsafSound: jest.fn(),
  stopAsafSound: jest.fn(),
  playWaitingForAsafSound: jest.fn(),
  stopWaitingForAsafSound: jest.fn(),
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
});

const base: any = {
  hand: [],
  topCard: null,
  topDiscardCards: [],
  isPlayerTurn: false,
  currentTurnPlayerId: 'u1',
  deckCount: 30,
  onDiscard: () => {},
  onCallYaniv: () => {},
  currentUserId: 'u2',
  playerNames: { u1: 'Ari', u2: 'Bob' },
  opponents: [],
  yanivCallerId: 'u1',
  yanivCallerName: 'Ari',
  yanivCalledAt: Date.now(),
  yanivContestTimerSeconds: 5,
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

test('non-caller sees the shared popup with no contest button', () => {
  act(() => {
    root.render(<TableCanvas {...base} />);
  });

  expect(container.querySelector('.yaniv-contest-overlay')).not.toBeNull();
  expect(container.querySelector('.contest-title')?.textContent).toBe('YANIV!');
  expect(container.querySelector('.contest-caller-name')?.textContent).toBe('Ari');
  expect(container.querySelector('.contest-timer-label')?.textContent).toBe('until reveal');
  expect(container.querySelector('.contest-btn')).toBeNull();
  expect(container.querySelector('.contest-waiting-message')).not.toBeNull();
});

test('caller sees the same popup, not a different one', () => {
  act(() => {
    root.render(<TableCanvas {...base} currentUserId="u1" />);
  });

  expect(container.querySelector('.yaniv-contest-overlay')).not.toBeNull();
  expect(container.querySelector('.contest-btn')).toBeNull();
  expect(container.querySelector('.contest-waiting-message')?.textContent).toContain('Revealing');
});
