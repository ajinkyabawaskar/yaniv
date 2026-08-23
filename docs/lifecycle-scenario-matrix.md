# Game Lifecycle Scenario Matrix

Every row is encoded as a test in
`src/test/java/shop/abwork/yanif/websocket/GameLifecycleScenariosTest.java`
(method named after the row ID). Legend: **C** = connected, **D** = disconnected-in-game.

## A. Game start

| ID | Scenario | Expected |
|----|----------|----------|
| A1 | Host starts game in LOBBY (all C) | Engine created, snapshot persisted, status IN_PROGRESS, every player gets a state push, NO turn timer armed |
| A2 | Non-host sends `/start` | Rejected ("Only host can start game"), no engine, no snapshot |
| A3 | Host starts while stale snapshot from an earlier game exists | Fresh deal overwrites map + snapshot; no resurrection of old hands |
| A4 | Start with only 1 player in room | Server rejects - cannot have a 1-player game |
| A5 | Second `/start` while game IN_PROGRESS | Rejected - game already running |

## B. Disconnect (per current phase)

| ID | Scenario | Expected |
|----|----------|----------|
| B1 | D on own turn (WAIT_FOR_TURN) | Marked disconnected, presence DISCONNECTED_IN_GAME, others notified, turn timer armed immediately |
| B2 | D during opponent's turn | Marked + notified, NO timer armed yet |
| B3 | D during YANIV_CALLED | Contest timer unaffected, still resolves at expiry |
| B4 | D during ROUND_OVER | No timers; Next Round blocked until reconnect or all-gone auto-advance |
| B5 | D after GAME_OVER | Treated as plain offline; presence cleared; no timers |
| B6 | D while not in any room/game | No-op beyond presence |
| B7 | ALL players go D mid-round | Auto-play chain runs turns to completion: yaniv → contest expiry → ROUND_OVER auto-advance → next round … until GAME_OVER persists finishGame, deletes snapshot, evicts engine |

## C. Reconnect

| ID | Scenario | Expected |
|----|----------|----------|
| C1 | Reconnect while own turn timer pending | Removed from disconnected set, presence back IN_GAME, others notified, timer disarmed for them |
| C2 | Reconnect after own auto-play already fired | Receives fresh state showing the auto-played move; game continues normally |
| C3 | Reconnect during ROUND_OVER | Gets results state (hands revealed), can trigger next round |
| C4 | Reconnect after server restart mid-game | Restored from snapshot: identical hand/deck/discard/turn |
| C5 | Reconnect when DB says IN_PROGRESS but snapshot missing (pre-snapshot deploy) | Room aborted to LOBBY exactly once; lobby state pushed |
| C6 | Reconnect when DB says FINISHED/LOBBY but stale snapshot exists | Snapshot discarded+deleted, NOT restored into game view |
| C7 | Reconnect of a user not in any room | Ignored |

## D. Actions & races

| ID | Scenario | Expected |
|----|----------|----------|
| D1 | Human acts just before timer expiry (D player returns then acts) | Exactly one move applied; no auto-play marker ever |
| D2 | Timer task fires after human already acted (stale validation) | Task no-ops; no duplicate mutation |
| D3 | Duplicate actionId replay | Deduplicated; fresh state resent; single mutation |
| D4 | Action by non-current player | "Not your turn" error; no mutation |
| D5 | Action where playerId ≠ authenticated user | Rejected |
| D6 | Action with invalid combination | Engine rejects; error sent; no mutation |

## E. Round end / Yaniv / Game end

| ID | Scenario | Expected |
|----|----------|----------|
| E1 | Yaniv call by connected player | YANIV_CALLED broadcast; exactly ONE contest-resolve scheduled |
| E2 | Contest inside window | Immediate resolve, resolve timer cancelled, ROUND_OVER broadcast |
| E3 | Nobody contests | Auto-resolve at window end → ROUND_OVER |
| E4 | Auto-played (disconnected) caller's Yaniv | Contest window still scheduled and resolves |
| E5 | RoundHistory persisted once per completed round | Exactly one row per round transition |
| E6 | Two players click Next Round concurrently | First advances; second rejected; no double deal |
| E7 | ROUND_OVER with ≥1 connected player | Never auto-advances |
| E8 | ROUND_OVER all-D, one reconnects before advance fires | Advance task no-ops (round number guard) |
| E9 | GAME_OVER reached | finishGame(winner) persisted, snapshot deleted, engine evicted |
| E10 | Action after GAME_OVER cleanup | Clean "Game not found" error, no resurrect |

## F. Storage faults

| ID | Scenario | Expected |
|----|----------|----------|
| F1 | saveGameState throws during action | Action still succeeds and broadcasts; warning logged |
| F2 | Corrupt snapshot JSON | Treated as absent → confirmed-miss path (abort if DB IN_PROGRESS) |
| F3 | Redis read throws during restore check | No abort, no restore; caller gets clean error |
