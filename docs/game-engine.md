# Game Engine — rules, scoring and lifecycle

The complete behaviour of the Yaniv rules engine, derived from the source. If you are changing
scoring, combination validation, the turn state machine or the round lifecycle, read this first.

**The code is the authority; this document tracks it.** Every claim below cites the method and
`file:line` it came from. Where a rule contradicts something in `docs/yaniv-rules.md`, `docs/prd.md`
or `docs/ui-ux-spec.md`, the contradiction is recorded explicitly in
[Documentation contradictions](#documentation-contradictions) rather than silently reconciled.

Line numbers drift; method names don't. If a citation doesn't land, search for the method name.

## Where the behaviour lives

| File | Owns |
|---|---|
| `game/YanivGameEngine.java` | The state machine, scoring, Asaf, halving, elimination, round advance |
| `game/validator/CardCombinationValidator.java` | What may be discarded (singles, sets, runs) |
| `game/model/DiscardPile.java` | What may be picked up |
| `game/model/Card.java` | Suits, ranks, scoring values, card identity |
| `game/model/Deck.java` | 52-card composition, shuffle, draw |
| `game/model/Hand.java` | Hand score |
| `game/model/DiscardCombination.java` | One discard, and the sort that decides a run's "ends" |
| `game/AutoPlayStrategy.java` | The move a bot plays for a disconnected player |
| `game/GameSnapshot.java` | The engine state that survives a restart |
| `websocket/GameStateController.java` | Orchestration: turn timers, contest timer, dedup, persistence |
| `frontend/src/utils/yanivRules.ts` | **A second copy of the discard rules, in TypeScript**, for client-side validation |
| `shared/rules-contract.json` | The case table both implementations are tested against |

> **The discard rules exist twice.** `frontend/src/utils/yanivRules.ts` reimplements combination
> validation client-side so the UI can grey out illegal selections without a round trip. Change one
> without the other and the UI will either offer a move the server rejects, or block a legal one.
>
> **A shared contract guards this.** `shared/rules-contract.json` holds one case table that both
> implementations are tested against — `RulesContractTest.java` on the server,
> `yanivRules.contract.test.ts` on the client. A rule change that updates only one side fails.
> Add a case there whenever you change a rule. The contract also pins the sequence ladder, so the
> Java and TypeScript copies of it cannot drift apart either.

Changing any of these means updating this document — `scripts/check-engine-docs.sh` enforces it at
commit time.

## Cards

**52 cards, four suits, thirteen ranks. There are no jokers** (`Deck.java:31-43`,
`Card.java:10-16`). The validator says so outright: "No Jokers in this version"
(`CardCombinationValidator.java:9`). Joker references elsewhere in the repo are dead — see
[contradictions](#documentation-contradictions).

Card ids are positional and deterministic: `card_1` … `card_52`, suit-major in the order HEARTS,
DIAMONDS, CLUBS, SPADES, and ACE→KING within each suit (`Deck.java:32-42`). This encoding is
load-bearing: deck recycling reconstructs cards from the id space rather than shuffling the pile
back in.

**`Card.equals` and `hashCode` use the id alone** — suit and rank are ignored (`Card.java:51-62`).
So `Hand.removeCard`, `Hand.containsCard` and `DiscardPile.getDrawableCard` all match by id, and the
engine will accept a fabricated `Card` carrying a real id but a lying rank (see
[Known defects](#known-defects)).

### A rank has two different values depending on context

This is the single easiest thing to get wrong in this codebase.

| Rank | Scoring value | Sequence value (Ace low) | Sequence value (Ace high) |
|---|---|---|---|
| ACE | 1 | 1 | 14 |
| 2–10 | face value | face value | face value |
| JACK | 10 | 11 | 11 |
| QUEEN | 10 | 12 | 12 |
| KING | 10 | 13 | 13 |

`Card.getValue()` returns the **scoring** value (`Card.java:47-49`). Sequence adjacency uses a
separate ladder in `CardCombinationValidator.getRankValueLow/High` (`:138-177`).

Never use `Card.getValue()` for run adjacency — a Jack, Queen and King all score 10 and would look
like a "run" of identical values. `AutoPlayStrategy.seqRankValue` carries a comment warning about
exactly this (`AutoPlayStrategy.java:197-220`).

**Both ladders live on `Card.Rank`.** Each constant carries its scoring `value` plus
`sequenceLow`/`sequenceHigh`, read through `rank.sequenceValue(aceHigh)`. `CardCombinationValidator`,
`AutoPlayStrategy` and `DiscardCombination` all call that one method — they used to keep three
private copies of the ladder between them.

The client has its own copy in `yanivRules.ts`, unavoidably, since it is a different language. The
two are pinned together by the `sequenceValues` section of `shared/rules-contract.json`, so changing
one without the other fails a test.

## Setting up a round

- **5 cards per player** (`YanivGameEngine.java:165-170`, and `:662-668` for later rounds).
- **One card turned face up** as a `SINGLE` combination, so the first player always has a pickup
  available (`:172-174`, `:670-672`).
- Deck after the deal: `52 − 5×players − 1`.
- **Minimum 2 players**, enforced in the controller, not the engine
  (`GameStateController.java:605-608`). The engine itself accepts one player, or zero.
- **Maximum 6 players** by default, also outside the engine (`entity/Game.java:54`,
  `RoomController.java:101,164-167`).

**Round 1 starts with `playerIds.get(0)`** — in practice the host, who is added first
(`RoomController.java:107`).

**Later rounds start with the player seated after the Yaniv caller, not the round winner**
(`:674-678`). A Yaniv call does not advance `currentPlayerIndex`, so the next round begins one seat
past whoever called. This differs from the common house rule where the winner leads.

## A turn

The engine is an explicit state machine (`YanivGameEngine.java:18-20`). The order is **always
discard, then draw** — there is no draw-first path.

```
WAIT_FOR_TURN → (discard) → DRAW_CARD → (draw) ─┬─→ finalizeTurn → WAIT_FOR_TURN (next player)
                                                └─→ BONUS_DISCARD → (accept/decline) → finalizeTurn
```

### 1. `processDiscard(playerId, cards)` — `:184-215`

Rejects a discard that isn't the current player's (`:185-187`), fails validation (`:192-194`), or
names a card not in hand (`:197-201`). On success it removes the cards from the hand and parks them
in **`pendingDiscard`** — they are *not* on the pile yet (`:204-205`).

**The staging buffer is the important part.** Because the current player's discard only reaches the
pile after they draw, the pile top during their draw is still the *previous* player's discard. This
is what makes it impossible to re-take the card you just threw.

### 2. `processDraw(playerId, drawSource, drawnCard)` — `:224-263`

State must be `DRAW_CARD` (`:229-231`). Source is case-insensitive and must be `DECK` or
`DISCARD_PILE` (`:235,251,257-259`).

- **DECK**: if the deck is empty, recycle first (§[Deck exhaustion](#deck-exhaustion)); take the top
  card. Then test the bonus-discard trigger — if it fires, the method **returns early in state
  `BONUS_DISCARD` with the pending discard still off the pile** (`:244-250`).
- **DISCARD_PILE**: the card must be in `discardPile.isDrawable(id)` (`:252-255`).

### 3. `finalizeTurn()` — `:342-356`

Pushes `pendingDiscard` onto the pile via `pushPendingDiscardToPile` (`:328-341`, which classifies
the combination and is a no-op once the cards are already down), clears the bonus fields, and
advances to the next non-eliminated player. The accept path of a bonus discard calls
`pushPendingDiscardToPile` itself, *before* pushing the bonus card, so the two land in the order
they left the hand.

**A drawn card is never removed from the discard pile.** `processDraw` only adds it to the hand
(`:256`); `DiscardPile` has no removal API. The taken card just becomes unreachable once a newer
combination lands on top. It still appears in `getAllDiscardedCards()`.

### Hand size only ever shrinks, and never past one

A turn removes N ≥ 1 cards and adds exactly 1, so a hand never exceeds the 5 dealt. With a bonus
discard it can shrink twice in one turn. Maximum combination length is therefore 5 — which is why
the Ace-high heuristic in `DiscardCombination.sortSequenceCards` can never misfire in practice
(it would need a 12-card combination).

**The floor is one card, not zero.** Every other path reaches it for free: discarding the whole hand
still draws one card back, so a hand-clearing mixed run leaves you with one. The bonus discard is the
only second removal in a turn, and therefore the only route to an empty hand — which is why taking
it on a last card deals a replacement.

## What may be discarded

`isValidCombination(cards, handSize)` = single **or** set **or** sequence
(`CardCombinationValidator.java:164-166`).

### Single
Any one card (`:17-19`). An empty list is invalid at every branch, so `processDiscard([])` throws.

### Set
- **2 to 4 cards inclusive** (`:26-28`). A pair is a set.
- All must share the same rank; **suits are unrestricted** (`:35-39`).
- **Cards must be distinct.** Listing the same card twice is rejected (`hasDuplicateCardIds`);
  it is not a pair. Card identity is the id alone, so without this a caller could name one card
  repeatedly and have it removed from the hand more than once.

### Sequence (run)
- **Minimum 2 cards** (`:70-72`). Maximum is bounded by hand size, so 5.
- **K-A-2 wrap is explicitly rejected** (`:74-77`, `hasCornerWrapping` `:109-120`).
- **Normally all one suit** (`:79-85`).
- **Mixed-suit runs are legal only when they empty the hand** — `cards.size() != handSize` is
  rejected. At any length from 2 upwards a mixed run is legal *provided nothing is left behind*; a
  run that leaves even one card must be single-suit. This is what `handSize` is for, and it is why
  every caller has to pass the hand's size before the discard. Clearing the hand relaxes only the
  suit requirement — corner wrapping and non-consecutive ranks are still rejected first.
- **Ace is low or high, chosen per combination, never both within one.** The ranks are mapped
  through both ladders and the run is valid if *either* is strictly consecutive (`:126-138`).
  `A-2-3` valid, `Q-K-A` valid, `K-A-2` invalid.
- Duplicate ranks produce a gap of −1 and are rejected (`:143-156`).

`getCombinationType` matches in the order SINGLE → SET → SEQUENCE/MIXED_SEQUENCE (`:181-198`), so a
same-rank pair is always classified a SET.

## What may be picked up

**Only the top (most recent) combination is ever drawable** (`DiscardPile.getDrawableCards`,
`:53-79`). Everything below it is permanently locked.

| Top combination | Drawable |
|---|---|
| SINGLE | that card |
| SET | **any one** card of the set |
| SEQUENCE / MIXED_SEQUENCE | **only the two ends** — first and last after sorting |

Exactly one card is taken; there is no API to take a whole combination.

Runs are **sorted at construction** (`DiscardCombination.java:20-28`), so the caller may pass them
in any order and "the ends" are the low and high cards, not the first and last passed. Sets and
singles keep insertion order.

`getTopCard()` is a display field only (`GameStateController.java:658`) — for a sorted run it
returns the *low end*, not "the card on top". The authoritative list pushed to clients is
`drawableDiscardCards` (`:703-713`).

This section matches the pickup rules in `docs/yaniv-rules.md`, and the matrix there is encoded in
`YanivRulesTest`. A `MIXED_SEQUENCE` on the pile can be 2 to 5 cards long — whatever emptied the
discarder's hand — and in every case only its two ends are drawable.

## The bonus discard

A non-standard house rule built into the engine.

**Trigger** — all three must hold (`processDraw:249-258`):
1. the discard this turn was **exactly one card**;
2. the draw source was **DECK** (a discard-pile draw never reaches the check);
3. the drawn card has the **same rank but a different suit** as the discarded card.

The engine parks in `BONUS_DISCARD` and waits for `processBonusDiscard(playerId, shouldDiscard)`
(`:282-311`). Accepting removes the card and pushes it as its **own SINGLE combination**; the player
draws **no replacement**, so the hand shrinks by one. All 13 ranks can trigger it.

**Taking the bonus on your last card deals a replacement.** A player down to one card discards it,
draws its twin, and accepting would otherwise leave them holding nothing — no legal discard on their
next turn, and a hand score of zero that no Asaf can beat. So when the accept empties the hand the
engine deals one card from the deck (`:308-315`), recycling first if it has to. The replacement is
always from the **deck**: the top of the pile is the card they just threw. Declining draws nothing —
the replacement is only for the card they gave up. This is the *only* case where a bonus discard
does not shrink the hand.

**Push order matters, because only the top combination is drawable.** Accepting pushes the turn's
*original* discard first (`pushPendingDiscardToPile`, `:328-341`) and the bonus card second, so the
pile ends `[…, originalDiscard, bonusCard]` and the next player can take the bonus card — the one
that left the hand last. Pushing them the other way round buries the bonus card the instant it is
discarded, which is what the engine used to do.

**A parked bonus decision always has a deadline.** Nothing else in the engine waits on a client for
an answer, and until `GameStateController` put a clock on it a client that never asked the question
held the whole room. See [Turn timers](#turn-timers-and-auto-play).

Auto-play declines the bonus (`GameStateController.java:1566-1568`), as does the deadline
(`declineUnansweredBonus`, `:1528`). Both paths are exercised by `BonusDiscardTest`, which stacks
the deck through the snapshot to force the state rather than waiting for a 1-in-14 draw.

## Calling Yaniv

`callYaniv(playerId)` (`:333-349`).

- Must be the current player (`:335-337`).
- **Legal when `handScore <= yanivThreshold` — inclusive.** The guard throws on `>` (`:341-344`).
- **Threshold is 7**, from `game.yaniv-threshold` (`application.properties:52`). It is
  **server-wide, not per-room** — a room can customise `targetScore` but not this.
- **No round-number and no "must have taken a turn" precondition.** A player dealt ≤ 7 may call
  Yaniv as the first action of round 1.

Effects: sets `callerId`, stamps `yanivCalledTimestamp`, state → `YANIV_CALLED`. **`currentPlayerIndex`
is not advanced** — which is why the next round starts one seat past the caller.

**`callYaniv` requires `WAIT_FOR_TURN`.** Without that guard a player could discard first — state
`DRAW_CARD`, cards already out of hand — and then call Yaniv on a hand that excludes the staged
cards while those cards never reached the pile, or re-send the call to reset the contest window.
`handleGameAction` deliberately exempts `CALL_YANIV` from its own turn check (`:264`) and relies on
the engine for both checks.

## Asaf

After a Yaniv call the round sits in `YANIV_CALLED` for a **15-second contest window**
(`game.yaniv-contest-timer-seconds`, `application.properties:50`), enforced by a scheduled task in
the controller (`:1079-1101`), not the engine.

`contestYaniv(playerId)` resolves **immediately** (`:355-367`). It rejects a contest when there is
no active call, from the caller themselves, or from an eliminated player.

**Who contested has no bearing on the outcome.** `evaluateHands` (`:439-477`) independently:

1. takes the caller's hand score;
2. scans all **non-caller** players for the single lowest hand;
3. declares Asaf **iff `minOpponentScore < callerScore` — strictly less** (`:470-474`).

So a **tie means no Asaf** — the caller keeps their 0. And contesting costs nothing: a player whose
hand is nowhere near lowest can contest to skip the wait, and the Asaf credit still goes to whoever
actually holds the lowest hand.

**`contestYaniv` checks membership** against `playerIds`, so an authenticated stranger who knows
the room id cannot force early resolution of a game they are not in. `next-round` is checked in the
controller the same way.

**A tie between two equally-lowest opponents resolves to the earlier seat.** `evaluateHands`
scans `playerIds` in seat order rather than iterating the score `HashMap`, so the outcome is
deterministic. The other tied player takes their full hand. (This was previously decided by hash
order; pinned now by `YanivResolutionTest`.)

## Scoring

Hand value is a plain sum of `Card.getValue()` (`Hand.java:59-63`) — A=1, 2–10 face, J/Q/K=10. No
zero-valued card, no cap, no special case for a Yaniv hand.

`applyScores` (`:548-593`) assigns the round score added to each running total:

An **eliminated player's hand is emptied** when they go out. They are never dealt to again, so an
uncleared hand would linger as a phantom card count on every client and would be counted as neither
held nor in the deck when the deck is rebuilt — putting those card ids in two places at once.

| Player | Round score |
|---|---|
| Already eliminated | 0, skipped |
| **Caller, no Asaf** | **0** |
| **Caller, Asaf'd** | **their own hand score + 30** |
| The Asaf player (lowest opponent) | **0** |
| Opponent tied with the caller (non-Asaf case) | **0** — co-winner |
| Everyone else | their own hand score |

**The Asaf penalty is `hand + 30`, not a flat 30** (`:571-573`). A caller Asaf'd holding 6 takes 36.
`docs/ui-ux-spec.md:127` renders this as "ASAF! +30 Penalty", which understates it.

`getRoundWinners()` is every player scoring exactly 0 **who is still in the game** (`:845-856`). In
an Asaf that is only the Asaf player. The eliminated check is load-bearing: the table above parks a
knocked-out player on a round score of 0 for every remaining round, so without it they would be
announced as a co-winner alongside the real one for the rest of the game. A player knocked out *by*
the round being scored is safely excluded too — a round score of 0 leaves their running total
untouched, so it can never be what pushed them over the target. Note `GameStateController.java:820`
still reports `roundWinner = callerId` as a legacy field even when the caller *lost* the Asaf.

## The halving rule

```java
if (score > 0 && score % 50 == 0) {
    playerScores.put(playerId, score / 2);
}
```
`applyHalvingRule` (`:598-603`), called from `checkEliminations` for **every non-eliminated player at
every round end, before the elimination test** (`:610-616`).

Any positive exact multiple of 50 is **halved, not reset**: 50→25, 100→50, 150→75, 200→100.

**It only fires when the round actually moved the score onto that multiple.** A player parked on a
multiple of 50 who then scores 0 keeps their total — the guard is `roundScore == 0 → return`.

```
round N:   95 + 5  = 100 -> halved to 50   (landed on it)
round N+1: 50 + 0  = 50  -> stays 50       (round did not move them)
round N+2: 50 + 25 = 75  -> stays 75
```

(Previously it ran unconditionally at every round end, so an unchanged score kept re-halving:
100 → 50 → 25. Pinned now by `ScoringAndEliminationTest`.)

Interaction with the default `targetScore = 100`: landing on exactly 100 halves to 50 and you
survive; landing on 200 halves to 100, which is still `>= 100`, so you are eliminated in the same
pass.

It is now covered by `ScoringAndEliminationTest`, which pins both the landing and the
did-not-move cases.

## Elimination and game end

`checkEliminations` (`:609-635`): halve, then **eliminate iff `score >= targetScore`** — inclusive
(`:614-616`).

`targetScore` defaults to **100**, is **per-room** and caller-supplied via `POST /api/v1/rooms`, with
**no range validation** (`RoomController.java:99`).

Game over when `activePlayers <= 1` (`:621-625`). Otherwise state → `ROUND_OVER`, awaiting a client
`next-round`.

If every remaining player were to cross `targetScore` in the same round, the winner is the one
with the **lowest running score** among them; an exact tie leaves `winnerId` null, recorded as a
genuine draw rather than an arbitrary pick.

> In practice this is unreachable: `applyScores` always gives **someone** 0 (the caller when not
> Asaf'd, otherwise the Asaf player), and a player scoring 0 cannot cross the target. The branch is
> a guard, not a live path.

`startNextRound` (`:641-681`) requires `ROUND_OVER`, then builds a **brand-new 52-card deck** —
it does not reuse the remainder. `winnerId` and `yanivCalledTimestamp` are deliberately *not* reset.

## Deck exhaustion

`recycleDeck` (`:485-530`) does **not** shuffle the discard pile back in. It reconstructs the deck
from the 52-id space: collect every id held in a non-eliminated hand plus the top discard
combination, regenerate the canonical 52 ids, and keep the ones not held (`:488-518`). Then it drops
every combination except the newest (`:524-526`) and reshuffles.

Eliminated players' hands are deliberately *excluded* from the held set (`:489`), returning their
stale cards to circulation. Their hands are emptied on elimination, so there is nothing stale to
return — see [Scoring](#scoring).

---

# Orchestration

The engine is pure. Everything below lives in `GameStateController` and is where most of the
surprises are.

## Where live state actually is

`gameEngines` maps room id → engine (`GameStateController.java:43`). Engines are created only by
`/start` (`:615-619`) and by `getOrRestoreEngine` restoring a snapshot (`:970`), and removed only
when the game ends, when a room is aborted, and when the idle sweep reclaims it — see below.

A restart loses everything in memory: engines, disconnect sets, dedup entries, all timers. Games are
restored **lazily, on first touch** — there is no warm-up at boot.

**Idle rooms are evicted.** `evictIdleEngines` drops engines untouched for
`game.engine-idle-eviction-minutes` (default **5**), skipping any room with a timer still pending.
The sweep runs at half the idle window, so a room lingers at most one interval past the threshold.
Five minutes is comfortable: a round is short and every player action touches the room, so an
untouched table is genuinely done. This
is safe precisely because the engine map is a cache: the snapshot is the source of truth, so the next
player to touch an evicted room gets it rebuilt by `getOrRestoreEngine`. Without it, a game everyone
abandons mid-round — never finished, never aborted — would hold memory for the life of the process.

Two consequences worth knowing:

- **A room whose snapshot write failed is never evicted.** `finishMutation` persists best-effort, so
  a Redis outage leaves memory ahead of the snapshot. Those rooms are tracked in `unpersistedRooms`
  and held — evicting one would silently roll the game back to a stale snapshot, or lose it
  entirely. The sweep **retries the write** under the engine lock before giving up on a room:
  an abandoned game gets no further actions, so that retry is its only route back to being
  evictable rather than resident forever.
- **A finished game is kept in memory until its result reaches the database.** `GAME_OVER` releases
  the engine only once `completeGame` succeeds; the snapshot is deleted last, so the game stays
  recoverable until then. Nothing else would retry — a terminal engine accepts no further actions —
  so the sweep re-attempts it via `pendingFinalization`.
- **Eviction calls `presence.roomClosed(roomId)`**, so absences do not outlive the game that
  recorded them. A player still connected re-attaches on their next subscribe; one who had gone is
  simply no longer expected by a game that is no longer in memory.

### When a snapshot is trusted

`getOrRestoreEngine` (`:935-978`) discards a snapshot and deletes it if MySQL does not say
`IN_PROGRESS` (`:958-967`) — a FINISHED or LOBBY row is a tombstone.

A room is rewound to LOBBY only if **storage is reachable**, there is **no restorable snapshot**, and
MySQL says `IN_PROGRESS` (`shouldAbortToLobby`, `:985-999`). A corrupt snapshot counts as absent; a
thrown exception returns `false`. **A storage outage therefore never aborts a room** — this is
deliberate and test-backed (`F2`, `F3` in `GameLifecycleScenariosTest.java`).

## `finishMutation` — the single post-mutation hook

`:1029-1075`. Every mutation ends here, in this order:

1. **Save the snapshot** to Redis (try/catch — an outage only logs).
2. If `isRoundOver()`, **persist round history**.
3. **Branch**: game over → cancel timers, `finishGame`, delete snapshot, evict engine.
   Yaniv called → schedule the contest timer. Round over → schedule the auto-advance.
   Otherwise → schedule the turn timer if needed.
4. **Broadcast** per-player state.

**The order is load-bearing.** Snapshot before broadcast, or a client that reconnects on receipt
would be handed pre-mutation state. Timers before broadcast, because `scheduleTurnTimerIfNeeded`
writes `turnDeadlines`, which the broadcast reads to fill `turnEndsAt` (`:778`) — swap them and every
client gets the *previous* turn's countdown.

## Per-player filtering

All of it happens in `buildGameStateForPlayers` (`:644-803`), and every push goes to a **user
destination**, never a shared topic. Only two fields differ per recipient:

**The destination is room-scoped**: `/user/queue/room/{roomId}/game-state`, built by
`gameStateDestination(roomId)`. A user destination reaches every session a player has open, so a
single shared one let a message for one game overwrite a tab watching another — the client stores
the payload's `gameId` but never checks it. Errors go the same way, which is why `sendErrorToUser`
takes a room. Subscribing to it is also how the server learns which game a session is watching; see
`docs/adr/0001` and the **room attachment** entry in `CONTEXT.md`.

- `hand` — the recipient's own cards only (`:688-701`)
- `opponentCounts` — every *other* player's hand **size**, never card identities (`:731-740`)
- `bonusDiscardActive` / `pendingBonusCard` — **only the player being asked** (`:861`)

That third one is a card-identity leak as much as a UI concern. The bonus card is in the deciding
player's hand while they think about it, and if they keep it, it *stays* there — so naming it to the
table hands everyone a card they are not entitled to see. It also raised the prompt on every screen,
where nobody but the current player could dismiss it.

On `ROUND_OVER`/`GAME_OVER`, `allPlayerHands` is revealed to everyone (`:757-774`). The deck's
remaining order is never sent — only `deckCount`.

**`PlayerInfo.status` is real.** For a game it reports absence from *that* game
(`IN_GAME` / `DISCONNECTED_IN_GAME`); for a lobby it reports overall reachability. Because it rides
on every state push, a client that reloads or joins late sees the truth — the old delta message it
replaced could only be caught by clients that were listening at the moment it was sent.

**Cost: three queries per mutation, whatever the player count.** `loadRoomView` fetches the game
row, the player rows and every display name (one batched `getUsersByIds`) once per broadcast, and
each recipient's message is built from that shared `RoomView`. It used to re-query per recipient —
roughly `1 + N·(2 + N)` round-trips, so 49 for a 6-player table on every single action.
`GameLifecycleScenariosTest.F7` pins the count.

## Turn timers and auto-play

`scheduleTurnTimerIfNeeded` (`:1444`) puts a deadline on whatever the room is waiting for. There are
two waits and they are **not** the same kind of thing:

| Wait | Deadline armed when | Delay | On expiry |
| --- | --- | --- | --- |
| `WAIT_FOR_TURN` | auto-play on **and** the player is absent | `absence-grace-seconds`, then `spent-grace-delay-ms` | `autoPlayTurn` plays the whole turn |
| `BONUS_DISCARD` | **always** | `bonus-discard-timeout-seconds` | `declineUnansweredBonus` answers "keep" |

Either deadline is sent to the client as `turnEndsAt` + `turnTimerSeconds` (`:849-854`), gated on a
deadline actually being armed rather than on the state. It used to be sent only in `WAIT_FOR_TURN`,
which left the bonus deadline invisible: the panel simply vanished mid-thought.

**Only an absent player is ever auto-played.** A player with *any* session attached to the game — a
second tab, say — is never played for. See **absence** and **room attachment** in `CONTEXT.md`.

**A parked bonus decision is different, and gets a deadline whoever is watching.** It is liveness,
not auto-play: the player already made their move, only the yes/no is missing, and it blocks every
other player in the room. Declining costs them nothing — they keep the card they drew — so the
server can safely answer for them. It runs with `game.auto-play-enabled=false` too, pinned by
`GameStateControllerTurnTimerTest.theBonusDeadlineStillRunsWithAutoPlaySwitchedOff`.

> This is the bug the tester hit. A client that never rendered the prompt could not answer it, so
> the engine sat in `BONUS_DISCARD` while every retry threw `Cannot discard in current state`. Their
> discard stayed in `pendingDiscard`, off the pile — "I discarded a 10 and it never appeared."
> The client now reads the fields (`GameStateMessageContractTest` pins that), and the deadline is
> the guarantee that no future client can reintroduce the stall.

Neither expiry is reported as auto-play: `declineUnansweredBonus` calls `finishMutation` without an
`autoPlayedPlayerId`, because the player did discard and draw for themselves.

**Grace is once per absence, and only counted while it is their turn.** The first time an absent
player's turn comes round, the timer waits `game.absence-grace-seconds` (45). If they return inside
it, nothing is played for them and the grace is restored, because coming back and leaving again is
a *new* absence with a new `absentSince`. Once the grace has been spent — the server actually played
for them — later turns in that same absence go at 800 ms, so the table is not held up 45 seconds a
turn. `gracedAbsences` records which absence has been spent, keyed room+player.

The clock starts when their turn arrives, not when they left: a player who drops during someone
else's turn has not burned any grace.

Nothing polls for this. `Presence` announces a change in who is watching a game, and the orchestrator
re-evaluates the timer **and broadcasts** (`watchForAbsenceChanges`). Both halves matter: without the
first, Presence would know a player had gone and nothing would act on it; without the second, the
other players would not find out until the next card happened to be played.

That second half is the one assumption worth naming: `broadcastGameState` used to have a single
caller, inside `finishMutation`, which encoded "state only changes when the game mutates". That
stopped being true the moment presence started riding on the state message.

**All of it depends on the client noticing it disconnected.** `onWebSocketClose` is what flips
`isConnected`; `onDisconnect` alone fires only on a *graceful* STOMP disconnect, so a socket that
simply dies would leave the flag true, nothing that keys on it would re-run, and the client would
never resubscribe — leaving the server convinced the player is still away. `pagehide` force-closes
the socket when a tab goes, because closing a tab does not unmount React.

> `game.turn-timer-seconds` is **not** this timer. It is a display field (`:780`) and the ROUND_OVER
> auto-advance delay. The absence grace is `game.absence-grace-seconds`.

**Auto-play is on.** `game.auto-play-enabled=true`. It was held off while presence was unreliable;
it now rests on session-counted **absence**, so a player with any session attached is never played
for. The flag still gates both the turn timer and the round-over self-advance.

The countdown the client draws uses the total that the current deadline was actually set from
(`turnTimerTotals`), not `game.turn-timer-seconds` — those are different numbers that merely share a
default, and advertising the wrong one drew a 45-second arc against an 800 ms deadline.

Connected players still have unlimited thinking time: there is no turn timer for someone who is
watching. Only absence starts a clock.

**Round-over self-advance** fires only when *every non-eliminated* player is absent, after
`turnTimerSeconds`. Note "active" means non-eliminated, not connected — an
eliminated spectator who is still connected does not hold the round open. A player who drops during `ROUND_OVER` is still recorded as
absent by Presence, so unlike before, their drop *can* now trigger the advance.

### What the bot plays

`AutoPlayStrategy.decide` (`:47-62`) is static and deterministic:

1. **If the hand is at or below the Yaniv threshold, call Yaniv** — unconditionally, with no regard
   for Asaf risk (`:48-50`).
2. Otherwise enumerate every single, every set, every consecutive same-suit window (computed twice,
   Ace low and Ace high), plus the whole hand as a mixed-suit run, which is legal only because it
   empties the hand (`:121-157`). Pick the lowest resulting total; tie-break on more cards
   discarded, then lexicographically by joined card ids (`:105-114`).

Two caveats: a deck draw is scored as **value-neutral**, ignoring that the drawn card adds to the
hand (`:78-80`); and the evaluation models taking a pile card as a *swap* for the worst card, while
the engine only ever **adds** it (`:82-97`). It is a heuristic ranking, not a faithful simulation.

Auto-play always **declines** a bonus discard (`:1566-1568`). Any exception in `runTurnDeadline` is
logged and swallowed (`:1516-1518`), leaving the turn **stuck with no re-armed timer**.

## The contest timer

Scheduled by `finishMutation` when the engine enters `YANIV_CALLED` (`:1080-1102`). On expiry it
calls `resolveYanivCall()` and runs `finishMutation` again. If someone contests first, the engine
resolves inline and the handler cancels the future (`:393-396`).

The duration is captured **at engine construction** (`:618`) and carried in the snapshot, so a config
change does not apply to in-flight games.

The frontend computes the countdown as `yanivCalledAt + timerSeconds` against `Date.now()`
(`GameView.tsx:124-140`), so **client clock skew shifts the displayed countdown**. The server's own
timer is unaffected.

## Action deduplication

Keyed `roomId:userId:actionId` in a `ConcurrentHashMap` (`:62-64`, `:235`). A duplicate is not
re-applied; the server re-sends the caller's personal state instead — **or nothing at all** if the
engine is not currently in memory (`:240-244`). Entries older than 5 minutes are swept, but **only on
the success path of an action that carried an `actionId`** (`:247-249`).

Applies to `/action` only — `call-yaniv`, `contest-yaniv`, `next-round`, `start` and `join` have no
dedup.

The client sends an `actionId` with every action (`GameView.tsx`, `newActionId`). It is generated
when the action is *created*, not when it is sent, so a frame replayed from the offline queue
(`StompContext.tsx:74-76`) after a reconnect carries the same id and is ignored rather than applied
twice — the case this exists for.

## Disconnect and reconnect

A mid-game drop closes one session. `PresenceSessionListener` tells `Presence`, which records an
**absence** only if that was the player's *last* session watching this game — a second tab keeps
them present. The absence announcement re-arms the turn timer if it is their turn.

Changes to one player are serialised inside `Presence`, so two of their sessions opening or closing
at once cannot both read the same "before" and conclude nothing happened. A dropped absence
announcement would mean a turn is never re-armed.

`PresenceRedisProjection` is the **only** writer of the Redis presence key. It follows
`Presence.onPresenceChanged` and mirrors the current status; a failure is logged and changes
nothing, because memory is the truth and Redis knows nothing the process does not know better.
Previously two `@EventListener`s wrote that key on the same event with no `@Order` between them, so
what a disconnect meant depended on bean discovery order.

Leaving during `ROUND_OVER` now records an absence like any other — the player is still in the game.
It used to report plain offline, which meant a table where everyone had gone could never
self-advance.

`GameStateController` no longer listens for disconnects at all: everything that handler did is now
done by Presence and the absence announcement. `findRoomForUser` went with it — scanning live
engines for a user and taking the first match is meaningless when a player can be in several games.
The reconnect path uses the database, which is the record of who is in which game.

**A disconnected player is never removed from the game.** No code path calls
`removePlayerFromGame` — it exists (`GameService.java:175-180`) with zero callers. They keep their
seat, hand and turn position until the game ends or the process restarts. The 30-minute TTL affects
only the presence badge, not membership.

On reconnect (`:145-209`) the player is removed from the set, presence goes to `IN_GAME`, the others
are notified, and a **proactive** full state push is sent before the client asks — deliberately, to
dodge the subscribe-then-request race (`:191-194`).

In LOBBY the picture differs: a lobby member is not tracked as disconnected at all, and a new player
may still join. Once IN_PROGRESS, new joins are rejected (`RoomController.java:155-158`) but
**existing members may always re-enter**, idempotently (`:151-152`).

## What reaches MySQL

| Written | When |
|---|---|
| `games.status = IN_PROGRESS` | host `/start` succeeds (`:610`) |
| `games.status = FINISHED` + `winnerId` + `finishedAt` | engine reaches GAME_OVER, in `finishMutation` (`:1052`) |
| `games.status = LOBBY` | `abortStaleGame` rewind (`:1009`) |
| `game_players.finalScore` + `.placement` | game over, via `saveFinalStandings` — placement is the winner first, then **reverse elimination order** (last knocked out places higher), because an eliminated player's score freezes and ranking by points would favour whoever went out earlier |
| `game_players` row | room create, room join |
| `round_histories` row | each transition into `ROUND_OVER` (`:1041-1043`, `:1161-1176`) |

Live hands, deck and pile are **never** written to MySQL — only to the Redis snapshot
(`game:{id}:state`, 24-hour TTL, re-applied on every write, `GameService.java:24-26`).

`persistRoundHistory` also early-returns when `callerId` is null (`:1162-1164`).

---

# Known defects

The card-conservation, scoring and authorisation defects that used to fill this section are
**fixed**, each pinned by a test that was verified to fail against the pre-fix code:

| Was | Now | Pinned by |
|---|---|---|
| Duplicate card ids removed a card from the game | rejected as a non-distinct combination | `CardConservationTest` |
| `recycleDeck` regenerated a staged discard | `pendingDiscard` counted as held | `CardConservationTest` |
| `processDiscard` had no state guard | requires `WAIT_FOR_TURN` | `CardConservationTest` |
| `callYaniv` had no state guard | requires `WAIT_FOR_TURN` | `ScoringAndEliminationTest` |
| Halving re-fired on an unchanged score | only on landing | `ScoringAndEliminationTest` |
| Knocked-out players were announced as co-winners of every later round | `getRoundWinners` skips them | `ScoringAndEliminationTest.knockedOutPlayersAreNotRoundWinners` |
| Asaf tie-break followed hash order | seat order | `YanivResolutionTest` |
| `contestYaniv` accepted non-members | membership checked | `YanivResolutionTest` |
| `next-round` accepted non-members | membership checked | `GameLifecycleScenariosTest.F4` |
| The deciding round was never persisted | persisted on the `GAME_OVER` path | `GameLifecycleScenariosTest.E5` |
| `finalScore`/`placement` never written | written at game over | `GameLifecycleScenariosTest.E5b` |
| `/start` re-dealt a FINISHED game | rejected | `GameLifecycleScenariosTest.F5` |
| An unknown `actionType` silently persisted and broadcast | errors | `GameLifecycleScenariosTest.F6` |
| `processDraw` trusted the caller's `Card` | resolves it from the pile | `ScoringAndEliminationTest` |
| `gameEngines` was a bare `HashMap` | `ConcurrentHashMap` | — |
| Turn-advance loops could spin forever | bounded, then throw | — |
| Disconnect during `BONUS_DISCARD` stalled the room | covered by the turn timer | — |
| A *connected* player parked in `BONUS_DISCARD` stalled the room for good | every bonus decision has a deadline | `GameStateControllerTurnTimerTest.aBonusDecisionNobodyAnswersIsDeclinedInsteadOfStallingTheRoom` |
| An accepted bonus card was buried under the discard it matched | pushed last, so it is the drawable top | `BonusDiscardTest.acceptedBonusCardIsTheTopOfThePile` |
| A player on their last card could bonus-discard down to an empty hand | dealt a replacement, recycling the deck if needed | `BonusDiscardTest.bonusDiscardingTheLastCardDealsAReplacement`, `.theReplacementCanComeFromARecycledDeck` |
| The client never read `bonusDiscardActive` / `pendingBonusCard` | written to the store on every push | `GameStateMessageContractTest` |
| The bonus card was broadcast to the whole table, leaking a card they may keep | sent only to the player deciding | `GameStateControllerTurnTimerTest.theBonusCardIsSentOnlyToThePlayerDeciding` |
| The bonus prompt had no CSS at all, so it broke the table layout when it finally rendered | styled as a centred modal in `TableCanvas.css` | — |
| `handleNextRound` never restored from a snapshot | restores like every other handler | — |
| Reconnect during a storage outage NPE'd | returns without touching the room | — |
| Unauthenticated STOMP CONNECT passed through | rejected | — |
| `maxPlayers` was unvalidated | constrained to 2–6 | — |
| Invite handlers were unreachable | destination prefix corrected | — |
| Dedup was dead (no client `actionId`) | client sends a stable `actionId` | — |

Fixing the `processDiscard` guard also exposed three latent test flakes that had been silently
exercising the bug; those are fixed too, and the suite is stable across repeated runs.

## Still open

- **The invite feature has no test coverage.** Its handlers were unreachable until now, so nothing
  has ever exercised send / respond / cancel end to end.
- **`AutoPlayStrategy.evaluate` does not model the real move.** A deck draw is scored as
  value-neutral (`:78-80`), and taking a pile card is modelled as a *swap* while the engine only
  **adds** it (`:82-97`). It is a heuristic ranking, not a simulation.
- **`handSize` is now a dead parameter** threaded through three validator signatures, and
  `handSizeAtDiscard` is stored and snapshotted but read by no rule.
- **The rules still exist in two languages.** Consolidated to one copy per side and pinned by the
  shared contract, but a single implementation would need either a server-authoritative UI or
  generating the client rules at build time.

# Documentation contradictions

Where the rest of the repo disagrees with the code. **The code wins**; these are listed so you know
not to trust the other document.

| Topic | Other doc says | Code does |
|---|---|---|
| Jokers | ~~`docs/prd.md` sample and `cardPreload.ts` preloads~~ — **both removed**; the code never had jokers | No `JOKER` rank, no `NONE` suit, 52 cards |
| Halving | now documented in `docs/yaniv-rules.md` | halves only when a round lands the score on a multiple of 50 |
| Scoring / Asaf / elimination | **now documented** in `docs/yaniv-rules.md` §4b | `hand + 30` Asaf, Yaniv ≤ 7, elimination at `>= targetScore` |
| Asaf penalty | `docs/ui-ux-spec.md:127` renders "ASAF! +30 Penalty" | the caller takes **hand + 30**, not a flat 30 |
| Turn timer | ~~`CLAUDE.md`: auto-play fires after `game.turn-timer-seconds`~~ — **corrected** | hardcoded **800 ms** (`:1201`); 45 s is display-only and the round-over delay |
| Auto-play | `CLAUDE.md` describes it as active machinery | `game.auto-play-enabled=false` ships, **deliberately** — presence/connection status is unreliable, so auto-play is held off. The `@Value` default now also reads `false` so code and config agree. |
| Round history | `docs/lifecycle-scenario-matrix.md:60` (E5): "exactly one row per round transition" | **now true** — `finishMutation` persists on the `GAME_OVER` path too, and `E5_` exists |
| Redis | ~~`CLAUDE.md`: "Redis is used only for presence"~~ — **corrected** | also holds the game snapshot and invites |
| Round starter | undocumented | the player after the **caller**, not the round winner |
| Invites | the UI has always offered them | the handlers were unreachable until the destination prefix was corrected; still untested |
| Discard/pickup matrix | `docs/yaniv-rules.md` | **agrees exactly** — this part is trustworthy |
| Mixed-suit runs | briefly changed to "exactly 5 cards", then **reverted by decision** | back to **valid iff the discard empties the hand**, at any length ≥ 2; doc, both validators, the shared contract and the tests moved together |

`CLAUDE.md` is verified **correct** about: connected players never being auto-played, the
`finishMutation` ordering, the `game:{id}:state` key, snapshots only being trusted while MySQL says
`IN_PROGRESS`, and storage outages never aborting a room to LOBBY.

# Configuration

These are the only `game.*` keys in the codebase, all read in `GameStateController` and nowhere
else.

| Key | Ships as | Effect |
|---|---|---|
| `game.yaniv-threshold` | `7` | Highest hand that may call Yaniv. **Server-wide, not per-room.** |
| `game.yaniv-contest-timer-seconds` | `15` | Asaf window. Captured at engine construction, so changes don't reach in-flight games. |
| `game.turn-timer-seconds` | `45` | Display field and the round-over auto-advance delay. **Not the auto-play delay.** |
| `game.auto-play-enabled` | `false` (`@Value` default now also `false`) | Gates every turn timer and the round-over self-advance. **Deliberately off** while presence status is unreliable. |
| `game.absence-grace-seconds` | `45` | How long an absent player's turn is held before the server plays it for them. Once per absence, counted only during their turn. |
| `game.spent-grace-delay-ms` | `800` | Pace of later turns in the same absence. Low keeps the table moving; too low and a whole game finishes while someone's phone is locked. |
| `game.bonus-discard-timeout-seconds` | `30` | How long a matching-rank bonus decision is held before the server declines it. Applies to everyone, connected or not, and ignores `auto-play-enabled`: the decision blocks the whole room and declining costs the player nothing. A backstop for a client that cannot answer, not a game clock — the panel shows the countdown. |
| `game.engine-idle-eviction-minutes` | `5` | How long a room may go untouched before its engine is dropped from memory. State survives in the snapshot, so eviction costs at most one restore. |

Per-room, supplied in the `POST /api/v1/rooms` body: `targetScore` (default 100, unvalidated) and
`maxPlayers` (default 6, valid range 2–6, validated on create).
