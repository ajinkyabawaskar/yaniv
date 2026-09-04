import { create } from 'zustand';

export interface GameCard {
  id: string;
  rank: string;
  suit: string;
}

export interface PlayerInfo {
  userId: string;
  displayName: string;
  isHost: boolean;
  status: string;
}

/**
 * How close a player still in the game is to ending the round, and to losing the game.
 * Only ever sent to a player who has been knocked out -- the server omits the whole map
 * for anyone still holding cards, so this is undefined for them rather than hidden.
 *
 * yanivProximityPercent is null for a player who can already call Yaniv. Everyone in
 * Yaniv range must look identical, so the server sends no number to tell them apart.
 *
 * It is a percentage, in whole 10% steps, rather than the reachable hand score it comes
 * from: the score named the exact sum a player was about to land on, which is the one
 * thing the round is meant to keep tense. Do not reconstruct a score from it.
 */
export interface SpectatorReading {
  canCallYanivNow: boolean;
  yanivProximityPercent: number | null;
  pointsFromElimination: number;
}

/**
 * An emote another player sent, as it comes off the room topic. Cosmetic and transient:
 * never stored, never replayed, and safe to miss.
 *
 * targetUserId is the seat it animates over -- someone else for LOVE and RAGE, the
 * sender's own seat for a TAUNT thrown at the table. text is written by the server so
 * every screen shows the same words.
 */
export interface ReactionEvent {
  id: string;
  type: 'LOVE' | 'RAGE' | 'TAUNT';
  fromUserId: string;
  fromDisplayName: string;
  targetUserId: string;
  text?: string | null;
}

export interface GameState {
  gameId: string | null;
  roomCode: string | null;
  currentState: string | null;
  currentTurnPlayerId: string | null;
  roundNumber: number;
  scores: Record<string, number>;
  playerNames: Record<string, string>; // userId -> displayName
  players: PlayerInfo[]; // List of players in lobby/game
  eliminatedPlayers: string[];
  deckCount: number;
  topDiscardCard: GameCard | null;
  topDiscardCards: GameCard[];
  playerHand: GameCard[];
  drawableDiscardCards: GameCard[]; // Cards that can be drawn from discard pile
  opponentCounts: Record<string, number>; // userId -> card count
  roundScores: Record<string, number>; // Scores for the completed round
  roundWinner: string | null; // Player who won the round (called Yaniv) - legacy single
  roundWinners: string[] | null; // All players who scored 0 this round (winners)
  isAsaf: boolean; // Whether Asaf occurred
  asafByUserId: string | null; // Who caused Asaf
  isRoundOver: boolean; // Whether round is over and waiting for acknowledgment
  isGameOver: boolean; // Whether game is over (final winner)
  error: string | null;
  maxPlayers: number; // Maximum players allowed in the room
  targetScore: number; // Target score to win the game (default 100)

  // Yaniv Contest Timer fields
  yanivCallerId: string | null;
  yanivCallerName: string | null;
  yanivCalledAt: number | null; // Server epoch ms
  yanivContestTimerSeconds: number; // Total allowed seconds (e.g., 15)
  allPlayerHands: Record<string, GameCard[]>; // Revealed hands on ROUND_OVER

  // Turn timer / auto-play fields
  turnEndsAt: number | null; // Server epoch ms when current turn expires
  turnTimerSeconds: number; // Total allowed seconds per turn
  autoPlayedPlayerId: string | null; // Player whose last move was auto-played

  // Bonus discard fields
  bonusDiscardActive: boolean; // Whether player can do bonus discard
  pendingBonusCard: GameCard | null; // The drawn card matching discarded rank

  // Spectator meters, present only while this player is knocked out and watching
  spectatorReadings: Record<string, SpectatorReading> | null;

  // Disconnected players tracking for reconnection UI

  // Actions
  setGame: (game: Partial<GameState>) => void;
  setError: (error: string | null) => void;
  addCardToHand: (card: GameCard) => void;
  removeCardFromHand: (cardId: string) => void;
  clearGame: () => void;
}

export const useGameStore = create<GameState>((set) => ({
  gameId: null,
  roomCode: null,
  currentState: null,
  currentTurnPlayerId: null,
  roundNumber: 0,
  scores: {},
  playerNames: {},
  players: [],
  eliminatedPlayers: [],
  deckCount: 54,
  topDiscardCard: null,
  topDiscardCards: [],
  playerHand: [],
  drawableDiscardCards: [],
  opponentCounts: {},
  roundScores: {},
  roundWinner: null,
  roundWinners: null,
  isAsaf: false,
  asafByUserId: null,
  isRoundOver: false,
  isGameOver: false,
  error: null,
  maxPlayers: 6,
  targetScore: 100,

  // Yaniv Contest Timer initial values
  yanivCallerId: null,
  yanivCallerName: null,
  yanivCalledAt: null,
  yanivContestTimerSeconds: 15,
  allPlayerHands: {},

  // Turn timer / auto-play initial values
  turnEndsAt: null,
  turnTimerSeconds: 45,
  autoPlayedPlayerId: null,

  // Bonus discard initial values
  bonusDiscardActive: false,
  pendingBonusCard: null,
  spectatorReadings: null,

  // Disconnected players

  setGame: (game) => set((state) => ({ ...state, ...game })),
  setError: (error) => set({ error }),
  addCardToHand: (card) =>
    set((state) => ({
      playerHand: [...state.playerHand, card],
    })),
  removeCardFromHand: (cardId) =>
    set((state) => ({
      playerHand: state.playerHand.filter((c) => c.id !== cardId),
    })),
  clearGame: () =>
    set({
      gameId: null,
      roomCode: null,
      currentState: null,
      currentTurnPlayerId: null,
      roundNumber: 0,
      scores: {},
      playerNames: {},
      players: [],
      eliminatedPlayers: [],
      deckCount: 54,
      topDiscardCard: null,
      topDiscardCards: [],
      playerHand: [],
      drawableDiscardCards: [],
      opponentCounts: {},
      roundScores: {},
      roundWinner: null,
      roundWinners: null,
      isAsaf: false,
      asafByUserId: null,
      isRoundOver: false,
      isGameOver: false,
      error: null,
      maxPlayers: 6,
      targetScore: 100,
      yanivCallerId: null,
      yanivCallerName: null,
      yanivCalledAt: null,
      yanivContestTimerSeconds: 15,
      allPlayerHands: {},
      turnEndsAt: null,
      turnTimerSeconds: 45,
      autoPlayedPlayerId: null,
      bonusDiscardActive: false,
      pendingBonusCard: null,
      spectatorReadings: null,
    }),
}));

// Expose for e2e tests (Playwright reads store state through this handle)
if (typeof window !== 'undefined') {
  (window as any).__USE_GAME_STORE__ = useGameStore;
}

