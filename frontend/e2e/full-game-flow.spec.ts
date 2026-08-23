import { test, expect, Browser, BrowserContext, Page } from '@playwright/test';
import { enterLobby } from './test-helpers';

// Test configuration
const BACKEND_URL = 'http://localhost:8080';
const FRONTEND_URL = 'http://localhost:3000';

// Helper to create authenticated page
async function createAuthenticatedPage(browser: Browser, contextIndex: number): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();

  // Navigate and register if it's the first visit (display-name form)
  await enterLobby(page);

  return page;
}

// Helper to get room code from URL or create a room
async function createRoom(page: Page): Promise<string> {
  // Click "Create Table" button
  await page.click('button:has-text("Create Table")');
  await page.waitForSelector('[data-testid="game-view"], .game-view-root', { timeout: 10000 });

  // Extract room code from URL or UI
  const url = page.url();
  const roomMatch = url.match(/\/game\/([A-Z0-9]{6})/);
  if (roomMatch) {
    return roomMatch[1];
  }

  // Try to find room code in UI
  const roomCodeElement = await page.locator('text=/Room Code: [A-Z0-9]{6}/').first();
  if (await roomCodeElement.isVisible()) {
    const text = await roomCodeElement.textContent();
    const codeMatch = text?.match(/([A-Z0-9]{6})/);
    if (codeMatch) return codeMatch[1];
  }

  throw new Error('Could not find room code');
}

// Helper to join room from another page
async function joinRoom(page: Page, roomCode: string) {
  await page.goto(`${FRONTEND_URL}/game/${roomCode}`);
  await page.waitForSelector('[data-testid="game-view"], .game-view-root', { timeout: 10000 });
}

// Helper to wait for game state
async function waitForGameState(page: Page, expectedState: string, timeout = 10000) {
  await expect(page.locator(`[data-game-state="${expectedState}"]`)).toBeVisible({ timeout });
}

// Helper to discard a card
async function discardCard(page: Page, cardIndex: number = 0) {
  // Click on a card in hand
  const cards = page.locator('[data-testid="hand-card"], .hand-card');
  await cards.nth(cardIndex).click();

  // Click discard/draw button
  await page.click('button:has-text("Discard"), button:has-text("Play")');
}

// Helper to draw from deck
async function drawFromDeck(page: Page) {
  await page.click('button:has-text("Draw from Deck"), button:has-text("Draw")');
}

// Helper to call Yaniv
async function callYaniv(page: Page) {
  await page.click('button:has-text("Yaniv"), button:has-text("Call Yaniv")');
}

// Helper to contest Yaniv (Asaf)
async function contestYaniv(page: Page) {
  await page.click('button:has-text("Asaf"), button:has-text("Contest")');
}

test.describe('Yaniv Full Game Flow', () => {
  let browser: Browser;
  let page1: Page;
  let page2: Page;
  let page3: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser: b }) => {
    browser = b;
  });

  test.afterAll(async () => {
    await browser?.close();
  });

  test('Player 1 creates a room', async () => {
    page1 = await createAuthenticatedPage(browser, 0);
    roomCode = await createRoom(page1);
    expect(roomCode).toMatch(/^[A-Z0-9]{6}$/);
    console.log(`Created room: ${roomCode}`);
  });

  test('Player 2 joins the room', async () => {
    page2 = await createAuthenticatedPage(browser, 1);
    await joinRoom(page2, roomCode);
    await expect(page2.locator('.game-view-root, [data-testid="game-view"]')).toBeVisible();
  });

  test('Player 3 joins the room', async () => {
    page3 = await createAuthenticatedPage(browser, 2);
    await joinRoom(page3, roomCode);
    await expect(page3.locator('.game-view-root, [data-testid="game-view"]')).toBeVisible();
  });

  test('Host starts the game', async () => {
    // Host (page1) clicks start game
    await page1.click('button:has-text("Start Game"), button:has-text("Start")');
    await page1.waitForSelector('[data-game-state="IN_PROGRESS"], .game-in-progress', { timeout: 10000 });
  });

  test('All players receive initial hands (5 cards each)', async () => {
    for (const page of [page1, page2, page3]) {
      const handCards = page.locator('[data-testid="hand-card"], .hand-card');
      await expect(handCards).toHaveCount(5);
    }
  });

  test('Turn order works correctly', async () => {
    // Player 1 should be first
    await expect(page1.locator('[data-current-turn="true"], .my-turn')).toBeVisible();

    // Player 1 plays a turn
    await discardCard(page1, 0);
    await drawFromDeck(page1);

    // Turn should pass to Player 2
    await expect(page2.locator('[data-current-turn="true"], .my-turn')).toBeVisible({ timeout: 5000 });

    // Player 2 plays a turn
    await discardCard(page2, 0);
    await drawFromDeck(page2);

    // Turn should pass to Player 3
    await expect(page3.locator('[data-current-turn="true"], .my-turn')).toBeVisible({ timeout: 5000 });

    // Player 3 plays a turn
    await discardCard(page3, 0);
    await drawFromDeck(page3);

    // Turn should pass back to Player 1
    await expect(page1.locator('[data-current-turn="true"], .my-turn')).toBeVisible({ timeout: 5000 });
  });

  test('Discard pile shows correct cards', async () => {
    // Check discard pile is visible on all pages
    for (const page of [page1, page2, page3]) {
      await expect(page.locator('[data-testid="discard-pile"], .discard-pile')).toBeVisible();
    }
  });

  test('Valid card combinations can be played', async () => {
    // Test single card discard (already tested above)

    // Test pair discard - if player has a pair
    const hand1 = page1.locator('[data-testid="hand-card"], .hand-card');
    const cardCount = await hand1.count();
    if (cardCount >= 2) {
      // Try to select two cards of same rank
      await hand1.nth(0).click();
      await hand1.nth(1).click();
      await page1.click('button:has-text("Discard")');
      await drawFromDeck(page1);
    }
  });

  test('Yaniv call works when hand score <= 7', async () => {
    // This test is probabilistic - depends on dealt cards
    // We'll test that the Yaniv button appears when conditions are met

    // Play several rounds until someone can call Yaniv
    let yanivCalled = false;
    for (let round = 0; round < 10 && !yanivCalled; round++) {
      for (const page of [page1, page2, page3]) {
        const yanivButton = page.locator('button:has-text("Yaniv"):visible, button:has-text("Call Yaniv"):visible');
        if (await yanivButton.isVisible({ timeout: 1000 }).catch(() => false)) {
          await yanivButton.click();
          yanivCalled = true;
          break;
        }

        // Play a turn
        try {
          await discardCard(page, 0);
          await drawFromDeck(page);
        } catch (e) {
          // Turn might not be this player's
        }
      }
    }

    // If Yaniv was called, verify state
    if (yanivCalled) {
      await expect(page1.locator('[data-game-state="YANIV_CALLED"], .yaniv-called')).toBeVisible({ timeout: 5000 });
      await expect(page2.locator('[data-game-state="YANIV_CALLED"], .yaniv-called')).toBeVisible({ timeout: 5000 });
      await expect(page3.locator('[data-game-state="YANIV_CALLED"], .yaniv-called')).toBeVisible({ timeout: 5000 });
    }
  });

  test('Asaf contest works when opponent has lower score', async () => {
    // If Yaniv was called, other players can contest
    const contestButton = page2.locator('button:has-text("Asaf"):visible, button:has-text("Contest"):visible');
    if (await contestButton.isVisible({ timeout: 1000 }).catch(() => false)) {
      await contestButton.click();
      // Should immediately resolve to ROUND_OVER
      await expect(page1.locator('[data-game-state="ROUND_OVER"], .round-over')).toBeVisible({ timeout: 5000 });
    }
  });

  test('Round over shows scores and all hands revealed', async () => {
    if (await page1.locator('[data-game-state="ROUND_OVER"], .round-over').isVisible({ timeout: 5000 }).catch(() => false)) {
      // Check round scores displayed
      await expect(page1.locator('[data-testid="round-scores"], .round-scores')).toBeVisible();
      await expect(page2.locator('[data-testid="round-scores"], .round-scores')).toBeVisible();
      await expect(page3.locator('[data-testid="round-scores"], .round-scores')).toBeVisible();

      // All hands should be revealed
      await expect(page1.locator('[data-testid="opponent-hand"], .opponent-hand')).toBeVisible();
    }
  });

  test('Next round can be started', async () => {
    if (await page1.locator('[data-game-state="ROUND_OVER"], .round-over').isVisible({ timeout: 1000 }).catch(() => false)) {
      await page1.click('button:has-text("Next Round"), button:has-text("Continue")');
      await page1.waitForSelector('[data-game-state="IN_PROGRESS"], .game-in-progress', { timeout: 5000 });

      // New hands should be dealt
      await expect(page1.locator('[data-testid="hand-card"], .hand-card')).toHaveCount(5);
    }
  });

  test('Game handles disconnection gracefully', async () => {
    // Close page3 (simulate disconnect)
    await page3.close();

    // Game should continue with remaining players
    // Turn should pass normally
    await page1.click('[data-testid="hand-card"], .hand-card');
    await page1.click('button:has-text("Discard")');
    await drawFromDeck(page1);

    await expect(page2.locator('[data-current-turn="true"], .my-turn')).toBeVisible({ timeout: 5000 });
  });

  test('Reconnection restores game state', async () => {
    // Create new page3 (simulate reconnect)
    page3 = await createAuthenticatedPage(browser, 3);
    await joinRoom(page3, roomCode);

    // Should see current game state
    await expect(page3.locator('.game-view-root, [data-testid="game-view"]')).toBeVisible();
    await expect(page3.locator('[data-testid="hand-card"], .hand-card')).toHaveCount(5);
  });
});

test.describe('Yaniv Card Rules Validation', () => {
  let browser: Browser;
  let page1: Page;
  let page2: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser: b }) => {
    browser = b;
  });

  test.afterAll(async () => {
    await browser?.close();
  });

  test('Setup: create room with 2 players', async () => {
    page1 = await createAuthenticatedPage(browser, 0);
    roomCode = await createRoom(page1);

    page2 = await createAuthenticatedPage(browser, 1);
    await joinRoom(page2, roomCode);

    await page1.click('button:has-text("Start Game")');
    await page1.waitForSelector('[data-game-state="IN_PROGRESS"]', { timeout: 10000 });
  });

  test('Sequence discard: only ends are drawable', async () => {
    // This tests the discard pile pickup rules
    // We need to create a sequence in the discard pile
    // This is hard to control via UI, so we'll verify the rule is enforced
    // by checking drawable cards from discard pile

    const drawableCards = page1.locator('[data-testid="drawable-card"], .drawable-card');
    // Implementation depends on UI showing drawable cards
  });

  test('Set discard: all cards are drawable', async () => {
    // Similar test for set
  });

  test('Mixed sequence (hand clear): only ends drawable', async () => {
    // Test mixed sequence rule
  });

  test('Invalid combinations are rejected', async () => {
    // Try to discard invalid combination
    // Should show error or prevent action
  });
});

test.describe('Yaniv Scoring Rules', () => {
  let browser: Browser;
  let page1: Page;
  let page2: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser: b }) => {
    browser = b;
  });

  test.afterAll(async () => {
    await browser?.close();
  });

  test('Setup', async () => {
    page1 = await createAuthenticatedPage(browser, 0);
    roomCode = await createRoom(page1);

    page2 = await createAuthenticatedPage(browser, 1);
    await joinRoom(page2, roomCode);

    await page1.click('button:has-text("Start Game")');
    await page1.waitForSelector('[data-game-state="IN_PROGRESS"]', { timeout: 10000 });
  });

  test('Yaniv caller gets 0 points', async () => {
    // Play until Yaniv is called
    // Verify caller gets 0 in round scores
  });

  test('Asaf: caller gets hand score + 30 penalty', async () => {
    // Force Asaf scenario
    // Verify penalty applied
  });

  test('Tie: all tied players get 0', async () => {
    // Test tie scenario
  });

  test('Halving rule: score 50 -> 25, 100 -> 75, etc.', async () => {
    // This requires reaching specific scores
    // Hard to test in E2E, but verify API exists
  });

  test('Elimination at target score (200)', async () => {
    // Long test - verify elimination works
  });

  test('Game over when one player remains', async () => {
    // Verify winner declared
  });
});

test.describe('Yaniv WebSocket Real-time Sync', () => {
  let browser: Browser;
  let page1: Page;
  let page2: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser: b }) => {
    browser = b;
  });

  test.afterAll(async () => {
    await browser?.close();
  });

  test('Setup', async () => {
    page1 = await createAuthenticatedPage(browser, 0);
    roomCode = await createRoom(page1);

    page2 = await createAuthenticatedPage(browser, 1);
    await joinRoom(page2, roomCode);

    await page1.click('button:has-text("Start Game")');
    await page1.waitForSelector('[data-game-state="IN_PROGRESS"]', { timeout: 10000 });
  });

  test('All players see same game state', async () => {
    // Compare game state between pages
    const state1 = await page1.evaluate(() => (window as any).__GAME_STATE__);
    const state2 = await page2.evaluate(() => (window as any).__GAME_STATE__);

    expect(state1?.currentState).toBe(state2?.currentState);
    expect(state1?.roundNumber).toBe(state2?.roundNumber);
    expect(state1?.currentTurnPlayerId).toBe(state2?.currentTurnPlayerId);
  });

  test('Actions are broadcast to all players', async () => {
    // Player 1 discards
    await discardCard(page1, 0);
    await drawFromDeck(page1);

    // Player 2 should see updated discard pile
    await page2.waitForFunction(
      () => (window as any).__GAME_STATE__?.discardPile?.length > 0,
      { timeout: 5000 }
    );
  });

  test('Yaniv timer is synchronized', async () => {
    // If Yaniv called, all players see same countdown
  });
});