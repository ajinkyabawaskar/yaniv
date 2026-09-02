# CONTEXT

Yanif is an online multiplayer **Yaniv** card game. This file is the shared vocabulary: use these
terms, with these meanings, in code, tests, issues and commit messages.

For the exhaustive rules, scoring and lifecycle behaviour, see **[`docs/game-engine.md`](docs/game-engine.md)**.
For architecture and commands, see [`CLAUDE.md`](CLAUDE.md).

## Glossary

**Room** — a lobby keyed by a short room code, created over REST before play. Carries `targetScore`
and `maxPlayers`. A room becomes a **Game** when it starts.

**Game** — one full contest, from the first deal until a single player remains. Persisted in MySQL;
the live engine for it exists only in server memory.

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

**Turn timer / auto-play** — the server playing a move for a **disconnected** player. Connected
players are never auto-played.

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
  server. Broadcasts go to `/user/queue/game-state`, never to a shared topic.
- The engine stays **pure** — no Spring, no I/O, no clock beyond the timestamp it is handed.

## Language to avoid

- *"Deal"* for a single round's deal vs. the whole game — say **round** or **game**.
- *"Score"* unqualified — say **hand score** or **running score**.
- *"Winner"* unqualified — a **round winner** scores 0 for that round; the **game winner** is the
  last player not eliminated.
- *"Joker"* — there are none. The 52-card deck has no jokers, despite stale references in
  `docs/prd.md` and the frontend's card preloader.
