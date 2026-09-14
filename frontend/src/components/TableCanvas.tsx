import React, { useState, useEffect, useMemo, useCallback, useRef, useImperativeHandle, forwardRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { soundEngine } from '../utils/soundEngine';
import { playAsafSound, stopAsafSound, playWaitingForAsafSound, stopWaitingForAsafSound } from '../utils/sound';
import { hapticLightTick, hapticFirmSnap, hapticDoubleError } from '../utils/haptics';
import CardFlightLayer, { CardFlightSpec, FlightPoint, isLowEndDevice } from './CardFlightLayer';
import CardFace from './CardFace';
import './TableCanvas.css';
import { Card, isValidCombination, calculateHandScore, getRankValueLow } from '../utils/yanivRules';
import { assetUrl } from '../utils/api';

import type { ReactionEvent, SpectatorReading } from '../stores/gameStore';

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
  /** True once the server has revealed the round (ROUND_OVER / GAME_OVER). */
  isRoundOver?: boolean;
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
  onSendReaction?: (type: 'LOVE' | 'RAGE' | 'TAUNT' | 'MOCK' | 'SHOCK' | 'FLEX', targetUserId: string) => void;
}

/**
 * Emotes are events, not state. GameView owns the room subscription and hands each
 * broadcast straight to the table as it arrives, so nothing is buffered anywhere and a
 * table mounting into a new round starts silent instead of replaying the last one.
 */
export interface TableCanvasHandle {
  playReaction: (event: ReactionEvent) => void;
}

/** Long enough to outlast the log row's slide-in animation in TableCanvas.css. */
const EMOTE_BANNER_LIFETIME_MS = 2500;
const EMOTE_ICON: Record<string, string> = { LOVE: '❤️', RAGE: '😡', TAUNT: '💀', MOCK: '💥', SHOCK: '😱', FLEX: '😎' };
/**
 * Shown only if an emote somehow arrives without the server's words on it. The server
 * authors the real text (ReactionController.REACTION_TEXT) so that every screen in the
 * room shows the same thing and no client can put its own words on someone else's felt.
 */
const EMOTE_FALLBACK_TEXT: Record<string, string> = {
  LOVE: 'thanks for the card(s)',
  RAGE: 'jaldi khel l***',
  TAUNT: 'halke ho jao',
  MOCK: 'lambe lag gaye',
  SHOCK: 'oh no!',
  FLEX: 'oh yes!',
};

/**
 * Memoized card-art URL lookup. Called per card per render (including the
 * 1/sec turn-timer tick); without the cache it rebuilds two map literals and
 * two string concats for every card on every one of those renders.
 * Pure function of (rank, suit) — the cache cannot change what it returns.
 */
const cardImagePathCache = new Map<string, string>();

export const getCardImagePath = (rank: string, suit: string): string => {
  const cacheKey = rank + '|' + suit;
  const cached = cardImagePathCache.get(cacheKey);
  if (cached !== undefined) return cached;
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
    return assetUrl('/cards/ace_of_hearts.svg'); // fallback
  }

  const path = assetUrl(`/cards/${rankStr}_of_${suitStr}.svg`);
  cardImagePathCache.set(cacheKey, path);
  return path;
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
 * Flight anchors, measured as card-shaped boxes — never raw element rects.
 *
 * Raw rects carry whatever the element happens to be: the discard container
 * is two card-widths wide, a selected hand card carries a scale transform, a
 * fanned card carries rotation. Flying those boxes verbatim stretches the
 * card mid-motion (and `object-fit: cover` then crops the face into the
 * wrong shape). Every endpoint is instead a true card-aspect box in a single
 * measured size, centered on the anchor's visual center — so the flying card
 * keeps its exact shape for the whole flight at every breakpoint.
 * Returns null when the anchor is missing or unlaid-out (zero rect), in
 * which case callers fall back to applying the state change at once.
 */
export const FALLBACK_CARD_SIZE = { width: 88, height: 124 };

export const anchorCenter = (el: HTMLElement | null): { x: number; y: number } | null => {
  if (!el) return null;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) return null;
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
};

export const cardFlightBox = (
  el: HTMLElement | null,
  size: { width: number; height: number }
): FlightPoint | null => {
  const center = anchorCenter(el);
  if (!center) return null;
  return {
    x: center.x - size.width / 2,
    y: center.y - size.height / 2,
    width: size.width,
    height: size.height,
  };
};

export interface FanLandingSlot extends FlightPoint {
  /** End rotation matching the fan slot (same formula as DiscardFanCard). */
  rotation: number;
}

/**
 * Where each card of a fresh discard combo lands on the pile — one slot per
 * card, fanned the way DiscardFanCard renders them, instead of all stacked
 * on the pile center.
 *
 * Previously every flight in a set shared one `to` box, so N cards landed in
 * a pile and the real fan popped in underneath them spread out: the
 * stacked-landing → spread-pop snap. These slots mirror the fan's own math
 * (rotation/translateY identical to DiscardFanCard; horizontal step
 * proportional to the measured card width so it tracks every breakpoint the
 * same way the clamp()-sized cards do), so each flight docks exactly where
 * its pile card is about to render and the handoff is invisible.
 */
export const fanLandingSlots = (
  pileBox: FlightPoint,
  size: { width: number; height: number },
  count: number
): FanLandingSlot[] => {
  const n = Math.max(1, Math.min(count, 5));
  // Overlap step as a fraction of card width, matching the CSS negative
  // margins that cap the fan at ~two card widths (see TableCanvas.css):
  // 2 cards step wide, 3 tighter, 4+ near-flat.
  const step = n <= 2 ? size.width * 0.7 : n === 3 ? size.width * 0.5 : size.width * 0.28;
  const cx = pileBox.x + pileBox.width / 2;
  const cy = pileBox.y + pileBox.height / 2;
  const flatFan = n >= 4;
  return Array.from({ length: count }, (_, i) => {
    const off = i - (count - 1) / 2;
    return {
      x: cx + off * step - size.width / 2,
      y: cy - size.height / 2 + (flatFan ? 0 : Math.abs(off) * 2),
      width: size.width,
      height: size.height,
      rotation: off * (flatFan ? 1 : 3),
    };
  });
};

/**
 * What a knocked-out player is shown about someone still in the game.
 *
 * The emoji is the label, which is why there is no wording: 🚨 is the race to end the
 * round, 💀 is the race to be knocked out of the game.
 *
 * 🚨 is a percentage of the way to Yaniv range, 💀 is plain points against the score
 * limit. They read differently on purpose. Points would let a spectator name the exact
 * sum a player is about to land on, which is the suspense the round runs on; the score
 * limit is public, so counting down to it gives nothing away.
 *
 * A player who can already call Yaniv shows the word rather than a number. Every player
 * in Yaniv range has to look identical: the point is the suspense of not knowing which
 * of them takes it, and a number here would give the round away.
 */
const SpectatorMeters = React.memo(function SpectatorMeters({ reading }: { reading: SpectatorReading }) {
  const percent = reading.yanivProximityPercent;

  const yanivTier = reading.canCallYanivNow
    ? 'imminent'
    : percent !== null && percent >= 90
    ? 'close'
    : percent !== null && percent >= 60
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
            : `${percent}% of the way to Yaniv range after their next turn`
        }
      >
        🚨 {reading.canCallYanivNow ? 'YANIV' : `${percent}%`}
      </span>
      <span
        className={`spectator-meter elimination ${deathTier}`}
        title={`${reading.pointsFromElimination} points from being knocked out`}
      >
        💀 {reading.pointsFromElimination}
      </span>
    </div>
  );
}, areSpectatorMetersEqual);

function areSpectatorMetersEqual(
  prev: { reading: SpectatorReading },
  next: { reading: SpectatorReading }
): boolean {
  return (
    prev.reading.canCallYanivNow === next.reading.canCallYanivNow &&
    prev.reading.yanivProximityPercent === next.reading.yanivProximityPercent &&
    prev.reading.pointsFromElimination === next.reading.pointsFromElimination
  );
}

interface OpponentSeatProps {
  opponent: OpponentInfo;
  index: number;
  turnTimerSeconds: number;
  turnTimerTotalSeconds: number;
  autoPlayed: boolean;
}

/**
 * One seat in the opponents arc. Memoized so the per-second turn-timer tick
 * re-renders only the seat holding the turn (its progress bar moves) instead
 * of every seat in the arc.
 */
const OpponentSeat = React.memo(function OpponentSeat({
  opponent,
  index,
  turnTimerSeconds,
  turnTimerTotalSeconds,
  autoPlayed,
}: OpponentSeatProps) {
  const isTurn = opponent.isCurrentTurn;
  const isCurrentPlayer = opponent.isCurrentPlayer;
  const timerProgress = isTurn ? Math.max(0, turnTimerSeconds / (turnTimerTotalSeconds || 45)) : 1;
  const isUrgentTimer = isTurn && turnTimerSeconds <= 5;
  const isDisconnected = opponent.isDisconnected;
  const stackCount = Math.min(opponent.cardCount, 5);
  const stackCenter = (stackCount - 1) / 2;
  const displayName = isCurrentPlayer ? 'You' : opponent.displayName;

  return (
    <motion.div
      className={`opponent-seat ${isTurn ? 'active-turn' : ''} ${
        opponent.isEliminated ? 'eliminated' : ''
      } ${isDisconnected ? 'disconnected' : ''} ${isCurrentPlayer ? 'current-player' : ''}`}
      data-user-id={opponent.userId}
      initial={{ opacity: 0, y: -20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.1 }}
    >
      {isTurn && (
        <div className="turn-progress-track" aria-hidden="true">
          <div
            className={`turn-progress-fill ${isUrgentTimer ? 'urgent' : ''}`}
            style={{ width: `${Math.max(0, Math.min(100, timerProgress * 100))}%` }}
          />
        </div>
      )}
      <div className="opponent-identity">
        {isTurn && <span className="turn-dot" title="Current turn" />}
        <span className="opponent-name">{displayName}</span>
        {opponent.isHost && <span className="inline-badge" title="Host">👑</span>}
        {autoPlayed && (
          <span className="inline-badge" title="Turn auto-played by server">🤖</span>
        )}
        {isDisconnected && (
          <span className="inline-badge" title="Disconnected — reconnecting…">⚡</span>
        )}
      </div>
      <div className="opponent-meta">
        {opponent.spectatorReading && (
          <SpectatorMeters reading={opponent.spectatorReading} />
        )}
      </div>
      <div className="opponent-info-row">
        <div className="opponent-score-pill">
          <span className="score-val">{opponent.score} pts</span>
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
      </div>
    </motion.div>
  );
}, areOpponentSeatPropsEqual);

function areOpponentSeatPropsEqual(prev: OpponentSeatProps, next: OpponentSeatProps): boolean {
  const a = prev.opponent;
  const b = next.opponent;
  // Timer props only matter for the seat holding the turn; idle seats skip
  // every tick entirely.
  const timerRelevant =
    a.isCurrentTurn || b.isCurrentTurn
      ? prev.turnTimerSeconds === next.turnTimerSeconds &&
        prev.turnTimerTotalSeconds === next.turnTimerTotalSeconds
      : true;
  return (
    timerRelevant &&
    prev.index === next.index &&
    prev.autoPlayed === next.autoPlayed &&
    a.userId === b.userId &&
    a.displayName === b.displayName &&
    a.score === b.score &&
    a.isHost === b.isHost &&
    a.isCurrentTurn === b.isCurrentTurn &&
    a.isEliminated === b.isEliminated &&
    a.cardCount === b.cardCount &&
    a.isDisconnected === b.isDisconnected &&
    a.isCurrentPlayer === b.isCurrentPlayer &&
    a.spectatorReading === b.spectatorReading
  );
}

interface HandCardProps {
  card: Card;
  index: number;
  totalCards: number;
  isSelected: boolean;
  isDraggingThis: boolean;
  isDragTarget: boolean;
  isDealingAnimation: boolean;
  onCardClick: (card: Card) => void;
  onDragStart: (e: React.DragEvent, cardId: string) => void;
  onDragOver: (e: React.DragEvent, cardId: string) => void;
  onDragLeave: (cardId: string) => void;
  onDrop: (e: React.DragEvent, cardId: string) => void;
  registerEl: (cardId: string, el: HTMLDivElement | null) => void;
}

/**
 * One card in the player's hand. Memoized so selecting, hovering, or
 * timer-ticking one card doesn't re-render the siblings — only the card
 * whose props changed re-renders.
 */
const HandCard = React.memo(function HandCard({
  card,
  index,
  totalCards,
  isSelected,
  isDraggingThis,
  isDragTarget,
  isDealingAnimation,
  onCardClick,
  onDragStart,
  onDragOver,
  onDragLeave,
  onDrop,
  registerEl,
}: HandCardProps) {
  const centerOffset = index - (totalCards - 1) / 2;
  const rotationDeg = centerOffset * 3.5;
  const translateY = Math.abs(centerOffset) * 4;

  return (
    <motion.div
      ref={(el) => registerEl(card.id, el as HTMLDivElement | null)}
      className={`hand-card ${isSelected ? 'selected-lift' : ''} ${
        isDraggingThis ? 'is-being-dragged' : ''
      } ${isDragTarget ? 'drag-over-target' : ''} interactive`}
      style={{
        zIndex: isSelected ? 50 : isDragTarget ? 45 : index + 5,
        // Promote once so every select/hover/drag frame stays on the
        // compositor instead of re-rasterizing the SVG per frame.
        willChange: 'transform, opacity',
      }}
      // NOTE: no `layout` prop here by design. `layout` forces a layout
      // measurement + main-thread position animation on every hand reorder,
      // which blows the 8.33ms frame budget during sort/deal. Reorder and
      // select/hover motion below is transform/opacity-only (GPU path).
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
      transition={{
        type: 'spring',
        // Low-end devices (<= 4 cores) select softer: smaller per-frame deltas
        // keep the fan inside the compositor budget instead of repaints.
        stiffness: isLowEndDevice() ? 250 : 400,
        damping: isLowEndDevice() ? 24 : 28,
      }}
      draggable={true}
      onDragStart={(e) => onDragStart(e as unknown as React.DragEvent, card.id)}
      onDragOver={(e) => onDragOver(e as unknown as React.DragEvent, card.id)}
      onDragLeave={() => onDragLeave(card.id)}
      onDrop={(e) => onDrop(e as unknown as React.DragEvent, card.id)}
      onClick={() => onCardClick(card)}
    >
      <CardFace
        rank={card.rank}
        suit={card.suit}
        src={getCardImagePath(card.rank, card.suit)}
        alt={`${card.rank} of ${card.suit}`}
      />
      {isSelected && <div className="selected-gold-trim" />}
      {isDragTarget && <div className="reorder-insert-glow" />}
    </motion.div>
  );
}, areHandCardPropsEqual);

function areHandCardPropsEqual(prev: HandCardProps, next: HandCardProps): boolean {
  return (
    prev.card.id === next.card.id &&
    prev.card.rank === next.card.rank &&
    prev.card.suit === next.card.suit &&
    prev.index === next.index &&
    prev.totalCards === next.totalCards &&
    prev.isSelected === next.isSelected &&
    prev.isDraggingThis === next.isDraggingThis &&
    prev.isDragTarget === next.isDragTarget &&
    prev.isDealingAnimation === next.isDealingAnimation &&
    prev.onCardClick === next.onCardClick &&
    prev.onDragStart === next.onDragStart &&
    prev.onDragOver === next.onDragOver &&
    prev.onDragLeave === next.onDragLeave &&
    prev.onDrop === next.onDrop &&
    prev.registerEl === next.registerEl
  );
}

interface DiscardFanCardProps {
  card: Card;
  index: number;
  totalCards: number;
  isDrawable: boolean;
  isSequenceMiddleLocked: boolean;
  onDraw: (card: Card) => void;
}

/** One card in the discard fan. Memoized: pile re-renders only touch changed cards. */
const DiscardFanCard = React.memo(function DiscardFanCard({
  card,
  index,
  totalCards,
  isDrawable,
  isSequenceMiddleLocked,
  onDraw,
}: DiscardFanCardProps) {
  const centerOffset = index - (totalCards - 1) / 2;
  const flatFan = totalCards >= 4;
  const rotationDeg = centerOffset * (flatFan ? 1 : 3);
  const translateY = flatFan ? 0 : Math.abs(centerOffset) * 2;
  const zIndex = index + 1;

  return (
    <motion.div
      className={`discard-fan-card ${isDrawable ? 'drawable-eligible' : 'locked-ineligible'}`}
      style={{
        zIndex,
        transformOrigin: 'bottom center',
        willChange: 'transform, opacity',
      }}
      // Settle-in on mount: freshly arrived discards drop a few px and fade
      // up into their slot instead of popping in underneath the landing
      // flights. Memoization means only newly mounted cards run this —
      // survivors of a pile update stay put. (Rotate is omitted from initial
      // so the fan angle is already correct on the first frame.)
      initial={{ opacity: 0.4, scale: 0.9, y: translateY - 8 }}
      animate={{
        opacity: 1,
        scale: 1,
        rotate: rotationDeg,
        y: translateY,
      }}
      transition={{
        type: 'spring',
        stiffness: isLowEndDevice() ? 300 : 500,
        damping: isLowEndDevice() ? 26 : 30,
      }}
      // Hover handled by CSS :hover (transform-only, no framer-motion repaint)
      onClick={() => onDraw(card)}
    >
      <CardFace
        rank={card.rank}
        suit={card.suit}
        src={getCardImagePath(card.rank, card.suit)}
        alt={`${card.rank} of ${card.suit}`}
      />
      {isSequenceMiddleLocked && totalCards < 4 && (
        <div className="locked-indicator" title="Middle sequence cards cannot be drawn">
          🔒
        </div>
      )}
    </motion.div>
  );
}, areDiscardFanCardPropsEqual);

function areDiscardFanCardPropsEqual(prev: DiscardFanCardProps, next: DiscardFanCardProps): boolean {
  return (
    prev.card.id === next.card.id &&
    prev.card.rank === next.card.rank &&
    prev.card.suit === next.card.suit &&
    prev.index === next.index &&
    prev.totalCards === next.totalCards &&
    prev.isDrawable === next.isDrawable &&
    prev.isSequenceMiddleLocked === next.isSequenceMiddleLocked &&
    prev.onDraw === next.onDraw
  );
}

/**
 * Yaniv contest reveal window. Mounted only while a contest is open and owns
 * its countdown, so the per-second tick re-renders just this overlay — the
 * table, piles and seats underneath stay put for the whole 5s.
 */
const YanivContestOverlay = React.memo(function YanivContestOverlay({
  callerName,
  calledAt,
  contestTimerSeconds,
  onCountdownEnd,
}: {
  callerName: string;
  calledAt: number;
  contestTimerSeconds: number;
  onCountdownEnd: () => void;
}) {
  const [remaining, setRemaining] = useState(contestTimerSeconds);

  useEffect(() => {
    const endTime = calledAt + contestTimerSeconds * 1000;
    const updateTimer = () => {
      const next = Math.max(0, Math.ceil((endTime - Date.now()) / 1000));
      setRemaining((prev) => (prev === next ? prev : next));
      if (next <= 0) onCountdownEnd();
    };
    updateTimer();
    const interval = setInterval(updateTimer, 250);
    return () => clearInterval(interval);
  }, [calledAt, contestTimerSeconds, onCountdownEnd]);

  return (
    <motion.div
      className="yaniv-contest-overlay"
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 1.1 }}
      transition={{
        type: 'spring',
        damping: isLowEndDevice() ? 18 : 20,
        stiffness: isLowEndDevice() ? 140 : 200,
      }}
    >
      <div className="contest-overlay-bg" />
      <div className="contest-overlay-content">
        <div className="contest-pulse-ring" />
        <div className="contest-pulse-ring" style={{ animationDelay: '0.5s' }} />
        <div className="contest-pulse-ring" style={{ animationDelay: '1s' }} />

        <div className="contest-header">
          <h1 className="contest-title">YANIV!</h1>
        </div>

        <div className="contest-caller-info">
          <span className="contest-caller-label">Called by</span>
          <span className="contest-caller-name">{callerName}</span>
        </div>

        <div className="contest-timer">
          <span className={`contest-timer-value ${remaining <= 2 ? 'urgent' : ''}`}>{remaining}s</span>
          <span className="contest-timer-label">until reveal</span>
        </div>

        <div className="contest-progress-bar">
          <div
            className="contest-progress-fill"
            style={{
              width: `${(remaining / contestTimerSeconds) * 100}%`,
            }}
          />
        </div>

        {/* One shared popup for every seat — caller included. There is
            no contest action anymore; the window simply auto-reveals,
            so nothing here is gated on who called. */}
        <div className="contest-waiting-message">
          Revealing result…
        </div>
      </div>
    </motion.div>
  );
}, (prev, next) =>
  prev.callerName === next.callerName &&
  prev.calledAt === next.calledAt &&
  prev.contestTimerSeconds === next.contestTimerSeconds &&
  prev.onCountdownEnd === next.onCountdownEnd
);

function TableCanvas({
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
  yanivContestTimerSeconds = 5,
  allPlayerHands = {},
  isRoundOver = false,
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
  // Emotes
  onSendReaction,
}: TableCanvasProps, ref: React.Ref<TableCanvasHandle>) {
  const [selectedCards, setSelectedCards] = useState<string[]>([]);
  const [statusFeedback, setStatusFeedback] = useState<string | null>(null);
  const [isFeedbackError, setIsFeedbackError] = useState<boolean>(false);
  const [localSortedHand, setLocalSortedHand] = useState<Card[]>(hand);
  const [draggedCardId, setDraggedCardId] = useState<string | null>(null);
  const [dragOverCardId, setDragOverCardId] = useState<string | null>(null);
  const [isDealingAnimation, setIsDealingAnimation] = useState(false);
  const [showAsafBanner, setShowAsafBanner] = useState(false);
  const [, setLastTapTime] = useState<Record<string, number>>({});
  const [turnTimerSeconds, setTurnTimerSeconds] = useState<number>(30);
  const [hasPlayedYanivReadyChime, setHasPlayedYanivReadyChime] = useState(false);
  const [showYanivContestOverlay, setShowYanivContestOverlay] = useState(false);

  // ---- Card flight animations (discard out, draw in) ----
  // Flights are a cosmetic overlay measured in viewport coordinates; the real
  // hand/pile arrays only change when a flight lands (outgoing discards hold
  // the server send until landing; incoming draws withhold the new card from
  // the rendered hand until landing). Nothing here reflows the table.
  const [outgoingFlights, setOutgoingFlights] = useState<CardFlightSpec[]>([]);
  const [incomingFlight, setIncomingFlight] = useState<CardFlightSpec | null>(null);
  // Drawn cards withheld from the rendered hand until their flight lands.
  // A ref (not state): only the completion handler reads it, and nothing
  // renders from it directly — localSortedHand is the rendered state.
  const withheldRef = useRef<Card[]>([]);
  const pendingActionRef = useRef<{
    cardIds: string[];
    drawSource: 'DECK' | 'DISCARD_PILE';
    drawnCardId?: string;
    discardedCards: Card[];
  } | null>(null);
  // Remembered when our own discard is sent so the next server push can be
  // recognised as its result — and flown in from the right pile.
  const pendingDrawRef = useRef<{
    drawSource: 'DECK' | 'DISCARD_PILE';
    drawnCardId?: string;
  } | null>(null);
  const prevHandRef = useRef<Card[]>(hand);
  const drawPileRef = useRef<HTMLDivElement | null>(null);
  const discardPileRef = useRef<HTMLDivElement | null>(null);
  const handRowRef = useRef<HTMLDivElement | null>(null);
  const handCardEls = useRef(new Map<string, HTMLDivElement>());
  // Root for seat lookups. Seats are found imperatively at flight time via
  // their data-user-id attribute — always fresh, with no ref lifecycle to
  // go stale between the commit and the passive effects that measure them.
  const rootRef = useRef<HTMLDivElement | null>(null);
  // Flights driven by other players' actions, recognised from server-push
  // diffs (the push carries no actor/draw-source fields). Discard flights
  // gate the pile render via pileOverride; draw flights gate nothing (seat
  // counts are aggregates, not card identities).
  const [opponentDiscardFlights, setOpponentDiscardFlights] = useState<CardFlightSpec[]>([]);
  const [opponentDrawFlights, setOpponentDrawFlights] = useState<CardFlightSpec[]>([]);
  // While set, the pile renders this instead of the live props — the new
  // discards only appear when their flights land.
  const [pileOverride, setPileOverride] = useState<Card[] | null>(null);
  // Diff baselines for the push-driven path. Null until the first push.
  const prevPileRef = useRef<{ top: Card[]; drawable: Card[] } | null>(null);
  const prevTurnRef = useRef<string | null>(null);
  const prevDeckRef = useRef<number | null>(null);
  const prevRoundRef = useRef<number | null>(null);
  const isFlightAnimating =
    outgoingFlights.length > 0 ||
    incomingFlight !== null ||
    opponentDiscardFlights.length > 0 ||
    opponentDrawFlights.length > 0;
  // Mirror refs so stable (empty-deps) callbacks can read the latest values
  // without re-creating identity every render — which would defeat the
  // React.memo comparators on HandCard/DiscardFanCard/SingleFlight.
  const isFlightAnimatingRef = useRef(isFlightAnimating);
  isFlightAnimatingRef.current = isFlightAnimating;
  const localSortedHandRef = useRef(localSortedHand);
  localSortedHandRef.current = localSortedHand;
  // Prop mirrors for stable empty-deps callbacks (draw handlers, flight
  // completion). Ref writes during render are safe here: they are only read
  // inside event/effect callbacks, never during a concurrent render pass.
  const isPlayerTurnRef = useRef(isPlayerTurn);
  isPlayerTurnRef.current = isPlayerTurn;

  // One shape for all three. Every emote is words, so every emote reads as a
  // banner in the left corner below the opponent cards naming only its
  // sender — no target arrow, just emoji + sender + words.
  const [emoteBanners, setEmoteBanners] = useState<
    Array<{ id: string; type: string; from: string; text: string }>
  >([]);
  const reactionTimersRef = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());

  // Emotes are sent, not drawn. The animation runs when the server echoes the emote back
  // on the room topic, so the sender sees exactly what everyone else sees and a dropped
  // frame shows nothing rather than showing it to one player alone. The light tick
  // is the tap's only local feedback — the button itself has no background state.
  const sendReaction = useCallback(
    (type: 'LOVE' | 'RAGE' | 'TAUNT' | 'MOCK' | 'SHOCK' | 'FLEX', targetUserId: string) => {
      if (!targetUserId) return;
      hapticLightTick();
      onSendReaction?.(type, targetUserId);
    },
    [onSendReaction]
  );

  // Latest onDiscard without retriggering flight callbacks.
  const onDiscardRef = useRef(onDiscard);
  useEffect(() => {
    onDiscardRef.current = onDiscard;
  }, [onDiscard]);

  // Outgoing discard flights gate the server send: the real piles/hands only
  // change after the cards visually land on the discard pile.
  const handleOutgoingFlightComplete = useCallback((key: string) => {
    setOutgoingFlights((prev) => {
      const next = prev.filter((f) => f.key !== key);
      if (next.length === 0) {
        const pending = pendingActionRef.current;
        pendingActionRef.current = null;
        if (pending) {
          pendingDrawRef.current = { drawSource: pending.drawSource, drawnCardId: pending.drawnCardId };
          onDiscardRef.current(pending.cardIds, pending.drawSource, pending.drawnCardId);
          setSelectedCards([]);
          setStatusFeedback(null);
          setIsFeedbackError(false);
        }
      }
      return next;
    });
  }, []);

  // Incoming draw flights gate the render: the new card is withheld from the
  // sorted hand until the flight lands, so it never pops in early or doubles
  // up with the flying copy.
  const handleIncomingFlightComplete = useCallback((key: string) => {
    setIncomingFlight((prev) => {
      if (!prev || prev.key !== key) return prev;
      const withheld = withheldRef.current;
      withheldRef.current = [];
      if (withheld.length > 0) {
        setLocalSortedHand((sorted) => {
          const sortedIds = new Set(sorted.map((c) => c.id));
          const fresh = withheld.filter((c) => !sortedIds.has(c.id));
          return fresh.length > 0 ? [...sorted, ...fresh] : sorted;
        });
      }
      return null;
    });
  }, []);

  // ---- Batched geometry: single read pass, cached across flights ----
  // getBoundingClientRect forces a sync layout. Flights used to call it once
  // per card interleaved with React state writes (forced reflow per card).
  // Instead: all rects for one flight batch are read back-to-back with no
  // writes between them, and the card size (identical for every endpoint) is
  // cached for CARD_SIZE_CACHE_TTL_MS and invalidated by ResizeObserver.
  const cardSizeCacheRef = useRef<{ size: { width: number; height: number }; at: number } | null>(null);
  const CARD_SIZE_CACHE_TTL_MS = 500;

  // The one true card size for flights: a rendered hand card's layout box
  // (offsetWidth/Height — transform-free, so fan rotation and the selected
  // lift never leak in), exact at every breakpoint. Falls back to the
  // desktop card size when no hand card is rendered (or under test).
  // Cached: every endpoint in a batch shares one size, so N-card discards
  // measure once, not N times.
  const measuredCardSize = (): { width: number; height: number } => {
    const cached = cardSizeCacheRef.current;
    if (cached && Date.now() - cached.at < CARD_SIZE_CACHE_TTL_MS) return cached.size;
    for (const el of handCardEls.current.values()) {
      if (el.offsetWidth > 0 && el.offsetHeight > 0) {
        const size = { width: el.offsetWidth, height: el.offsetHeight };
        cardSizeCacheRef.current = { size, at: Date.now() };
        return size;
      }
    }
    return cached?.size ?? FALLBACK_CARD_SIZE;
  };

  // Layout breakpoint changes (fan overlap, card clamp()) resize every card:
  // drop the cached size so the next batch re-measures once. Observes the
  // table root (covers hand/pile/seat resizes via bubbled layout) rather than
  // each card — one observer, no per-card lifecycle. Throttled to one
  // invalidate per frame: a window drag fires ResizeObserver continuously and
  // each invalidate forces a full re-measure on the next flight batch.
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return;
    let throttle: ReturnType<typeof setTimeout> | null = null;
    const invalidate = () => {
      if (throttle !== null) return;
      throttle = setTimeout(() => {
        throttle = null;
        cardSizeCacheRef.current = null;
      }, 16);
    };
    const ro = new ResizeObserver(invalidate);
    if (rootRef.current) ro.observe(rootRef.current);
    if (handRowRef.current) ro.observe(handRowRef.current);
    return () => {
      if (throttle !== null) clearTimeout(throttle);
      ro.disconnect();
    };
  }, []);

  // Single-query seat lookup. The old querySelectorAll + forEach visited every
  // seat's rect; an attribute selector resolves only the actor's seat, so one
  // flight batch reads exactly the rects it animates (actor + pile/deck).
  const seatEl = (userId: string): HTMLElement | null => {
    const root = rootRef.current;
    if (!root) return null;
    try {
      return root.querySelector(`[data-user-id="${CSS.escape(userId)}"]`);
    } catch {
      // CSS.escape missing (very old browsers) or bad id: linear fallback.
      const seats = root.querySelectorAll('[data-user-id]');
      for (let i = 0; i < seats.length; i++) {
        if (seats[i].getAttribute('data-user-id') === userId) return seats[i] as HTMLElement;
      }
      return null;
    }
  };

  // Opponent flights share one completion path: draws just clear, and the
  // pile snaps to the live props once the last discard flight lands.
  const handleOpponentFlightComplete = useCallback((key: string) => {
    setOpponentDrawFlights((prev) => prev.filter((f) => f.key !== key));
    setOpponentDiscardFlights((prev) => {
      const next = prev.filter((f) => f.key !== key);
      if (next.length === 0) setPileOverride(null);
      return next;
    });
  }, []);

  // Opponent actions, recognised from server-push diffs. The push carries no
  // actor or draw-source fields, so this reconstructs the turn:
  // - actor = whoever held the turn before this push;
  // - fresh top-combination cards = their discards → seat-to-pile, face-up,
  //   with the pile held on its previous render until they land;
  // - deckCount -1 = drew from the deck → card-back deck-to-seat;
  // - deckCount unchanged + exactly one previously drawable card gone = picked
  //   that exact card from the pile → pile-to-seat, face-up (revealed), the
  //   way every player at a real table watches it happen.
  // Coverage caveat: when the new combo buries a multi-drawable top (a set,
  // or sequence ends) the push cannot say which of the vanished drawables
  // was picked up and which were merely covered — so those turns fly the
  // discards but no draw flight. A missing flight beats a lying face.
  // Our own pushes are owned by the actor path above (pending refs set, or
  // actor is us), so they never double-animate here.
  useEffect(() => {
    const curTop = topDiscardCards ?? [];
    const curDrawable = drawableDiscardCards ?? [];
    const prev = prevPileRef.current;
    const prevTurn = prevTurnRef.current;
    const prevDeck = prevDeckRef.current;
    const prevRound = prevRoundRef.current;
    prevPileRef.current = { top: curTop, drawable: curDrawable };
    prevTurnRef.current = currentTurnPlayerId;
    prevDeckRef.current = deckCount;
    prevRoundRef.current = roundNumber;

    // First push, new deal, or unmeasurable round boundary: sync silently.
    if (!prev || prevRound === null || prevRound !== roundNumber) return;
    // Our own action in flight, or our own result: the actor path owns it.
    if (pendingActionRef.current || pendingDrawRef.current) return;
    if (!prevTurn || prevTurn === currentUserId) return;

    const prevTopIds = new Set(prev.top.map((c) => c.id));
    const prevDrawableIds = new Set(prev.drawable.map((c) => c.id));
    const curTopIds = new Set(curTop.map((c) => c.id));
    const curDrawableIds = new Set(curDrawable.map((c) => c.id));
    const topChanged =
      curTop.length !== prev.top.length || curTop.some((c) => !prevTopIds.has(c.id));
    // Fresh discards: the new top combination. Ids are unique per deal, so a
    // changed top is wholly the actor's discard set.
    const discarded = topChanged ? curTop : [];
    // Picked-up card: drawable before, visible nowhere now. Locked middles
    // are excluded by construction — they were never drawable, so a covered
    // (not picked) combination yields nothing here.
    const picked =
      prevDeck !== null && deckCount === prevDeck
        ? prev.drawable.filter((c) => !curTopIds.has(c.id) && !curDrawableIds.has(c.id))
        : [];
    const deckDrawn = prevDeck !== null && deckCount === prevDeck - 1;

    // Sanity gates: a single turn discards at most a hand (5). Anything else
    // is a recycle, a reveal, or a state we do not understand — sync silently
    // rather than invent motion. Note the draw flight is NOT gated on pickup
    // ambiguity: when the new combo buries a multi-drawable top, several
    // vanished drawables are candidates but exactly one was picked up. The
    // discards are unambiguous and always fly; the draw flight runs only
    // when its source is certain (deck, or a single candidate).
    if (discarded.length > 5) return;
    if (discarded.length === 0 && picked.length === 0 && !deckDrawn) return;

    const size = measuredCardSize();
    // Seat anchor, looked up live via a single attribute query — always
    // fresh, with no ref lifecycle to go stale between the commit and the
    // passive effects that measure them. All rects below (actor, pile, deck)
    // are read back-to-back with no state writes between them: one layout
    // pass, not one per endpoint.
    const seatBox = (userId: string) => cardFlightBox(seatEl(userId), size);
    const actorBox = seatBox(prevTurn);
    if (!actorBox) return;

    const now = Date.now();
    const drawSource =
      deckDrawn ? 'DECK' : picked.length === 1 ? ('DISCARD_PILE' as const) : null;

    if (discarded.length > 0) {
      const pileBox = cardFlightBox(discardPileRef.current, size);
      if (!pileBox) return;
      // Hold the pile on its previous render; the discards appear as they land.
      const prevDisplay = prev.top.length > 0 ? prev.top : prev.drawable;
      setPileOverride(prevDisplay);
      // One fan slot per card: the set lands already fanned, each flight
      // docking where its pile card renders (see fanLandingSlots).
      const slots = fanLandingSlots(pileBox, size, discarded.length);
      setOpponentDiscardFlights(
        discarded.map((card, i) => ({
          key: `opp-discard-${card.id}-${now}`,
          from: actorBox,
          to: slots[i],
          faceUp: true,
          faceImageSrc: getCardImagePath(card.rank, card.suit),
          faceAlt: `${card.rank} of ${card.suit}`,
          faceRank: card.rank,
          faceSuit: card.suit,
          rotation: slots[i].rotation,
          delay: i * 0.06,
        }))
      );
    }

    if (drawSource === 'DECK') {
      const deckBox = cardFlightBox(drawPileRef.current, size);
      if (!deckBox) return;
      setOpponentDrawFlights([
        {
          key: `opp-draw-${prevTurn}-${now}`,
          from: deckBox,
          to: actorBox,
          faceUp: false,
        },
      ]);
    } else if (drawSource === 'DISCARD_PILE') {
      const pileBox = cardFlightBox(discardPileRef.current, size);
      if (!pileBox) return;
      const card = picked[0];
      setOpponentDrawFlights([
        {
          key: `opp-pickup-${card.id}-${now}`,
          from: pileBox,
          to: actorBox,
          faceUp: true,
          faceImageSrc: getCardImagePath(card.rank, card.suit),
          faceAlt: `${card.rank} of ${card.suit}`,
          faceRank: card.rank,
          faceSuit: card.suit,
        },
      ]);
    }

    if (discarded.length > 0 || drawSource) soundEngine.playDealerFlick();
  }, [topDiscardCards, drawableDiscardCards, deckCount, currentTurnPlayerId, opponents, roundNumber, currentUserId]);

  // Preserve user custom card reordering when hand state updates from server.
  // When the push is the result of our own discard-and-draw, the drawn card
  // is withheld and flown in from the pile it came from instead of popping
  // into the hand: deck draws fly out card-back-down and turn over mid-travel
  // (revealFace), discard pickups fly face-up (revealed).
  useEffect(() => {
    const prev = prevHandRef.current;
    prevHandRef.current = hand;
    const prevIds = new Set(prev.map((c) => c.id));
    const added = hand.filter((c) => !prevIds.has(c.id));
    const pendingDraw = pendingDrawRef.current;

    if (pendingDraw && added.length > 0) {
      pendingDrawRef.current = null;
      const drawn = pendingDraw.drawnCardId
        ? added.find((c) => c.id === pendingDraw.drawnCardId) ?? added[0]
        : added[0];
      const rest = hand.filter((c) => c.id !== drawn.id);
      withheldRef.current = [drawn];
      setLocalSortedHand((sorted) => {
        const restIds = new Set(rest.map((c) => c.id));
        const retained = sorted.filter((c) => restIds.has(c.id));
        const retainedIds = new Set(retained.map((c) => c.id));
        const newlyDrawn = rest.filter((c) => !retainedIds.has(c.id));
        return [...retained, ...newlyDrawn];
      });
      const fromEl =
        pendingDraw.drawSource === 'DECK' ? drawPileRef.current : discardPileRef.current;
      // Batched read pass: size (cached) + both endpoint rects back-to-back
      // with no DOM writes between them. setLocalSortedHand above only
      // schedules a React render — no synchronous layout — so this stays one
      // forced reflow, not one per endpoint.
      const size = measuredCardSize();
      const from = cardFlightBox(fromEl, size);
      const handRect = handRowRef.current?.getBoundingClientRect() ?? null;
      const to: FlightPoint =
        handRect && handRect.width > 0
          ? {
              x: handRect.left + handRect.width / 2 - size.width / 2,
              y: handRect.bottom - size.height - 6,
              width: size.width,
              height: size.height,
            }
          : {
              x: window.innerWidth / 2 - size.width / 2,
              y: window.innerHeight - size.height - 36,
              width: size.width,
              height: size.height,
            };
      if (from) {
        const faceUp = pendingDraw.drawSource === 'DISCARD_PILE';
        setIncomingFlight({
          key: `draw-${drawn.id}-${Date.now()}`,
          from,
          to,
          faceUp,
          // Deck draws carry the face along so the flight can turn over
          // mid-travel (revealFace) instead of snapping from back to face
          // when the withheld card joins the hand on landing.
          faceImageSrc: getCardImagePath(drawn.rank, drawn.suit),
          faceAlt: `${drawn.rank} of ${drawn.suit}`,
          faceRank: drawn.rank,
          faceSuit: drawn.suit,
          revealFace: !faceUp,
        });
        soundEngine.playDealerFlick();
      } else {
        // No measurable anchor (hidden tab, unmounted pile): render at once.
        withheldRef.current = [];
        setLocalSortedHand((sorted) => {
          const restIds = new Set(rest.map((c) => c.id));
          const retained = sorted.filter((c) => restIds.has(c.id));
          return [...retained, drawn];
        });
      }
      return;
    }

    pendingDrawRef.current = null;
    setLocalSortedHand((prevSorted) => {
      const incomingIds = new Set(hand.map((c) => c.id));
      const retained = prevSorted.filter((c) => incomingIds.has(c.id));
      const retainedIds = new Set(retained.map((c) => c.id));
      // Cards withheld for an in-progress draw flight join only when it lands.
      const heldIds = new Set(withheldRef.current.map((c) => c.id));
      const newlyDrawn = hand.filter((c) => !retainedIds.has(c.id) && !heldIds.has(c.id));
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

  // All transient sound/banner timers live here so rapid rounds or an
  // unmount clears every pending timeout — the old dealing loop fired N
  // untracked setTimeout sounds that leaked across rounds.
  const transientTimersRef = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());
  useEffect(() => {
    const timers = transientTimersRef.current;
    return () => {
      timers.forEach(clearTimeout);
      timers.clear();
    };
  }, []);

  useEffect(() => {
    if (hand.length > 0) {
      setIsDealingAnimation(true);
      const dealCount = Math.min(hand.length, 5);
      for (let i = 0; i < dealCount; i++) {
        const t = setTimeout(() => {
          transientTimersRef.current.delete(t);
          soundEngine.playDealerFlick();
        }, i * 140);
        transientTimersRef.current.add(t);
      }
      const timer = setTimeout(() => {
        transientTimersRef.current.delete(timer);
        setIsDealingAnimation(false);
      }, dealCount * 140 + 300);
      transientTimersRef.current.add(timer);
      return () => {
        transientTimersRef.current.delete(timer);
        clearTimeout(timer);
      };
    }
  }, [roundNumber]);

  
  // Waiting-for-Asaf: plays while every player watches the 5s Yaniv reveal
  // countdown. Stops when the countdown ends and all tables move to the result
  // screen — the server keeps sending yanivCallerId on ROUND_OVER (it names the
  // caller there), so the stop signal is the explicit isRoundOver push, not the
  // caller clearing. Declared before the asaf effect so the countdown track
  // stops before the asaf track starts on the same reveal commit.
  useEffect(() => {
    if (yanivCallerId && !isRoundOver) {
      playWaitingForAsafSound();
    } else {
      stopWaitingForAsafSound();
    }
    return () => stopWaitingForAsafSound();
  }, [yanivCallerId, isRoundOver]);

  useEffect(() => {
    if (isAsaf) {
      setShowAsafBanner(true);
      playAsafSound();
      const timer = setTimeout(() => setShowAsafBanner(false), 4500);
      return () => clearTimeout(timer);
    }
  }, [isAsaf]);

  // The banner covers the whole table and eats clicks. Its timer outlives the round it
  // belongs to, so a quick Next Round leaves it sitting over the new deal, blocking the
  // first turn. A new round always clears it. The asaf track is long, so the new round
  // also stops it — every client learns the round changed from the server broadcast,
  // which is what stops playback for all players.
  useEffect(() => {
    setShowAsafBanner(false);
    stopAsafSound();
    stopWaitingForAsafSound();
  }, [roundNumber]);

  // Never let the long tracks leak past this table (exit, game over unmount).
  useEffect(() => {
    return () => {
      stopAsafSound();
      stopWaitingForAsafSound();
    };
  }, []);

  // Turn timer driven by the server's authoritative deadline. The server
  // performs auto-play on expiry - the client only displays and ticks.
  // Guarded: the 250ms poll only commits state when the displayed second
  // actually changes, so the whole tree re-renders ~1/sec, not 4/sec.
  useEffect(() => {
    const total = turnTimerTotalSeconds || 45;
    if (!turnEndsAt) {
      setTurnTimerSeconds((prev) => (prev === total ? prev : total));
      return;
    }
    const updateTimer = () => {
      const next = Math.max(0, Math.ceil((turnEndsAt - Date.now()) / 1000));
      setTurnTimerSeconds((prev) => (prev === next ? prev : next));
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

  // Yaniv Contest window: just whether the overlay is shown. The countdown
  // lives inside <YanivContestOverlay>, so the table underneath does not
  // re-render once per second for the whole 5s reveal.
  useEffect(() => {
    setShowYanivContestOverlay(!!(yanivCallerId && yanivCalledAt && yanivContestTimerSeconds > 0));
  }, [yanivCallerId, yanivCalledAt, yanivContestTimerSeconds]);

  const handleContestCountdownEnd = useCallback(() => {
    setShowYanivContestOverlay(false);
  }, []);

  const currentHandCards = localSortedHand;

  // Central reaction strip: every emote stays fireable while you are still in
  // the game. Aimed at whoever holds the turn when that is someone else still
  // playing; otherwise thrown at the table (self-target, the way a taunt has
  // always gone out), so your own turn never locks the strip to one emoji.
  // Same targeting the per-seat buttons had, one fixed place.
  const turnHolder = useMemo(
    () => opponents.find((o) => o.userId === currentTurnPlayerId) ?? null,
    [opponents, currentTurnPlayerId]
  );
  const canAimAtTurnHolder =
    !!turnHolder && !turnHolder.isCurrentPlayer && !turnHolder.isEliminated;
  const meEliminated = opponents.some((o) => o.isCurrentPlayer && o.isEliminated);
  // Nobody to aim at — your own turn, or the turn holder is gone — means the
  // table, not a disabled strip. The server already accepts a self-target for
  // every type (that is what a TAUNT is), and only the sender is ever drawn.
  const emoteTargetUserId =
    canAimAtTurnHolder && turnHolder ? turnHolder.userId : currentUserId ?? null;
  const canSendEmote = !meEliminated && !!emoteTargetUserId;
  const emoteHint = (text: string) => {
    if (!canSendEmote) return 'Reactions are unavailable while you are out of the game';
    if (canAimAtTurnHolder && turnHolder) return `Tell ${turnHolder.displayName}: ${text}`;
    return `Tell the table: ${text}`;
  };

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

  // What the pile renders: the live props, unless an opponent's discards are
  // still flying in — then the previous render, so the new cards appear as
  // their flights land instead of popping in underneath them.
  const renderDiscardCards = pileOverride ?? discardDisplayCards;
  // Mirrors for stable draw callbacks (declared after the memo above).
  const discardDisplayRef = useRef(discardDisplayCards);
  discardDisplayRef.current = discardDisplayCards;
  const drawableRef = useRef(drawableDiscardCards);
  drawableRef.current = drawableDiscardCards;

  // A new deal invalidates everything in flight: drops, timers and pending
  // sends belong to the previous round's layout. (Push-diff baselines are
  // NOT reset here: the diff effect runs before this one and re-baselines
  // itself when it sees the round change. Resetting them here would wipe a
  // baseline the diff effect just established on mount, blinding it to the
  // next push.)
  useEffect(() => {
    pendingActionRef.current = null;
    pendingDrawRef.current = null;
    withheldRef.current = [];
    setOutgoingFlights([]);
    setIncomingFlight(null);
    setOpponentDiscardFlights([]);
    setOpponentDrawFlights([]);
    setPileOverride(null);
    handCardEls.current.clear();
  }, [roundNumber]);

  // Card click: Single-tap toggle or double-tap multi-select. Stable identity
  // so memoized HandCards don't re-render when unrelated state ticks.
  const handleCardClick = useCallback((card: Card) => {
    if (isFlightAnimatingRef.current) return;
    const now = Date.now();
    setLastTapTime((prevTimes) => {
      const lastTap = prevTimes[card.id] || 0;
      const isDoubleTap = now - lastTap < 300;
      if (isDoubleTap) {
        const matchingRankCards = localSortedHandRef.current.filter((c) => c.rank === card.rank);
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
      return { ...prevTimes, [card.id]: now };
    });
  }, []);

  // Drag-and-drop handler for hand reordering only (no staging). Stable
  // identities for the same memo reason; dragOver target is compared via ref
  // to avoid re-creating the callback every hover change.
  const dragOverRef = useRef<string | null>(null);
  const draggedCardIdRef = useRef<string | null>(null);
  const handleHandCardDragStart = useCallback((e: React.DragEvent, cardId: string) => {
    draggedCardIdRef.current = cardId;
    setDraggedCardId(cardId);
    e.dataTransfer.setData('handReorderId', cardId);
    e.dataTransfer.effectAllowed = 'move';
    soundEngine.playFeltSlide(0.4);
  }, []);

  const handleHandCardDragOver = useCallback((e: React.DragEvent, targetCardId: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragOverRef.current !== targetCardId) {
      dragOverRef.current = targetCardId;
      setDragOverCardId(targetCardId);
    }
  }, []);

  const handleHandCardDragLeave = useCallback((targetCardId: string) => {
    if (dragOverRef.current === targetCardId) {
      dragOverRef.current = null;
      setDragOverCardId(null);
    }
  }, []);

  const handleHandCardDrop = useCallback((e: React.DragEvent, targetCardId: string) => {
    e.preventDefault();
    e.stopPropagation();
    const sourceId = e.dataTransfer.getData('handReorderId') || draggedCardIdRef.current;

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

    draggedCardIdRef.current = null;
    dragOverRef.current = null;
    setDraggedCardId(null);
    setDragOverCardId(null);
  }, []);

  const registerHandCardEl = useCallback((cardId: string, el: HTMLDivElement | null) => {
    if (el) handCardEls.current.set(cardId, el);
    else handCardEls.current.delete(cardId);
  }, []);

  const validateAndDiscard = useCallback((
    drawSource: 'DECK' | 'DISCARD_PILE',
    drawnCardId?: string
  ) => {
    if (isFlightAnimating) return;
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
    // Fly the discards from the hand to the discard pile first; the server
    // send (and with it every real state change) waits until they land.
    // Both ends are card-shaped boxes in one measured size, so the flight
    // never stretches or crops the card.
    // Batched read pass: resolve every source element first, then measure
    // all rects back-to-back with no writes between them — one forced
    // reflow for the whole multi-card discard, not one per card.
    const size = measuredCardSize();
    const sources = cardsObjects.map((card) => handCardEls.current.get(card.id) ?? handRowRef.current);
    const pileBox = cardFlightBox(discardPileRef.current, size);
    // One fan slot per card (not one shared center box): a set lands already
    // fanned, each flight docking where its pile card is about to render.
    const slots = pileBox ? fanLandingSlots(pileBox, size, cardsObjects.length) : [];
    const now = Date.now();
    const flights: CardFlightSpec[] = [];
    sources.forEach((el, i) => {
      const card = cardsObjects[i];
      const from = cardFlightBox(el, size);
      const slot = slots[i];
      if (from && slot) {
        flights.push({
          key: `discard-${card.id}-${now}`,
          from,
          to: slot,
          faceUp: true,
          faceImageSrc: getCardImagePath(card.rank, card.suit),
          faceAlt: `${card.rank} of ${card.suit}`,
          faceRank: card.rank,
          faceSuit: card.suit,
          rotation: slot.rotation,
          delay: i * 0.06,
        });
      }
    });
    soundEngine.playDealerFlick();
    if (flights.length === 0) {
      // No measurable anchors (hidden tab, tiny viewport): send at once.
      pendingDrawRef.current = { drawSource, drawnCardId };
      onDiscardRef.current(selectedCards, drawSource, drawnCardId);
      setSelectedCards([]);
      setStatusFeedback(null);
      setIsFeedbackError(false);
      return;
    }
    pendingActionRef.current = {
      cardIds: [...selectedCards],
      drawSource,
      drawnCardId,
      discardedCards: cardsObjects,
    };
    setOutgoingFlights(flights);
  }, [selectedCards, localSortedHand, isFlightAnimating]);

  // Latest validateAndDiscard without re-creating downstream callbacks:
  // DiscardFanCard's onDraw prop stays stable across selection/timer ticks.
  const validateAndDiscardRef = useRef(validateAndDiscard);
  validateAndDiscardRef.current = validateAndDiscard;

  const handleDrawFromDeck = useCallback(() => {
    if (!isPlayerTurnRef.current) return;
    validateAndDiscardRef.current('DECK');
  }, []);

  const handleDrawFromDiscard = useCallback((targetCard: Card) => {
    if (!isPlayerTurnRef.current) return;

    const display = discardDisplayRef.current;
    if (display.length === 0) {
      setStatusFeedback('Discard pile is empty');
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }

    const isDrawable = drawableRef.current.some((dc) => dc.id === targetCard.id);
    if (!isDrawable) {
      setStatusFeedback(
        `Locked: Middle card (${targetCard.rank} of ${targetCard.suit}) cannot be drawn. Only outer ends of a sequence are eligible.`
      );
      setIsFeedbackError(true);
      soundEngine.playInvalidRejection();
      hapticDoubleError();
      return;
    }

    validateAndDiscardRef.current('DISCARD_PILE', targetCard.id);
  }, []);

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

  // Show one emote the room has broadcast. Called by GameView as each one arrives, so an
  // emote is drawn exactly once, when it happens, and is never held anywhere.
  // Only the sender is named: the target stays on the wire (the strip still aims
  // at the turn holder) but is never drawn, so no "Ari -> Bob" arrow.
  const playReaction = useCallback(
    (event: ReactionEvent) => {
      if (!event?.id) return;

      setEmoteBanners((prev) => [
        ...prev,
        {
          id: event.id,
          type: event.type,
          from: event.fromDisplayName || 'Someone',
          text: event.text || EMOTE_FALLBACK_TEXT[event.type] || '',
        },
      ]);

      // Each emote clears itself once its own animation has finished; a single shared
      // timer would leave everything that arrived later on screen forever.
      const timer = setTimeout(() => {
        reactionTimersRef.current.delete(timer);
        setEmoteBanners((prev) => prev.filter((b) => b.id !== event.id));
      }, EMOTE_BANNER_LIFETIME_MS);
      reactionTimersRef.current.add(timer);
    },
    []
  );

  useImperativeHandle(ref, () => ({ playReaction }), [playReaction]);

  // Drop any emote timers still pending when the table unmounts.
  useEffect(() => {
    const timers = reactionTimersRef.current;
    return () => {
      timers.forEach(clearTimeout);
      timers.clear();
    };
  }, []);

  const selectedCardIndex = useMemo(() => {
    if (selectedCards.length === 1) {
      return localSortedHand.findIndex((c) => c.id === selectedCards[0]);
    }
    return -1;
  }, [selectedCards, localSortedHand]);

  // Single stable callback for the flight layer. Reads the incoming key via
  // ref so its identity never changes on timer/selection ticks — memoized
  // SingleFlights skip re-render and framer-motion never restarts mid-flight.
  // Completion handlers themselves are idempotent (repeat keys are no-ops).
  const incomingKeyRef = useRef<string | null>(null);
  incomingKeyRef.current = incomingFlight?.key ?? null;
  const handleAnyFlightComplete = useCallback((key: string) => {
    if (incomingKeyRef.current === key) handleIncomingFlightComplete(key);
    else if (key.startsWith('opp-')) handleOpponentFlightComplete(key);
    else handleOutgoingFlightComplete(key);
  }, [handleIncomingFlightComplete, handleOpponentFlightComplete, handleOutgoingFlightComplete]);

  // One array identity for the flight overlay: without this, every parent
  // re-render (timer tick, hover, selection) hands CardFlightLayer a fresh
  // array and re-renders every in-flight card.
  const allFlights = useMemo(
    () => [
      ...outgoingFlights,
      ...(incomingFlight ? [incomingFlight] : []),
      ...opponentDiscardFlights,
      ...opponentDrawFlights,
    ],
    [outgoingFlights, incomingFlight, opponentDiscardFlights, opponentDrawFlights]
  );

  // Note: Keyboard reordering (ArrowLeft/ArrowRight) still works for selected cards

  return (
    <div className="table-canvas-root" ref={rootRef}>
      <div className="felt-surface">
        {/* Yaniv Contest Overlay */}
        <AnimatePresence>
          {showYanivContestOverlay && yanivCallerId && yanivCallerName && (
            <YanivContestOverlay
              callerName={yanivCallerName}
              calledAt={yanivCalledAt ?? 0}
              contestTimerSeconds={yanivContestTimerSeconds || 5}
              onCountdownEnd={handleContestCountdownEnd}
            />
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
          {opponents.map((opponent, idx) => (
            <OpponentSeat
              key={opponent.userId || idx}
              opponent={opponent}
              index={idx}
              turnTimerSeconds={turnTimerSeconds}
              turnTimerTotalSeconds={turnTimerTotalSeconds || 45}
              autoPlayed={autoPlayedPlayerId != null && autoPlayedPlayerId === opponent.userId}
            />
          ))}
        </div>

        {/* 2. Center Play Area - Natural Piles on Felt */}
        <div className="center-play-area">
          {/* Emote log: left-corner banners below the opponent cards, confined
              to this area and painted behind the piles. Absolute +
              click-through, so arrivals/expiries never move the piles
              underneath. */}
          <div className="emote-log-strip" role="status" aria-live="polite">
            {emoteBanners.map((banner) => (
              <div key={banner.id} className="emote-log-row" data-type={banner.type.toLowerCase()}>
                <span className="emote-emoji" aria-hidden="true">
                  {EMOTE_ICON[banner.type] || '💬'}
                </span>
                <span className="emote-from">{banner.from}</span>
                <span className="emote-msg">{banner.text}</span>
              </div>
            ))}
          </div>
          {/* Draw Pile (Left) */}
          <div className="pile-column draw-pile-column" onClick={handleDrawFromDeck}>
            <div ref={drawPileRef} className={`deck-stack-3d ${selectedCards.length > 0 ? 'pulse-prompt' : ''}`}>
              <div className="deck-layer layer-3" />
              <div className="deck-layer layer-2" />
              <div className="deck-layer layer-1">
                <div className="card-back-pattern">
                  <div className="card-back-emblem">♠</div>
                </div>
              </div>
            </div>

            {/* Fixed slot: the badge appearing must not move the cards. */}
            <span className="pile-label">Draw</span>
            <span className="pile-count">{deckCount} cards</span>
            <div className="draw-prompt-slot">
              {isPlayerTurn && (
                <motion.div
                  className="draw-prompt-badge"
                  initial={{ scale: 0.8, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                >
                  Tap to draw deck
                </motion.div>
              )}
            </div>
          </div>

          {/* Discard Pile (Right) - Chinese Hand Fan Layout */}
          <div className="pile-column discard-pile-column">

            <div ref={discardPileRef} className="discard-fan-container">
              {renderDiscardCards.length === 0 ? (
                <div className="discard-empty-box">Empty</div>
              ) : (
                <>
                  {/* Older discards tucked underneath the active fan. */}
                  <div className="discard-depth" aria-hidden="true">
                    <div className="discard-depth-layer depth-2" />
                    <div className="discard-depth-layer depth-1" />
                  </div>
                <div
                  className="discard-cards-fan"
                  data-count={Math.min(renderDiscardCards.length, 5)}
                >
                  {renderDiscardCards.map((card, idx) => {
                    const isDrawable = drawableDiscardCards.some((dc) => dc.id === card.id);
                    const isSequenceMiddleLocked =
                      renderDiscardCards.length >= 3 &&
                      !isDrawable &&
                      idx > 0 &&
                      idx < renderDiscardCards.length - 1;
                    const totalCards = Math.min(renderDiscardCards.length, 5);

                    return (
                      <DiscardFanCard
                        key={card.id || idx}
                        card={card}
                        index={idx}
                        totalCards={totalCards}
                        isDrawable={isDrawable}
                        isSequenceMiddleLocked={isSequenceMiddleLocked}
                        onDraw={handleDrawFromDiscard}
                      />
                    );
                    })}
                </div>
                </>
              )}
            </div>

            {/* Fixed slot: the hint appearing must not move the cards. */}
            <span className="pile-label">Discard</span>
            <span className="pile-count">{renderDiscardCards.length} cards</span>
            <div className="discard-prompt-slot">
              {isPlayerTurn && (
                <div className="discard-prompt-hint">
                  {drawableDiscardCards.length > 1
                    ? 'Tap outer cards (ends only) to draw'
                    : 'Tap top card to draw'}
                </div>
              )}
            </div>
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
                <CardFace
                  rank={pendingBonusCard.rank}
                  suit={pendingBonusCard.suit}
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
          {/* Reactions live here, above the sort/Yaniv strip: aimed at the turn
              holder when there is one, otherwise thrown at the table — never
              a set per seat, and never locked to a single emoji. */}
          <div className="reaction-strip" role="group" aria-label="Table reactions">
            <button
              type="button"
              className="reaction-btn reaction-love"
              onClick={() => emoteTargetUserId && sendReaction('LOVE', emoteTargetUserId)}
              disabled={!canSendEmote}
              title={emoteHint(EMOTE_FALLBACK_TEXT.LOVE)}
              aria-label={emoteHint(EMOTE_FALLBACK_TEXT.LOVE)}
            >
              ❤️
            </button>
            <button
              type="button"
              className="reaction-btn reaction-rage"
              onClick={() => emoteTargetUserId && sendReaction('RAGE', emoteTargetUserId)}
              disabled={!canSendEmote}
              title={emoteHint(EMOTE_FALLBACK_TEXT.RAGE)}
              aria-label={emoteHint(EMOTE_FALLBACK_TEXT.RAGE)}
            >
              😡
            </button>
            <button
              type="button"
              className="reaction-btn reaction-mock"
              onClick={() => emoteTargetUserId && sendReaction('MOCK', emoteTargetUserId)}
              disabled={!canSendEmote}
              title={emoteHint(EMOTE_FALLBACK_TEXT.MOCK)}
              aria-label={emoteHint(EMOTE_FALLBACK_TEXT.MOCK)}
            >
              💥
            </button>
            <button
              type="button"
              className="reaction-btn reaction-shock"
              onClick={() => emoteTargetUserId && sendReaction('SHOCK', emoteTargetUserId)}
              disabled={!canSendEmote}
              title={emoteHint(EMOTE_FALLBACK_TEXT.SHOCK)}
              aria-label={emoteHint(EMOTE_FALLBACK_TEXT.SHOCK)}
            >
              😱
            </button>
            <button
              type="button"
              className="reaction-btn reaction-flex"
              onClick={() => emoteTargetUserId && sendReaction('FLEX', emoteTargetUserId)}
              disabled={!canSendEmote}
              title={emoteHint(EMOTE_FALLBACK_TEXT.FLEX)}
              aria-label={emoteHint(EMOTE_FALLBACK_TEXT.FLEX)}
            >
              😎
            </button>
            <button
              type="button"
              className="reaction-btn reaction-taunt"
              onClick={() => currentUserId && sendReaction('TAUNT', currentUserId)}
              disabled={!canSendEmote}
              title={`Tell the table: ${EMOTE_FALLBACK_TEXT.TAUNT}`}
              aria-label={`Tell the table: ${EMOTE_FALLBACK_TEXT.TAUNT}`}
            >
              💀
            </button>
          </div>
          <div className="player-hud-bar">
            <div className={`hand-total-btn hud-btn ${isYanivEligible && isPlayerTurn ? 'ready-yaniv' : ''}`}>
              <span className="score-label">Hand Total:</span>
              <span className="score-digits">{handScore}</span>
            </div>

            <div className="hand-sort-controls">
              <button className="sort-btn hud-btn interactive" onClick={handleSortByRank} title="Sort hand by rank">
                Sort Ranks
              </button>
              <button className="sort-btn hud-btn interactive" onClick={handleSortBySuit} title="Sort hand by suit">
                Sort Suites
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
            <div ref={handRowRef} className="player-hand-fanned">
                {localSortedHand.map((card, idx) => (
                  <HandCard
                    key={card.id}
                    card={card}
                    index={idx}
                    totalCards={localSortedHand.length}
                    isSelected={selectedCards.includes(card.id)}
                    isDraggingThis={draggedCardId === card.id}
                    isDragTarget={dragOverCardId === card.id && draggedCardId !== card.id}
                    isDealingAnimation={isDealingAnimation}
                    onCardClick={handleCardClick}
                    onDragStart={handleHandCardDragStart}
                    onDragOver={handleHandCardDragOver}
                    onDragLeave={handleHandCardDragLeave}
                    onDrop={handleHandCardDrop}
                    registerEl={registerHandCardEl}
                  />
                ))}
            </div>
          </div>
        </div>
      </div>
      {/* Card flights paint above everything in a fixed overlay: discards fly
          hand → discard pile, deck draws fly out card-back-down and turn over
          mid-travel pile → hand, and
          discard pickups fly face-up (revealed) pile → hand. Opponent turns
          fly the same paths from every seat, driven by server-push diffs. */}
      <CardFlightLayer
        flights={allFlights}
        onFlightComplete={handleAnyFlightComplete}
      />
    </div>
  );
}

// Shallow-memoized: every prop is a store slice / stable memoized object, and
// the store preserves reference identity for untouched slices, so a GameView
// re-render that doesn't change any prop skips re-rendering the whole table.
// (React.memo over forwardRef: the ref is handled outside the props compare.)
export default React.memo(forwardRef(TableCanvas));
