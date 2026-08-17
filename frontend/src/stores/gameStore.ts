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
  error: string | null;
  maxPlayers: number; // Maximum players allowed in the room

  // Yaniv Contest Timer fields
  yanivCallerId: string | null;
  yanivCallerName: string | null;
  yanivCalledAt: number | null; // Server epoch ms
  yanivContestTimerSeconds: number; // Total allowed seconds (e.g., 15)
  allPlayerHands: Record<string, GameCard[]>; // Revealed hands on ROUND_OVER

  // Disconnected players tracking for reconnection UI
  disconnectedPlayers: Set<string>; // userIds of disconnected players

  // Actions
  setGame: (game: Partial<GameState>) => void;
  setError: (error: string | null) => void;
  addCardToHand: (card: GameCard) => void;
  removeCardFromHand: (cardId: string) => void;
  clearGame: () => void;
  addDisconnectedPlayer: (userId: string) => void;
  removeDisconnectedPlayer: (userId: string) => void;
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
  error: null,
  maxPlayers: 6,

  // Yaniv Contest Timer initial values
  yanivCallerId: null,
  yanivCallerName: null,
  yanivCalledAt: null,
  yanivContestTimerSeconds: 15,
  allPlayerHands: {},

  // Disconnected players
  disconnectedPlayers: new Set(),

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
  addDisconnectedPlayer: (userId) =>
    set((state) => {
      const newSet = new Set(state.disconnectedPlayers);
      newSet.add(userId);
      return { disconnectedPlayers: newSet };
    }),
  removeDisconnectedPlayer: (userId) =>
    set((state) => {
      const newSet = new Set(state.disconnectedPlayers);
      newSet.delete(userId);
      return { disconnectedPlayers: newSet };
    }),
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
      error: null,
      maxPlayers: 6,
      yanivCallerId: null,
      yanivCallerName: null,
      yanivCalledAt: null,
      yanivContestTimerSeconds: 15,
      allPlayerHands: {},
      disconnectedPlayers: new Set(),
    }),
}));

