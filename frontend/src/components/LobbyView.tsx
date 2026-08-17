import React, { useState, useEffect } from 'react';
import { preloadAllCards } from '../utils/cardPreload';
import './LobbyView.css';

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

  const handleCopyFriendCode = () => {
    if (!friendCode) return;
    navigator.clipboard.writeText(friendCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
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
            <h3>Host a Game</h3>
            <p>Create a room, set target score (200 pts), and invite your friends or share your room code.</p>
            <button className="lobby-primary-btn" onClick={onCreateGame}>
              Create Table
            </button>
          </div>

          {/* Card 2: Join by Room Code */}
          <div className="action-card join-card">
            <div className="card-top-icon">🔑</div>
            <h3>Join Game</h3>
            <p>Enter a 6-character room code or open an invite link shared by the host.</p>

            {!showJoinForm ? (
              <button className="lobby-secondary-btn" onClick={() => setShowJoinForm(true)}>
                Enter Room Code
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
              <span><strong>Call Yaniv</strong> when hand score $\le 7$ to win the round with 0 penalty!</span>
            </div>
            <div className="rule-item">
              <span className="rule-badge">4</span>
              <span><strong>Asaf Penalty:</strong> If another player has $\le$ your score, they get 0 and you take $+30$ penalty!</span>
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
