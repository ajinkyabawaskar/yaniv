import { test, expect } from '@playwright/test';

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:3000';

/**
 * WebSocket-based game flow tests
 * These tests use Playwright's WebSocket support to interact with the game
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
  return {
    userId: data.userId,
    jwtToken: data.jwtToken,
    displayName: data.displayName,
  };
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

async function joinRoomApi(user: GameUser, roomCode: string): Promise<{ gameId: string }> {
  const response = await fetch(`${BACKEND_URL}/api/v1/rooms/${roomCode}/join`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${user.jwtToken}`,
    },
  });
  return response.json();
}

test.describe('Yaniv WebSocket Game Flow', () => {
  let user1: GameUser;
  let user2: GameUser;
  let gameId: string;
  let roomCode: string;

  test.beforeAll(async () => {
    user1 = await createUser('ws_test_1', 'WS Player 1');
    user2 = await createUser('ws_test_2', 'WS Player 2');
  });

  test('Create room and join', async () => {
    const room = await createRoomApi(user1);
    gameId = room.gameId;
    roomCode = room.roomCode;

    await joinRoomApi(user2, roomCode);
  });

  test('Connect to WebSocket and start game', async ({ page }) => {
    // Navigate to game page with auth
    await page.goto(FRONTEND_URL);

    // Set auth token
    await page.evaluate((token) => localStorage.setItem('jwtToken', token), user1.jwtToken);
    await page.evaluate((id) => localStorage.setItem('userId', id), user1.userId);

    // Go to game
    await page.goto(`${FRONTEND_URL}/game/${roomCode}`);

    // Wait for WebSocket connection
    await page.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Start game (host only)
    await page.click('button:has-text("Start Game"), button:has-text("Start")');

    // Wait for game to start
    await page.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'IN_PROGRESS',
      { timeout: 10000 }
    );

    // Verify game state
    const state = await page.evaluate(() => (window as any).__GAME_STATE__);
    expect(state.currentState).toBe('IN_PROGRESS');
    expect(state.roundNumber).toBe(1);
    expect(state.hand.length).toBe(5);
  });

  test('Player 2 connects and sees same state', async ({ page, browser }) => {
    const page2 = await browser.newPage();
    await page2.goto(FRONTEND_URL);
    await page2.evaluate((token) => localStorage.setItem('jwtToken', token), user2.jwtToken);
    await page2.evaluate((id) => localStorage.setItem('userId', id), user2.userId);

    await page2.goto(`${FRONTEND_URL}/game/${roomCode}`);

    await page2.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Wait for game state
    await page2.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'IN_PROGRESS',
      { timeout: 10000 }
    );

    // Verify both players see same state
    const state1 = await page.evaluate(() => (window as any).__GAME_STATE__);
    const state2 = await page2.evaluate(() => (window as any).__GAME_STATE__);

    expect(state1.currentState).toBe(state2.currentState);
    expect(state1.roundNumber).toBe(state2.roundNumber);
    expect(state1.currentTurnPlayerId).toBe(state2.currentTurnPlayerId);
    expect(state2.hand.length).toBe(5);
  });

  test('Play a turn: discard and draw', async ({ page }) => {
    // Get current state
    let state = await page.evaluate(() => (window as any).__GAME_STATE__);
    const currentPlayer = state.currentTurnPlayerId;

    // Only current player can play
    if (currentPlayer === user1.userId) {
      // Select first card
      await page.click('[data-testid="hand-card"], .hand-card, .card-in-hand >> nth=0');

      // Click discard
      await page.click('button:has-text("Discard"), button:has-text("Play")');

      // Wait for DRAW_CARD state
      await page.waitForFunction(
        () => (window as any).__GAME_STATE__?.currentState === 'DRAW_CARD',
        { timeout: 5000 }
      );

      // Draw from deck
      await page.click('button:has-text("Draw from Deck"), button:has-text("Draw")');

      // Wait for next turn
      await page.waitForFunction(
        () => (window as any).__GAME_STATE__?.currentState === 'WAIT_FOR_TURN',
        { timeout: 5000 }
      );

      state = await page.evaluate(() => (window as any).__GAME_STATE__);
      expect(state.currentState).toBe('WAIT_FOR_TURN');
      // Turn should have advanced
      expect(state.currentTurnPlayerId).not.toBe(user1.userId);
    }
  });

  test('Discard pile updates for all players', async ({ page, browser }) => {
    const page2 = await browser.newPage();
    await page2.goto(FRONTEND_URL);
    await page2.evaluate((token) => localStorage.setItem('jwtToken', token), user2.jwtToken);
    await page2.evaluate((id) => localStorage.setItem('userId', id), user2.userId);
    await page2.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page2.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Player 1 discards a card
    await page.click('[data-testid="hand-card"], .hand-card, .card-in-hand >> nth=0');
    await page.click('button:has-text("Discard"), button:has-text("Play")');
    await page.click('button:has-text("Draw from Deck"), button:has-text("Draw")');

    // Player 2 should see updated discard pile
    await page2.waitForFunction(
      () => {
        const gs = (window as any).__GAME_STATE__;
        return gs?.topDiscardCards && gs.topDiscardCards.length > 0;
      },
      { timeout: 5000 }
    );

    const state2 = await page2.evaluate(() => (window as any).__GAME_STATE__);
    expect(state2.topDiscardCards.length).toBeGreaterThan(0);
  });
});

test.describe('Yaniv Contest Timer', () => {
  let user1: GameUser;
  let user2: GameUser;
  let gameId: string;
  let roomCode: string;

  test.beforeAll(async () => {
    user1 = await createUser('ws_timer_1', 'Timer Player 1');
    user2 = await createUser('ws_timer_2', 'Timer Player 2');
  });

  test('Yaniv call starts 15-second contest timer', async ({ page, browser }) => {
    // Setup
    const room = await createRoomApi(user1);
    gameId = room.gameId;
    roomCode = room.roomCode;
    await joinRoomApi(user2, roomCode);

    // Player 1 page
    await page.goto(FRONTEND_URL);
    await page.evaluate((token) => localStorage.setItem('jwtToken', token), user1.jwtToken);
    await page.evaluate((id) => localStorage.setItem('userId', id), user1.userId);
    await page.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );
    await page.click('button:has-text("Start Game")');
    await page.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'IN_PROGRESS',
      { timeout: 10000 }
    );

    // Player 2 page
    const page2 = await browser.newPage();
    await page2.goto(FRONTEND_URL);
    await page2.evaluate((token) => localStorage.setItem('jwtToken', token), user2.jwtToken);
    await page2.evaluate((id) => localStorage.setItem('userId', id), user2.userId);
    await page2.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page2.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Play turns until someone can call Yaniv
    // This is probabilistic, so we'll just test the timer UI appears
    // when Yaniv is called via direct WebSocket message

    // Send Yaniv call via WebSocket
    await page.evaluate((gameId) => {
      const client = (window as any).__STOMP_CLIENT__;
      if (client?.connected) {
        client.publish({
          destination: `/app/room/${gameId}/call-yaniv`,
          body: JSON.stringify({ playerId: (window as any).__CURRENT_USER_ID__ }),
        });
      }
    }, gameId);

    // Both should see YANIV_CALLED state with timer
    await page.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'YANIV_CALLED',
      { timeout: 5000 }
    );
    await page2.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'YANIV_CALLED',
      { timeout: 5000 }
    );

    const state1 = await page.evaluate(() => (window as any).__GAME_STATE__);
    const state2 = await page2.evaluate(() => (window as any).__GAME_STATE__);

    expect(state1.currentState).toBe('YANIV_CALLED');
    expect(state2.currentState).toBe('YANIV_CALLED');
    expect(state1.yanivContestTimerSeconds).toBe(15);
    expect(state2.yanivContestTimerSeconds).toBe(15);
    expect(state1.yanivCalledAt).toBeGreaterThan(0);
    expect(state2.yanivCalledAt).toBeGreaterThan(0);
  });

  test('Contest (Asaf) cancels timer and resolves immediately', async ({ page }) => {
    // Player 2 contests
    await page.evaluate((gameId) => {
      const client = (window as any).__STOMP_CLIENT__;
      if (client?.connected) {
        client.publish({
          destination: `/app/room/${gameId}/contest-yaniv`,
          body: JSON.stringify({ playerId: (window as any).__CURRENT_USER_ID__ }),
        });
      }
    }, gameId);

    // Should immediately go to ROUND_OVER
    await page.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'ROUND_OVER',
      { timeout: 5000 }
    );
  });
});

test.describe('Yaniv Reconnection', () => {
  let user1: GameUser;
  let user2: GameUser;
  let gameId: string;
  let roomCode: string;

  test.beforeAll(async () => {
    user1 = await createUser('ws_recon_1', 'Recon Player 1');
    user2 = await createUser('ws_recon_2', 'Recon Player 2');
  });

  test('Disconnect and reconnect restores state', async ({ page, browser }) => {
    // Setup
    const room = await createRoomApi(user1);
    gameId = room.gameId;
    roomCode = room.roomCode;
    await joinRoomApi(user2, roomCode);

    // Player 1 page
    await page.goto(FRONTEND_URL);
    await page.evaluate((token) => localStorage.setItem('jwtToken', token), user1.jwtToken);
    await page.evaluate((id) => localStorage.setItem('userId', id), user1.userId);
    await page.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );
    await page.click('button:has-text("Start Game")');
    await page.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'IN_PROGRESS',
      { timeout: 10000 }
    );

    // Player 2 page
    const page2 = await browser.newPage();
    await page2.goto(FRONTEND_URL);
    await page2.evaluate((token) => localStorage.setItem('jwtToken', token), user2.jwtToken);
    await page2.evaluate((id) => localStorage.setItem('userId', id), user2.userId);
    await page2.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page2.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Close page2 (simulate disconnect)
    await page2.close();

    // Player 1 plays a turn
    await page.click('[data-testid="hand-card"], .hand-card, .card-in-hand >> nth=0');
    await page.click('button:has-text("Discard"), button:has-text("Play")');
    await page.click('button:has-text("Draw from Deck"), button:has-text("Draw")');

    // Reconnect: new page2
    const page3 = await browser.newPage();
    await page3.goto(FRONTEND_URL);
    await page3.evaluate((token) => localStorage.setItem('jwtToken', token), user2.jwtToken);
    await page3.evaluate((id) => localStorage.setItem('userId', id), user2.userId);
    await page3.goto(`${FRONTEND_URL}/game/${roomCode}`);
    await page3.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      { timeout: 10000 }
    );

    // Should receive current game state
    await page3.waitForFunction(
      () => (window as any).__GAME_STATE__?.currentState === 'IN_PROGRESS',
      { timeout: 10000 }
    );

    const state3 = await page3.evaluate(() => (window as any).__GAME_STATE__);
    expect(state3.currentState).toBe('IN_PROGRESS');
    expect(state3.hand.length).toBe(5);
    expect(state3.opponentCounts).toBeDefined();
  });
});