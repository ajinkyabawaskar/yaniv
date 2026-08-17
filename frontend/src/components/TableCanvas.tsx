import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { soundEngine } from '../utils/soundEngine';
import { hapticLightTick, hapticFirmSnap, hapticDoubleError } from '../utils/haptics';
import './TableCanvas.css';

export interface Card {
  id: string;
  rank: string;
  suit: string;
}

export interface OpponentInfo {
  userId: string;
  displayName: string;
  score: number;
  isHost: boolean;
  isCurrentTurn: boolean;
  isEliminated: boolean;
  cardCount: number;
}

interface TableCanvasProps {
  hand: Card[];
  topCard: Card | null;
  topDiscardCards?: Card[];
  isPlayerTurn: boolean;
  currentTurnPlayerId: string | null;
  deckCount: number;
  opponents?: OpponentInfo[];
  roundNumber?: number;
  scores?: Record<string, number>;
  onDiscard: (cardIds: string[], drawSource: string, drawnCardId?: string) => void;
  onCallYaniv: () => void;
  onContestYaniv: () => void;
  drawableDiscardCards?: Card[];
  isAsaf?: boolean;
  asafByUserId?: string | null;
  roundWinner?: string | null;
  playerNames?: Record<string, string>;
  yanivThreshold?: number;
  serverError?: string | null;
  // Yaniv Contest Timer fields
  yanivCallerId?: string | null;
  yanivCallerName?: string | null;
  yanivCalledAt?: number | null;
  yanivContestTimerSeconds?: number;
  allPlayerHands?: Record<string, Card[]>;
  // Current user ID for Yaniv contest UI
  currentUserId?: string | null;
}

export const getCardImagePath = (rank: string, suit: string): string => {
  const rankMap: Record<string, string> = {
    ACE: 'ace',
    TWO: '2',
    THREE: '3',
    FOUR: '4',
    FIVE: '5',
    SIX: '6',
    SEVEN: '7',
    EIGHT: '8',
    NINE: '9',
    TEN: '10',
    JACK: 'jack',
    QUEEN: 'queen',
    KING: 'king',
  };

  const suitMap: Record<string, string> = {
    HEARTS: 'hearts',
    DIAMONDS: 'diamonds',
    CLUBS: 'clubs',
    SPADES: 'spades',
  };

  const rankStr = rankMap[rank];
  const suitStr = suitMap[suit];

  if (!rankStr || !suitStr) {
    return '/cards/ace_of_hearts.svg'; // fallback
  }

  return `/cards/${rankStr}_of_${suitStr}.svg`;
};

export const getSuitColor = (suit: string) => {
  switch (suit) {
    case 'HEARTS':
    case 'DIAMONDS':
      return '#ef4444';
    case 'CLUBS':
    case 'SPADES':
      return '#111827';
    default:
      return '#8b5cf6';
  }
};

export const getRankValueLow = (rank: string): number => {
  const values: Record<string, number> = {
    ACE: 1, TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
    SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
    JACK: 11, QUEEN: 12, KING: 13,
  };
  return values[rank] || 0;
};

export const getRankValueHigh = (rank: string): number => {
  const values: Record<string, number> = {
    TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
    SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
    JACK: 11, QUEEN: 12, KING: 13, ACE: 14,
  };
  return values[rank] || 0;
};

export const calculateHandScore = (hand: Card[]) => {
  const rankValues: Record<string, number> = {
    ACE: 1,
    TWO: 2,
    THREE: 3,
    FOUR: 4,
    FIVE: 5,
    SIX: 6,
    SEVEN: 7,
    EIGHT: 8,
    NINE: 9,
    TEN: 10,
    JACK: 10,
    QUEEN: 10,
    KING: 10,
  };
  return hand.reduce((sum, card) => sum + (rankValues[card.rank] !== undefined ? rankValues[card.rank] : 0), 0);
};

export const isValidCombination = (cards: Card[], handSize?: number): { valid: boolean; reason?: string } => {
  if (!cards || cards.length === 0) return { valid: false, reason: 'No cards selected' };
  if (cards.length === 1) return { valid: true };

  const ranks = cards.map((c) => c.rank);

  // Check for set (same rank, 2-4 cards)
  if (new Set(ranks).size === 1) {
    if (cards.length > 4) {
      return { valid: false, reason: 'Sets cannot have more than 4 cards' };
    }
    return { valid: true };
  }

  // Check for sequence (same suit, consecutive ranks)
  if (cards.length >= 2) {
    const suits = new Set(cards.map((c) => c.suit));
    const isMixedSuit = suits.size > 1;

    if (isMixedSuit && (handSize === undefined || cards.length !== handSize)) {
      return {
        valid: false,
        reason: 'Mixed-suit sequences are only valid if they discard your ENTIRE hand',
      };
    }

    const hasKing = cards.some((c) => c.rank === 'KING');
    const hasAce = cards.some((c) => c.rank === 'ACE');
    const hasTwo = cards.some((c) => c.rank === 'TWO');
    if (hasKing && hasAce && hasTwo) {
      return { valid: false, reason: 'Corner-wrapping sequences (K-A-2) are strictly illegal' };
    }

    const ranksLow = cards.map((c) => getRankValueLow(c.rank)).filter((v) => v > 0);
    const validLow = ranksLow.length === cards.length && isValidSequenceRanks(ranksLow);

    const ranksHigh = cards.map((c) => getRankValueHigh(c.rank)).filter((v) => v > 0);
    const validHigh = ranksHigh.length === cards.length && isValidSequenceRanks(ranksHigh);

    if (validLow || validHigh) {
      return { valid: true };
    }
  }

  return {
    valid: false,
    reason: 'Invalid combination. Must be: single card, set (same rank, 2–4), or same-suit sequence (2+ consecutive).',
  };
};

const isValidSequenceRanks = (ranks: number[]): boolean => {
  if (ranks.length === 0) return false;
  const sorted = [...ranks].sort((a, b) => a - b);
  for (let i = 0; i < sorted.length - 1; i++) {
    if (sorted[i + 1] - sorted[i] !== 1) {
      return false;
    }
  }
  return true;
};

export default function TableCanvas({
  hand,
  topCard,
  topDiscardCards = [],
  isPlayerTurn,
  currentTurnPlayerId,
  deckCount,
  opponents = [],
  roundNumber = 1,
  scores = {},
  onDiscard,
  onCallYaniv,
  onContestYaniv,
  drawableDiscardCards = [],
  isAsaf = false,
  asafByUserId = null,
  roundWinner = null,
  playerNames = {},
  yanivThreshold = 7,
  serverError = null,
  // Yaniv Contest Timer fields
  yanivCallerId = null,
  yanivCallerName = null,
  yanivCalledAt = null,
  yanivContestTimerSeconds = 15,
  allPlayerHands = {},
}: TableCanvasProps) {
  const [selectedCards, setSelectedCards] = useState<string[]>([]);
  const [statusFeedback, setStatusFeedback] = useState<string | null>(null);
  const [isFeedbackError, setIsFeedbackError] = useState<boolean>(false);
  const [localSortedHand, setLocalSortedHand] = useState<Card[]>(hand);
  const [draggedCardId, setDraggedCardId] = useState<string | null>(null);
  const [dragOverCardId, setDragOverCardId] = useState<string | null>(null);
  const [isDealingAnimation, setIsDealingAnimation] = useState(false);
  const [showAsafBanner, setShowAsafBanner] = useState(false);
  const [lastTapTime, setLastTapTime] = useState<Record<string, number>>({});
  const [turnTimerSeconds, setTurnTimerSeconds] = useState<number>(30);
  const [hasPlayedYanivReadyChime, setHasPlayedYanivReadyChime] = useState(false);
  const [yanivContestTimerRemaining, setYanivContestTimerRemaining] = useState<number>(0);
  const [showYanivContestOverlay, setShowYanivContestOverlay] = useState(false);

  // Preserve user custom card reordering when hand state updates from server
  useEffect(() => {
    setLocalSortedHand((prev) => {
      const incomingIds = new Set(hand.map((c) => c.id));
      const retained = prev.filter((c) => incomingIds.has(c.id));
      const retainedIds = new Set(retained.map((c) => c.id));
      const newlyDrawn = hand.filter((c) => !retainedIds.has(c.id));
      return [...retained, ...newlyDrawn];
    });
  }, [hand]);

  useEffect(() => {
    if (serverError) {
      setStatusFeedback(serverError);
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
    }
  }, [serverError]);

  useEffect(() => {
    if (hand.length > 0) {
      setIsDealingAnimation(true);
      const dealCount = Math.min(hand.length, 5);
      for (let i = 0; i < dealCount; i++) {
        setTimeout(() => {
          soundEngine.playDealerFlick();
        }, i * 140);
      }
      const timer = setTimeout(() => {
        setIsDealingAnimation(false);
      }, dealCount * 140 + 300);
      return () => clearTimeout(timer);
    }
  }, [roundNumber]);

  
  useEffect(() => {
    if (isAsaf) {
      setShowAsafBanner(true);
      soundEngine.playAsafCrash();
      const timer = setTimeout(() => setShowAsafBanner(false), 4500);
      return () => clearTimeout(timer);
    }
  }, [isAsaf]);

  useEffect(() => {
    setTurnTimerSeconds(30);
    const interval = setInterval(() => {
      setTurnTimerSeconds((prev) => {
        if (prev <= 1) {
          // Auto-play: discard first card and draw from deck when timer expires
          if (isPlayerTurn && hand.length > 0) {
            const autoDiscard = [hand[0].id];
            soundEngine.playTimerTick(true);
            onDiscard(autoDiscard, 'DECK');
          }
          return 0;
        }
        if (prev <= 6 && prev > 1) {
          soundEngine.playTimerTick(prev <= 3);
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [currentTurnPlayerId, isPlayerTurn, hand, onDiscard]);

  useEffect(() => {
    if (!isPlayerTurn) {
      setSelectedCards([]);
      setStatusFeedback(null);
    }
  }, [isPlayerTurn, currentTurnPlayerId]);

  // Yaniv Contest Timer Effect
  useEffect(() => {
    if (yanivCallerId && yanivCalledAt && yanivContestTimerSeconds > 0) {
      setShowYanivContestOverlay(true);
      const endTime = yanivCalledAt + yanivContestTimerSeconds * 1000;
      
      const updateTimer = () => {
        const now = Date.now();
        const remaining = Math.max(0, Math.ceil((endTime - now) / 1000));
        setYanivContestTimerRemaining(remaining);
        
        if (remaining <= 0) {
          setShowYanivContestOverlay(false);
        }
      };
      
      updateTimer();
      const interval = setInterval(updateTimer, 100);
      return () => clearInterval(interval);
    } else {
      setShowYanivContestOverlay(false);
    }
  }, [yanivCallerId, yanivCalledAt, yanivContestTimerSeconds]);

  const currentHandCards = localSortedHand;

  const handScore = useMemo(() => calculateHandScore(currentHandCards), [currentHandCards]);
  const isYanivEligible = handScore <= yanivThreshold;

  useEffect(() => {
    if (isYanivEligible && !hasPlayedYanivReadyChime && isPlayerTurn) {
      soundEngine.playYanivReadyChime();
      setHasPlayedYanivReadyChime(true);
    } else if (!isYanivEligible) {
      setHasPlayedYanivReadyChime(false);
    }
  }, [isYanivEligible, hasPlayedYanivReadyChime, isPlayerTurn]);

  const discardDisplayCards = useMemo(() => {
    if (topDiscardCards && topDiscardCards.length > 0) {
      return topDiscardCards;
    }
    if (drawableDiscardCards && drawableDiscardCards.length > 0) {
      return drawableDiscardCards;
    }
    if (topCard) {
      return [topCard];
    }
    return [];
  }, [topDiscardCards, drawableDiscardCards, topCard]);

  // Card click: Single-tap toggle or double-tap multi-select
  const handleCardClick = (card: Card) => {
    const now = Date.now();
    const lastTap = lastTapTime[card.id] || 0;
    const isDoubleTap = now - lastTap < 300;
    setLastTapTime((prev) => ({ ...prev, [card.id]: now }));

    if (isDoubleTap) {
      const matchingRankCards = localSortedHand.filter((c) => c.rank === card.rank);
      const matchingIds = matchingRankCards.map((c) => c.id);
      setSelectedCards((prev) => {
        const allSelected = matchingIds.every((id) => prev.includes(id));
        if (allSelected) {
          return prev.filter((id) => !matchingIds.includes(id));
        } else {
          return Array.from(new Set([...prev, ...matchingIds]));
        }
      });
      soundEngine.playMultiSelectTick();
      hapticLightTick();
    } else {
      setSelectedCards((prev) =>
        prev.includes(card.id) ? prev.filter((id) => id !== card.id) : [...prev, card.id]
      );
      soundEngine.playCardSelectTick();
      hapticLightTick();
    }
  };

  // Drag-and-drop handler for hand reordering only (no staging)
  const handleHandCardDragStart = (e: React.DragEvent, cardId: string) => {
    setDraggedCardId(cardId);
    e.dataTransfer.setData('handReorderId', cardId);
    e.dataTransfer.effectAllowed = 'move';
    soundEngine.playFeltSlide(0.4);
  };

  const handleHandCardDragOver = (e: React.DragEvent, targetCardId: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragOverCardId !== targetCardId) {
      setDragOverCardId(targetCardId);
    }
  };

  const handleHandCardDragLeave = (targetCardId: string) => {
    if (dragOverCardId === targetCardId) {
      setDragOverCardId(null);
    }
  };

  const handleHandCardDrop = (e: React.DragEvent, targetCardId: string) => {
    e.preventDefault();
    e.stopPropagation();
    const sourceId = e.dataTransfer.getData('handReorderId') || draggedCardId;

    if (sourceId && sourceId !== targetCardId) {
      setLocalSortedHand((prev) => {
        const sourceIdx = prev.findIndex((c) => c.id === sourceId);
        const targetIdx = prev.findIndex((c) => c.id === targetCardId);
        if (sourceIdx === -1 || targetIdx === -1) return prev;
        const next = [...prev];
        const [moved] = next.splice(sourceIdx, 1);
        next.splice(targetIdx, 0, moved);
        return next;
      });
      soundEngine.playCardSelectTick();
      hapticLightTick();
    }

    setDraggedCardId(null);
    setDragOverCardId(null);
  };

  // Nudge selected card left/right
  const handleNudgeCardLeft = (cardId: string) => {
    setLocalSortedHand((prev) => {
      const idx = prev.findIndex((c) => c.id === cardId);
      if (idx <= 0) return prev;
      const next = [...prev];
      const [moved] = next.splice(idx, 1);
      next.splice(idx - 1, 0, moved);
      return next;
    });
    soundEngine.playCardSelectTick();
    hapticLightTick();
  };

  const handleNudgeCardRight = (cardId: string) => {
    setLocalSortedHand((prev) => {
      const idx = prev.findIndex((c) => c.id === cardId);
      if (idx === -1 || idx >= prev.length - 1) return prev;
      const next = [...prev];
      const [moved] = next.splice(idx, 1);
      next.splice(idx + 1, 0, moved);
      return next;
    });
    soundEngine.playCardSelectTick();
    hapticLightTick();
  };

  // Keyboard navigation for reordering selected card
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (selectedCards.length === 1) {
        const cardId = selectedCards[0];
        if (e.key === 'ArrowLeft') {
          e.preventDefault();
          handleNudgeCardLeft(cardId);
        } else if (e.key === 'ArrowRight') {
          e.preventDefault();
          handleNudgeCardRight(cardId);
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedCards]);

  const validateAndDiscard = useCallback((
    drawSource: 'DECK' | 'DISCARD_PILE',
    drawnCardId?: string
  ) => {
    if (selectedCards.length === 0) {
      setStatusFeedback('Select cards to discard from your hand first!');
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }
    const cardsObjects = localSortedHand.filter((c) => selectedCards.includes(c.id));
    const validation = isValidCombination(cardsObjects, localSortedHand.length);
    if (!validation.valid) {
      setStatusFeedback(validation.reason || 'Invalid combination');
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }
    soundEngine.playDealerFlick();
    onDiscard(selectedCards, drawSource, drawnCardId);
    setSelectedCards([]);
    setStatusFeedback(null);
    setIsFeedbackError(false);
  }, [selectedCards, localSortedHand, onDiscard]);

  const handleDrawFromDeck = () => {
    if (!isPlayerTurn) return;
    validateAndDiscard('DECK');
  };

  const handleDrawFromDiscard = (targetCard: Card) => {
    if (!isPlayerTurn) return;

    if (discardDisplayCards.length === 0) {
      setStatusFeedback('Discard pile is empty');
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }

    const isDrawable = drawableDiscardCards.some((dc) => dc.id === targetCard.id);
    if (!isDrawable) {
      setStatusFeedback(
        `Locked: Middle card (${targetCard.rank} of ${targetCard.suit}) cannot be drawn. Only outer ends of a sequence are eligible.`
      );
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }

    validateAndDiscard('DISCARD_PILE', targetCard.id);
  };

  const handleSortByRank = () => {
    soundEngine.playSortCascade();
    hapticLightTick();
    setLocalSortedHand((prev) => [...prev].sort((a, b) => getRankValueLow(a.rank) - getRankValueLow(b.rank)));
  };

  const handleSortBySuit = () => {
    soundEngine.playSortCascade();
    hapticLightTick();
    setLocalSortedHand((prev) =>
      [...prev].sort((a, b) => {
        if (a.suit === b.suit) {
          return getRankValueLow(a.rank) - getRankValueLow(b.rank);
        }
        return a.suit.localeCompare(b.suit);
      })
    );
  };

  const selectedCardIndex = useMemo(() => {
    if (selectedCards.length === 1) {
      return localSortedHand.findIndex((c) => c.id === selectedCards[0]);
    }
    return -1;
  }, [selectedCards, localSortedHand]);

  return (
    <div className="table-canvas-root">
      <div className="felt-surface">
        {/* Yaniv Contest Overlay */}
        <AnimatePresence>
          {showYanivContestOverlay && yanivCallerId && yanivCallerName && (
            <motion.div
              className="yaniv-contest-overlay"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 1.1 }}
              transition={{ type: 'spring', damping: 20, stiffness: 200 }}
            >
              <div className="contest-overlay-bg" />
              <div className="contest-overlay-content">
                <div className="contest-pulse-ring" />
                <div className="contest-pulse-ring" style={{ animationDelay: '0.5s' }} />
                <div className="contest-pulse-ring" style={{ animationDelay: '1s' }} />
                
                <div className="contest-header">
                  <div className="contest-icon">⚡</div>
                  <h1 className="contest-title">YANIV CALLED</h1>
                </div>
                
                <div className="contest-caller-info">
                  <span className="contest-caller-label">Called by</span>
                  <span className="contest-caller-name">{yanivCallerName}</span>
                </div>
                
                <div className="contest-timer">
                  <span className="contest-timer-value">{yanivContestTimerRemaining}s</span>
                  <span className="contest-timer-label">to Contest (Asaf)</span>
                </div>
                
                <div className="contest-progress-bar">
                  <div 
                    className="contest-progress-fill"
                    style={{ 
                      width: `${(yanivContestTimerRemaining / yanivContestTimerSeconds) * 100}%` 
                    }} 
                  />
                </div>
                
                {yanivCallerId !== currentUserId && (
                  <motion.button
                    className="contest-btn"
                    onClick={onContestYaniv}
                    whileHover={{ scale: 1.05 }}
                    whileTap={{ scale: 0.95 }}
                    animate={{
                      boxShadow: [
                        '0 0 20px rgba(239, 68, 68, 0.4)',
                        '0 0 40px rgba(239, 68, 68, 0.8)',
                        '0 0 20px rgba(239, 68, 68, 0.4)',
                      ],
                    }}
                    transition={{ repeat: Infinity, duration: 1.2 }}
                  >
                    🛡️ CONTEST (ASAF)
                  </motion.button>
                )}
                
                {yanivCallerId === currentUserId && (
                  <div className="contest-waiting-message">
                    Waiting for other players to decide...
                  </div>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Dramatic Asaf Banner */}
        <AnimatePresence>
          {showAsafBanner && (
            <motion.div
              className="dramatic-asaf-overlay"
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 1.1 }}
              transition={{ duration: 0.3 }}
            >
              <div className="asaf-strike-card">
                <span className="asaf-warning-badge">⚠️ COUNTER STRIKE</span>
                <h1 className="asaf-title">ASAF!</h1>
                <p className="asaf-details">
                  {asafByUserId ? playerNames[asafByUserId] || asafByUserId : 'An opponent'} had equal or lower score!
                </p>
                <div className="asaf-penalty-tag">+30 Point Penalty Applied</div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* 1. Opponents Arc (Top Semi-Circle) */}
        <div className="opponents-radial-arc">
          {opponents.map((opponent, idx) => {
            const isTurn = opponent.isCurrentTurn;
            const timerProgress = isTurn ? Math.max(0, turnTimerSeconds / 30) : 1;
            const isUrgentTimer = isTurn && turnTimerSeconds <= 5;

            return (
              <motion.div
                key={opponent.userId || idx}
                className={`opponent-seat ${isTurn ? 'active-turn' : ''} ${
                  opponent.isEliminated ? 'eliminated' : ''
                }`}
                initial={{ opacity: 0, y: -20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.1 }}
              >
                <div className="opponent-avatar-wrap">
                  {isTurn && (
                    <svg className={`turn-timer-ring ${isUrgentTimer ? 'urgent' : ''}`} viewBox="0 0 100 100">
                      <circle cx="50" cy="50" r="46" className="timer-track" />
                      <circle
                        cx="50"
                        cy="50"
                        r="46"
                        className="timer-fill"
                        style={{
                          strokeDasharray: 289,
                          strokeDashoffset: 289 * (1 - timerProgress),
                        }}
                      />
                    </svg>
                  )}
                  <div className="opponent-avatar">{opponent.displayName.substring(0, 2).toUpperCase()}</div>
                  {opponent.isHost && <span className="host-badge" title="Host">👑</span>}
                </div>

                <div className="opponent-meta">
                  <span className="opponent-name">{opponent.displayName}</span>
                  <div className="opponent-score-pill">
                    <span className="score-val">{opponent.score} pts</span>
                  </div>
                </div>

                <div className="opponent-card-stack" title={`${opponent.cardCount} cards in hand`}>
                  {Array.from({ length: Math.min(opponent.cardCount, 5) }).map((_, cIdx) => (
                    <div
                      key={cIdx}
                      className="mini-card-back"
                      style={{
                        transform: `translateX(${(cIdx - 2) * 4}px) rotate(${(cIdx - 2) * 6}deg)`,
                      }}
                    />
                  ))}
                  <span className="card-count-badge">{opponent.cardCount}</span>
                </div>
              </motion.div>
            );
          })}
        </div>

        {/* 2. Center Play Area (2-Zone Layout) */}
        <div className="center-play-area">
          {/* Zone 1: Draw Pile (Left) */}
          <div
            className={`zone-column draw-pile-zone ${selectedCards.length > 0 ? 'pulse-prompt' : ''}`}
            onClick={handleDrawFromDeck}
          >
            <div className="zone-header">
              <span className="zone-title">DRAW PILE</span>
              <span className="deck-count-pill">{deckCount} left</span>
            </div>

            <div className="deck-stack-3d">
              <div className="deck-layer layer-3" />
              <div className="deck-layer layer-2" />
              <div className="deck-layer layer-1">
                <div className="card-back-pattern">
                  <div className="card-back-emblem">♠</div>
                </div>
              </div>
            </div>

            {isPlayerTurn && (
              <motion.div
                className="draw-prompt-badge"
                initial={{ scale: 0.8, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
              >
                Tap to Draw Deck
              </motion.div>
            )}
          </div>

          {/* Zone 2: Discard Pile (Right) - expanded */}
          <div className="zone-column discard-pile-zone">
            <div className="zone-header">
              <span className="zone-title">DISCARD PILE</span>
              <span className="discard-count-pill">{discardDisplayCards.length} cards</span>
            </div>

            <div className="discard-fan-container">
              {discardDisplayCards.length === 0 ? (
                <div className="discard-empty-box">Empty</div>
              ) : (
                <div className="discard-cards-fan">
                  {discardDisplayCards.map((card, idx) => {
                    const isDrawable = drawableDiscardCards.some((dc) => dc.id === card.id);
                    const isSequenceMiddleLocked =
                      discardDisplayCards.length >= 3 &&
                      !isDrawable &&
                      idx > 0 &&
                      idx < discardDisplayCards.length - 1;

                    return (
                      <motion.div
                        key={card.id || idx}
                        className={`discard-fan-card ${isDrawable ? 'drawable-eligible' : 'locked-ineligible'}`}
                        style={{
                          left: `${idx * 26}px`,
                          zIndex: idx + 1,
                        }}
                        whileHover={isDrawable ? { y: -12, scale: 1.05 } : { x: [-2, 2, -2, 0] }}
                        onClick={() => handleDrawFromDiscard(card)}
                      >
                        <img
                          src={getCardImagePath(card.rank, card.suit)}
                          alt={`${card.rank} of ${card.suit}`}
                          className="card-img"
                        />

                        {isDrawable && (
                          <div className="pick-handle-glow" title="Eligible to pick up">
                            ✨ Pick
                          </div>
                        )}

                        {isSequenceMiddleLocked && (
                          <div className="locked-indicator" title="Middle sequence cards cannot be drawn">
                            🔒
                          </div>
                        )}
                      </motion.div>
                    );
                  })}
                </div>
              )}
            </div>

            {isPlayerTurn && (
              <div className="discard-prompt-hint">
                {drawableDiscardCards.length > 1
                  ? 'Tap outer card (ends only) to draw'
                  : 'Tap top card to draw'}
              </div>
            )}
          </div>
        </div>

        {/* 3. Main Player Dock (Bottom Center) */}
        <div className="main-player-dock">
          <div className="player-hud-bar">
            <div className={`hand-total-pill ${isYanivEligible ? 'ready-yaniv' : ''}`}>
              <span className="score-label">TOTAL:</span>
              <span className="score-digits">{handScore}</span>
              {isYanivEligible && <span className="yaniv-badge">✨ READY FOR YANIV!</span>}
            </div>

            <div className="hand-sort-controls">
              <button className="sort-btn" onClick={handleSortByRank} title="Sort hand by rank">
                ↕ Rank
              </button>
              <button className="sort-btn" onClick={handleSortBySuit} title="Sort hand by suit">
                ♣ Suit
              </button>

              {/* Nudge Buttons when 1 card is selected */}
              {selectedCards.length === 1 && (
                <div className="nudge-group">
                  <button
                    className="nudge-btn"
                    onClick={() => handleNudgeCardLeft(selectedCards[0])}
                    disabled={selectedCardIndex <= 0}
                    title="Shift card left (or press ← key)"
                  >
                    ◀
                  </button>
                  <button
                    className="nudge-btn"
                    onClick={() => handleNudgeCardRight(selectedCards[0])}
                    disabled={selectedCardIndex === -1 || selectedCardIndex >= localSortedHand.length - 1}
                    title="Shift card right (or press → key)"
                  >
                    ▶
                  </button>
                </div>
              )}
            </div>

            {isPlayerTurn && isYanivEligible && (
              <motion.button
                className="call-yaniv-btn"
                onClick={onCallYaniv}
                whileHover={{ scale: 1.06 }}
                whileTap={{ scale: 0.95 }}
                animate={{
                  boxShadow: [
                    '0 0 15px rgba(212, 175, 55, 0.4)',
                    '0 0 35px rgba(212, 175, 55, 0.85)',
                    '0 0 15px rgba(212, 175, 55, 0.4)',
                  ],
                }}
                transition={{ repeat: Infinity, duration: 1.5 }}
              >
                🔔 CALL YANIV!
              </motion.button>
            )}

            {isPlayerTurn && selectedCards.length > 0 && (
              <div className="selected-hint">
                Tap Draw Pile or a discard card to discard & draw
              </div>
            )}
          </div>

          <div className="player-hand-container">
            <div className="player-hand-fanned">
              <AnimatePresence>
                {localSortedHand.map((card, idx) => {
                  const isSelected = selectedCards.includes(card.id);

                  const totalCards = localSortedHand.length;
                  const centerOffset = idx - (totalCards - 1) / 2;
                  const rotationDeg = centerOffset * 3.5;
                  const translateY = Math.abs(centerOffset) * 4;
                  const isDraggingThis = draggedCardId === card.id;
                  const isDragTarget = dragOverCardId === card.id && draggedCardId !== card.id;

                  return (
                    <motion.div
                      key={card.id}
                      className={`hand-card ${isSelected ? 'selected-lift' : ''} ${
                        isDraggingThis ? 'is-being-dragged' : ''
                      } ${isDragTarget ? 'drag-over-target' : ''} interactive`}
                      style={{
                        zIndex: isSelected ? 50 : isDragTarget ? 45 : idx + 5,
                      }}
                      layout
                      initial={
                        isDealingAnimation
                          ? { y: -200, x: 0, opacity: 0, rotate: 180 }
                          : { opacity: 1, y: 0 }
                      }
                      animate={{
                        opacity: isDraggingThis ? 0.4 : 1,
                        y: isSelected ? -24 : isDragTarget ? -16 : translateY,
                        rotate: isSelected || isDragTarget ? 0 : rotationDeg,
                        scale: isSelected ? 1.08 : isDragTarget ? 1.05 : 1,
                      }}
                      transition={{ type: 'spring', stiffness: 400, damping: 28 }}
                      draggable={true}
                      onDragStart={(e) => handleHandCardDragStart(e as any, card.id)}
                      onDragOver={(e) => handleHandCardDragOver(e as any, card.id)}
                      onDragLeave={() => handleHandCardDragLeave(card.id)}
                      onDrop={(e) => handleHandCardDrop(e as any, card.id)}
                      onClick={() => handleCardClick(card)}
                      whileHover={{ y: isSelected ? -28 : -14, scale: 1.05 }}
                    >
                      <img
                        src={getCardImagePath(card.rank, card.suit)}
                        alt={`${card.rank} of ${card.suit}`}
                        className="card-img"
                      />
                      {isSelected && <div className="selected-gold-trim" />}
                      {isDragTarget && <div className="reorder-insert-glow" />}
                    </motion.div>
                  );
                })}
              </AnimatePresence>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
