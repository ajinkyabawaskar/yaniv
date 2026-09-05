import React, { useState, useEffect } from 'react';
import { preloadAllCards } from '../utils/cardPreload';
import { gameApi } from '../utils/api';
import './LobbyView.css';

interface OpenLobby {
  gameId: string;
  roomCode: string;
  status: string;
  targetScore: number;
  maxPlayers: number;
  hostUserId: string;
  hostDisplayName?: string;
  createdAt: string;
  playerCount: number;
}

interface LobbyViewProps {
  onCreateGame: () => void;
  onJoinGame: (roomCode: string) => void;
}

export default function LobbyView({ onCreateGame, onJoinGame }: LobbyViewProps) {
  const [joinCode, setJoinCode] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [openLobbies, setOpenLobbies] = useState<OpenLobby[]>([]);
  const [loadingLobbies, setLoadingLobbies] = useState(true);

  // Preload all card SVGs when lobby mounts - happens silently in background
  useEffect(() => {
    preloadAllCards()
      .then(() => {
        console.log('[CardPreload] All 54 card SVGs preloaded successfully');
      })
      .catch((err) => {
        console.warn('[CardPreload] Preload completed with some errors:', err);
      });
  }, []);

  // Fetch open lobbies (uses authenticated apiClient; endpoint is also public as fallback)
  useEffect(() => {
    let mounted = true;

    const fetchOpenLobbies = async () => {
      try {
        const data = await gameApi.getOpenLobbies();
        if (mounted) {
          setOpenLobbies(data as any);
        }
      } catch (err) {
        console.warn('[LobbyView] Failed to fetch open lobbies:', err);
      } finally {
        if (mounted) setLoadingLobbies(false);
      }
    };

    fetchOpenLobbies();
    const interval = setInterval(fetchOpenLobbies, 5000); // Refresh every 5s for near-realtime open tables

    const handleVisibility = () => {
      if (document.visibilityState === 'visible') fetchOpenLobbies();
    };
    const handleLobbyCreated = () => fetchOpenLobbies();
    document.addEventListener('visibilitychange', handleVisibility);
    window.addEventListener('yanif:lobby-created', handleLobbyCreated as EventListener);
    window.addEventListener('focus', fetchOpenLobbies);

    return () => {
      mounted = false;
      clearInterval(interval);
      document.removeEventListener('visibilitychange', handleVisibility);
      window.removeEventListener('yanif:lobby-created', handleLobbyCreated as EventListener);
      window.removeEventListener('focus', fetchOpenLobbies);
    };
  }, []);

  const handleJoinGame = async () => {
    if (!joinCode.trim()) return;

    try {
      setIsLoading(true);
      await onJoinGame(joinCode.trim().toUpperCase());
    } finally {
      setIsLoading(false);
      setJoinCode('');
    }
  };

  const handleJoinOpenLobby = async (roomCode: string) => {
    try {
      setIsLoading(true);
      await onJoinGame(roomCode);
    } finally {
      setIsLoading(false);
    }
  };

  const formatTimeAgo = (isoString: string) => {
    // Server sends UTC (LocalDateTime in UTC). Ensure JS parses as UTC: append Z if no zone.
    const utcString = isoString.endsWith('Z') || isoString.includes('+') ? isoString : isoString + 'Z';
    const diff = Date.now() - new Date(utcString).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    return `${hours}h ago`;
  };

  return (
    <div className="lobby-view-root">
      <div className="lobby-hero-card">
        <div className="hero-emblem">♠ ♥ ♦ ♣</div>
        <h1 className="lobby-main-title">Yaniv Table Lounge</h1>
        <p className="lobby-subtitle">Zero-friction real-time multiplayer card action</p>

        {/* Primary: open tables first — the fastest path to playing. */}
        <div className="open-tables-section">
          <div className="section-header">
            <h3 className="section-title">Open Tables</h3>
            <span className="refresh-indicator" title="Auto-refreshes every 5s" aria-hidden="true" />
          </div>
          
          {loadingLobbies ? (
            <div className="open-tables-loading">Loading tables...</div>
          ) : openLobbies.length === 0 ? (
            <div className="open-tables-empty">
              <p>No open tables right now.</p>
              <p className="empty-hint">Create one or wait for a friend to host!</p>
            </div>
          ) : (
            <div className="open-tables-grid">
              {openLobbies.map((lobby) => (
                <div key={lobby.gameId} className="open-table-card">
                  <span className="table-code">{lobby.roomCode}</span>
                  <span className="table-status">
                    {lobby.playerCount}/{lobby.maxPlayers} seated
                  </span>
                  <span className="host-name">{lobby.hostDisplayName || 'Unknown'}</span>
                  <span className="meta-sub">
                    Target {lobby.targetScore} • {formatTimeAgo(lobby.createdAt)}
                  </span>
                  <button
                    className="join-table-btn"
                    onClick={() => handleJoinOpenLobby(lobby.roomCode)}
                    disabled={lobby.playerCount >= lobby.maxPlayers || isLoading}
                  >
                    {lobby.playerCount >= lobby.maxPlayers ? 'Full' : 'Join'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Secondary: host first, then an always-visible code entry. */}
        <div className="lobby-quick-actions">
          <button className="lobby-primary-btn" onClick={onCreateGame}>
            🎲 Host a Table
          </button>
          <form
            className="join-inline-row"
            onSubmit={(e) => {
              e.preventDefault();
              handleJoinGame();
            }}
          >
            <input
              type="text"
              placeholder="Table code — e.g. RMX92A"
              aria-label="Table code"
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              maxLength={6}
              disabled={isLoading}
            />
            <button
              type="submit"
              className="submit-join-btn"
              disabled={!joinCode.trim() || isLoading}
            >
              {isLoading ? 'Joining...' : 'Join'}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}