import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { soundEngine } from '../utils/soundEngine';
import { hapticLightTick, hapticFirmSnap, hapticDoubleError } from '../utils/haptics';
import './TableCanvas.css';
import { Card, isValidCombination, calculateHandScore, getRankValueLow } from '../utils/yanivRules';

import type { SpectatorReading } from '../stores/gameStore';

export type { Card } from '../utils/yanivRules';

export interface OpponentInfo {
  userId: string;
  displayName: string;
  score: number;
  isHost: boolean;
  isCurrentTurn: boolean;
  isEliminated: boolean;
  cardCount: number;
  isDisconnected?: boolean;
  isCurrentPlayer?: boolean;
  /** Present only when the viewer has been knocked out and this seat is still playing. */
  spectatorReading?: SpectatorReading;
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
  // Server-driven turn timer / auto-play
  turnEndsAt?: number | null;
  turnTimerTotalSeconds?: number;
  autoPlayedPlayerId?: string | null;
  // Bonus discard fields
  bonusDiscardActive?: boolean;
  pendingBonusCard?: Card | null;
  onBonusDiscard?: ((shouldDiscard: boolean) => void) | null;
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

// Discard rules live in utils/yanivRules so they can be unit tested without React,
// and so the shared contract test can run the same cases the server runs.
export { getRankValueLow, getRankValueHigh, calculateHandScore, isValidCombination } from '../utils/yanivRules';

/**
 * What a knocked-out player is shown about someone still in the game.
 *
 * Both numbers are plain points -- the same scoring as the scoreboard, the round scores
 * and the revealed hands -- so there is nothing new to learn to read them. The emoji is
 * the label, which is why there is no wording: 🚨 is the race to end the round, 💀 is
 * the race to be knocked out of the game.
 *
 * A player who can already call Yaniv shows the word rather than a number. Every player
 * in Yaniv range has to look identical: the point is the suspense of not knowing which
 * of them takes it, and a number here would give the round away.
 */
function SpectatorMeters({ reading }: { reading: SpectatorReading }) {
  const reachable = reading.lowestReachableHandScore;

  const yanivTier = reading.canCallYanivNow
    ? 'imminent'
    : reachable !== null && reachable <= 7
    ? 'close'
    : reachable !== null && reachable <= 15
    ? 'warm'
    : 'far';

  const deathTier =
    reading.pointsFromElimination <= 10 ? 'imminent' : reading.pointsFromElimination <= 25 ? 'close' : 'far';

  return (
    <div className="spectator-meters" aria-label="Spectator view">
      <span
        className={`spectator-meter yaniv ${yanivTier}`}
        title={
          reading.canCallYanivNow
            ? 'Can call Yaniv right now'
            : `Could get down to ${reachable} points next turn`
        }
      >
        🚨 {reading.canCallYanivNow ? 'YANIV' : reachable}
      </span>
      <span
        className={`spectator-meter elimination ${deathTier}`}
        title={`${reading.pointsFromElimination} points from being knocked out`}
      >
        💀 {reading.pointsFromElimination}
      </span>
    </div>
  );
}

export default function TableCanvas({
  hand,
  topCard,
  topDiscardCards = [],
  isPlayerTurn,
  currentTurnPlayerId,
  deckCount,
  opponents = [],
  roundNumber = 1,
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
  // Current user ID for Yaniv contest UI
  currentUserId = null,
  // Server-driven turn timer / auto-play
  turnEndsAt = null,
  turnTimerTotalSeconds = 45,
  autoPlayedPlayerId = null,
  // Bonus discard fields
  bonusDiscardActive = false,
  pendingBonusCard = null,
  onBonusDiscard = null,
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

  const [reactionParticles, setReactionParticles] = useState<
    Array<{ id: string; type: 'love' | 'rage'; x: number; y: number }>
  >([]);

  const triggerReaction = useCallback(
    (type: 'love' | 'rage', targetPlayerId: string) => {
      const activePlayer = opponents.find((p) => p.isCurrentTurn || p.isCurrentPlayer);
      if (!activePlayer) return;

      const avatar = document.querySelector(`.opponent-avatar.current-player-avatar`);
      if (!avatar) return;

      const rect = avatar.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;

      setReactionParticles((prev) => [
        ...prev,
        { id: Date.now().toString(), type, x, y },
      ]);
    },
    [opponents]
  );

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

  // The banner covers the whole table and eats clicks. Its timer outlives the round it
  // belongs to, so a quick Next Round leaves it sitting over the new deal, blocking the
  // first turn. A new round always clears it.
  useEffect(() => {
    setShowAsafBanner(false);
  }, [roundNumber]);

  // Turn timer driven by the server's authoritative deadline. The server
  // performs auto-play on expiry - the client only displays and ticks.
  useEffect(() => {
    const total = turnTimerTotalSeconds || 45;
    if (!turnEndsAt) {
      setTurnTimerSeconds(total);
      return;
    }
    const updateTimer = () => {
      setTurnTimerSeconds(Math.max(0, Math.ceil((turnEndsAt - Date.now()) / 1000)));
    };
    updateTimer();
    const interval = setInterval(updateTimer, 250);
    return () => clearInterval(interval);
  }, [turnEndsAt, turnTimerTotalSeconds]);

  // Final-seconds tick sounds on your own turn
  useEffect(() => {
    if (isPlayerTurn && turnEndsAt && turnTimerSeconds > 0 && turnTimerSeconds <= 5) {
      soundEngine.playTimerTick(turnTimerSeconds <= 2);
    }
  }, [turnTimerSeconds, isPlayerTurn, turnEndsAt]);

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

  // Keyboard navigation for reordering selected card
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (selectedCards.length === 1) {
        const cardId = selectedCards[0];
        if (e.key === 'ArrowLeft') {
          e.preventDefault();
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
        } else if (e.key === 'ArrowRight') {
          e.preventDefault();
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
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectedCards]);

  // Clean up reaction particles after animation completes (1.5s > 1.3s love/rage animation)
  useEffect(() => {
    const cleanupTimer = setTimeout(() => {
      setReactionParticles([]);
    }, 1500);
    return () => clearTimeout(cleanupTimer);
  }, []);

  const selectedCardIndex = useMemo(() => {
    if (selectedCards.length === 1) {
      return localSortedHand.findIndex((c) => c.id === selectedCards[0]);
    }
    return -1;
  }, [selectedCards, localSortedHand]);

  // Note: Keyboard reordering (ArrowLeft/ArrowRight) still works for selected cards

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
              onClick={() => setShowAsafBanner(false)}
              role="button"
              tabIndex={0}
              aria-label="Dismiss Asaf announcement"
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') setShowAsafBanner(false);
              }}
            >
              <div className="asaf-strike-card">
                <span className="asaf-warning-badge">⚠️ COUNTER STRIKE</span>
                <h1 className="asaf-title">ASAF!</h1>
                <p className="asaf-details">
                  {asafByUserId ? playerNames[asafByUserId] || asafByUserId : 'An opponent'} had equal or lower score!
                </p>
                <div className="asaf-penalty-tag">+30 Point Penalty Applied</div>
                <span className="asaf-dismiss-hint">Tap to dismiss</span>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* 1. All Players Arc (Top Semi-Circle) - includes current player */}
        <div className="opponents-radial-arc">
          {opponents.map((opponent, idx) => {
            const isTurn = opponent.isCurrentTurn;
            const isCurrentPlayer = opponent.isCurrentPlayer;
            const timerProgress = isTurn ? Math.max(0, turnTimerSeconds / (turnTimerTotalSeconds || 45)) : 1;
            const isUrgentTimer = isTurn && turnTimerSeconds <= 5;
            const isDisconnected = opponent.isDisconnected;
            const isAutoPlayed = autoPlayedPlayerId != null && autoPlayedPlayerId === opponent.userId;
            // Center the mini card-back stack on the actual count, not a fixed 5-card layout
            const stackCount = Math.min(opponent.cardCount, 5);
            const stackCenter = (stackCount - 1) / 2;

            // Display name: "You" for current player, actual name for others
            const displayName = isCurrentPlayer ? 'You' : opponent.displayName;
            // Avatar initials: first 2 letters for ALL players (including current player)
            const avatarInitials = opponent.displayName.substring(0, 2).toUpperCase();

            return (
              <motion.div
                key={opponent.userId || idx}
                className={`opponent-seat ${isTurn ? 'active-turn' : ''} ${
                  opponent.isEliminated ? 'eliminated' : ''
                } ${isDisconnected ? 'disconnected' : ''} ${isCurrentPlayer ? 'current-player' : ''}`}
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
                  <div className={`opponent-avatar ${isDisconnected ? 'disconnected' : ''} ${isCurrentPlayer ? 'current-player-avatar' : ''}`}>
                    {avatarInitials}
                    {isDisconnected && <span className="disconnected-indicator" title="Disconnected">⚡</span>}
                  </div>
                  {opponent.isHost && <span className="host-badge" title="Host">👑</span>}
                  {isAutoPlayed && (
                    <span className="autoplay-badge" title="Turn auto-played by server">
                      🤖
                    </span>
                  )}
                  <div className="reaction-buttons">
                    <button
                      className="reaction-btn reaction-love"
                      onClick={() => triggerReaction('love', opponent.userId || '')}
                      title="Love"
                    >
                      ❤️
                    </button>
                    <button
                      className="reaction-btn reaction-rage"
                      onClick={() => triggerReaction('rage', opponent.userId || '')}
                      title="Rage"
                    >
                      😡
                    </button>
                  </div>
                </div>

                <div className="opponent-meta">
                  <span className="opponent-name">{displayName}</span>
                  <div className="opponent-score-pill">
                    <span className="score-val">{opponent.score} pts</span>
                  </div>
                  {opponent.spectatorReading && (
                    <SpectatorMeters reading={opponent.spectatorReading} />
                  )}
                  {/* Show YOUR TURN only when it's actually this player's turn AND it's the current user */}
                  {isTurn && isCurrentPlayer && (
                    <span className="current-player-turn-indicator-small">
                      <span className="turn-label-small">YOUR TURN</span>
                    </span>
                  )}
                  {isTurn && !isCurrentPlayer && (
                    <span className="current-player-turn-indicator-small opponent-turn">
                      <span className="turn-label-small">{displayName}'s Turn</span>
                    </span>
                  )}
                  {isDisconnected && (
                    <span className="disconnected-badge" title="Reconnecting...">🔄 Reconnecting</span>
                  )}
                </div>

                <div className="opponent-card-stack" title={`${opponent.cardCount} cards in hand`}>
                  {Array.from({ length: stackCount }).map((_, cIdx) => (
                    <div
                      key={cIdx}
                      className="mini-card-back"
                      style={{
                        transform: `translateX(${(cIdx - stackCenter) * 4}px) rotate(${(cIdx - stackCenter) * 6}deg)`,
                      }}
                    />
                  ))}
                  <span className="card-count-badge">{opponent.cardCount}</span>
                </div>
              </motion.div>
            );
          })}
        </div>

        {/* 3. Reaction Animation Overlay */}
        <div className="reaction-animation-container">
          {reactionParticles.map((particle) => {
            const className = particle.type === 'love'
              ? 'love-particle'
              : 'rage-particle';
            const html = particle.type === 'love' ? '❤️' : '😡';
            return (
              <span
                key={particle.id}
                className={className}
                style={{
                  left: particle.x - 9,
                  top: particle.y - 9,
                }}
              >
                {html}
              </span>
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

          {/* Zone 2: Discard Pile (Right) - Chinese Hand Fan Layout */}
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

                    // Chinese hand fan: tight overlap with slight rotation
                    // Fan spread based on card count (max 5 cards)
                    const totalCards = Math.min(discardDisplayCards.length, 5);
                    const centerOffset = idx - (totalCards - 1) / 2;
                    const rotationDeg = centerOffset * 3; // Gentle fan spread
                    const translateY = Math.abs(centerOffset) * 2;
                    // Z-index: center card on top, edges behind
                    const zIndex = totalCards - Math.abs(centerOffset);

                    return (
                      <motion.div
                        key={card.id || idx}
                        className={`discard-fan-card ${isDrawable ? 'drawable-eligible' : 'locked-ineligible'}`}
                        style={{
                          zIndex,
                          transformOrigin: 'bottom center',
                        }}
                        animate={{
                          rotate: rotationDeg,
                          y: translateY,
                        }}
                        transition={{ type: 'spring', stiffness: 500, damping: 30 }}
                        whileHover={isDrawable ? { y: -8, scale: 1.03, rotate: 0, boxShadow: '0 0 20px rgba(212, 175, 55, 0.8), 0 8px 24px rgba(0, 0, 0, 0.6)' } : { x: [-1, 1, -1, 0] }}
                        onClick={() => handleDrawFromDiscard(card)}
                      >
                        <img
                          src={getCardImagePath(card.rank, card.suit)}
                          alt={`${card.rank} of ${card.suit}`}
                          className="card-img"
                        />

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
                  ? 'Tap outer cards (ends only) to draw'
                  : 'Tap top card to draw'}
              </div>
            )}
          </div>
        </div>

        {/* Bonus Discard UI - shown when player draws a matching rank card from deck */}
        {bonusDiscardActive && pendingBonusCard && onBonusDiscard && (
          <div className="bonus-discard-overlay">
            <div className="bonus-discard-panel">
              <div className="bonus-discard-header">
                <span className="bonus-icon">✨</span>
                <span className="bonus-title">Matching Rank Bonus!</span>
              </div>
              <div className="bonus-discard-explanation">
                {/* Only the drawn card is on the wire; the card it matched is staged
                    server-side and never sent, so don't claim to know its suit. */}
                You drew a <strong>{pendingBonusCard.rank} of {pendingBonusCard.suit}</strong>, matching the {pendingBonusCard.rank} you just discarded!
              </div>
              <div className="bonus-card-display">
                <img
                  src={getCardImagePath(pendingBonusCard.rank, pendingBonusCard.suit)}
                  alt={`${pendingBonusCard.rank} of ${pendingBonusCard.suit}`}
                  className="bonus-card-img"
                />
                <span className="bonus-card-label">{pendingBonusCard.rank} of {pendingBonusCard.suit}</span>
              </div>
              <div className="bonus-discard-actions">
                <button
                  className="bonus-btn bonus-btn-discard"
                  onClick={() => onBonusDiscard(true)}
                  disabled={!isPlayerTurn}
                >
                  🗑️ Discard it
                </button>
                <button
                  className="bonus-btn bonus-btn-keep"
                  onClick={() => onBonusDiscard(false)}
                  disabled={!isPlayerTurn}
                >
                  🤚 Keep it
                </button>
              </div>
              <div className="bonus-discard-hint">
                {!isPlayerTurn
                  ? 'Waiting for your turn...'
                  : turnEndsAt
                    ? `${turnTimerSeconds}s to choose — either way, your turn ends here.`
                    : 'Either way, your turn ends here.'}
              </div>
            </div>
          </div>
        )}

        {/* 3. Main Player Dock (Bottom Center) */}
        <div className="main-player-dock">
          <div className="player-hud-bar">
            <div className={`hand-total-btn hud-btn outline-only ${isYanivEligible && isPlayerTurn ? 'ready-yaniv' : ''}`}>
              <span className="score-label">Hand Total:</span>
              <span className="score-digits">{handScore}</span>
            </div>

            <div className="hand-sort-controls">
              <button className="sort-btn hud-btn interactive" onClick={handleSortByRank} title="Sort hand by rank">
                ↕ Rank
              </button>
              <button className="sort-btn hud-btn interactive" onClick={handleSortBySuit} title="Sort hand by suit">
                ♣ Suit
              </button>
              <button
                className={`sort-btn hud-btn interactive call-yaniv-btn-hud ${isYanivEligible && isPlayerTurn ? 'eligible' : ''}`}
                onClick={onCallYaniv}
                title="Call Yaniv"
                disabled={!isYanivEligible || !isPlayerTurn}
              >
                🔔 Yaniv!
              </button>
            </div>
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
                      whileHover={{ y: isSelected ? -28 : -14, scale: 1.05, zIndex: 60 }}
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
