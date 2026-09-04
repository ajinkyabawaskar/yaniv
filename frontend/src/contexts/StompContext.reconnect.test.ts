/**
 * Reconnection depends on one structural detail that is easy to get wrong and
 * invisible until someone's phone locks.
 *
 * @stomp/stompjs calls `webSocketFactory` on EVERY connection attempt, including
 * every automatic retry. A factory that closes over a single long-lived socket
 * hands the same already-closed instance back on each retry, so the client retries
 * forever and never reconnects. The only way out is a full remount — logging out
 * and back in. That shipped in the initial commit and hid behind an `isConnected`
 * flag that never went false, so the UI claimed everything was fine.
 *
 * There is no component-test harness in this repo, so this reads the source. Crude,
 * but it pins the exact shape of the bug rather than trusting nobody reintroduces it.
 */
import fs from 'fs';
import path from 'path';

const SOURCE = fs.readFileSync(path.join(__dirname, 'StompContext.tsx'), 'utf8');

describe('STOMP reconnection', () => {
  it('builds a new socket on every connection attempt', () => {
    expect(SOURCE).toMatch(/webSocketFactory:\s*\(\)\s*=>\s*\n?\s*new SockJS\(/);
  });

  it('never constructs a socket outside the factory', () => {
    const constructions = SOURCE.match(/new SockJS\(/g) ?? [];
    expect(constructions).toHaveLength(1);
  });

  it('stops retrying and re-authenticates when the server refuses the token', () => {
    // A refused token is refused identically on every retry, so the loop never ends by
    // itself. Both halves are required: deactivate() stops the retries, sessionExpired()
    // is the only thing that can produce a token the server will accept.
    expect(SOURCE).toContain('client.deactivate()');
    expect(SOURCE).toContain('sessionExpired()');
  });

  it('reads the auth reason from the header the server actually sends', () => {
    // Cross-language contract: config/StompAuthErrorHandler.java spells this too. A
    // mismatch does not throw -- it silently restores the endless retry loop.
    expect(SOURCE).toContain("const AUTH_ERROR_HEADER = 'x-auth-error'");

    const handler = fs.readFileSync(
      path.join(__dirname, '../../../src/main/java/shop/abwork/yanif/config/StompAuthErrorHandler.java'),
      'utf8'
    );
    expect(handler).toContain('AUTH_ERROR_HEADER = "x-auth-error"');
  });

  it('does not tear down the session for a non-auth STOMP error', () => {
    // A server fault should keep retrying, not log the player out mid-game. The teardown
    // has to sit behind the header check rather than run for every error frame.
    const onStompError = SOURCE.slice(
      SOURCE.indexOf('client.onStompError'),
      SOURCE.indexOf('const handlePageHide')
    );
    expect(onStompError).toMatch(/if \(frame\.headers\[AUTH_ERROR_HEADER\]\)/);
    expect(onStompError.indexOf('if (frame.headers[AUTH_ERROR_HEADER])'))
      .toBeLessThan(onStompError.indexOf('sessionExpired()'));
  });

  it('comes back when the page is restored from the back/forward cache', () => {
    // pagehide closes the socket; without a matching pageshow the client sits on a
    // dead one with its retry timer frozen by the browser, and never wakes up.
    expect(SOURCE).toContain("addEventListener('pagehide'");
    expect(SOURCE).toContain("addEventListener('pageshow'");
  });
});
