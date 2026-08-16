# Front-End UI/UX Refinement Specification: Yaniv Web App

## 1. Executive Summary & Objective

Refine and enhance the existing React frontend for the Yaniv card game to deliver a mobile-first, touch-optimized, casino-themed player experience.

This specification establishes strict rules for drag-and-drop staging mechanics, Framer Motion dealing and turn animations, discard pile pickup eligibility visuals, real-time state reconciliation contracts, and an **integrated zero-asset Native Web Audio Sound Engine** for high-immersion tactile audio feedback.

---

## 2. Native Web Audio Engine (Zero-Asset Sound System)

To eliminate network asset latency and guarantee instant tactile feedback across mobile viewports, all interactive UI states trigger procedural audio generated on-the-fly via the browser's native **Web Audio API** (`AudioContext`).

### 2.1 Audio Context & Autoplay Lifecycle

* **Global Singleton:** Maintain a single managed `AudioContext` instance wrapped in a user-gesture unlock listener (`touchstart` / `mousedown`).
* **Dynamic Audio Nodes:** Synthesize card physics using procedurally filtered White Noise generators (sliding felt textures) and exponential pitch-ramp Oscillators (card snaps and ticks).

### 2.2 Synthesized Sound Registry

| UI State / Action | Acoustic Signature | Procedural Synthesis Parameters |
| --- | --- | --- |
| **Card Select (Tap)** | Crisp, high-frequency card tick | `Triangle` wave, pitch decay $600\text{Hz} \to 150\text{Hz}$ over $20\text{ms}$, gain envelope peak $0.2$. |
| **Multi-Select (Double-Tap)** | Arpeggiated double-flick | Two staggered tick pulses ($15\text{ms}$ offset) with second pulse pitched $+3$ semitones higher. |
| **Staging Drop (Valid)** | Heavy felt impact thud | Dual node: Low-pass filtered noise burst ($400\text{Hz}$ cutoff, $30\text{ms}$) + Sine wave thud ($120\text{Hz} \to 40\text{Hz}$). |
| **Staging Drop (Invalid)** | Elastic rejection spring | Sawtooth pitch sweep $180\text{Hz} \to 90\text{Hz}$ over $150\text{ms}$ with rapid amplitude tremor. |
| **Felt Slide / Drag** | Friction noise across felt | Dynamic White Noise buffer connected to Biquad Bandpass Filter; frequency scales with drag velocity vector. |
| **Dealer Round-Robin** | Air-cushion card flick | Fast noise burst envelope ($15\text{ms}$, high-pass $1200\text{Hz}$) coupled with a soft thud on landing. |
| **Yaniv Call Bell** | Resonant metallic chime | Dual Sine harmonics ($880\text{Hz}$ A5 & $1760\text{Hz}$ A6) with long exponential decay ($1.2\text{s}$) + frequency modulation vibrato. |
| **Asaf Penalty** | Distorted crimson crash | Square wave low-end rumble ($80\text{Hz}$) + overdrive distortion node fading over $600\text{ms}$. |
| **Timer Warning ($\le 5\text{s}$)** | Ticking clock heartbeat | Low Sine pulse ($100\text{Hz}$, $10\text{ms}$) firing once per second, pitching up to $200\text{Hz}$ on the final 2 seconds. |

---

## 3. Table Canvas Layout & Visual Theme

### 3.1 Casino Felt Styling

* **Table Surface:** Deep royal green felt texture featuring a radial vignette (brighter center, darkened edges) and subtle micro-grain texturing.
* **Accent Palette:** Brushed gold framing for active player turns, staging zones, and call buttons; deep crimson for penalty overlays and Asaf calls.
* **Card Physics & Elevation:** Dynamic 3D depth using CSS perspective and expanding drop-shadows when cards are lifted, dragged, or staged.

### 3.2 Radial Ergonomics

* **Main Player:** Fixed at the bottom center of the viewport with floating hand controls and point summary pill.
* **Opponents:** Arranged along the top semi-circle of the table. Each seat displays display name, current score, active turn spotlight, and remaining face-down card count.
* **Center Play Area (3-Zone Layout):**
1. **Draw Pile (Left):** Face-down deck stack with a remaining card count badge.
2. **Staging Zone (Center):** Pulsing dotted gold drop area for staging pending discards before completing a draw.
3. **Discard Pile (Right):** Horizontal fan displaying the face-up cards from the last discarded combination.



---

## 4. The 3-Zone Workflow & Touch Gesture Engine

To maintain atomic backend state transactions (discarding selected cards and drawing in a single turn payload), turn actions are staged locally before dispatching to the server.

### 4.1 Turn Action Flow

1. **Selection:**
* Tapping a card in hand toggles its selection state (slides card upward with a gold border, light tactile haptic, and triggers **Card Select Tick** audio).
* Double-tapping a card automatically selects all matching ranks in hand (triggers **Multi-Select Arpeggiated Audio**).


2. **Staging:**
* Dragging any selected card pulls the entire selected group toward the **Staging Zone** (triggers continuous low-volume **Felt Slide Noise** relative to finger speed).
* Releasing cards over the **Staging Zone** snaps them into place.
* If the combination is valid, the staging boundary glows green, plays the **Valid Staging Heavy Drop** sound, and the Draw and Discard piles begin pulsing.
* If invalid, the cards perform an elastic shake animation, play the **Invalid Rejection Spring** sound, and revert to hand.


3. **Draw & Execution:**
* With cards in the Staging Zone, tapping or dragging from either the **Draw Pile** or an eligible card in the **Discard Pile** completes the turn.
* Staged cards slide into the Discard Pile with a **Felt Slide** audio sweep, the drawn card flies into the hand with an **Air-Cushion Flick** sound, and the turn payload is dispatched to the server.



---

## 5. Discard Pile Horizontal Fan & Pickup Eligibility

When multi-card combinations (Sets or Sequences) are discarded, all cards in the combination must remain visible, but **only rule-eligible cards may be picked up**.

### 5.1 Visual Fan Layout

* Discarded combinations overlap horizontally with a fixed offset so every card value and suit remains fully visible.

### 5.2 Interaction Rules by Combination Type

* **Sequences (e.g., 4♠ - 5♠ - 6♠):**
* Only the **outermost low and high cards** (4♠ and 6♠) receive active drag handles, a gold aura glow, and touch responsiveness.
* Middle cards (5♠) are visually dimmed, display a locked indicator on hover/tap, and ignore drag/tap gestures.
* Tapping an ineligible middle card triggers an elastic rubber-band rejection effect, double error haptic vibration, and the **Invalid Rejection Audio**.


* **Sets (e.g., three 8s):**
* All individual cards in the set receive an active pick-me highlight; any single card from the set can be pulled into hand with full gesture and audio responsiveness.


* **Single Discard:**
* The single top card is fully active and pickable.



---

## 6. Animation & Synchronized Audio Requirements

### 6.1 Sequential Dealer Animation

* **Trigger:** Fired upon receiving a round-start event from the server.
* **Behavior:** An animated dealer deck pushes cards outward in a clockwise circle round-robin sequence (Seat 1 → Seat 2 → Seat 3 → Player), dealing 1 card at a time until every hand reaches 5 cards.
* **Audio Sync:** Every dealing keyframe emission fires a synchronized **Air-Cushion Card Flick** sound (staggered evenly by animation duration).
* **Landing:** Cards land face-down on the felt with a low-pass thud, then rotate face-up into the player's hand with a 3D tilt.

### 6.2 Turn Transitions

* **Optimistic Flight:** The drawn card animates along a smooth parabolic arc into its sorted hand index accompanied by a quick pitch-ascending slide audio node.
* **Staging Motion:** Staged discards translate smoothly into the Discard Pile, replacing the previous round's face-up cards.

### 6.3 Yaniv & Asaf Dramatic Visual Banners

* **Yaniv Call:** Slams a golden bell icon onto screen center with radial light sweeps, flashing caller cards face-up while triggering the resonant **Yaniv Call Bell Chime** sound.
* **Asaf Counter:** Triggers a crimson screen-edge flash, striking the Yaniv caller's seat with an `"ASAF! +30 Penalty"` overlay banner while firing the distorted **Asaf Penalty Crash** audio.

---

## 7. Real-Time HUD & Accessibility Enhancements

* **Dynamic Hand Total Pill:**
* Floating badge fixed directly above the player's hand calculating current hand point sum in real time.
* When total point value is $\le 7$ (Yaniv threshold), the badge transitions to a pulsing bright gold background displaying `"READY FOR YANIV!"` and plays a soft high-pitched golden chime.


* **Hand Sorting Actions:**
* One-tap action controls on the hand bar to instantly re-sort cards by **Rank** or **Suit** (accompanied by a rapid 5-card cascade tick audio effect).


* **Tactile Haptics + Audio Pairing:**
* Light tick on card selection paired with **Card Select Tick**.
* Firm snap vibration paired with **Staging Drop Thud**.
* Double error buzz paired with **Invalid Rejection Audio**.


* **Turn Timer Ring:**
* Active player seat displays a circular countdown ring timer transitioning from green to flashing red during the final 5 seconds of a turn, synchronized with the **Timer Warning Pulse** sound.



---

## 8. Real-Time State Reconciliation Rules

* **Optimistic Local UI:** Dragging cards into staging updates local UI state and triggers local audio instantly to eliminate perceived input lag.
* **Server Payload Authority:** On receiving an updated authoritative game state push from the server, the UI reconciles its local state with the server state and clears the local staging area.
* **State Rollback:** If the backend rejects a turn action or a network disconnection occurs, staged cards seamlessly animate back into the player's hand, play the **Invalid Rejection Audio**, and display a toast notification.

---

## 9. Verification & Acceptance Criteria

1. **Audio Responsiveness:** All sound cues must trigger via `AudioContext` within $<10\text{ms}$ of touch events without downloading external audio files.
2. **Staging Isolation:** Staging cards in the Staging Zone holds them locally without network dispatch until a draw pile is tapped/dragged.
3. **Sequence Drag Guard:** In a sequence discard (e.g., 4♠-5♠-6♠), attempting to draw 5♠ must trigger visual rejection, error haptics, and the rejection audio cue.
4. **Responsive Scaling & Autoplay Resilience:** The UI and Web Audio Engine must function seamlessly on mobile viewports, resuming `AudioContext` state automatically upon the first touch gesture.