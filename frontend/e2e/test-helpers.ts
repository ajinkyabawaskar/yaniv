import { Page, BrowserContext, expect } from '@playwright/test';

/**
 * Test helpers for Yaniv E2E tests
 */

/**
 * Navigate to the app and land in the lobby.
 * Handles first-visit registration (display-name form) as well as
 * returning visitors who auto-resolve via their stored displayName.
 */
export async function enterLobby(page: Page, url?: string): Promise<void> {
  await page.goto(url || FRONTEND_URL);

  const lobby = page.locator('.lobby-view-root');
  const authInput = page.locator('#displayName');

  // Either we land straight in the lobby (returning visitor),
  // or the AuthView asks for a display name on first visit.
  const target = Promise.race([
    lobby.waitFor({ state: 'visible', timeout: 15000 }).then(() => 'lobby' as const),
    authInput.waitFor({ state: 'visible', timeout: 15000 }).then(() => 'auth' as const),
  ]);
  const where = await target;

  if (where === 'auth') {
    const uniqueName = `PW_${Math.random().toString(36).slice(2, 10)}`;
    await authInput.fill(uniqueName);
    await page.click('.auth-submit-btn');
    await expect(lobby).toBeVisible({ timeout: 15000 });
  }
}

/**
 * Wait for WebSocket connection to be established
 */
export async function waitForWebSocket(page: Page, timeout = 10000): Promise<void> {
  await page.waitForFunction(
    () => {
      const ctx = (window as any).__STOMP_CLIENT__;
      return ctx?.connected === true;
    },
    { timeout }
  );
}

/**
 * Get current game state from the page's store
 */
export async function getGameState(page: Page): Promise<any> {
  return await page.evaluate(() => {
    // Access Zustand store directly
    const store = (window as any).__USE_GAME_STORE__;
    if (store) {
      return store.getState();
    }
    return null;
  });
}

/**
 * Create a room via API and return room code
 */
export async function createRoomViaApi(page: Page, targetScore = 200, maxPlayers = 6): Promise<string> {
  const response = await page.request.post(`${BACKEND_URL}/api/v1/rooms`, {
    data: { targetScore, maxPlayers },
    headers: {
      'Authorization': `Bearer ${await getAuthToken(page)}`,
    },
  });
  const data = await response.json();
  return data.roomCode;
}

/**
 * Join a room via API
 */
export async function joinRoomViaApi(page: Page, roomCode: string): Promise<void> {
  await page.request.post(`${BACKEND_URL}/api/v1/rooms/${roomCode}/join`, {
    headers: {
      'Authorization': `Bearer ${await getAuthToken(page)}`,
    },
  });
}

/**
 * Get JWT token from localStorage
 */
export async function getAuthToken(page: Page): Promise<string> {
  return await page.evaluate(() => localStorage.getItem('jwtToken') || '');
}

/**
 * Set auth token in localStorage
 */
export async function setAuthToken(page: Page, token: string): Promise<void> {
  await page.evaluate((t) => localStorage.setItem('jwtToken', t), token);
}

/**
 * Simulate a player turn: discard first card and draw from deck
 */
export async function playSimpleTurn(page: Page): Promise<void> {
  // Select first card in hand
  const handCards = page.locator('[data-testid="hand-card"], .hand-card, .card-in-hand');
  const count = await handCards.count();
  if (count > 0) {
    await handCards.first().click();
    await page.click('button:has-text("Discard"), button:has-text("Play"), [data-testid="discard-btn"]');
  }

  // Draw from deck
  await page.click('button:has-text("Draw from Deck"), button:has-text("Draw"), [data-testid="draw-deck-btn"]');
}

/**
 * Call Yaniv if possible
 */
export async function tryCallYaniv(page: Page): Promise<boolean> {
  const yanivBtn = page.locator('button:has-text("Yaniv"):visible, button:has-text("Call Yaniv"):visible');
  if (await yanivBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
    await yanivBtn.click();
    return true;
  }
  return false;
}

/**
 * Contest Yaniv (Asaf) if possible
 */
export async function tryContestYaniv(page: Page): Promise<boolean> {
  const contestBtn = page.locator('button:has-text("Asaf"):visible, button:has-text("Contest"):visible');
  if (await contestBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
    await contestBtn.click();
    return true;
  }
  return false;
}

/**
 * Start next round
 */
export async function startNextRound(page: Page): Promise<void> {
  await page.click('button:has-text("Next Round"), button:has-text("Continue"), [data-testid="next-round-btn"]');
}

/**
 * Get hand cards count
 */
export async function getHandCount(page: Page): Promise<number> {
  const handCards = page.locator('[data-testid="hand-card"], .hand-card, .card-in-hand');
  return await handCards.count();
}

/**
 * Get opponent hand counts
 */
export async function getOpponentCounts(page: Page): Promise<Record<string, number>> {
  return await page.evaluate(() => {
    const store = (window as any).__USE_GAME_STORE__;
    if (store) {
      return store.getState().opponentCounts || {};
    }
    return {};
  });
}

/**
 * Get current turn player ID
 */
export async function getCurrentTurnPlayer(page: Page): Promise<string | null> {
  return await page.evaluate(() => {
    const store = (window as any).__USE_GAME_STORE__;
    if (store) {
      return store.getState().currentTurnPlayerId || null;
    }
    return null;
  });
}

/**
 * Check if it's the current player's turn
 */
export async function isMyTurn(page: Page): Promise<boolean> {
  return await page.evaluate(() => {
    const store = (window as any).__USE_GAME_STORE__;
    if (store) {
      const state = store.getState();
      const userId = (window as any).__CURRENT_USER_ID__;
      return state.currentTurnPlayerId === userId;
    }
    return false;
  });
}


export interface ApiUser { userId: string; jwtToken: string; displayName: string; }

/** Create a distinct user via the REST API (UI registration collapses all
 *  Playwright contexts onto one fingerprint-shared account). */
export async function createApiUser(fingerprint: string, name: string): Promise<ApiUser> {
  const response = await fetch(`${BACKEND_URL}/api/v1/users/resolve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fingerprintHash: fingerprint, displayName: name }),
  });
  const data = await response.json();
  return { userId: data.userId, jwtToken: data.jwtToken, displayName: data.displayName };
}

// Backend URL for API calls
export const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
export const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';

// ---------------------------------------------------------------------------
// Seating & gameplay helpers (current DOM: .game-view-container, .hand-card,
// .draw-pile-zone, .call-yaniv-btn-hud, .contest-btn, .continue-round-btn)
// ---------------------------------------------------------------------------

export interface SeatedUser {
  userId: string;
  jwtToken: string;
  displayName?: string;
}

/**
 * Open a page authenticated as an API-created user (token injected into
 * localStorage) and optionally seat them at a room via the /join deep link.
 */
/**
 * Deterministically populate __GAME_STATE__ on a seated page by requesting a
 * state push over STOMP (the proactive connect-push can race subscriptions).
 */
export async function pullGameState(page: Page): Promise<void> {
  await page.waitForFunction(
    () => {
      const st = (window as any).__USE_GAME_STORE__?.getState();
      return !!(st?.gameId && (window as any).__STOMP_CLIENT__?.connected);
    },
    undefined,
    { timeout: 20000 }
  );
  const gameId = await page.evaluate(() => (window as any).__USE_GAME_STORE__.getState().gameId);

  // Clear any stale snapshot so the wait below proves FRESH state arrived
  await page.evaluate(() => { delete (window as any).__GAME_STATE__; });
  await page.evaluate((dest) => (window as any).__STOMP_CLIENT__?.publish({
    destination: dest, body: JSON.stringify({}),
  }), '/app/room/' + gameId + '/state');
  await page.waitForFunction(() => !!((window as any).__GAME_STATE__), undefined, { timeout: 20000 });
}

/**
 * Wait for the game to start; if the start push was lost, force a refresh and retry.
 */
export async function waitForGameStartedReliable(page: Page, timeout = 20000): Promise<void> {
  try {
    await waitForGameStarted(page, Math.min(timeout, 15000));
  } catch {
    await pullGameState(page);
    await waitForGameStarted(page, 10000);
  }
}

/** Pull state only when the page currently has none. */
export async function ensureGameState(page: Page): Promise<void> {
  if (!(await getState(page))) {
    await pullGameState(page);
  }
}

export async function openSeatedPage(
  browser: import('@playwright/test').Browser,
  user: SeatedUser,
  roomCode?: string
): Promise<Page> {
  const page = await browser.newPage();
  await page.goto(FRONTEND_URL); // boot the app once
  await page.evaluate((u) => {
    localStorage.setItem('jwtToken', u.jwtToken);
    localStorage.setItem('userId', u.userId);
    localStorage.setItem('user', JSON.stringify({ userId: u.userId, displayName: u.displayName || 'Player' }));
  }, user);
  await page.goto(roomCode ? `${FRONTEND_URL}/join/${roomCode}` : FRONTEND_URL);
  if (roomCode) {
    const container = page.locator('.game-view-container');
    try {
      await container.waitFor({ state: 'visible', timeout: 20000 });
    } catch {
      // Occasional lost join round-trip: reload the deep link once
      await page.goto(`${FRONTEND_URL}/join/${roomCode}`);
      await container.waitFor({ state: 'visible', timeout: 20000 });
    }

    // The server's proactive connect-push can race our subscription - pull
    // state explicitly so __GAME_STATE__ is populated deterministically.
    try {
      await pullGameState(page);
    } catch {
      await pullGameState(page);
    }
  } else {
    await page.locator('.lobby-view-root').waitFor({ state: 'visible', timeout: 20000 });
  }
  return page;
}

export async function getState(page: Page): Promise<any> {
  return page.evaluate(() => (window as any).__GAME_STATE__ ?? null);
}

export async function waitForGameState(page: Page, expected: string, timeout = 20000): Promise<void> {
  await page.waitForFunction(
    (s) => (window as any).__GAME_STATE__?.currentState === s,
    expected,
    { timeout }
  );
}


/** Engine broadcasts never say "IN_PROGRESS" (that's the DB status) - a dealt
 *  hand plus any non-lobby engine state means the game is underway. */
export async function waitForGameStarted(page: Page, timeout = 20000): Promise<void> {
  await page.waitForFunction(
    () => {
      const gs = (window as any).__GAME_STATE__;
      return !!gs && gs.hand?.length > 0 && gs.currentState !== 'LOBBY';
    },
    undefined,
    { timeout }
  );
}

/** Wait until it is this page's player's turn and the table accepts actions. */
export async function waitForMyTurn(page: Page, timeout = 30000): Promise<void> {
  await page.waitForFunction(
    () => {
      const gs = (window as any).__GAME_STATE__;
      const me = (window as any).__CURRENT_USER_ID__;
      return !!gs && !!me && gs.currentState === 'WAIT_FOR_TURN' && gs.currentTurnPlayerId === me;
    },
    undefined,
    { timeout }
  );
}

/** Wait until the turn moved off `prevPlayerId` back to a normal waiting state. */
export async function waitForTurnChange(page: Page, prevPlayerId: string, timeout = 20000): Promise<void> {
  await page.waitForFunction(
    (prev) => {
      const gs = (window as any).__GAME_STATE__;
      return !!gs && gs.currentState === 'WAIT_FOR_TURN' && gs.currentTurnPlayerId !== prev;
    },
    prevPlayerId,
    { timeout }
  );
}

/** Select the first hand card, then tap the deck: one atomic discard+draw. */
export async function playTurnFromDeck(page: Page): Promise<void> {
  const stale = page.locator('.hand-card.selected-lift');
  const staleCount = await stale.count();
  for (let i = 0; i < staleCount; i++) {
    await stale.first().click();
  }
  await page.locator('.hand-card').first().click();
  await page.locator('.draw-pile-zone').click();
}

/**
 * Discard the largest same-rank group when possible (shrinks the hand toward
 * a callable Yaniv), otherwise fall back to a single card. A full 5-card hand
 * can never score <= 7 - its minimum is A+A+2+2+3 = 9.
 */
export async function playSmartTurn(page: Page): Promise<void> {
  // Deselect anything left over from previous tests - leftover selections
  // silently corrupt every later discard
  const stale = page.locator('.hand-card.selected-lift');
  const staleCount = await stale.count();
  for (let i = 0; i < staleCount; i++) {
    await stale.first().click();
  }

  const hand: Array<{ id: string; rank: string }> = (await getState(page))?.hand ?? [];
  const byRank = new Map<string, number[]>();
  hand.forEach((card, index) => {
    const list = byRank.get(card.rank) ?? [];
    list.push(index);
    byRank.set(card.rank, list);
  });
  const groups = [...byRank.values()].filter((g) => g.length >= 2).sort((a, b) => b.length - a.length);
  const targets = groups[0] ?? [0];

  for (const index of targets) {
    await page.locator('.hand-card').nth(index).click();
  }
  await page.locator('.draw-pile-zone').click();
}

/** Click the Yaniv HUD button when it is enabled (eligible + my turn). */
export async function tryCallYanivUi(page: Page): Promise<boolean> {
  const btn = page.locator('button.call-yaniv-btn-hud:not([disabled])');
  if (await btn.isVisible({ timeout: 500 }).catch(() => false)) {
    await btn.click();
    return true;
  }
  return false;
}

/** Click through the round-over results screen. */
export async function continueNextRound(page: Page): Promise<void> {
  await page.locator('.continue-round-btn').click();
}

/** Read the 6-char room code from the in-game header tag. */
export async function readRoomCode(page: Page): Promise<string> {
  const text = await page.locator('.room-code-tag').textContent();
  const match = text?.match(/[A-Z0-9]{6}/);
  if (!match) throw new Error('Could not read room code from header: ' + text);
  return match[0];
}