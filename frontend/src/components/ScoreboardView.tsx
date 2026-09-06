import React from 'react';
import { useGameStore } from '../stores/gameStore';
import './ScoreboardView.css';

interface ScoreboardViewProps {
  scores: Record<string, number>;
  currentTurnPlayerId: string | null;
  eliminatedPlayers: string[];
  targetScore?: number;
}

export default React.memo(function ScoreboardView({
  scores,
  currentTurnPlayerId,
  eliminatedPlayers,
  targetScore,
}: ScoreboardViewProps) {
  const playerNames = useGameStore((state) => state.playerNames);

  const sortedPlayers = React.useMemo(
    () =>
      Object.entries(scores)
        .sort((a, b) => a[1] - b[1])
        .map(([playerId, score], rankIndex) => ({
          playerId,
          score,
          displayName: playerNames[playerId] || playerId.substring(0, 8),
          isCurrentTurn: playerId === currentTurnPlayerId,
          isEliminated: eliminatedPlayers.includes(playerId),
          rank: rankIndex + 1,
        })),
    [scores, playerNames, currentTurnPlayerId, eliminatedPlayers]
  );

  return (
    <div className="scoreboard-panel">
      <div className="scoreboard-header">
        <h3>Live Leaderboard</h3>
        <span className="leaderboard-icon">🏆</span>
      </div>

      <div className="scoreboard-player-list">
        {sortedPlayers.map((player) => (
          <MemoScoreboardRow key={player.playerId} player={player} />
        ))}
      </div>

      <div className="scoreboard-footer-tip">
        <span>Target Limit: {targetScore || 100} pts</span>
      </div>
    </div>
  );
},
areScoreboardPropsEqual);

interface ScoreboardRow {
  playerId: string;
  score: number;
  displayName: string;
  isCurrentTurn: boolean;
  isEliminated: boolean;
  rank: number;
}

const MemoScoreboardRow = React.memo(function ScoreboardRowView({
  player,
}: {
  player: ScoreboardRow;
}) {
  return (
    <div
      className={`scoreboard-player-row ${player.isCurrentTurn ? 'turn-active' : ''} ${
        player.isEliminated ? 'player-out' : ''
      }`}
    >
      <div className="row-left">
        <span className="rank-number">#{player.rank}</span>
        <div className="player-meta-info">
          <span className="player-name-text">{player.displayName}</span>
          {player.isCurrentTurn && <span className="turn-tag">PLAYING</span>}
          {player.isEliminated && <span className="eliminated-tag">ELIMINATED</span>}
        </div>
      </div>

      <div className="score-total-badge">
        <span className="score-digit">{player.score}</span>
        <span className="pts-label">pts</span>
      </div>
    </div>
  );
});

function areScoreboardPropsEqual(
  prev: ScoreboardViewProps,
  next: ScoreboardViewProps
): boolean {
  return (
    prev.scores === next.scores &&
    prev.currentTurnPlayerId === next.currentTurnPlayerId &&
    prev.eliminatedPlayers === next.eliminatedPlayers &&
    prev.targetScore === next.targetScore
  );
}
