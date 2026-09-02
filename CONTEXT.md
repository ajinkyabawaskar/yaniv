# CONTEXT

Yanif is an online multiplayer **Yaniv** card game. This file is the shared vocabulary: use these
terms, with these meanings, in code, tests, issues and commit messages.

For the exhaustive rules, scoring and lifecycle behaviour, see **[`docs/game-engine.md`](docs/game-engine.md)**.
For architecture and commands, see [`CLAUDE.md`](CLAUDE.md).

## Glossary

**Game** — one contest, from creation until a single player remains. Carries a short **room code**,
`targetScore` and `maxPlayers`, and moves through lobby, in progress, and finished. Persisted in
MySQL; the live engine for it exists only in server memory.

**Room** — the same thing as a **Game**, named for the stage before play starts. The room id *is*
the game id; they were never two entities. A player may be in several games at once, so "the
player's room" is never well defined — always say which one.

**Round** — one deal-to-Yaniv cycle within a game. Ends when someone calls Yaniv and the call
resolves. A game is many rounds.

**Engine** — `YanivGameEngine`, the pure rules state machine. Knows nothing about WebSockets,
persistence or players' connections. All orchestration lives in `GameStateController`.

**Hand score** — the sum of a hand's card values: Ace 1, pip cards face value, J/Q/K all 10.
Distinct from a **running score**, the total a player has accumulated across rounds. Players are
eliminated on their *running* score.

**Combination** — a legal discard: a **single**, a **set** (2–4 cards of one rank), or a
**sequence** (2+ consecutive cards of one suit; mixed suits allowed only at exactly 5 cards). "Run" and "sequence" mean the same thing; prefer *sequence*, which is what the code says.

**Pending discard** — cards removed from a hand but not yet on the pile, staged while the player
draws. The reason you can never re-take the card you just discarded.

**Drawable** — the cards a player may pick up from the pile: only from the *top* combination, and
for a sequence only its two **ends**. Not the same as the pile's top card.

**Bonus discard** — a house rule: draw from the deck a card matching the rank you just discarded,
and you may throw it too, with no replacement. See `docs/game-engine.md`.

**Yaniv** — the call ending a round, legal at a hand score **≤ 7**.

**Asaf** — the penalty when a caller is beaten: an opponent held a **strictly lower** hand. The
caller takes their own **hand score + 30**; the lowest opponent takes 0. A tie is *not* an Asaf.

**Contest** — a player disputing a Yaniv call within the 15-second window. Contesting only resolves
the round early; it does not decide who receives the Asaf.

**Halving** — a round that moves a running score exactly onto a positive multiple of 50 halves
it. A score the round did not move is never halved.

**Elimination** — a player whose running score reaches `targetScore` (default 100) is out. Last
player standing wins.

**Placement** — final standing: the winner first, then players in reverse elimination order.
Outlasting someone ranks above them regardless of final points, because an eliminated player's
score freezes when they go out.

**Session** — one live client connection. A player may hold several at once (several tabs), and is
only away when the last one goes.
_Avoid_: connection, socket, client.

**Presence** — whether a player is reachable at all, derived from their sessions. Distinct from
**room attachment**: a player may be present but attached to no room.
_Avoid_: online status, connection state.

**Room attachment** — which room a session is watching. A session is attached to at most one room;
a player is attached to a room while any of their sessions is.

**Absence** — a player having no session attached to a room, beginning at a definite moment. An
episode with a start, not a flag: it ends when they attach again, and a later drop is a new absence.
_Avoid_: disconnected (that describes a session, not a player).

**Grace period** — how long an absent player's turn is held before the server plays it for them.
Granted once per **absence**, and only counted while it is that player's turn.

**Turn timer / auto-play** — the server playing a move for an **absent** player once their **grace
period** has elapsed. A player with any session attached to the room is never auto-played.

**Snapshot** — the full engine state serialised to Redis after every mutation, so a restarted
server resumes games instead of re-dealing.

## Invariants worth protecting

- A turn is **always discard-then-draw**. There is no draw-first path.
- **Hand size never grows.** A turn removes N ≥ 1 cards and adds at most 1.
- **Scoring values and sequence values are different ladders.** Ace is 1 when scoring but 1-or-14 in
  a sequence; J/Q/K all score 10 but are 11/12/13 in a sequence. Never use `Card.getValue()` for
  sequence adjacency.
- **Card identity is the id alone.** `Card.equals` ignores suit and rank.
- Each player receives a **view filtered to their own hand**; opponents' cards must never leave the
  server. State is addressed to one player in one room, never to a shared topic.
- The engine stays **pure** — no Spring, no I/O, no clock beyond the timestamp it is handed.

## Language to avoid

- *"Deal"* for a single round's deal vs. the whole game — say **round** or **game**.
- *"Score"* unqualified — say **hand score** or **running score**.
- *"Winner"* unqualified — a **round winner** scores 0 for that round; the **game winner** is the
  last player not eliminated.
- *"Joker"* — there are none. The 52-card deck has no jokers, despite stale references in
  `docs/prd.md` and the frontend's card preloader.
