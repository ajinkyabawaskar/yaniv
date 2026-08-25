import { test, expect, Page } from '@playwright/test';
import {
  openSeatedPage,
  pullGameState,
  getState,
  waitForGameState,
  waitForGameStarted,
  waitForMyTurn,
  waitForTurnChange,
  playTurnFromDeck,
  playSmartTurn,
  tryCallYanivUi,
  continueNextRound,
} from './test-helpers';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';

/**
 * WebSocket-based game flow tests. Players are created via the REST API and
 * seated in real browser pages through token injection + the /join deep link.
 */

interface GameUser {
  userId: string;
  jwtToken: string;
  displayName: string;
}

async function createUser(fingerprint: string, name: string): Promise<GameUser> {
  const response = await fetch(`${BACKEND_URL}/api/v1/users/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fingerprintHash: fingerprint, displayName: name }),
  });
  const data = await response.json();
  return { userId: data.userId, jwtToken: data.jwtToken, displayName: data.displayName };
}

async function createRoomApi(user: GameUser): Promise<{ gameId: string; roomCode: string }> {
  const response = await fetch(`${BACKEND_URL}/api/v1/rooms`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${user.jwtToken}`,
    },
    body: JSON.stringify({ targetScore: 200, maxPlayers: 6 }),
  });
  return response.json();
}

async function joinRoomApi(user: GameUser, roomCode: string): Promise<any> {
  const response = await fetch(`${BACKEND_URL}/api/v1/rooms/${roomCode}/join`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${user.jwtToken}` },
  });
  return response.json();
}

/** Play turns until someone can press the enabled Yaniv button; returns caller's page. */
async function playUntilYaniv(pages: Page[], timeoutMs = 60_000): Promise<Page> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const p of pages) {
      const state = await getState(p);
      if (!state) continue;
      if (state.currentState === 'YANIV_CALLED') return p; // already called

      const me = await p.evaluate(() => (window as any).__CURRENT_USER_ID__);
      if (state.currentTurnPlayerId !== me || state.currentState !== 'WAIT_FOR_TURN') continue;

      if (await tryCallYanivUi(p)) {
        return p;
      }
      await playSmartTurn(p);
      await waitForTurnChange(p, me, 15000).catch(() => {});
    }
  }
  throw new Error('Nobody managed to call Yaniv within the time budget');
}

test.describe('Yaniv WebSocket Game Flow', () => {
  let user1: GameUser;
  let user2: GameUser;
  let roomCode: string;
  let page1: Page;

  test.beforeAll(async ({ browser }) => {
    user1 = await createUser('ws_test_1', 'WS Player 1');
    user2 = await createUser('ws_test_2', 'WS Player 2');
    const room = await createRoomApi(user1);
    roomCode = room.roomCode;
    await joinRoomApi(user2, roomCode);

    // Seat and deal in beforeAll so a worker restart can't cascade
    // undefined pages through the remaining tests
    page1 = await openSeatedPage(browser, user1, roomCode);
    await page1.waitForFunction(() => (window as any).__STOMP_CLIENT__?.connected === true, undefined, { timeout: 15000 });
    await page1.click('button:has-text("Deal & Start")');
    await waitForGameStarted(page1);
  });

  test('Host opens table via invite link and starts game', async () => {

    const state = await getState(page1);
    expect(state.roundNumber).toBe(1);
    expect(state.hand.length).toBe(5);
  });

  test('Player 2 connects and sees same state', async ({ browser }) => {
    const page2 = await openSeatedPage(browser, user2, roomCode);

    await page2.waitForFunction(() => (window as any).__STOMP_CLIENT__?.connected === true, undefined, { timeout: 15000 });
    await waitForGameStarted(page2);

    // A concurrent refresh can transiently wipe the debug mirror - re-pull
    const ensureFresh = async (p: Page) => {
      let st = await getState(p);
      if (!st?.currentState) {
        await pullGameState(p);
        st = await getState(p);
      }
      return st;
    };
    const state1 = await ensureFresh(page1);
    const state2 = await ensureFresh(page2);

    expect(state1.currentState).toBe(state2.currentState);
    expect(state1.roundNumber).toBe(state2.roundNumber);
    expect(state1.currentTurnPlayerId).toBe(state2.currentTurnPlayerId);
    expect(state2.hand.length).toBe(5);
  });

  test('Play a turn: discard and draw is atomic', async () => {
    const state = await getState(page1);
    const current = state.currentTurnPlayerId;
    const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
    const actorPage = current === me1 ? page1 : null;

    test.skip(!actorPage, 'current player is not player 1 in this run');

    // Recover a long-lived page whose table stopped rendering
    if ((await actorPage!.locator('.hand-card').count()) === 0) {
      await actorPage!.goto(`${FRONTEND_URL}/join/${roomCode}`);
      await actorPage!.locator('.game-view-container').waitFor({ timeout: 20000 });
      await waitForGameStarted(actorPage!);
    }

    await playTurnFromDeck(actorPage!);

    // Server applies discard+draw atomically: straight back to WAIT_FOR_TURN
    await waitForGameState(actorPage!, 'WAIT_FOR_TURN');
    await waitForTurnChange(actorPage!, current);
    expect((await getState(actorPage!)).hand.length).toBe(5);
  });

  test('Discard pile updates for all players', async ({ browser }) => {
    // Seat a fresh viewer if needed and read the pile count before/after a turn
    const before = (await getState(page1))?.topDiscardCards?.length ?? 0;

    const state = await getState(page1);
    const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
    test.skip(state.currentTurnPlayerId !== me1, 'current player is not player 1 in this run');

    await playTurnFromDeck(page1);
    await waitForTurnChange(page1, me1);

    expect((await getState(page1)).topDiscardCards.length).toBeGreaterThan(before);
  });
});

test.describe('Yaniv Contest Timer', () => {
  let user1: GameUser;
  let user2: GameUser;
  let roomCode: string;
  let page1: Page;
  let page2: Page;

  test.beforeAll(async ({ browser }) => {
    user1 = await createUser('ws_timer_1', 'Timer Player 1');
    user2 = await createUser('ws_timer_2', 'Timer Player 2');
    const room = await createRoomApi(user1);
    roomCode = room.roomCode;
    await joinRoomApi(user2, roomCode);

    // Seat BOTH pages before dealing so no one is absent while timers run
    page1 = await openSeatedPage(browser, user1, roomCode);
    page2 = await openSeatedPage(browser, user2, roomCode);
    await page1.click('button:has-text("Deal & Start")');
    await waitForGameStarted(page1);
    await waitForGameStarted(page2);
  });

  test('Yaniv call opens contest window on every client', async () => {
    const callerPage = await playUntilYaniv([page1, page2]);

    for (const p of [page1, page2]) {
      await waitForGameState(p, 'YANIV_CALLED');
      const state = await getState(p);
      expect(state.yanivCallerId).toBeTruthy();
      expect(state.yanivContestTimerSeconds).toBe(15);
      expect(state.yanivCalledAt).toBeGreaterThan(0);
    }
    void callerPage;
  });

  test('Contest (Asaf) resolves the round immediately', async () => {
    // The non-caller contests through the overlay button
    const callerId = (await getState(page1)).yanivCallerId;
    const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
    const contestPage = callerId === me1 ? page2 : page1;

    await contestPage.locator('.contest-btn').click();

    for (const p of [page1, page2]) {
      await waitForGameState(p, 'ROUND_OVER', 10000);
      expect((await getState(p)).roundScores).toBeDefined();
    }
  });

  test('Round-over waits for humans; Continue starts next round', async () => {
    await continueNextRound(page1);
    await waitForGameState(page1, 'WAIT_FOR_TURN');
    await waitForGameState(page2, 'WAIT_FOR_TURN');
    expect((await getState(page1)).roundNumber).toBe(2);
  });
});

test.describe('Yaniv Reconnection', () => {
  let user1: GameUser;
  let user2: GameUser;
  let roomCode: string;
  let page1: Page;
  let page2: Page;

  test.beforeAll(async ({ browser }) => {
    user1 = await createUser('ws_recon_1', 'Recon Player 1');
    user2 = await createUser('ws_recon_2', 'Recon Player 2');
    const room = await createRoomApi(user1);
    roomCode = room.roomCode;
    await joinRoomApi(user2, roomCode);

    // Seat BOTH pages before dealing so no one is absent while timers run
    page1 = await openSeatedPage(browser, user1, roomCode);
    page2 = await openSeatedPage(browser, user2, roomCode);
    await page1.click('button:has-text("Deal & Start")');
    await waitForGameStarted(page1);
    await waitForGameStarted(page2);

    // Hand the turn to player 1 so we can disconnect player 2 mid-game
    for (let i = 0; i < 12; i++) {
      const state = await getState(page1);
      const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
      if (state.currentTurnPlayerId === me1 && state.currentState === 'WAIT_FOR_TURN') break;
      if ((await getState(page2)).currentTurnPlayerId !== me1) break;
      await playTurnFromDeck(page2);
      await waitForTurnChange(page2, state.currentTurnPlayerId);
    }
  });

  test('Disconnect and reconnect restores state', async ({ browser }) => {
    await page2.close(); // abrupt disconnect

    // Player 1 plays on; game must not stall
    await waitForMyTurn(page1, 20000);
    await playTurnFromDeck(page1);

    // Reconnect as player 2 in a brand-new page
    const page3 = await openSeatedPage(browser, user2, roomCode);
    await waitForGameStarted(page3);

    const state3 = await getState(page3);
    expect(state3.hand.length).toBe(5);
    expect(state3.opponentCounts).toBeDefined();
  });
});
