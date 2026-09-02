# Product Requirement Document (PRD): Yaniv Web App

## 1. Executive Summary & Goals

* **Product Name:** Yaniv Online Platform
* **Target Audience:** Casual card game players and friends playing remotely.
* **Core Value Proposition:** A real-time, zero-friction multiplayer web app for playing Yaniv. Requires **no traditional registration or password logins**; users pick a display name and are identified via client-side browser fingerprinting. Players can maintain a lightweight friends list, monitor real-time online/offline statuses, and join games via direct invite toasts, shareable 6-character room codes, or 1-click URL invite links.
* **Key Architecture Pillars:**
* **Frontend:** React (SPA) + `@stomp/stompjs` + `@fingerprintjs/fingerprintjs` (Client-side identity) + CSS/Framer Motion animations.
* **Backend:** Spring Boot 3.x (REST for room/friend metadata, STOMP WebSockets over SockJS for game state & presence).
* **In-Memory Store:** Redis (Pub/Sub for WebSocket node scaling, active game session state, and global user presence tracking).
* **Database:** MySQL (Fingerprint profiles, display names, friend relationships, match histories).

---

## 2. Identity, Social & Invite System Architecture

### 2.1 Identity Resolution (No-Login Auth)

1. **Fingerprint Capture:** Upon opening the React app, a client-side library (e.g., `@fingerprintjs/fingerprintjs`) generates a unique `fingerprintHash`.
2. **First Visit:** If the `fingerprintHash` is unknown to MySQL, the UI prompts for a **Display Name** (stored in `localStorage` for convenience) and registers a new record in MySQL.
3. **Repeat Visit:** The server resolves the `fingerprintHash` to an existing `user_id`. The user can edit their display name anytime.
4. **Session Authentication:** Spring issues a lightweight JWT containing `userId` and `fingerprintHash` to authenticate subsequent REST and STOMP connections.

### 2.2 Presence & Friend Social Engine

* **Presence Tracking:** When a WebSocket connection opens, Redis registers `user:presence:{userId} = "ONLINE"`. Heartbeats refresh this key. On socket disconnect (or timeout), the key transitions to `"OFFLINE"`.
* **Presence Broadcasting:** Subscriptions to `/user/queue/friends-presence` push real-time online/offline status updates to all mutual friends.
* **Friend Invitations:** Users can add friends using another user's public 8-character `Friend Code` (derived from their `fingerprintHash`).

---

## 3. System Architecture & Component Mapping

```
               ┌──────────────────────────────────────────────────┐
               │              React Frontend SPA                  │
               │   (Browser Fingerprinting + STOMP Client)        │
               └────────┬─────────────────────────────────┬───────┘
                        │ REST                            │ WebSocket (STOMP)
                        ▼                                 ▼
       ┌──────────────────────────────┐    ┌──────────────────────────────┐
       │   Spring Boot REST Layer     │    │ Spring WebSocket Gateway     │
       │ (Auth, Rooms, Friends API)   │    │ (State Engine & Presence)    │
       └──────────────┬───────────────┘    └──────────────┬───────────────┘
                      │                                   │
                      │ JDBC/JPA                          │ Pub/Sub & Presence Cache
                      ▼                                   ▼
               ┌──────────────┐                    ┌──────────────┐
               │  MySQL DB    │                    │ Redis Cache  │
               └──────────────┘                    └──────────────┘

```

| Component | Technology | Primary Responsibility |
| --- | --- | --- |
| **Persistence** | MySQL | Users (Fingerprint ID + Display Name), Friendships, Games Log, Round History. |
| **Cache & Pub/Sub** | Redis | Room state cache, user presence keys (`ONLINE`/`OFFLINE`/`IN_GAME`), WS message fanout. |
| **Backend Framework** | Spring Boot 3.x | JWT-via-Fingerprint filter, REST APIs, Yaniv State Machine, Friend & Invite Handlers. |
| **Real-time Engine** | Spring WebSockets + STOMP | Full-duplex turn actions, masked state pushes, presence notifications, invite toasts. |
| **Frontend Framework** | React 18+ (TS) | Fingerprint generation, friends sidebar, game table UI, toast notifications. |

---

## 4. Core Functional Requirements

### 4.1 Room Creation & Invite Mechanics

Users can join games through three distinct pathways:

1. **Direct Friend Invite (In-App Toast):**

* Host clicks "Invite" next to an online friend in the sidebar.
* Target player receives a real-time STOMP notification pop-up with **[Accept]** / **[Decline]** buttons.

2. **Deep-Link URL:**

* Host copies a unique URL (`[https://yaniv.app/join/RMX92A](https://yaniv.app/join/RMX92A)`).
* Recipient clicking the link lands in the app; identity is auto-resolved via fingerprinting, and they bypass the lobby straight into the room.

3. **6-Character Room Code:**

* Host shares code `RMX92A`. Any user enters this code on the homepage to join.

### 4.2 Gameplay Rules & Logic

All game mechanics, state transitions, discard/pickup validations, and scoring penalties are defined in **`yaniv-rules.md`**. The backend State Machine must strictly adhere to the rules defined in that document, including:

* Sequence and Set validation logic.
* Hand-clearing mixed-suit sequence exceptions.
* Pick-up rules (Draw pile vs. Discard pile).
* Yaniv/Asaf call evaluations and scoring resets.

---

## 5. API & WebSocket Interface Specifications

### 5.1 REST Endpoints (HTTPS)

* `POST /api/v1/users/resolve` — Input: `{ fingerprintHash, displayName }`. Returns JWT session token & user record.
* `PUT /api/v1/users/profile` — Update display name.
* `POST /api/v1/friends/request` — Send friend request via `friendCode`.
* `POST /api/v1/friends/respond` — Accept/decline friend request.
* `POST /api/v1/rooms` — Create room with parameters (max players, penalty limit).
* `GET /api/v1/rooms/code/{roomCode}` — Resolve room code/URL link for joining.

### 5.2 WebSocket Topics & Messages (STOMP)

#### Friend & Presence Subscriptions

* `SUBSCRIBE /user/queue/presence` — Pushes online status changes of friends (`ONLINE`, `OFFLINE`, `IN_GAME`).
* `SUBSCRIBE /user/queue/invites` — Inbound room invites:

```json
{
  "inviteId": "inv_8821",
  "hostDisplayName": "Alex",
  "roomCode": "RMX92A",
  "targetScore": 200
}

```

* `SEND /app/friends/invite` — Host invites a friend: `{ "friendUserId": "usr_771", "roomCode": "RMX92A" }`.

#### Game Action & State Pushes

* `SEND /app/room/{roomId}/action` — Player turn submission:

```json
{
  "actionType": "DISCARD_AND_DRAW",
  "playerId": "usr_9921",
  "discardedCardIds": ["c_101", "c_102"],
  "drawSource": "DISCARD_PILE", 
  "drawnCardId": "c_99" 
}

```

* `SUBSCRIBE /user/queue/game-state` — Masked per-player game view:

```json
{
  "gameId": "game_303",
  "currentTurnPlayerId": "usr_9921",
  "turnPhase": "AWAITING_ACTION",
  "myHand": [
    {"id": "c_12", "suit": "HEARTS", "rank": "FIVE", "value": 5},
    {"id": "c_88", "suit": "SPADES", "rank": "KING", "value": 10}
  ],
  "opponentCounts": { "usr_4412": 4, "usr_8819": 5 },
  "topDiscardPile": [{"id": "c_99", "suit": "CLUBS", "rank": "TEN"}],
  "drawableDiscardCardIds": ["c_99"],
  "drawDeckCount": 32,
  "scores": { "usr_9921": 18, "usr_4412": 42 }
}

```

---

## 6. Database Schema (MySQL)

```sql
CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    fingerprint_hash VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(50) NOT NULL,
    friend_code VARCHAR(8) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_fingerprint (fingerprint_hash),
    INDEX idx_friend_code (friend_code)
);

CREATE TABLE friendships (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id_1 VARCHAR(36) NOT NULL,
    user_id_2 VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL, -- PENDING, ACCEPTED, BLOCKED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_friendship (user_id_1, user_id_2),
    FOREIGN KEY (user_id_1) REFERENCES users(id),
    FOREIGN KEY (user_id_2) REFERENCES users(id)
);

CREATE TABLE games (
    id VARCHAR(36) PRIMARY KEY,
    room_code VARCHAR(6) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL, -- LOBBY, IN_PROGRESS, FINISHED
    target_score INT NOT NULL DEFAULT 200,
    winner_id VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (winner_id) REFERENCES users(id)
);

CREATE TABLE game_players (
    game_id VARCHAR(36),
    user_id VARCHAR(36),
    final_score INT,
    placement INT,
    PRIMARY KEY (game_id, user_id),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE round_histories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id VARCHAR(36) NOT NULL,
    round_number INT NOT NULL,
    caller_id VARCHAR(36) NOT NULL,
    is_asaf BOOLEAN NOT NULL DEFAULT FALSE,
    asaf_by_user_id VARCHAR(36),
    round_scores_json JSON NOT NULL,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

```

---

## 7. Front-End Technical Specifications (React)

* **Identity Initialization Component (`AuthProvider`):**
* Computes browser fingerprint using `ThumbmarkJS` on mount.
* Calls `/api/v1/users/resolve` silently in the background. If first-time user, displays a lightweight modal asking: *"Enter your Display Name to start playing"*.
* **UI Structure:**
* `FriendsSidebar`: Collapsible panel showing online/offline friends, presence indicators (Green = Online, Yellow = In Game, Gray = Offline), and "Invite to Room" action buttons.
* `NotificationToastContainer`: Floating overlay to display incoming game invitations.
* `LobbyView`: Direct URL sharing widget with a 1-click "Copy Link" button and display of joined players.
* `TableCanvas`: Interactive card table with drag-and-drop or click-to-select mechanics, draw/discard actions, and Yaniv call buttons.

---

## 8. Non-Functional Requirements (NFRs)

* **Zero-Friction Onboarding:** Complete fingerprint resolution and lobby entry in $< 300\text{ ms}$.
* **Presence Accuracy:** Disconnect detection and presence updates delivered to online friends within $< 2\text{ seconds}$ via Redis heartbeat eviction.
* **Latency:** Turn actions and state pushes executed in $< 100\text{ ms}$.
* **Scalability:** Horizontal scaling supported across multiple Spring Boot nodes via Redis WebSocket Pub/Sub.