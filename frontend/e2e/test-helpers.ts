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
 * Wait for game to be in specific state
 */
export async function waitForGameState(
  page: Page,
  expectedState: string,
  timeout = 10000
): Promise<void> {
  await page.waitForFunction(
    (state) => {
      const gs = (window as any).__GAME_STATE__;
      return gs?.currentState === state;
    },
    expectedState,
    { timeout }
  );
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

// Backend URL for API calls
export const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
export const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';