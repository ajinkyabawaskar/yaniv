/**
 * The table is unmounted between rounds (GameView renders it only while `gameStarted`),
 * so these pin the rule that it carries no emote history across that boundary: an emote
 * is drawn when GameView hands it over, and never at any other time. They also pin how
 * an emote reads, since the words are the whole point of one.
 */
import React, { act, createRef } from 'react';
import { createRoot, Root } from 'react-dom/client';
import TableCanvas, { TableCanvasHandle } from './TableCanvas';
import type { ReactionEvent } from '../stores/gameStore';

(global as any).IS_REACT_ACT_ENVIRONMENT = true;

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

/** Aimed at someone else, the way love and rage are. */
const aimed = (id: string, type: 'LOVE' | 'RAGE', text: string): ReactionEvent => ({
  id,
  type,
  fromUserId: 'u1',
  fromDisplayName: 'Ari',
  targetUserId: 'u2',
  text,
});

/** Thrown at the table: the server aims it back at its own sender. */
const taunt = (id: string): ReactionEvent => ({
  id,
  type: 'TAUNT',
  fromUserId: 'u1',
  fromDisplayName: 'Ari',
  targetUserId: 'u1',
  text: 'halke ho jao',
});

const baseProps = {
  hand: [],
  topCard: null,
  isPlayerTurn: false,
  currentTurnPlayerId: null,
  deckCount: 30,
  onDiscard: () => {},
  onCallYaniv: () => {},
  onContestYaniv: () => {},
  currentUserId: 'u1',
  playerNames: { u1: 'Ari', u2: 'Bob' },
};

let container: HTMLDivElement;
let root: Root;
let ref: React.RefObject<TableCanvasHandle>;

const mount = () => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  ref = createRef<TableCanvasHandle>();
  act(() => {
    root.render(<TableCanvas ref={ref} {...baseProps} />);
  });
};

const play = (event: ReactionEvent) => act(() => ref.current!.playReaction(event));
const unmount = () => {
  act(() => root.unmount());
  container.remove();
};
const banners = () => Array.from(container.querySelectorAll('.emote-log-row'));
const readBanner = (i = 0) => {
  const row = banners()[i] as HTMLElement;
  return {
    type: row.dataset.type,
    emoji: row.querySelector('.emote-emoji')!.textContent,
    who: row.querySelector('.emote-from')!.textContent,
    text: row.querySelector('.emote-msg')!.textContent,
  };
};

afterEach(() => {
  document.body.innerHTML = '';
});

test('an emote handed to the table is shown with its words', () => {
  mount();
  expect(banners()).toHaveLength(0);
  play(aimed('a', 'LOVE', 'thanks for the card(s)'));
  expect(banners()).toHaveLength(1);
  expect(readBanner().text).toBe('thanks for the card(s)');
  unmount();
});

test('an aimed emote names both players so the room knows who it is for', () => {
  mount();
  play(aimed('a', 'RAGE', 'jaldi khel l***'));
  expect(readBanner().who).toBe('Ari → Bob');
  expect(readBanner().type).toBe('rage');
  expect(readBanner().emoji).toBe('😡');
  unmount();
});

test('a taunt names only its sender, because it is thrown at the whole table', () => {
  mount();
  play(taunt('a'));
  expect(readBanner().who).toBe('Ari');
  expect(readBanner().type).toBe('taunt');
  unmount();
});

test('a table mounted for a new round shows nothing until it is handed an emote', () => {
  mount();
  play(taunt('a'));
  expect(banners()).toHaveLength(1);
  unmount();

  // A new round. Whatever the last one said is gone with the component that drew it.
  mount();
  expect(banners()).toHaveLength(0);
  unmount();
});

test('an emote clears itself once its animation is over', () => {
  jest.useFakeTimers();
  mount();
  play(taunt('a'));
  expect(banners()).toHaveLength(1);
  act(() => {
    jest.advanceTimersByTime(3000);
  });
  expect(banners()).toHaveLength(0);
  unmount();
  jest.useRealTimers();
});

test('a table-wide pile-on keeps every emote until its own TTL clears it', () => {
  mount();
  act(() => {
    ['a', 'b', 'c', 'd', 'e'].forEach((id) => ref.current!.playReaction(taunt(id)));
  });
  expect(banners()).toHaveLength(5);
  unmount();
});
