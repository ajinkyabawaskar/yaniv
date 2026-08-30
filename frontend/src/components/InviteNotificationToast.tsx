import React, { useEffect, useState } from 'react';
import { useStomp } from '../contexts/StompContext';
import { soundEngine } from '../utils/soundEngine';
import './InviteNotificationToast.css';

export interface InviteNotification {
  inviteId: string;
  hostDisplayName: string;
  roomCode: string;
  targetScore: number;
}

interface InviteNotificationToastProps {
  onAccept: (roomCode: string) => void;
}

export default function InviteNotificationToast({ onAccept }: InviteNotificationToastProps) {
  const { subscribe, send, isConnected } = useStomp();
  const [invites, setInvites] = useState<InviteNotification[]>([]);

  useEffect(() => {
    if (!isConnected) return;

    const subscription = subscribe('/user/queue/invites', (message) => {
      const data = JSON.parse(message.body);

      if (!data.type || data.type === 'INVITE') {
        setInvites((prev) => {
          // Avoid duplicate invite IDs
          if (prev.some((inv) => inv.inviteId === data.inviteId)) return prev;
          soundEngine.playYanivBell(); // Pleasant notification chime
          return [...prev, data];
        });
      }
    });

    return () => {
      subscription?.unsubscribe();
    };
  }, [isConnected]);

  const handleAccept = (invite: InviteNotification) => {
    send('/app/game/invite-respond', {
      inviteId: invite.inviteId,
      accepted: true,
    });
    setInvites((prev) => prev.filter((i) => i.inviteId !== invite.inviteId));
    onAccept(invite.roomCode);
  };

  const handleDecline = (invite: InviteNotification) => {
    send('/app/game/invite-respond', {
      inviteId: invite.inviteId,
      accepted: false,
    });
    setInvites((prev) => prev.filter((i) => i.inviteId !== invite.inviteId));
  };

  if (invites.length === 0) return null;

  return (
    <div className="invite-toasts-container">
      {invites.map((invite) => (
        <div key={invite.inviteId} className="invite-toast-card">
          <div className="toast-icon">♠</div>
          <div className="toast-body">
            <h4 className="toast-title">Game Invitation</h4>
            <p className="toast-text">
              <strong>{invite.hostDisplayName}</strong> invited you to play table <code>#{invite.roomCode}</code>
            </p>
          </div>
          <div className="toast-actions-row">
            <button className="accept-invite-btn" onClick={() => handleAccept(invite)}>
              Accept
            </button>
            <button className="decline-invite-btn" onClick={() => handleDecline(invite)}>
              Decline
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}
