# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Yanif is an online multiplayer Yaniv card game: a Spring Boot backend serving both a REST API and a STOMP/WebSocket real-time channel, plus a React frontend that is built into the backend's static resources so a single JAR serves everything.

## Commands

### Backend (project root)

```bash
./mvnw test                              # all backend tests
./mvnw test -Dtest=YanivRulesTest        # single test class
./mvnw test -Dtest=YanivRulesTest#methodName  # single test method
mvn clean package                        # build JAR (target/)
./mvnw spring-boot:run                   # run backend on :8080
```

Backend tests use H2 in-memory via the `test` profile (`src/test/resources/application-test.yml`) and exercise `GameService` directly (not WebSocket controllers), so they need no external services.

### Frontend (`frontend/`)

```bash
npm start                # CRA dev server with proxy to localhost:8080
npm test                 # react-scripts tests
npm run test:e2e         # Playwright e2e (requires backend on :8080 AND frontend running)
npm run build            # versioned production build, copied to ../src/main/resources/static/
npm run build:local      # local build without copying
```

E2E specs live in `frontend/e2e/`; see `scripts/run-all-tests.sh` to run backend + e2e together.

### Running locally

Backend needs MySQL on localhost:3306 (db `yanif`, root/root) and Redis on localhost:6379. If you don't run them locally, SSH tunnels to the production box are documented in `docs/build-deploy.md`.

### Versioning / release

```bash
./bump-version.sh patch|minor|major     # bumps app.version in application.properties AND frontend/package.json
```

`npm run build` injects `app.version` as `REACT_APP_VERSION` and copies output into `src/main/resources/static/`. Never edit files under `static/` — they are build artifacts. Deploy steps are in `docs/build-deploy.md`.

## Architecture

### Two channels, split by responsibility

- **REST** (`controller/`, base `/api/v1`): auth/session resolution (`users/resolve`), room create/join/lookup (`rooms`), friends CRUD, version check. Used for lobby-era operations and identity.
- **STOMP over WebSocket** (`websocket/`, endpoint `/ws`, app prefix `/app`, broker `/queue` + `/topic`): everything during a game. Clients send actions to `/app/room/{roomId}/action`, `/call-yaniv`, `/contest-yaniv`, `/join`, `/start`, `/next-round`, etc.; the server pushes per-player state to **user destinations** (`/user/queue/game-state`) rather than broadcasting one shared payload — each player receives a view filtered to their own hand.

Auth: JWT (jjwt) via `JwtAuthenticationFilter`. The HTTP endpoint `/ws/**` is permitAll but the STOMP CONNECT frame carries the JWT in an `Authorization: Bearer` header (see `WebSocketConfig`). Frontend passes it in `StompContext.tsx`.

### Live game state is in-memory only

`GameStateController` holds `Map<String, YanivGameEngine>` — active game engines live only in server memory (restarting the server drops games in progress). `YanivGameEngine` (`game/`) is the pure rules engine; `game/model/` has Card/Deck/Hand/DiscardPile and `game/validator/CardCombinationValidator` validates sets/runs before discard. Only outcomes persist to MySQL via JPA entities: `User`, `Game`, `GamePlayer`, `RoundHistory`, `Friendship` (scores/history survive restarts).

Other in-memory machinery inside `GameStateController` worth knowing about: a `ScheduledExecutorService` drives Yaniv contest timers, action IDs are deduplicated per player, and disconnect/reconnect handling keeps disconnected players "in" an active game (`SessionDisconnectEvent`/`SessionConnectedEvent` listeners) until the game ends.

- **Redis** is used only for presence: `PresenceService` stores TTL'd statuses (`ONLINE`, `OFFLINE`, `IN_GAME`, `DISCONNECTED_IN_GAME`) keyed by userId, refreshed by client heartbeats (`PresenceController`).

### Frontend structure

React 18 + CRA + TypeScript. State is Zustand stores (`stores/authStore.ts`, `stores/gameStore.ts`) fed by two React contexts that wrap the whole app: `AuthContext` (token lifecycle) and `StompContext` (single STOMP connection, auto-reconnect delay 3s). Routing: `/login` → AuthView, `/home` and `/join/:roomCode` → MainView, which hosts LobbyView/GameView/TableCanvas. Card SVGs are preloaded at app mount (`utils/cardPreload.ts`) and served from `/cards/*`.

When adding a game feature, the path is typically: engine method in `YanivGameEngine` → message handler in `GameStateController` → state push to `/user/queue/game-state` → handler in `GameView.tsx` writing to `gameStore`.

## Reference docs

- `docs/prd.md` — product requirements
- `docs/yaniv-rules.md` — game rules (scoring, asaf penalties, valid combinations)
- `docs/ui-ux-spec.md` — UI spec
- `docs/automated-tests.md` — full testing guide
- `docs/mysql.setup.md`, `docs/redis-setup.md`, `docs/build-deploy.md` — infrastructure
