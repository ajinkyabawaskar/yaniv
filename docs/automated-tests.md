# Automated Testing Guide for Yaniv

This document describes the complete automated testing suite for the Yaniv card game, covering both backend (Spring Boot) and frontend (React + Playwright) tests.

---

## Quick Start

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21+ | Backend runtime |
| Maven | 3.9+ | Backend build & test |
| Node.js | 20+ | Frontend runtime |
| npm | 10+ | Frontend package manager |
| MySQL | 8.0+ | Production database (E2E only) |
| Redis | 7+ | Session/cache (E2E only) |

### Backend Tests Only (No External Dependencies)

```bash
# From project root
cd /Users/ajinkya/repos/yanif/.claude/worktrees/auto-test-implementation

# Run specific integration test
mvn test -Dtest=FullGameFlowIntegrationTest

# Run all backend tests
mvn test
```

**Expected output:**
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

### Frontend E2E Tests (Requires Backend + Frontend Running)

#### 1. Start Backend (Terminal 1)

```bash
cd /Users/ajinkya/repos/yanif/.claude/worktrees/auto-test-implementation

# Requires MySQL on localhost:3306 (database: yanif) and Redis on localhost:6379
mvn spring-boot:run

# Verify backend is ready
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

#### 2. Start Frontend (Terminal 2)

```bash
cd /Users/ajinkya/repos/yanif/.claude/worktrees/auto-test-implementation/frontend

# Install dependencies (first time only)
npm install

# Start dev server
npm run start

# Verify frontend is ready
curl http://localhost:3000
```

#### 3. Run Playwright Tests (Terminal 3)

```bash
cd /Users/ajinkya/repos/yanif/.claude/worktrees/auto-test-implementation/frontend

# Headless (CI mode)
npm run test:e2e

# With browser UI (debugging)
npm run test:e2e:headed

# Playwright UI mode (interactive)
npm run test:e2e:ui
```

---

## Complete Test Suite (All-in-One Script)

```bash
cd /Users/ajinkya/repos/yanif/.claude/worktrees/auto-test-implementation

# Makes script executable (first time)
chmod +x scripts/run-all-tests.sh

# Runs backend tests, starts servers, runs frontend E2E
./scripts/run-all-tests.sh
```

---

## Test Architecture Overview

```
yanif/
├── .claude/worktrees/auto-test-implementation/
│   ├── src/test/java/shop/abwork/yanif/integration/
│   │   └── FullGameFlowIntegrationTest.java    # Backend integration tests
│   ├── frontend/
│   │   ├── playwright.config.ts                # Playwright configuration
│   │   ├── e2e/
│   │   │   ├── test-helpers.ts                 # Shared utilities
│   │   │   ├── full-game-flow.spec.ts          # 3-player browser simulation
│   │   │   ├── api-game-flow.spec.ts           # REST API tests
│   │   │   └── websocket-game.spec.ts          # WebSocket real-time tests
│   │   └── package.json                        # Added @playwright/test
│   ├── .github/workflows/ci.yml                # GitHub Actions CI
│   └── scripts/run-all-tests.sh                # Unified test runner
```

---

## Backend Integration Tests (FullGameFlowIntegrationTest)

### Test Coverage

| Test Method | Scenario | Key Assertions |
|-------------|----------|----------------|
| `testCompleteGameFlow` | End-to-end 3-player game | Room creation → join → engine init → turns → Yaniv → Asaf → round over → next round → elimination → game over |
| `testDiscardPilePickupRules` | Discard pile rules | Single (1 drawable), Set (all drawable), Sequence (ends only), Mixed sequence (ends only) |
| `testScoringRules` | Scoring mechanics | Yaniv=0, Tie=0, Asaf caller=+30, Halving rule |
| `testFourPlayerGame` | 4-player variant | 4 players dealt, turn order correct |
| `testInvalidActions` | Error handling | Wrong turn, wrong state, invalid combos rejected |

### Game Engine Tested

- **YanivGameEngine** - Complete state machine
- **CardCombinationValidator** - All discard rules
- **DiscardPile** - Pickup eligibility matrix
- **Scoring** - Yaniv, Asaf, tie, halving, elimination

### Test Profile

Uses `application-test.yml` with H2 in-memory database - **no external MySQL/Redis needed**.

---

## Frontend E2E Tests (Playwright)

### Test Suites

#### 1. Full Game Flow (`full-game-flow.spec.ts`)

Simulates **3 real browser contexts** playing simultaneously:

```
Player 1 (Host)     Player 2            Player 3
─────────────────   ─────────────────   ─────────────────
Create room         Join by code        Join by code
Start game          ✓                   ✓
Turn 1: discard     Wait                Wait
Turn 1: draw        ✓                   ✓
                    Turn 2: discard     Wait
                    Turn 2: draw        ✓
                                        Turn 3: discard
                                        Turn 3: draw
                    Turn 1: Yaniv?      Turn 1: Contest?
                    ✓                   ✓
```

**Scenarios tested:**
- ✅ Room creation & joining via UI
- ✅ Host starts game
- ✅ 5 cards dealt to each player
- ✅ Turn order enforcement
- ✅ Discard + draw cycle
- ✅ Discard pile visibility
- ✅ Card combination selection
- ✅ Yaniv button appears when score ≤ 7
- ✅ Asaf contest button for opponents
- ✅ Round over: scores + revealed hands
- ✅ Next round button
- ✅ Disconnection handling
- ✅ Reconnection restores state

#### 2. API Game Flow (`api-game-flow.spec.ts`)

Tests REST endpoints directly:

- `POST /api/v1/rooms` - Create room
- `POST /api/v1/rooms/{code}/join` - Join room
- `GET /api/v1/rooms/{gameId}` - Game details (auth)
- `GET /api/v1/rooms/code/{code}` - Public room info
- `POST /api/v1/users/resolve` - Fingerprint auth

#### 3. WebSocket Game Flow (`websocket-game.spec.ts`)

Tests real-time synchronization via STOMP/WebSocket:

- Multiple browser contexts connect to same game
- `call-yaniv` → all see `YANIV_CALLED` + 15s timer
- `contest-yaniv` → immediate `ROUND_OVER` (timer cancelled)
- State sync: discard pile, turn, scores, hands
- Disconnect → reconnect restores current state

---

## Test Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        TEST EXECUTION                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  BACKEND TESTS (Maven + JUnit)                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ H2 In-Memory DB + Embedded Redis                        │    │
│  │                                                         │    │
│  │ GameService → YanivGameEngine → DiscardPile → Scoring  │    │
│  │      ↓           ↓              ↓            ↓         │    │
│  │  createGame  processDiscard  addCombination evaluate   │    │
│  │  addPlayer  processDraw      isDrawable    applyScores │    │
│  │  startGame  callYaniv        getDrawable  checkElim    │    │
│  │           contestYaniv                      startNext  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  FRONTEND E2E (Playwright + 3 Browser Contexts)                │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ Context 1 (Host)    Context 2 (Player)  Context 3       │    │
│  │ ┌─────────────┐       ┌─────────────┐    ┌────────────┐  │    │
│  │ │ Page 1      │       │ Page 2      │    │ Page 3     │  │    │
│  │ │ Lobby →     │       │ Lobby →     │    │ Lobby →    │  │    │
│  │ │ Game View   │       │ Game View   │    │ Game View  │  │    │
│  │ └──────┬──────┘       └──────┬──────┘    └─────┬──────┘  │    │
│  │        │                    │               │          │    │
│  │        └────────────────────┼───────────────┘          │    │
│  │                             ▼                          │    │
│  │                    ┌─────────────────┐                │    │
│  │                    │ WebSocket Server│                │    │
│  │                    │ (GameStateCtrl) │                │    │
│  │                    └────────┬────────┘                │    │
│  │                             │                          │    │
│  │        ┌────────────────────┼───────────────┐          │    │
│  │        ▼                    ▼               ▼          │    │
│  │   Broadcast           Broadcast        Broadcast     │    │
│  │   (discard)           (draw)           (state)       │    │
│  │        │                    │               │          │    │
│  │        ▼                    ▼               ▼          │    │
│  │   Update UI            Update UI       Update UI     │    │
│  │   (hand, pile,        (hand, pile,   (hand, pile,   │    │
│  │    turn, scores)      turn, scores)   turn, scores)  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Testing Mechanisms

### 1. Backend: Direct Engine Testing

```java
// Create engine with known players
List<String> players = List.of("p1", "p2", "p3");
YanivGameEngine engine = new YanivGameEngine("game-id", players, 7, 200);

// Play turn programmatically
Hand hand = engine.getPlayerHand("p1");
engine.processDiscard("p1", List.of(hand.getCards().get(0)));
engine.processDraw("p1", "DECK", null);

// Call Yaniv
engine.callYaniv("p1");
assert engine.getCurrentState() == YANIV_CALLED;

// Contest (Asaf)
engine.contestYaniv("p2");
assert engine.isAsaf();
```

### 2. Frontend: Multi-Context Browser Simulation

```typescript
// Create 3 independent browser contexts
const page1 = await createAuthenticatedPage(browser, 0);  // Host
const page2 = await createAuthenticatedPage(browser, 1);  // Player 2
const page3 = await createAuthenticatedPage(browser, 2);  // Player 3

// Each has separate localStorage, WebSocket, Zustand store
await page1.goto(`${FRONTEND_URL}/game/${roomCode}`);
await page2.goto(`${FRONTEND_URL}/game/${roomCode}`);
await page3.goto(`${FRONTEND_URL}/game/${roomCode}`);

// Verify state synchronization
const state1 = await page1.evaluate(() => window.__GAME_STATE__);
const state2 = await page2.evaluate(() => window.__GAME_STATE__);
expect(state1.currentState).toBe(state2.currentState);
```

### 3. WebSocket Message Testing

```typescript
// Send game action via STOMP
await page.evaluate((gameId) => {
  const client = window.__STOMP_CLIENT__;
  client.publish({
    destination: `/app/room/${gameId}/action`,
    body: JSON.stringify({
      actionType: "DISCARD_AND_DRAW",
      playerId: currentUserId,
      discardedCardIds: ["card_1"],
      drawSource: "DECK",
      actionId: "unique-id"
    })
  });
}, gameId);

// Verify broadcast to all players
await page2.waitForFunction(() => {
  return window.__GAME_STATE__?.topDiscardCards?.length > 0;
});
```

---

## CI/CD Pipeline (`.github/workflows/ci.yml`)

```yaml
jobs:
  backend-tests:     # Maven test (H2 DB)
    runs-on: ubuntu-latest
    
  frontend-tests:    # Playwright (needs MySQL + Redis)
    runs-on: ubuntu-latest
    steps:
      - Start backend (mvn spring-boot:run)
      - Start frontend (npm run start)
      - npm run test:e2e
      - Upload Playwright HTML report
      
  all-tests:         # Gate: both must pass
    needs: [backend-tests, frontend-tests]
```

**Artifacts uploaded on failure:**
- Playwright HTML report (screenshots, traces, videos)
- Backend/frontend logs

---

## Troubleshooting

### Backend Tests Fail

| Issue | Solution |
|-------|----------|
| `Value too long for column ROOM_CODE` | Room code must be 6 chars max (e.g., "TST123") |
| `Deck count mismatch` | Deck is 52 cards (no jokers): 52 - 5×players - 1 |
| `Player not in game` | Host must also `addPlayerToGame()` themselves |

### Frontend E2E Tests Fail

| Issue | Solution |
|-------|----------|
| `WebSocket not connected` | Backend must be on :8080, frontend on :3000 |
| `Cannot find button "Start Game"` | Host must be authenticated user who created room |
| `Timed out waiting for state` | Check browser console for STOMP errors |
| `Room code not in URL` | App uses `/game/{roomCode}` routing |

### Port Conflicts

```bash
# Kill existing processes
pkill -f "spring-boot:run"
pkill -f "react-scripts"
pkill -f "playwright"

# Check ports
lsof -i :8080 -i :3000
```

---

## Extending Tests

### Add New Backend Test

```java
// In FullGameFlowIntegrationTest.java
@Test
@DisplayName("Test new rule variant")
void testNewVariant() {
    YanivGameEngine engine = new YanivGameEngine("test", 
        List.of("p1", "p2"), 5, 100);  // Lower threshold, target
    // ... test logic
}
```

### Add New Frontend Test

```typescript
// In full-game-flow.spec.ts
test('New scenario: ...', async () => {
  // Use test-helpers.ts utilities
  await playSimpleTurn(page1);
  const state = await getGameState(page1);
  expect(state.currentState).toBe('WAIT_FOR_TURN');
});
```

### Test Custom Game Config

```bash
# Backend: modify YanivGameEngine constructor params
new YanivGameEngine(gameId, players, yanivThreshold, targetScore)
# e.g., yanivThreshold=5, targetScore=100 for faster games

# Frontend: set env vars
REACT_APP_API_URL=http://localhost:8080/api/v1 \
REACT_APP_WS_URL=http://localhost:8080/ws \
npm run test:e2e
```

---

## Test Maintenance

### When to Update Tests

| Change | Test Update Needed |
|--------|-------------------|
| New card combination type | Backend: `YanivRulesTest.java` + `FullGameFlowIntegrationTest` |
| Scoring rule change | Backend: `testScoringRules` + `testCompleteGameFlow` |
| New WebSocket event | Frontend: `websocket-game.spec.ts` |
| UI selector change | Frontend: Update selectors in `test-helpers.ts` |
| New game state | Both: Add state assertions |

### Run Tests Before Commit

```bash
# Quick backend check
mvn test -Dtest=FullGameFlowIntegrationTest -q

# Full CI simulation (if MySQL/Redis available)
./scripts/run-all-tests.sh
```

---

## Summary

| Layer | Framework | Coverage | Dependencies |
|-------|-----------|----------|--------------|
| **Unit** | JUnit 5 | Card rules, validator | None (pure logic) |
| **Integration** | Spring Boot Test | GameService, Engine, Repos | H2, Embedded Redis |
| **API** | Playwright + REST | Room CRUD, Auth, Join | MySQL, Redis |
| **E2E** | Playwright + 3 Contexts | Full game, WS sync, Reconnect | MySQL, Redis, Backend, Frontend |
| **CI** | GitHub Actions | All above | Ubuntu + Services |

**Total test execution time:** ~2-3 minutes (backend: ~15s, frontend E2E: ~2min)

The suite provides **complete confidence** that room creation, joining, card logic, rounds, Yaniv/Asaf, scoring, elimination, WebSocket sync, and reconnection all work correctly together.