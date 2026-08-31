import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import { useStomp } from '../contexts/StompContext';
import { gameApi, friendApi, presenceApi, userApi, FRONTEND_VERSION } from '../utils/api';
import FriendsSidebar, { FriendInfo } from '../components/FriendsSidebar';
import InviteNotificationToast from '../components/InviteNotificationToast';
import LobbyView from '../components/LobbyView';
import GameView from '../components/GameView';
import Avatar from '../components/Avatar';
import './MainView.css';

interface MainViewProps {
  initialRoomCode?: string;
}

export default function MainView({ initialRoomCode }: MainViewProps) {
  const navigate = useNavigate();
  const params = useParams<{ roomCode?: string }>();
  const [searchParams] = useSearchParams();
  const roomCodeFromUrl = initialRoomCode || params.roomCode || searchParams.get('room');

  const { user, login, logout, jwtToken } = useAuthStore();
  const { isConnected, subscribe, send } = useStomp();

  const [activeView, setActiveView] = useState<'lobby' | 'game'>('lobby');
  const [currentGameId, setCurrentGameId] = useState<string | null>(null);
  const [currentRoomCode, setCurrentRoomCode] = useState<string | null>(null);
  const [friends, setFriends] = useState<FriendInfo[]>([]);
  const [loadingFriends, setLoadingFriends] = useState(true);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [showEditProfileModal, setShowEditProfileModal] = useState(false);
  const [newDisplayName, setNewDisplayName] = useState(user?.displayName || '');
  const [savingProfile, setSavingProfile] = useState(false);

  // Detect mobile viewport
  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth <= 768);
    checkMobile();
    window.addEventListener('resize', checkMobile);
    return () => window.removeEventListener('resize', checkMobile);
  }, []);

  const loadFriends = useCallback(async () => {
    try {
      setLoadingFriends(true);
      try {
        await presenceApi.markOnline();
      } catch (err) {
        console.error('Failed to mark online:', err);
      }
      const response = await friendApi.getList();
      setFriends(response.friends || []);
    } catch (err) {
      console.error('Failed to load friends:', err);
    } finally {
      setLoadingFriends(false);
    }
  }, []);

  useEffect(() => {
    loadFriends();
  }, [loadFriends]);

  // WebSocket presence subscription
  useEffect(() => {
    if (!isConnected) return;

    const subscription = subscribe('/user/queue/presence', (message) => {
      const presenceData = JSON.parse(message.body);
      setFriends((prev) =>
        prev.map((friend) => ({
          ...friend,
          presence:
            presenceData.friendsPresence?.[friend.userId] ||
            (presenceData.userId === friend.userId ? presenceData.presence : friend.presence),
        }))
      );
    });

    send('/app/presence/subscribe', {});
    loadFriends();

    return () => {
      subscription?.unsubscribe();
    };
  }, [isConnected, subscribe, send, loadFriends]);

  const handleJoinGame = useCallback(async (code: string) => {
    try {
      const response = await gameApi.joinRoom(code);
      setCurrentGameId(response.gameId);
      setCurrentRoomCode(response.roomCode);
      setActiveView('game');
    } catch (err) {
      console.error('Failed to join game:', err);
      alert('Could not join table #' + code + '. It may be full or invalid.');
    }
  }, []);

  // Handle deep-link join on load if roomCode exists
  useEffect(() => {
    if (roomCodeFromUrl && activeView !== 'game') {
      handleJoinGame(roomCodeFromUrl);
    }
  }, [roomCodeFromUrl, handleJoinGame]);

  const handleCreateGame = async () => {
    try {
      const response = await gameApi.createRoom(100, 6);
      setCurrentGameId(response.gameId);
      setCurrentRoomCode(response.roomCode);
      setActiveView('game');
      window.dispatchEvent(new CustomEvent('yanif:lobby-created'));
    } catch (err) {
      console.error('Failed to create game:', err);
    }
  };

  const handleExitGame = () => {
    setCurrentGameId(null);
    setCurrentRoomCode(null);
    setActiveView('lobby');
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const toggleSidebar = () => {
    if (isMobile) {
      setIsSidebarOpen(!isSidebarOpen);
    } else {
      setIsSidebarCollapsed(!isSidebarCollapsed);
    }
  };

  const closeSidebar = () => {
    if (isMobile) {
      setIsSidebarOpen(false);
    }
  };

  const handleUpdateDisplayName = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newDisplayName.trim() || !user) return;

    try {
      setSavingProfile(true);
      await userApi.updateProfile(newDisplayName.trim());
      localStorage.setItem('displayName', newDisplayName.trim());
      const updatedUser = { ...user, displayName: newDisplayName.trim() };
      login(updatedUser, jwtToken || '');
      setShowEditProfileModal(false);
    } catch (err) {
      console.error('Failed to update display name:', err);
    } finally {
      setSavingProfile(false);
    }
  };

  return (
    <div className="main-view-root">
      {/* Top Luxury Navigation Bar */}
      <header className="main-top-navbar">
        <div className="nav-left">
          <div className="brand-badge">
            <span className="brand-icon">♠</span>
            <span className="brand-name">YANIV</span>
          </div>
          <button
            className="sidebar-toggle-btn"
            onClick={toggleSidebar}
            title={isMobile ? 'Toggle Friends' : 'Toggle Friends Sidebar'}
          >
            {isMobile
              ? isSidebarOpen
                ? '✕ Close'
                : '👥 Friends'
              : isSidebarCollapsed
              ? '👥 Show Friends'
              : '◀ Hide'}
          </button>
        </div>

        <div className="nav-right">
          {FRONTEND_VERSION && FRONTEND_VERSION !== 'dev' && (
            <span className="version-text" title="Frontend Version">
              v{FRONTEND_VERSION}
            </span>
          )}

          <div className="user-profile-badge" onClick={() => setShowEditProfileModal(true)}>
            <div className="user-info">
              <span className="user-name">{user?.displayName}</span>
              <span className="edit-hint">✎ Edit Nickname</span>
            </div>
            <Avatar
              name={user?.displayName}
              presence={isConnected ? 'ONLINE' : 'OFFLINE'}
              size="xs"
              className="nav-avatar"
            />
          </div>

          <button onClick={handleLogout} className="nav-logout-btn">
            Logout
          </button>
        </div>
      </header>

      {/* Mobile Sidebar Overlay */}
      {isMobile && (
        <div
          className={`sidebar-overlay ${isSidebarOpen ? 'visible' : ''}`}
          onClick={closeSidebar}
          aria-hidden="true"
        />
      )}

      {/* Main Content Workspace */}
      <div className="main-workspace">
        {/* Collapsible Friends Sidebar */}
        <aside
          className={`main-sidebar-aside ${isSidebarCollapsed ? 'collapsed' : ''} ${isMobile && isSidebarOpen ? 'mobile-open' : ''}`}
          role="complementary"
          aria-label="Friends sidebar"
        >
          <FriendsSidebar
            friends={friends}
            loading={loadingFriends}
            onFriendsChanged={loadFriends}
            isCollapsed={isSidebarCollapsed}
            onToggleCollapse={() => setIsSidebarCollapsed(true)}
            onClose={closeSidebar}
          />
        </aside>

        {/* Center Canvas Area */}
        <main className="main-canvas-area">
          {activeView === 'lobby' ? (
            <LobbyView
              onCreateGame={handleCreateGame}
              onJoinGame={handleJoinGame}
              friendCode={user?.friendCode || ''}
            />
          ) : (
            <GameView gameId={currentGameId!} roomCode={currentRoomCode!} onExit={handleExitGame} />
          )}
        </main>
      </div>

      {/* In-App Invitation Toast Overlay */}
      <InviteNotificationToast onAccept={(roomCode) => handleJoinGame(roomCode)} />

      {/* Edit Profile Modal */}
      {showEditProfileModal && (
        <div className="modal-backdrop">
          <div className="edit-profile-card">
            <h3>Change Display Name</h3>
            <form onSubmit={handleUpdateDisplayName}>
              <input
                type="text"
                value={newDisplayName}
                onChange={(e) => setNewDisplayName(e.target.value)}
                maxLength={30}
                autoFocus
              />
              <div className="modal-btn-row">
                <button type="submit" disabled={savingProfile || !newDisplayName.trim()} className="save-btn">
                  {savingProfile ? 'Saving...' : 'Save'}
                </button>
                <button type="button" onClick={() => setShowEditProfileModal(false)} className="cancel-btn">
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
