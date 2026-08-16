import React, { useState, useEffect } from 'react';
import { friendApi } from '../utils/api';
import { useStomp } from '../contexts/StompContext';
import { useGameStore } from '../stores/gameStore';
import './FriendsSidebar.css';

export interface FriendInfo {
  userId: string;
  displayName: string;
  friendCode: string;
  presence: string; // ONLINE, OFFLINE, IN_GAME
}

interface FriendsSidebarProps {
  friends: FriendInfo[];
  loading: boolean;
  onFriendsChanged: () => Promise<void>;
  isCollapsed?: boolean;
  onToggleCollapse?: () => void;
  onClose?: () => void;
}

interface PendingRequest {
  friendshipId: string;
  fromUserId: string;
  fromDisplayName?: string;
  fromFriendCode?: string;
}

export default function FriendsSidebar({
  friends,
  loading,
  onFriendsChanged,
  isCollapsed = false,
  onToggleCollapse,
  onClose,
}: FriendsSidebarProps) {
  const { send, isConnected } = useStomp();
  const currentGameId = useGameStore((s) => s.gameId);
  const currentRoomCode = useGameStore((s) => s.roomCode);

  const [showAddFriend, setShowAddFriend] = useState(false);
  const [friendCode, setFriendCode] = useState('');
  const [sending, setSending] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [pendingRequests, setPendingRequests] = useState<PendingRequest[]>([]);
  const [respondingTo, setRespondingTo] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [invitedFriends, setInvitedFriends] = useState<Record<string, boolean>>({});

  const loadPendingRequests = async () => {
    try {
      const response = (await friendApi.getPendingRequests()) as { requests?: PendingRequest[] };
      setPendingRequests(response.requests || []);
    } catch (err) {
      console.error('Failed to load friend requests:', err);
    }
  };

  useEffect(() => {
    loadPendingRequests();
  }, []);

  const handleRefresh = async () => {
    try {
      setRefreshing(true);
      await Promise.all([onFriendsChanged(), loadPendingRequests()]);
    } finally {
      setRefreshing(false);
    }
  };

  const handleAddFriend = async () => {
    if (!friendCode.trim()) return;

    try {
      setSending(true);
      await friendApi.sendRequest(friendCode.trim());
      setFriendCode('');
      setShowAddFriend(false);
      await handleRefresh();
      setFeedback({ type: 'success', message: 'Friend request sent!' });
    } catch (err) {
      console.error('Failed to send friend request:', err);
      const message = (err as { response?: { data?: { error?: string } } }).response?.data?.error;
      setFeedback({ type: 'error', message: message || 'Could not send friend request.' });
    } finally {
      setSending(false);
    }
  };

  const handleRespondToRequest = async (friendshipId: string, accepted: boolean) => {
    try {
      setRespondingTo(friendshipId);
      await friendApi.respondRequest(friendshipId, accepted);
      setPendingRequests((requests) => requests.filter((r) => r.friendshipId !== friendshipId));
      if (accepted) {
        await handleRefresh();
      }
      setFeedback({
        type: 'success',
        message: accepted ? 'Friend request accepted.' : 'Friend request declined.',
      });
    } catch (err) {
      console.error('Failed to respond to friend request:', err);
      setFeedback({ type: 'error', message: 'Could not update this friend request.' });
    } finally {
      setRespondingTo(null);
    }
  };

  const handleInviteFriend = (friend: FriendInfo) => {
    if (!isConnected || !currentGameId) return;

    send('/app/game/invite', {
      friendUserId: friend.userId,
      gameId: currentGameId,
      roomCode: currentRoomCode,
    });

    setInvitedFriends((prev) => ({ ...prev, [friend.userId]: true }));
    setFeedback({ type: 'success', message: `Invite sent to ${friend.displayName}!` });
    setTimeout(() => {
      setInvitedFriends((prev) => ({ ...prev, [friend.userId]: false }));
    }, 5000);

    // Close sidebar on mobile after inviting
    if (onClose) {
      onClose();
    }
  };

  const getPresenceColor = (presence: string) => {
    switch (presence) {
      case 'ONLINE':
        return 'var(--status-online)';
      case 'IN_GAME':
        return 'var(--status-ingame)';
      case 'OFFLINE':
      default:
        return 'var(--status-offline)';
    }
  };

  const getPresenceLabel = (presence: string) => {
    switch (presence) {
      case 'ONLINE':
        return 'Online';
      case 'IN_GAME':
        return 'In Game';
      case 'OFFLINE':
      default:
        return 'Offline';
    }
  };

  return (
    <div className={`friends-sidebar-root ${isCollapsed ? 'collapsed' : ''}`}>
      {/* Header */}
      <div className="sidebar-header">
        <div className="header-title-group">
          <h2>Friends</h2>
          <span className="friends-count">({friends.length})</span>
        </div>

        <div className="sidebar-action-btns">
          <button
            className="icon-btn refresh-btn"
            onClick={handleRefresh}
            disabled={refreshing}
            title="Refresh friends"
          >
            {refreshing ? '⟳' : '↻'}
          </button>
          <button
            className="icon-btn add-btn"
            onClick={() => setShowAddFriend(!showAddFriend)}
            title="Add new friend"
          >
            +
          </button>
          {onToggleCollapse && (
            <button className="icon-btn collapse-toggle-btn" onClick={onToggleCollapse} title="Collapse sidebar">
              ◀
            </button>
          )}
          {onClose && (
            <button className="icon-btn close-btn" onClick={onClose} title="Close sidebar" aria-label="Close sidebar">
              ✕
            </button>
          )}
        </div>
      </div>

      {/* Add Friend Form */}
      {showAddFriend && (
        <div className="add-friend-card">
          <label className="input-label">Enter 8-character Friend Code:</label>
          <div className="add-friend-input-row">
            <input
              type="text"
              placeholder="e.g. 7A8B9C1D"
              value={friendCode}
              onChange={(e) => setFriendCode(e.target.value.toUpperCase())}
              maxLength={8}
              disabled={sending}
              autoFocus
            />
            <button className="submit-add-btn" onClick={handleAddFriend} disabled={sending || !friendCode.trim()}>
              {sending ? '...' : 'Add'}
            </button>
          </div>
        </div>
      )}

      {/* Feedback Toast */}
      {feedback && (
        <div className={`friend-feedback-banner ${feedback.type}`}>
          <span>{feedback.message}</span>
          <button onClick={() => setFeedback(null)}>×</button>
        </div>
      )}

      {/* Pending Requests */}
      {pendingRequests.length > 0 && (
        <div className="pending-requests-section">
          <h3>Pending Requests ({pendingRequests.length})</h3>
          {pendingRequests.map((req) => (
            <div key={req.friendshipId} className="pending-request-card">
              <div className="req-info">
                <span className="req-name">{req.fromDisplayName || 'New Player'}</span>
                {req.fromFriendCode && <span className="req-code">{req.fromFriendCode}</span>}
              </div>
              <div className="req-actions">
                <button
                  className="accept-btn"
                  onClick={() => handleRespondToRequest(req.friendshipId, true)}
                  disabled={respondingTo === req.friendshipId}
                >
                  ✓
                </button>
                <button
                  className="decline-btn"
                  onClick={() => handleRespondToRequest(req.friendshipId, false)}
                  disabled={respondingTo === req.friendshipId}
                >
                  ✕
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Friends List */}
      <div className="friends-list-container">
        {loading ? (
          <div className="loading-state">Loading friends...</div>
        ) : friends.length === 0 ? (
          <div className="empty-state">
            <p>No friends added yet.</p>
            <span className="empty-hint">Add friends using their 8-character Friend Code to invite them to games!</span>
          </div>
        ) : (
          friends.map((friend) => {
            const isOnline = friend.presence === 'ONLINE';
            const wasInvited = invitedFriends[friend.userId];

            return (
              <div key={friend.userId} className="friend-row-card">
                <div className="friend-info-left">
                  <div
                    className="presence-status-dot"
                    style={{ backgroundColor: getPresenceColor(friend.presence) }}
                    title={getPresenceLabel(friend.presence)}
                  />
                  <div className="name-and-code">
                    <span className="friend-display-name">{friend.displayName}</span>
                    <span className="friend-code-sub">{friend.friendCode}</span>
                  </div>
                </div>

                <div className="friend-row-right">
                  {/* Invite to Room button when in a game room */}
                  {currentGameId && isOnline && (
                    <button
                      className={`invite-friend-btn ${wasInvited ? 'invited' : ''}`}
                      onClick={() => handleInviteFriend(friend)}
                      disabled={wasInvited}
                    >
                      {wasInvited ? 'Sent' : 'Invite'}
                    </button>
                  )}
                  <span className="presence-text-tag">{getPresenceLabel(friend.presence)}</span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
