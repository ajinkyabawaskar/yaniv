import React, { useEffect, useState, useMemo, useRef } from 'react';
import { useStomp } from '../contexts/StompContext';
import { useGameStore } from '../stores/gameStore';
import { gameApi } from '../utils/api';
import TableCanvas, { OpponentInfo, getCardImagePath } from './TableCanvas';
import ScoreboardView from './ScoreboardView';
import { playTurnChangeSound, playYourTurnSound, isSoundEnabled, setSoundEnabled, setupAudioUnlock } from '../utils/sound';
import { isBgMusicEnabled, setBgMusicEnabled, setupBgMusicUnlock, preloadBgMusic } from '../utils/backgroundMusic';
import './GameView.css';

interface GameViewProps {
  gameId: string;
  roomCode: string;
  onExit: () => void;
}

/**
 * Identifies one player action so the server can discard duplicates.
 *
 * Generated when the action is created, not when it is sent, so a frame replayed
 * from the offline queue after a reconnect carries the same id and is ignored
 * rather than applied twice. The server namespaces it by room and user.
 */
let actionCounter = 0;
const newActionId = (): string => `${Date.now()}-${++actionCounter}`;

export default function GameView({ gameId, roomCode, onExit }: GameViewProps) {
  const { send, subscribe, isConnected, flushPending } = useStomp();
  const gameState = useGameStore();
  const [loading, setLoading] = useState(true);
  const [isPlayerTurn, setIsPlayerTurn] = useState(false);
  const [gameStarted, setGameStarted] = useState(false);
  const [isHost, setIsHost] = useState(false);
  const [startMessage, setStartMessage] = useState<string | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const [showRoundOver, setShowRoundOver] = useState(false);
  
  const [isMobile, setIsMobile] = useState(false);
  const [showScoreboard, setShowScoreboard] = useState(false);
  const [yanivContestTimerRemaining, setYanivContestTimerRemaining] = useState<number>(0);
  const [autoPlayNotice, setAutoPlayNotice] = useState<string | null>(null);

  const currentUserId = localStorage.getItem('userId') || '';
  const [soundEnabled, setSoundEnabledState] = useState(() => isSoundEnabled());
  const [bgMusicEnabled, setBgMusicEnabledState] = useState(() => isBgMusicEnabled());
  const prevTurnRef = useRef<string | null>(null);
  const hasStartedRef = useRef(false);

  // Sound unlock on first interaction
  useEffect(() => {
    setupAudioUnlock();
    setupBgMusicUnlock();
    preloadBgMusic();
    const handler = (e: Event) => setSoundEnabledState((e as CustomEvent).detail);
    const bgHandler = (e: Event) => setBgMusicEnabledState((e as CustomEvent).detail);
    window.addEventListener('yanif:sound-toggled', handler as EventListener);
    window.addEventListener('yanif:bg-music-toggled', bgHandler as EventListener);
    return () => {
      window.removeEventListener('yanif:sound-toggled', handler as EventListener);
      window.removeEventListener('yanif:bg-music-toggled', bgHandler as EventListener);
    };
  }, []);

  // Detect turn switches and play distinct sounds
  useEffect(() => {
    const turn = gameState.currentTurnPlayerId;
    const state = gameState.currentState;
    const isActive = state === 'WAIT_FOR_TURN' || state === 'BONUS_DISCARD' || state === 'YANIV_CALLED';
    if (!isActive || !turn) {
      if (state === 'LOBBY' || state === 'ROUND_OVER' || state === 'GAME_OVER') {
        hasStartedRef.current = false;
        prevTurnRef.current = null;
      }
      return;
    }
    // Ignore initial load — set baseline without sound
    if (!hasStartedRef.current) {
      hasStartedRef.current = true;
      prevTurnRef.current = turn;
      return;
    }
    if (prevTurnRef.current === turn) return;
    prevTurnRef.current = turn;
    if (turn === currentUserId) {
      playYourTurnSound();
    } else {
      playTurnChangeSound();
    }
  }, [gameState.currentTurnPlayerId, gameState.currentState, currentUserId]);

  // Detect mobile viewport
  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth <= 768);
    checkMobile();
    window.addEventListener('resize', checkMobile);
    return () => window.removeEventListener('resize', checkMobile);
  }, []);

  // Keep --game-header-h in sync with the real header height (it can wrap to
  // multiple rows on mobile); fixed-position drawer/overlay offset by this.
  const containerRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const container = containerRef.current;
    const header = headerRef.current;
    if (!container || !header) return;
    const update = () =>
      container.style.setProperty('--game-header-h', `${header.offsetHeight}px`);
    update();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(update);
    observer.observe(header);
    return () => observer.disconnect();
  }, []);

  // Handle visibility change for robust reconnection on mobile
  // When app comes to foreground, ensure we request game state if connected
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (!document.hidden && isConnected) {
        // App came to foreground - request fresh game state
        // This handles case where STOMP reconnected but GameView's isConnected effect didn't fire
        console.log('App foregrounded, requesting game state');
        send('/app/room/' + gameId + '/state', {});
        send('/app/room/' + gameId + '/join', {});
      }
    };

    window.addEventListener('visibilitychange', handleVisibilityChange);
    return () => window.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [isConnected, gameId, send]);

  // Yaniv Contest Timer - updates every 100ms during contest period
  useEffect(() => {
    if (gameState.yanivCallerId && gameState.yanivCalledAt && gameState.yanivContestTimerSeconds > 0) {
      const endTime = gameState.yanivCalledAt + gameState.yanivContestTimerSeconds * 1000;

      const updateTimer = () => {
        const now = Date.now();
        const remaining = Math.max(0, Math.ceil((endTime - now) / 1000));
        setYanivContestTimerRemaining(remaining);
      };

      updateTimer();
      const interval = setInterval(updateTimer, 100);
      return () => clearInterval(interval);
    } else {
      setYanivContestTimerRemaining(0);
    }
  }, [gameState.yanivCallerId, gameState.yanivCalledAt, gameState.yanivContestTimerSeconds]);

  // Helper to check if a player is a round winner
  const isRoundWinner = (userId: string) => {
    return gameState.roundWinners?.includes(userId) || gameState.roundWinner === userId;
  };

  useEffect(() => {
    gameState.setGame({ gameId, roomCode });

    // Room-scoped: a user destination reaches every tab this player has open, so a
    // shared one lets another game's state overwrite this view. Subscribing here is
    // also how the server learns this session is watching this game, and the
    // unsubscribe below is how it learns we left. See docs/adr/0001.
    const subscription = isConnected
      ? subscribe('/user/queue/room/' + gameId + '/game-state', (message) => {
          const gameData = JSON.parse(message.body);
          console.log('Received game state:', gameData);

          if (gameData.error) {
            console.error('Game error:', gameData.error);
            setServerError(gameData.error);
            setTimeout(() => setServerError(null), 4000);
            return;
          }

          const isRoundOverState = gameData.currentState === 'ROUND_OVER' || gameData.currentState === 'GAME_OVER';
          const isGameOverState = gameData.currentState === 'GAME_OVER';
          setShowRoundOver(isRoundOverState);

          gameState.setGame({
            gameId: gameData.gameId,
            roomCode: gameData.roomCode,
            currentState: gameData.currentState,
            currentTurnPlayerId: gameData.currentTurnPlayerId,
            roundNumber: gameData.roundNumber,
            scores: gameData.scores || {},
            playerNames: gameData.playerNames || {},
            players: gameData.players || [],
            eliminatedPlayers: gameData.eliminatedPlayers || [],
            deckCount: gameData.deckCount,
            topDiscardCard: gameData.topDiscardCard,
            topDiscardCards: gameData.topDiscardCards || (gameData.topDiscardCard ? [gameData.topDiscardCard] : []),
            playerHand: gameData.hand || [],
            drawableDiscardCards: gameData.drawableDiscardCards || [],
            opponentCounts: gameData.opponentCounts || {},
            roundScores: gameData.roundScores || {},
            roundWinner: gameData.roundWinner || null,
            // The real winner list. roundWinner above is a legacy field that always names
            // the Yaniv caller, even when an Asaf means they lost the round.
            roundWinners: gameData.roundWinners || null,
            isAsaf: gameData.isAsaf || false,
            asafByUserId: gameData.asafByUserId || null,
            isRoundOver: isRoundOverState,
            isGameOver: isGameOverState,
            // Yaniv contest timer fields
            yanivCallerId: gameData.yanivCallerId || null,
            yanivCallerName: gameData.yanivCallerName || null,
            yanivCalledAt: gameData.yanivCalledAt || null,
            yanivContestTimerSeconds: gameData.yanivContestTimerSeconds || 15,
            allPlayerHands: gameData.allPlayerHands || {},
            // Turn timer / auto-play fields
            turnEndsAt: gameData.turnEndsAt || null,
            turnTimerSeconds: gameData.turnTimerSeconds || 45,
            autoPlayedPlayerId: gameData.autoPlayedPlayerId || null,
            maxPlayers: gameData.maxPlayers || 6,
            targetScore: gameData.targetScore || 100,
            // Without these the engine waits in BONUS_DISCARD for a decision the player
            // has no way to make, and the client just keeps retrying its discard.
            bonusDiscardActive: gameData.bonusDiscardActive || false,
            pendingBonusCard: gameData.pendingBonusCard || null,
          });

          if (gameData.autoPlayedPlayerId) {
            const name = (gameData.playerNames || {})[gameData.autoPlayedPlayerId];
            setAutoPlayNotice(`${name || 'A player'} was auto-played (turn timer expired)`);
            setTimeout(() => setAutoPlayNotice(null), 4000);
          }

          const userId = localStorage.getItem('userId');
          // Expose for e2e tests (Playwright asserts on these handles)
          (window as any).__GAME_STATE__ = gameData;
          (window as any).__CURRENT_USER_ID__ = userId;
          setIsPlayerTurn(gameData.currentTurnPlayerId === userId);
          setGameStarted(gameData.currentState !== 'LOBBY' && gameData.currentState !== 'ROUND_OVER' && gameData.currentState !== 'GAME_OVER');
          setLoading(false);
        })
      : null;

    if (isConnected) {
      send('/app/room/' + gameId + '/state', {});
      send('/app/room/' + gameId + '/join', {});
    }

    gameApi
      .getGameDetails(gameId)
      .then((gameData: any) => {
        gameState.setGame({
          gameId: gameData.gameId,
          roomCode: gameData.roomCode,
          currentState: gameData.status,
          players: gameData.players || [],
          maxPlayers: gameData.maxPlayers || 6,
        });
        const userId = localStorage.getItem('userId');
        setIsHost(gameData.hostUserId === userId);
        setGameStarted(gameData.status !== 'LOBBY');
      })
      .catch((err) => console.error('Failed to load game table:', err))
      .finally(() => setLoading(false));

    return () => {
      subscription?.unsubscribe();
    };
  }, [gameId, isConnected]);

  useEffect(() => {
    if (isConnected) {
      flushPending();
      send('/app/room/' + gameId + '/join', {});
    }
  }, [isConnected, gameId, send, flushPending]);

  const handleStartGame = () => {
    if (!isConnected) {
      setStartMessage('Connecting to game server... please wait');
      return;
    }
    if (!isHost) {
      setStartMessage('Only the host can start the game');
      return;
    }
    setStartMessage(null);
    send('/app/room/' + gameId + '/start', {});
  };

  const handleNextRound = () => {
    send('/app/room/' + gameId + '/next-round', {});
    setShowRoundOver(false);
  };

  const handleDiscard = (cardIds: string[], drawSource: string, drawnCardId?: string) => {
    const userId = localStorage.getItem('userId');
    send('/app/room/' + gameId + '/action', {
      actionType: 'DISCARD_AND_DRAW',
      playerId: userId,
      discardedCardIds: cardIds,
      drawSource,
      drawnCardId,
      actionId: newActionId(),
    });
  };

  const handleBonusDiscard = (shouldDiscard: boolean) => {
    const userId = localStorage.getItem('userId');
    send('/app/room/' + gameId + '/action', {
      actionType: 'BONUS_DISCARD',
      playerId: userId,
      bonusDiscard: shouldDiscard,
      actionId: newActionId(),
    });
  };

  const handleCallYaniv = () => {
    const userId = localStorage.getItem('userId');
    send('/app/room/' + gameId + '/call-yaniv', {
      playerId: userId,
    });
  };

  const handleContestYaniv = () => {
    const userId = localStorage.getItem('userId');
    send('/app/room/' + gameId + '/contest-yaniv', {
      playerId: userId,
    });
  };

  // Compute all players for TableCanvas radial layout (including current player)
  const allPlayersList: OpponentInfo[] = useMemo(() => {
    const players = gameState.players || [];
    return players
      .map((p) => ({
        userId: p.userId,
        displayName: p.displayName,
        score: gameState.scores?.[p.userId] || 0,
        isHost: p.isHost,
        isCurrentTurn: gameState.currentTurnPlayerId === p.userId,
        isEliminated: gameState.eliminatedPlayers?.includes(p.userId) || false,
        cardCount: p.userId === currentUserId
          ? gameState.playerHand?.length || 5
          : (gameState.opponentCounts?.[p.userId] !== undefined ? gameState.opponentCounts[p.userId] : 5),
        // Carried on every state push, so a reload or a late join sees the truth.
        isDisconnected: p.status === 'DISCONNECTED_IN_GAME',
        isCurrentPlayer: p.userId === currentUserId,
      }));
  }, [gameState.players, gameState.scores, gameState.currentTurnPlayerId, gameState.eliminatedPlayers, gameState.opponentCounts, currentUserId, gameState.playerHand]);

  if (loading) {
    return (
      <div className="game-view-loading">
        <div className="loading-spinner" />
        <p>Loading table...</p>
      </div>
    );
  }

  return (
    <div className="game-view-container" ref={containerRef}>
      {/* Header Bar */}
      <div className="game-view-header" ref={headerRef}>
        <div className="header-left">
          <div className="table-info">
            <span className="room-code-tag">TABLE #{roomCode}</span>
            <span className="round-badge">ROUND {gameState.roundNumber || 1}</span>
          </div>
          {gameState.yanivCallerId && yanivContestTimerRemaining > 0 && (
            <span className="yaniv-timer-badge">
              ⏱️ {yanivContestTimerRemaining}s
            </span>
          )}
          {autoPlayNotice && (
            <span className="autoplay-notice-badge">
              🤖 {autoPlayNotice}
            </span>
          )}
        </div>

        <div className="header-actions">
          <button
            className="header-btn sound-toggle-btn"
            onClick={() => {
              const next = !bgMusicEnabled;
              setBgMusicEnabled(next);
              setBgMusicEnabledState(next);
            }}
            aria-label={bgMusicEnabled ? 'Mute background music' : 'Unmute background music'}
            title={bgMusicEnabled ? 'Mute background music' : 'Unmute background music'}
          >
            <span>{bgMusicEnabled ? '🎵' : '🔇'}</span>
          </button>
          <button
            className="header-btn sound-toggle-btn"
            onClick={() => {
              const next = !soundEnabled;
              setSoundEnabled(next);
              setSoundEnabledState(next);
            }}
            aria-label={soundEnabled ? 'Mute sounds' : 'Unmute sounds'}
            title={soundEnabled ? 'Mute turn sounds' : 'Unmute turn sounds'}
          >
            <span>{soundEnabled ? '🔊' : '🔇'}</span>
          </button>
          {gameStarted && (
            <button
              className="header-btn"
              onClick={() => setShowScoreboard(!showScoreboard)}
              aria-label={showScoreboard ? 'Hide Scoreboard' : 'Show Scoreboard'}
              aria-expanded={showScoreboard}
            >
              <span>{showScoreboard ? 'Hide' : 'Scores'}</span>
            </button>
          )}
          <button className="header-btn" onClick={onExit}>
            <span>Exit Table</span>
          </button>
        </div>
      </div>

      {/* Main Game Screen */}
      {!gameStarted ? (
        <div className="game-lobby-wrapper">
          <div className="lobby-glass-panel">
            <h2 className="lobby-title">Game Table #{roomCode}</h2>
            <p className="lobby-desc">Share table code or link to assemble your table (2–{gameState.maxPlayers} players)</p>

            <div className="lobby-players-grid">
              <h4>Joined Players ({gameState.players?.length || 0}/{gameState.maxPlayers})</h4>
              <div className="players-list">
                {gameState.players?.map((player) => (
                  <div key={player.userId} className="lobby-player-card">
                    <div className="player-avatar">{player.displayName.substring(0, 2).toUpperCase()}</div>
                    <div className="player-meta">
                      <span className="name">{player.displayName}</span>
                      <div className="badges">
                        {player.isHost && <span className="host-tag">👑 HOST</span>}
                        {player.userId === currentUserId && <span className="you-tag">YOU</span>}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {isHost ? (
              <div className="host-controls">
                <button
                  className="start-game-btn"
                  onClick={handleStartGame}
                  disabled={!isConnected || (gameState.players?.length || 0) < 2}
                >
                  {isConnected ? 'Deal & Start Game' : 'Connecting to Server...'}
                </button>
                {startMessage && <p className="status-msg">{startMessage}</p>}
                {(gameState.players?.length || 0) < 2 && (
                  <p className="status-msg">Waiting for at least 2 players to join...</p>
                )}
              </div>
            ) : (
              <div className="guest-waiting">
                <div className="pulse-dot" />
                <span>Waiting for table host to start the game...</span>
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="game-play-layout">
          <div className="table-area-wrapper">
            <TableCanvas
              hand={gameState.playerHand}
              topCard={gameState.topDiscardCard}
              topDiscardCards={gameState.topDiscardCards}
              isPlayerTurn={isPlayerTurn}
              currentTurnPlayerId={gameState.currentTurnPlayerId}
              deckCount={gameState.deckCount}
              opponents={allPlayersList}
              roundNumber={gameState.roundNumber}
              onDiscard={handleDiscard}
              onCallYaniv={handleCallYaniv}
              onContestYaniv={handleContestYaniv}
              drawableDiscardCards={gameState.drawableDiscardCards}
              isAsaf={gameState.isAsaf}
              asafByUserId={gameState.asafByUserId}
              roundWinner={gameState.roundWinner}
              playerNames={gameState.playerNames}
              yanivCallerId={gameState.yanivCallerId}
              yanivCallerName={gameState.yanivCallerName}
              yanivCalledAt={gameState.yanivCalledAt}
              yanivContestTimerSeconds={gameState.yanivContestTimerSeconds}
              allPlayerHands={gameState.allPlayerHands}
              serverError={serverError}
              currentUserId={currentUserId}
              turnEndsAt={gameState.turnEndsAt}
              turnTimerTotalSeconds={gameState.turnTimerSeconds}
              autoPlayedPlayerId={gameState.autoPlayedPlayerId}
              bonusDiscardActive={gameState.bonusDiscardActive}
              pendingBonusCard={gameState.pendingBonusCard}
              onBonusDiscard={handleBonusDiscard}
            />
          </div>

          {/* Scoreboard - Hidden on mobile, shown as drawer */}
          <div className={`scoreboard-wrapper ${isMobile && showScoreboard ? 'mobile-open' : ''}`}>
            <ScoreboardView
              scores={gameState.scores}
              currentTurnPlayerId={gameState.currentTurnPlayerId}
              eliminatedPlayers={gameState.eliminatedPlayers}
              targetScore={gameState.targetScore}
            />
          </div>

          {/* Mobile Scoreboard Overlay */}
          {isMobile && (
            <div
              className={`scoreboard-overlay ${showScoreboard ? 'visible' : ''}`}
              onClick={() => setShowScoreboard(false)}
              aria-hidden="true"
            />
          )}
        </div>
      )}

      {/* Round Over / Game Over Modal & Scorecard */}
      {showRoundOver && (
        <div className="round-over-overlay">
          <div className="round-over-card">
            <div className="round-complete-header">
              <span className="trophy-icon">🏆</span>
              <h2>
                {gameState.isGameOver ? 'Game Over!' : `Round ${gameState.roundNumber} Complete!`}
              </h2>
            </div>

            {gameState.isAsaf && (
              <div className="asaf-alert-badge">
                ⚠️ ASAF! {gameState.playerNames?.[gameState.asafByUserId || ''] || 'An opponent'} triggered an Asaf! (+30 Penalty to caller)
              </div>
            )}

            <div className="round-winner-banner">
              {gameState.isGameOver
                ? (() => {
                    // For game over, find the winner from scores (lowest score wins)
                    const scores = gameState.scores || {};
                    const activePlayers = gameState.players?.filter(p => !gameState.eliminatedPlayers?.includes(p.userId)) || [];
                    if (activePlayers.length === 1) {
                      return (
                        <span className="winner-name game-winner">
                          🌟 {gameState.playerNames?.[activePlayers[0].userId] || activePlayers[0].userId} wins the game!
                        </span>
                      );
                    }
                    // Fallback: use roundWinners
                    if (gameState.roundWinners && gameState.roundWinners.length > 0) {
                      return gameState.roundWinners.map((winnerId, idx) => (
                        <span key={winnerId} className="winner-name">
                          {idx > 0 ? ' & ' : '🌟 '}
                          {gameState.playerNames?.[winnerId] || winnerId}
                          {gameState.isAsaf && winnerId === gameState.asafByUserId ? ' (ASAF)' : ''}
                        </span>
                      ));
                    }
                    return <span className="winner-name">🏆 Game Over!</span>;
                  })()
                : gameState.roundWinners && gameState.roundWinners.length > 0
                ? gameState.roundWinners.map((winnerId, idx) => (
                    <span key={winnerId} className="winner-name">
                      {idx > 0 ? ' & ' : '🌟 '}
                      {gameState.playerNames?.[winnerId] || winnerId}
                      {gameState.isAsaf && winnerId === gameState.asafByUserId ? ' (ASAF)' : ''}
                    </span>
                  ))
                : gameState.roundWinner
                ? `🌟 ${gameState.playerNames?.[gameState.roundWinner] || gameState.roundWinner} called Yaniv!`
                : 'Round Finished'}
            </div>

            {/* Revealed Hands Section */}
            {gameState.allPlayerHands && Object.keys(gameState.allPlayerHands).length > 0 && (
              <div className="revealed-hands-section">
                <h3 className="revealed-hands-title">All Hands Revealed</h3>
                <div className="revealed-hands-grid">
                  {gameState.players?.map((player) => {
                    const hand = gameState.allPlayerHands?.[player.userId] || [];
                    const handScore = hand.reduce((sum, card) => {
                      const rankValues: Record<string, number> = {
                        ACE: 1, TWO: 2, THREE: 3, FOUR: 4, FIVE: 5,
                        SIX: 6, SEVEN: 7, EIGHT: 8, NINE: 9, TEN: 10,
                        JACK: 10, QUEEN: 10, KING: 10,
                      };
                      return sum + (rankValues[card.rank] || 0);
                    }, 0);
                    const roundScore = gameState.roundScores?.[player.userId] || 0;
                    const totalScore = gameState.scores?.[player.userId] || 0;
                    const isWinner = isRoundWinner(player.userId);
                    const isAsafPlayer = player.userId === gameState.asafByUserId;

                    return (
                      <div
                        key={player.userId}
                        className={`revealed-hand-card ${isWinner ? 'winner' : ''} ${isAsafPlayer ? 'asaf' : ''}`}
                      >
                        <div className="revealed-hand-header">
                          <span className="revealed-hand-name">{player.displayName}</span>
                          <div className="revealed-hand-badges">
                            {player.isHost && <span className="cell-badge">HOST</span>}
                            {isWinner && <span className="cell-badge gold">WINNER</span>}
                            {isAsafPlayer && <span className="cell-badge crimson">ASAF</span>}
                          </div>
                        </div>
                        <div className="revealed-hand-cards">
                          {hand.map((card) => (
                            <img
                              key={card.id}
                              src={getCardImagePath(card.rank, card.suit)}
                              alt={`${card.rank} of ${card.suit}`}
                              className="revealed-card-img"
                            />
                          ))}
                        </div>
                        <div className="revealed-hand-scores">
                          <span className="hand-score">Hand: {handScore}</span>
                          <span className="round-score">{roundScore > 0 ? `+${roundScore}` : roundScore}</span>
                          <span className="total-score">Total: {totalScore}</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            <div className="scorecard-container">
              <table className="scorecard-table">
                <thead>
                  <tr>
                    <th>Player</th>
                    <th>Round Pts</th>
                    <th>Total Score</th>
                  </tr>
                </thead>
                <tbody>
                  {gameState.players?.map((player) => {
                    const roundScore = gameState.roundScores?.[player.userId] || 0;
                    const totalScore = gameState.scores?.[player.userId] || 0;
                    const isWinner = isRoundWinner(player.userId);
                    const isAsafPlayer = player.userId === gameState.asafByUserId;

                    return (
                      <tr
                        key={player.userId}
                        className={`${isWinner ? 'winner-row' : ''} ${isAsafPlayer ? 'asaf-row' : ''}`}
                      >
                        <td>
                          <div className="player-row-cell">
                            <span className="name">{player.displayName}</span>
                            {player.isHost && <span className="cell-badge">HOST</span>}
                            {isWinner && <span className="cell-badge gold">WINNER</span>}
                            {isAsafPlayer && <span className="cell-badge crimson">ASAF</span>}
                          </div>
                        </td>
                        <td className="round-pts">
                          {roundScore > 0 ? `+${roundScore}` : `${roundScore}`}
                        </td>
                        <td className="total-pts">{totalScore}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {!gameState.isGameOver && (
              <button className="continue-round-btn" onClick={handleNextRound}>
                Continue to Next Round →
              </button>
            )}
            {gameState.isGameOver && (
              <div className="game-over-final">
                <p>Thanks for playing! 🎉</p>
                <button className="continue-round-btn" onClick={onExit}>
                  Exit Table
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}