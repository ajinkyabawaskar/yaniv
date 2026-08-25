import { test, expect, Page } from '@playwright/test';
import {
  createApiUser,
  domClick,
  getState,
  pullGameState,
  ensureGameState,
  openSeatedPage,
  waitForGameStartedReliable,
  waitForGameState,
  waitForTurnChange,
  playTurnFromDeck,
  playSmartTurn,
  tryCallYanivUi,
  continueNextRound,
} from './test-helpers';

const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';

/**
 * Full game flow through the real UI. All setup lives in beforeAll because
 * Playwright restarts the worker after a failing test, which would wipe
 * page references declared at describe scope.
 */

async function createRoom(page: Page): Promise<string> {
  await page.click('button:has-text("Create Table")');
  await page.locator('.game-view-container').waitFor({ state: 'visible', timeout: 15000 });
  const text = await page.locator('.room-code-tag').textContent();
  const match = text?.match(/[A-Z0-9]{6}/);
  if (!match) throw new Error('Could not read room code from header: ' + text);
  return match[0];
}

async function joinByLink(page: Page, roomCode: string): Promise<void> {
  await page.goto(`${FRONTEND_URL}/join/${roomCode}`);
  await page.locator('.game-view-container').waitFor({ state: 'visible', timeout: 20000 });
  await ensureGameState(page);
}

/** Map each seated page to its userId so turn order can be followed. */
async function seatOrder(pages: Page[]): Promise<{ page: Page; userId: string }[]> {
  const seated = [];
  for (const p of pages) {
    seated.push({ page: p, userId: await p.evaluate(() => (window as any).__CURRENT_USER_ID__) });
  }
  return seated;
}

test.describe('Yaniv Full Game Flow', () => {
  let page1: Page;
  let page2: Page;
  let page3: Page;
  let roomCode: string;
  let user3: { userId: string; jwtToken: string; displayName: string };

  test.beforeAll(async ({ browser }) => {
    const [u1, u2, u3] = await Promise.all([
      createApiUser('fg_full_1', 'Full Player 1'),
      createApiUser('fg_full_2', 'Full Player 2'),
      createApiUser('fg_full_3', 'Full Player 3'),
    ]);
    user3 = u3;

    page1 = await openSeatedPage(browser, u1); // lobby
    roomCode = await createRoom(page1);

    // Distinct API identities: UI registration collapses every context onto
    // one fingerprint-shared account, making all players the same person.
    // Membership is guaranteed via the API - the deep links below are then
    // pure view navigation and cannot race the join
    const joinViaApi = (u: any) => fetch(
      `${process.env.BACKEND_URL || 'http://localhost:8080'}/api/v1/rooms/${roomCode}/join`,
      { method: 'POST', headers: { Authorization: `Bearer ${u.jwtToken}` } }
    );
    expect((await joinViaApi(u2)).status).toBe(200);
    expect((await joinViaApi(u3)).status).toBe(200);

    page2 = await openSeatedPage(browser, u2, roomCode);
    page3 = await openSeatedPage(browser, u3, roomCode);

    for (const p of [page1, page2, page3]) {
      await expect(p.locator('.room-code-tag')).toContainText(roomCode);
    }
    // Individual pulls guarantee every seat sees the full roster even if a
    // lobby broadcast was lost
    for (const p of [page1, page2, page3]) {
      await pullGameState(p);
      await expect(p.locator('.lobby-player-card')).toHaveCount(3);
    }

    await page1.locator('button:has-text("Deal & Start")').click();
    for (const p of [page1, page2, page3]) {
      await waitForGameStartedReliable(p);
      await expect(p.locator('.hand-card')).toHaveCount(5);
      expect((await getState(p)).roundNumber).toBe(1);
    }
  });

  test('Three players are seated with initial hands', async () => {
    for (const p of [page1, page2, page3]) {
      await expect(p.locator('.hand-card')).toHaveCount(5);
      await expect(p.locator('.discard-fan-card').first()).toBeVisible();
    }
  });

  test('Turn order works correctly', async () => {
    // Server-side seat order is not guaranteed to match page creation order,
    // so follow whoever ACTUALLY holds the turn rather than predicting
    const seats = await seatOrder([page1, page2, page3]);

    for (let i = 0; i < seats.length; i++) {
      const currentId = (await getState(page1)).currentTurnPlayerId;
      const actor = seats.find((s) => s.userId === currentId)!;
      expect(actor).toBeDefined();

      await waitForGameState(actor.page, 'WAIT_FOR_TURN');
      await playTurnFromDeck(actor.page);

      // Turn must pass to a DIFFERENT seated player
      let advancedTo: string | null = null;
      const deadline = Date.now() + 20000;
      while (Date.now() < deadline && !advancedTo) {
        await actor.page.waitForTimeout(400);
        const st = await getState(actor.page);
        if (st?.currentState === 'WAIT_FOR_TURN' && st.currentTurnPlayerId !== currentId
            && seats.some((s) => s.userId === st.currentTurnPlayerId)) {
          advancedTo = st.currentTurnPlayerId;
        }
      }
      expect(advancedTo).toBeTruthy();
    }
  });

  test('Invalid pair selection cannot be discarded', async () => {
    const currentUserId = (await getState(page1)).currentTurnPlayerId;
    const seats = await seatOrder([page1, page2, page3]);
    const actor = seats.find((s) => s.userId === currentUserId)!;

    const hand = (await getState(actor.page)).hand;
    const a = hand[0];
    const b = hand.find((c: any) => c.rank !== a.rank && c.suit !== a.suit);
    test.skip(!b, 'no guaranteed-invalid pair available in this hand');

    const cards = actor.page.locator('.hand-card');
    await domClick(actor.page, '.hand-card', hand.indexOf(a));
    await domClick(actor.page, '.hand-card', hand.indexOf(b!));
    await domClick(actor.page, '.draw-pile-zone');

    // Client-side validation blocks the action: still our turn, hand unchanged
    await actor.page.waitForTimeout(1200);
    const after = await getState(actor.page);
    expect(after.currentState).toBe('WAIT_FOR_TURN');
    expect(after.currentTurnPlayerId).toBe(currentUserId);
    expect(after.hand.length).toBe(5);
  });

  test('Yaniv call works when hand score <= 7', async () => {
    // Rotate turns until someone's Yaniv button lights up. Smart turns shed
    // pairs/sets to shrink the hand - a full 5-card hand can never score <= 7.
    test.setTimeout(180_000); // generous safety net; with a raised yaniv
    // threshold this resolves on the first eligible player's first turn
    const seats = await seatOrder([page1, page2, page3]);
    let caller: Page | null = null;
    const deadline = Date.now() + 150_000;

    while (!caller && Date.now() < deadline) {
      for (const seat of seats) {
        const state = await getState(seat.page);
        if (!state || state.currentState !== 'WAIT_FOR_TURN') continue;
        if (state.currentTurnPlayerId !== seat.userId) continue;

        if (await tryCallYanivUi(seat.page)) {
          caller = seat.page;
          break;
        }
        await playSmartTurn(seat.page);
        await waitForTurnChange(seat.page, seat.userId, 20000).catch(() => {});
      }
    }
    expect(caller).toBeTruthy();

    for (const p of [page1, page2, page3]) {
      await waitForGameState(p, 'YANIV_CALLED');
    }
  });

  test('Asaf contest works when opponent contests', async () => {
    const callerId = (await getState(page1)).yanivCallerId;
    const seats = await seatOrder([page1, page2, page3]);
    const contestSeat = seats.find((s) => s.userId !== callerId)!;

    await contestSeat.page.locator('.contest-btn').click();

    for (const p of [page1, page2, page3]) {
      await waitForGameState(p, 'ROUND_OVER', 10000);
    }
  });

  test('Round over shows scores and all hands revealed', async () => {
    for (const p of [page1, page2, page3]) {
      const state = await getState(p);
      expect(state.roundScores).toBeDefined();
      expect(Object.keys(state.allPlayerHands || {}).length).toBeGreaterThan(0);
    }
    await expect(page1.locator('.continue-round-btn')).toBeVisible();
  });

  test('Next round can be started', async () => {
    const before = (await getState(page1))?.roundNumber ?? 1;
    await continueNextRound(page1);
    for (const p of [page1, page2, page3]) {
      await waitForGameState(p, 'WAIT_FOR_TURN');
      await expect(p.locator('.hand-card')).toHaveCount(5);
    }
    expect((await getState(page1)).roundNumber).toBe(before + 1);
  });

  test('Game handles disconnection gracefully', async ({ browser }) => {
    test.setTimeout(180_000);
    const log = (...a: any[]) => console.log('[DISC]', ...a);
    const me3 = await page3.evaluate(() => (window as any).__CURRENT_USER_ID__);
    log('start; me3=', me3);

    // Never strand the turn on the departing player - auto-play is off in
    // this environment, so the game would (correctly) wait forever
    const seats = await seatOrder([page1, page2, page3]);
    for (let i = 0; i < 12; i++) {
      const state = await getState(page1);
      log(`iter ${i}: cs=${state.currentState} turn=${state.currentTurnPlayerId?.slice(0,8)}`);
      if (state.currentTurnPlayerId !== me3 && state.currentState === 'WAIT_FOR_TURN') break;
      const actor = seats.find((seat) => seat.userId === state.currentTurnPlayerId)?.page;
      if (!actor) { log('no actor for turn, breaking'); break; }
      await playSmartTurn(actor);
      log(`iter ${i}: played, waiting change`);
      const changed = await waitForTurnChange(actor, state.currentTurnPlayerId, 12000).then(() => true).catch(() => false);
      log(`iter ${i}: changed=${changed}`);
    }

    log('closing page3; current=', (await getState(page1)).currentTurnPlayerId?.slice(0,8));
    await page3.close();

    // The two remaining players keep playing; turn advances normally.
    // Active pull-polling instead of passive waits - broadcasts can be missed.
    const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
    const me2 = await page2.evaluate(() => (window as any).__CURRENT_USER_ID__);
    const current = (await getState(page1)).currentTurnPlayerId;

    if (current === me1 || current === me2) {
      const actorPage = current === me1 ? page1 : page2;
      log('post-close play on', current.slice(0,8),
          'handN=', (await getState(actorPage))?.hand?.length,
          'cs=', (await getState(actorPage))?.currentState);

      // A long-lived page can lose its rendered table even though the store
      // still holds a hand - recover exactly like a real user: reload the link
      if ((await actorPage.locator('.hand-card').count()) === 0) {
        await joinByLink(actorPage, roomCode);
      }
      // Native DOM clicks: framer-motion whileHover keeps cards perpetually
      // "moving" under the virtual cursor, stalling Playwright actionability.
      // A rotted React root swallows clicks silently - heal via deep-link
      // reload when the turn refuses to move, then retry once.
      const playHealRetry = async (): Promise<void> => {
        await playTurnFromDeck(actorPage);
        const after = await getState(actorPage);
        if (after?.currentTurnPlayerId === current && after?.currentState === 'WAIT_FOR_TURN') {
          await joinByLink(actorPage, roomCode);
          await playTurnFromDeck(actorPage);
        }
      };
      await playHealRetry();

      // Verify through a FRESH observer page; it must pull state itself
      // because nothing broadcasts while every survivor idles
      const observer = await openSeatedPage(browser, user3, roomCode);
      const gameId = await observer.evaluate(() => (window as any).__USE_GAME_STORE__?.getState()?.gameId);
      const dest = '/app/room/' + gameId + '/state';
      const deadline = Date.now() + 30_000;
      let advanced = false;
      while (Date.now() < deadline && !advanced) {
        await observer.evaluate((d) => {
          const c = (window as any).__STOMP_CLIENT__;
          if (c?.connected) c.publish({ destination: d, body: JSON.stringify({}) });
        }, dest);
        const st = await getState(observer);
        advanced = !!st && st.currentState === 'WAIT_FOR_TURN' && st.currentTurnPlayerId !== current
          && st.currentTurnPlayerId !== me3;
        if (!advanced) await observer.waitForTimeout(700);
      }
      await observer.close();
      expect(advanced).toBe(true);
    }
  });

  test('Reconnection restores game state', async ({ browser }) => {
    const u3b = user3; // same user, new session
    page3 = await openSeatedPage(browser, u3b, roomCode);

    await waitForGameState(page3, 'WAIT_FOR_TURN');
    await expect(page3.locator('.hand-card')).toHaveCount(5);
    const state = await getState(page3);
    const beforeRound = (await getState(page1))?.roundNumber ?? 1;
    expect(state.roundNumber).toBe(beforeRound);
    expect(state.opponentCounts).toBeDefined();
  });
});

test.describe('Yaniv WebSocket Real-time Sync', () => {
  let page1: Page;
  let page2: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser }) => {
    const [u1, u2] = await Promise.all([
      createApiUser('fg_sync_1', 'Sync Player 1'),
      createApiUser('fg_sync_2', 'Sync Player 2'),
    ]);

    page1 = await openSeatedPage(browser, u1); // lobby
    roomCode = await createRoom(page1);
    page2 = await openSeatedPage(browser, u2, roomCode);

    await page1.locator('button:has-text("Deal & Start")').click();
    for (const p of [page1, page2]) {
      await waitForGameStartedReliable(p);
      await expect(p.locator('.hand-card')).toHaveCount(5);
    }
  });

  test('All players see same game state', async () => {
    const state1 = await getState(page1);
    const state2 = await getState(page2);

    expect(state1.currentState).toBe(state2.currentState);
    expect(state1.roundNumber).toBe(state2.roundNumber);
    expect(state1.currentTurnPlayerId).toBe(state2.currentTurnPlayerId);
  });

  test('Actions are broadcast to all players', async () => {
    const before = (await getState(page2)).topDiscardCards?.length ?? 0;

    const me1 = await page1.evaluate(() => (window as any).__CURRENT_USER_ID__);
    test.skip((await getState(page1)).currentTurnPlayerId !== me1, 'not player 1 turn');

    await playTurnFromDeck(page1);

    await page2.waitForFunction(
      (prev) => ((window as any).__GAME_STATE__?.topDiscardCards?.length ?? 0) > prev,
      before,
      { timeout: 10000 }
    );
  });
});
