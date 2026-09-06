/* k6 WebSocket load script: concurrent room traffic guardrails.
 *
 * Simulates N VUs that resolve a user, create/join a room, open a STOMP-over-
 * SockJS session is out of scope for k6's ws API, so this script measures the
 * layers beneath it that bound action latency:
 *   1. REST resolve + room create/join RTT (auth/session path).
 *   2. Raw WebSocket connect RTT to /ws (handshake cost).
 *
 * Game-action RTT (throw cards / call Yaniv) is covered by the Playwright
 * websocket-game suite against a live table; the thresholds below guard the
 * transport so regressions in pooling/TLS/handshake show up here first.
 *
 * Run:  k6 run -e BACKEND_URL=http://localhost:8080 scripts/perf/k6-ws-actions.js
 *
 * Guardrails (fail the run on breach):
 *   - p95 http_req_duration < 800ms
 *   - ws_connecting duration p95 < 1500ms
 *   - failed checks < 1%
 */
import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 }, // ramp to 20 concurrent rooms
    { duration: '1m', target: 20 }, // sustain
    { duration: '15s', target: 0 }, // drain
  ],
  thresholds: {
    http_req_duration: ['p(95)<800'],
    ws_connecting: ['p(95)<1500'],
    checks: ['rate>0.99'],
  },
};

const BACKEND = __ENV.BACKEND_URL || 'http://localhost:8080';

function resolveUser(fp) {
  const res = http.post(
    `${BACKEND}/api/v1/users/resolve`,
    JSON.stringify({ fingerprintHash: fp, displayName: `k6-${fp.slice(0, 6)}` }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'resolve 200': (r) => r.status === 200 });
  if (res.status !== 200) fail(`resolve failed: ${res.status} ${res.body}`);
  return res.json();
}

export default function () {
  const fp = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const user = resolveUser(fp);
  const headers = { Authorization: `Bearer ${user.jwtToken}` };

  // 1. Room create (lobby path: measures auth + MySQL write + Hikari wait).
  const roomRes = http.post(
    `${BACKEND}/api/v1/rooms`,
    JSON.stringify({ targetScore: 100, maxPlayers: 4 }),
    { headers: { ...headers, 'Content-Type': 'application/json' } },
  );
  check(roomRes, { 'room create 200': (r) => r.status === 200 });

  // 2. WS handshake RTT to the STOMP endpoint (SockJS info first, then raw ws).
  const info = http.get(`${BACKEND}/ws/info`);
  check(info, { 'ws info reachable': (r) => r.status === 200 });

  const wsUrl = BACKEND.replace(/^http/, 'ws') + '/ws/websocket';
  const wsRes = ws.connect(wsUrl, {}, (socket) => {
    socket.on('open', () => socket.close());
  });
  check(wsRes, { 'ws connect ok': (r) => r && r.status === 101 });
}
