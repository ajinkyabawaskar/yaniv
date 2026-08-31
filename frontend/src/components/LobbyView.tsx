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
  friendCode: string;
}

export default function LobbyView({ onCreateGame, onJoinGame, friendCode }: LobbyViewProps) {
  const [joinCode, setJoinCode] = useState('');
  const [showJoinForm, setShowJoinForm] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [copiedCode, setCopiedCode] = useState(false);
  const [cardsPreloaded, setCardsPreloaded] = useState(false);
  const [openLobbies, setOpenLobbies] = useState<OpenLobby[]>([]);
  const [loadingLobbies, setLoadingLobbies] = useState(true);

  // Preload all card SVGs when lobby mounts - happens in background while user waits
  useEffect(() => {
    let mounted = true;

    preloadAllCards().then(() => {
      if (mounted) {
        setCardsPreloaded(true);
        console.log('[CardPreload] All 54 card SVGs preloaded successfully');
      }
    }).catch((err) => {
      console.warn('[CardPreload] Preload completed with some errors:', err);
      if (mounted) setCardsPreloaded(true);
    });

    return () => {
      mounted = false;
    };
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
      setShowJoinForm(false);
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

  const handleCopyFriendCode = () => {
    if (!friendCode) return;
    navigator.clipboard.writeText(friendCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
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

        {/* Action Cards Grid */}
        <div className="lobby-actions-grid">
          {/* Card 1: Create Game */}
          <div className="action-card create-card">
            <div className="card-top-icon">🎲</div>
            <h3>Host a Table</h3>
            <p>Create a table, set target score (100 pts), and invite your friends or share your table code.</p>
            <button className="lobby-primary-btn" onClick={onCreateGame}>
              Create Table
            </button>
          </div>

          {/* Card 2: Join by Table Code */}
          <div className="action-card join-card">
            <div className="card-top-icon">🔑</div>
            <h3>Join a Table</h3>
            <p>Enter a 6-character table code or open an invite link shared by the host.</p>

            {!showJoinForm ? (
              <button className="lobby-secondary-btn" onClick={() => setShowJoinForm(true)}>
                Enter Table Code
              </button>
            ) : (
              <div className="join-input-group">
                <input
                  type="text"
                  placeholder="e.g. RMX92A"
                  value={joinCode}
                  onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
                  maxLength={6}
                  disabled={isLoading}
                  autoFocus
                />
                <div className="join-btn-row">
                  <button
                    className="submit-join-btn"
                    onClick={handleJoinGame}
                    disabled={!joinCode.trim() || isLoading}
                  >
                    {isLoading ? 'Joining...' : 'Join'}
                  </button>
                  <button className="cancel-join-btn" onClick={() => setShowJoinForm(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Open Tables Section */}
        <div className="open-tables-section">
          <div className="section-header">
            <h3 className="section-title">🟢 Open Tables</h3>
            {loadingLobbies ? (
              <span className="refresh-indicator">⟳</span>
            ) : (
              <span className="refresh-indicator" title="Auto-refreshes every 5s">⟳</span>
            )}
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
                  <div className="table-card-header">
                    <span className="table-code">{lobby.roomCode}</span>
                    <span className="table-status">
                      {lobby.playerCount}/{lobby.maxPlayers}
                    </span>
                  </div>
                  <div className="table-card-body">
                    <div className="table-host">
                      <span className="host-label">Host:</span>
                      <span className="host-name">{lobby.hostDisplayName || 'Unknown'}</span>
                    </div>
                    <div className="table-meta">
                      <span className="meta-item">Target: {lobby.targetScore}</span>
                      <span className="meta-item">{formatTimeAgo(lobby.createdAt)}</span>
                    </div>
                  </div>
                  <button
                    className="join-table-btn"
                    onClick={() => handleJoinOpenLobby(lobby.roomCode)}
                    disabled={lobby.playerCount >= lobby.maxPlayers || isLoading}
                  >
                    {lobby.playerCount >= lobby.maxPlayers ? 'Full' : 'Join Table'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Share Friend Code Strip */}
        <div className="friend-code-strip">
          <div className="strip-left">
            <span className="strip-label">YOUR FRIEND CODE:</span>
            <code className="code-display">{friendCode || '--------'}</code>
          </div>
          <button className="copy-code-btn" onClick={handleCopyFriendCode} title="Copy friend code">
            {copiedCode ? '✓ Copied!' : '📋 Copy Code'}
          </button>
        </div>

        {/* Yaniv Rules Summary */}
        <div className="rules-summary-box">
          <h4>Quick Rules Reference</h4>
          <div className="rules-grid">
            <div className="rule-item">
              <span className="rule-badge">1</span>
              <span><strong>Discard</strong> any single card, matching rank sets (2–4), or consecutive runs (2+).</span>
            </div>
            <div className="rule-item">
              <span className="rule-badge">2</span>
              <span><strong>Draw</strong> 1 card from Draw Pile or outer eligible cards of Discard Pile.</span>
            </div>
            <div className="rule-item">
              <span className="rule-badge">3</span>
              <span><strong>Call Yaniv</strong> when hand score ≤ 7 to win the round with 0 penalty!</span>
            </div>
            <div className="rule-item">
              <span className="rule-badge">4</span>
              <span><strong>Asaf Penalty:</strong> If another player has ≤ your score, they get 0 and you take +30 penalty!</span>
            </div>
          </div>
        </div>

        {/* Card Assets Preload Status */}
        <div className="preload-status-bar">
          <span className={`preload-indicator ${cardsPreloaded ? 'complete' : 'loading'}`}>
            {cardsPreloaded ? '✓ Card assets ready' : '⟳ Loading card assets...'}
          </span>
          <span className="preload-hint">Instant card draws in-game</span>
        </div>
      </div>
    </div>
  );
}