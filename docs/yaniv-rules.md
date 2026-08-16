# Yaniv Game Rules & Combination Matrix

This document defines the strict validation rules for both **discards** and **pickups** in the card game **Yaniv**.

---

## 1. Allowed Discard Types Summary

A player may discard any **one** of the following valid combination types on their turn:

| Discard Type | Minimum Cards | Maximum Cards | Key Condition |
| --- | --- | --- | --- |
| **Single Card** | 1 | 1 | Any single card in hand. |
| **Set** | 2 | 4 | All cards must share the **exact same rank**. |
| **Sequence (Run)** | 2 | Hand Size | Cards must be in **consecutive order** and share the **same suit** *(Exception: Mixed-Suit Hand Clear)*. |

---

## 2. Invalid Discard Rules (Strict Constraints)

When validating discards, explicitly reject any combination matching the following constraints:

1. **No Corner-Wrapping Sequences:**

* Aces can act as low ($1$) or high ($14$), but cannot bridge between Kings and 2s.
* Valid sequence examples: $A\diamondsuit - 2\diamondsuit$, $Q\spadesuit - K\spadesuit - A\spadesuit$.
* Corner-wrapping sequences like $K-A-2$ are **illegal**.

2. **Mixed-Suit Sequences with Remaining Cards:**

* Sequence combinations across different suits are **strictly illegal** if any unplayed cards remain in the hand after the discard.

3. **Duplicate Rank Sequences:**

* Sequences must consist of strictly increasing consecutive ranks (e.g., $4\heartsuit - 4\heartsuit - 5\heartsuit$ is **illegal**).

---

## 3. Discard Pile Pick-Up Rules

After a player plays a valid discard combination, the discarded cards are placed on top of the discard pile. The next player may pick up a card from either the **Stock Draw Pile** or the **Discard Pile** according to the following strict rules:

### A. Stock Draw Pile Pickup

* The player draws exactly **1 unseen card** from the top of the face-down deck.

### B. Discard Pile Pickup Rules (Single Card Only)

* **Exact One-Card Pick Limit:** A player may pick up **only 1 card** from the discard pile, even if the previous player discarded a multi-card combination (a Set or a Sequence).
* **Outer-Card Selection Rule (Sequence Discards):**
* If the previous player discarded a **Sequence**, the next player may only pick up either the **first card (lowest end)** or the **last card (highest end)** of that sequence.
* *Middle cards of a sequence are locked and unavailable for pickup.*


* **Any-Card Selection Rule (Set Discards):**
* If the previous player discarded a **Set** (matching ranks) or a **Single Card**, the next player may pick **any 1 card** from that discard set.



---

## 4. Detailed Breakdown of Allowed Discards

### A. Single Card Discards

* **Size:** Exactly 1 card.
* **Rules:** Any individual card in the hand (Number, Face card, or Ace) can be discarded.

### B. Sets (Matching Ranks)

* **Size:** 2, 3, or 4 cards.
* **Rules:**
* All cards in the group must share the **exact same rank** (e.g., two $7$s, three $Q$s, or four $10$s).
* Suits can be any mix.

### C. Sequences / Runs

* **Size:** Minimum 2 cards up to the max cards in hand.
* **Standard Sequence Rules:**
* All cards must belong to the **same suit**.
* Ranks must be strictly **consecutive** (minimum length 2).
* **Ace Flexibility:**
* **Low:** Ace pairs below 2 (e.g., $A\clubsuit - 2\clubsuit$ or $A\clubsuit - 2\clubsuit - 3\clubsuit$).
* **High:** Ace pairs above King (e.g., $Q\clubsuit - K\clubsuit - A\clubsuit$).
* **Special Rule — Hand-Clearing Mixed-Suit Sequence:**
* A consecutive sequence across **different suits** (e.g., $4\heartsuit - 5\spadesuit - 6\diamondsuit$) is **VALID** if and only if discarding it empties your entire hand (i.e., `discard.length == hand.length`).

---

## 5. Discard & Pickup Combination Schema

```
┌────────────────────────────────────────────────────────────────────────┐
│                        VALID DISCARD TYPES                             │
├────────────────────────────────────────────────────────────────────────┤
│ 1. SINGLE CARD                                                         │
│    └── [Card A]                                                        │
│                                                                        │
│ 2. SETS (Same Rank, Minimum 2)                                         │
│    ├── Pair:        [Rank X, Rank X]                                   │
│    ├── Three-of-a-Kind: [Rank X, Rank X, Rank X]                       │
│    └── Four-of-a-Kind:  [Rank X, Rank X, Rank X, Rank X]               │
│                                                                        │
│ 3. SEQUENCES (Consecutive, Minimum 2)                                  │
│    ├── Standard Run:[Same Suit, Consecutive]                           │
│    └── Hand Clear:  [Mixed Suits, Consecutive, Discards ALL Hand Cards]│
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│                        DISCARD PICKUP RULES                            │
├────────────────────────────────────────────────────────────────────────┤
│ 1. FROM SINGLE DISCARD: [Card A]            ──> Pick [Card A]          │
│ 2. FROM SET DISCARD:    [7♠, 7♥, 7♦]        ──> Pick ANY 1 (7♠/7♥/7♦)  │
│ 3. FROM SEQUENCE:       [4♥, 5♥, 6♥, 7♥]    ──> Pick ENDS ONLY (4♥ or 7♥)│
└────────────────────────────────────────────────────────────────────────┘

```

---

## 6. Implementation Specification for LLM / Developer

This section provides explicit pseudocode and test suites covering both **Discard Validation** and **Pickup Validation**.

### A. Algorithmic Pickup Validation Logic (Pseudocode)

```python
def getValidPickupCards(topDiscardSet):
    """
    Given the array of cards discarded by the previous player,
    returns an array of individual cards the current player is allowed to pick up.
    """
    if len(topDiscardSet) == 0:
        return []

    # Case 1: Single Card or Set (Matching Ranks)
    if len(topDiscardSet) == 1 or all_cards_same_rank(topDiscardSet):
        return topDiscardSet  # All cards in the set are eligible for individual selection

    # Case 2: Sequence (Consecutive Ranks)
    if is_valid_consecutive_sequence(topDiscardSet):
        sorted_sequence = sort_sequence(topDiscardSet)
        first_card = sorted_sequence[0]
        last_card = sorted_sequence[-1]
        
        # If 2-card sequence, both ends are returned
        if len(sorted_sequence) == 2:
            return [first_card, last_card]
        
        # If 3+ card sequence, ONLY outer endpoints are eligible
        return [first_card, last_card]

    return []

def isValidPickup(chosenCard, topDiscardSet):
    valid_cards = getValidPickupCards(topDiscardSet)
    return chosenCard in valid_cards

```

---

### B. Comprehensive Test Cases (Discard & Pickup)

| # | Discard Pile State | Proposed Pickup | Result | Reason / Explanation |
| --- | --- | --- | --- | --- |
| 1 | $[7\heartsuit]$ | $[7\heartsuit]$ | **VALID** | Pickup from single-card discard. |
| 2 | $[8\spadesuit, 8\diamondsuit, 8\clubsuit]$ | $[8\diamondsuit]$ | **VALID** | Player can pick *any one* card from a matching rank set. |
| 3 | $[8\spadesuit, 8\diamondsuit, 8\clubsuit]$ | $[8\spadesuit, 8\diamondsuit]$ | **INVALID** | Player cannot pick up multiple cards from a set. |
| 4 | $[3\heartsuit, 4\heartsuit, 5\heartsuit]$ | $[3\heartsuit]$ | **VALID** | Lowest card (end) of a sequence. |
| 5 | $[3\heartsuit, 4\heartsuit, 5\heartsuit]$ | $[5\heartsuit]$ | **VALID** | Highest card (end) of a sequence. |
| 6 | $[3\heartsuit, 4\heartsuit, 5\heartsuit]$ | $[4\heartsuit]$ | **INVALID** | Middle cards of a sequence are locked. |
| 7 | $[4\heartsuit, 5\spadesuit, 6\diamondsuit]$ *(Mixed Hand Clear)* | $[4\heartsuit]$ | **VALID** | First card of mixed sequence. |
| 8 | $[4\heartsuit, 5\spadesuit, 6\diamondsuit]$ *(Mixed Hand Clear)* | $[5\spadesuit]$ | **INVALID** | Middle card of mixed sequence remains locked. |