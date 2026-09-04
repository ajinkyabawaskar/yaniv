import { test, expect, Page } from '@playwright/test';
import {
  createApiUser,
  openSeatedPage,
  getState,
  pullGameState,
  BACKEND_URL,
} from './test-helpers';

/**
 * Losing the connection and getting it back.
 *
 * This path was broken from the initial commit and nobody noticed, because
 * `isConnected` never went false — the UI claimed all was well while the socket
 * was dead. Once it started reporting the truth the real fault surfaced: the
 * client could never reconnect, and the only way back was logging out.
 *
 * Two halves, and both have to work:
 *   1. the socket comes back without a reload;
 *   2. the server sees the resubscribe, so the other players stop being told
 *      this one is away.
 *
 * The second is the one people actually complain about, and no unit test in
 * either language can reach it — it spans a browser, a STOMP session, and the
 * server's presence bookkeeping.
 */

test.describe.configure({ mode: 'serial' });

/** Parse, but say what actually came back — "Unexpected end of JSON input" names nothing. */
async function asJson(response: Response, what: string): Promise<any> {
  const body = await response.text();
  if (!response.ok || !body) {
    throw new Error(`${what} failed: HTTP ${response.status} ${body || '(empty body)'}`);
  }
  return JSON.parse(body);
}

async function createRoom(jwt: string): Promise<{ gameId: string; roomCode: string }> {
  const response = await fetch(`${BACKEND_URL}/api/v1/rooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${jwt}` },
    body: JSON.stringify({ targetScore: 200, maxPlayers: 6 }),
  });
  return asJson(response, 'create room');
}

async function joinRoom(jwt: string, roomCode: string): Promise<void> {
  await fetch(`${BACKEND_URL}/api/v1/rooms/${roomCode}/join`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${jwt}` },
  });
}

const connected = (page: Page) =>
  page.evaluate(() => !!(window as any).__STOMP_CLIENT__?.connected);

/** How the roster on someone else's screen describes this player. */
async function statusOf(observer: Page, userId: string): Promise<string | undefined> {
  const state = await getState(observer);
  return state?.players?.find((p: any) => p.userId === userId)?.status;
}

test.describe('Reconnection', () => {
  let host: Awaited<ReturnType<typeof createApiUser>>;
  let guest: Awaited<ReturnType<typeof createApiUser>>;
  let hostPage: Page;
  let guestPage: Page;
  let roomCode: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    // The project name and a random suffix, not just a timestamp: the projects run in
    // parallel workers, and two of them landing on the same millisecond resolve to the
    // same account — after which they fight over one user's rooms.
    const stamp = `${testInfo.project.name}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    host = await createApiUser(`reconnect-host-${stamp}`, 'ReconnectHost');
    guest = await createApiUser(`reconnect-guest-${stamp}`, 'ReconnectGuest');

    ({ roomCode } = await createRoom(host.jwtToken));
    await joinRoom(guest.jwtToken, roomCode);

    hostPage = await openSeatedPage(browser, host, roomCode);
    guestPage = await openSeatedPage(browser, guest, roomCode);
  });

  test.afterAll(async () => {
    await hostPage?.close();
    await guestPage?.close();
  });

  test('a dropped socket reconnects without reloading the page', async () => {
    expect(await connected(guestPage)).toBe(true);

    // Kill it the way a locked phone or a dead network does: no DISCONNECT frame.
    await guestPage.evaluate(() => (window as any).__STOMP_CLIENT__?.forceDisconnect());

    await guestPage.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === false,
      undefined,
      { timeout: 10000 }
    );

    // No reload, no re-login: the client's own retry has to carry it back. The
    // factory must mint a new socket for that retry, or it re-offers the dead one
    // forever and this never resolves.
    await guestPage.waitForFunction(
      () => (window as any).__STOMP_CLIENT__?.connected === true,
      undefined,
      { timeout: 30000 }
    );
  });

  test('the reconnected player receives game state again', async () => {
    // Proves the resubscribe happened, not merely the socket: a fresh push has to
    // arrive on the room destination.
    await pullGameState(guestPage);
    const state = await getState(guestPage);
    expect(state).toBeTruthy();
    expect(state.gameId).toBeTruthy();
  });

  test('the other player stops seeing them as away', async () => {
    await guestPage.waitForTimeout(500); // let the attach propagate
    await pullGameState(hostPage);

    await expect
      .poll(async () => statusOf(hostPage, guest.userId), { timeout: 15000 })
      .not.toBe('DISCONNECTED_IN_GAME');
  });
});
