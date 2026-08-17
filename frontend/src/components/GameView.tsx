import React, { useEffect, useState, useMemo } from 'react';
import { useStomp } from '../contexts/StompContext';
import { useGameStore } from '../stores/gameStore';
import { gameApi } from '../utils/api';
import TableCanvas, { OpponentInfo, getCardImagePath } from './TableCanvas';
import ScoreboardView from './ScoreboardView';
import './GameView.css';

interface GameViewProps {
  gameId: string;
  roomCode: string;
  onExit: () => void;
}

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
  const [copiedLink, setCopiedLink] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [showScoreboard, setShowScoreboard] = useState(false);
  const [yanivContestTimerRemaining, setYanivContestTimerRemaining] = useState<number>(0);

  const currentUserId = localStorage.getItem('userId') || '';

  // Detect mobile viewport
  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth <= 768);
    checkMobile();
    window.addEventListener('resize', checkMobile);
    return () => window.removeEventListener('resize', checkMobile);
  }, []);

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

    const subscription = isConnected
      ? subscribe('/user/queue/game-state', (message) => {
          const gameData = JSON.parse(message.body);
          console.log('Received game state:', gameData);

          if (gameData.error) {
            console.error('Game error:', gameData.error);
            setServerError(gameData.error);
            setTimeout(() => setServerError(null), 4000);
            return;
          }

          const isRoundOverState = gameData.currentState === 'ROUND_OVER';
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
            isAsaf: gameData.isAsaf || false,
            asafByUserId: gameData.asafByUserId || null,
            isRoundOver: isRoundOverState,
            // Yaniv contest timer fields
            yanivCallerId: gameData.yanivCallerId || null,
            yanivCallerName: gameData.yanivCallerName || null,
            yanivCalledAt: gameData.yanivCalledAt || null,
            yanivContestTimerSeconds: gameData.yanivContestTimerSeconds || 15,
            allPlayerHands: gameData.allPlayerHands || {},
          });

          const userId = localStorage.getItem('userId');
          setIsPlayerTurn(gameData.currentTurnPlayerId === userId);
          setGameStarted(gameData.currentState !== 'LOBBY' && gameData.currentState !== 'ROUND_OVER');
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
        });
        const userId = localStorage.getItem('userId');
        setIsHost(gameData.hostUserId === userId);
        setGameStarted(gameData.status !== 'LOBBY');
      })
      .catch((err) => console.error('Failed to load game room:', err))
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

  const handleCopyInviteLink = () => {
    const joinUrl = `${window.location.origin}/join/${roomCode}`;
    navigator.clipboard.writeText(joinUrl);
    setCopiedLink(true);
    setTimeout(() => setCopiedLink(false), 2500);
  };

  // Compute Opponents for TableCanvas radial layout
  const opponentsList: OpponentInfo[] = useMemo(() => {
    const players = gameState.players || [];
    return players
      .filter((p) => p.userId !== currentUserId)
      .map((p) => ({
        userId: p.userId,
        displayName: p.displayName,
        score: gameState.scores?.[p.userId] || 0,
        isHost: p.isHost,
        isCurrentTurn: gameState.currentTurnPlayerId === p.userId,
        isEliminated: gameState.eliminatedPlayers?.includes(p.userId) || false,
        cardCount: gameState.opponentCounts?.[p.userId] !== undefined ? gameState.opponentCounts[p.userId] : 5,
      }));
  }, [gameState.players, gameState.scores, gameState.currentTurnPlayerId, gameState.eliminatedPlayers, gameState.opponentCounts, currentUserId]);

  if (loading) {
    return (
      <div className="game-view-loading">
        <div className="loading-spinner" />
        <p>Loading table...</p>
      </div>
    );
  }

  return (
    <div className="game-view-container">
      {/* Header Bar */}
      <div className="game-view-header">
        <div className="header-left">
          <span className="room-code-tag">ROOM #{roomCode}</span>
          <span className="round-badge">ROUND {gameState.roundNumber || 1}</span>
          {gameState.yanivCallerId && yanivContestTimerRemaining > 0 && (
            <span className="yaniv-timer-badge">
              ⏱️ {yanivContestTimerRemaining}s
            </span>
          )}
        </div>

        <div className="header-actions">
          <button className="copy-link-btn" onClick={handleCopyInviteLink}>
            <span>{copiedLink ? '✓ Link Copied' : '🔗 Invite Link'}</span>
          </button>
          {gameStarted && (
            <button
              className="scoreboard-toggle-btn"
              onClick={() => setShowScoreboard(!showScoreboard)}
              aria-label={showScoreboard ? 'Hide Scoreboard' : 'Show Scoreboard'}
              aria-expanded={showScoreboard}
            >
              <span>🏆 {showScoreboard ? 'Hide' : 'Scores'}</span>
            </button>
          )}
          <button className="exit-table-btn" onClick={onExit}>
            <span>Exit Table</span>
          </button>
        </div>
      </div>

      {/* Main Game Screen */}
      {!gameStarted ? (
        <div className="game-lobby-wrapper">
          <div className="lobby-glass-panel">
            <h2 className="lobby-title">Game Lobby #{roomCode}</h2>
            <p className="lobby-desc">Share room code or link to assemble your table (2–4 players)</p>

            <div className="invite-link-box">
              <input
                type="text"
                readOnly
                value={`${window.location.origin}/join/${roomCode}`}
                className="invite-url-input"
              />
              <button className="copy-btn" onClick={handleCopyInviteLink}>
                {copiedLink ? 'Copied!' : 'Copy'}
              </button>
            </div>

            <div className="lobby-players-grid">
              <h4>Joined Players ({gameState.players?.length || 0}/4)</h4>
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
              opponents={opponentsList}
              roundNumber={gameState.roundNumber}
              scores={gameState.scores}
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
            />
          </div>

          {/* Scoreboard - Hidden on mobile, shown as drawer */}
          <div className={`scoreboard-wrapper ${isMobile && showScoreboard ? 'mobile-open' : ''}`}>
            <ScoreboardView
              scores={gameState.scores}
              currentTurnPlayerId={gameState.currentTurnPlayerId}
              eliminatedPlayers={gameState.eliminatedPlayers}
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

      {/* Round Over Modal & Scorecard */}
      {showRoundOver && (
        <div className="round-over-overlay">
          <div className="round-over-card">
            <div className="round-complete-header">
              <span className="trophy-icon">🏆</span>
              <h2>Round {gameState.roundNumber} Complete!</h2>
            </div>

            {gameState.isAsaf && (
              <div className="asaf-alert-badge">
                ⚠️ ASAF! {gameState.playerNames?.[gameState.asafByUserId || ''] || 'An opponent'} triggered an Asaf! (+30 Penalty to caller)
              </div>
            )}

            <div className="round-winner-banner">
              {gameState.roundWinners && gameState.roundWinners.length > 0
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

            <button className="continue-round-btn" onClick={handleNextRound}>
              Continue to Next Round →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
