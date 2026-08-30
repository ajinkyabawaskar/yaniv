import React from 'react';
import './Avatar.css';

interface AvatarProps {
  src?: string;
  alt?: string;
  name?: string;
  presence?: 'ONLINE' | 'OFFLINE' | 'IN_GAME' | 'DISCONNECTED_IN_GAME';
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}

const SIZE_MAP = {
  xs: 24,
  sm: 32,
  md: 40,
  lg: 56,
  xl: 80,
};

const INDICATOR_SIZE_MAP = {
  xs: 8,
  sm: 10,
  md: 12,
  lg: 14,
  xl: 18,
};

export default function Avatar({
  src,
  alt,
  name,
  presence = 'OFFLINE',
  size = 'md',
  className = '',
}: AvatarProps) {
  const dimension = SIZE_MAP[size];
  const indicatorSize = INDICATOR_SIZE_MAP[size];

  const getInitials = (displayName: string) => {
    return displayName
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  const getPresenceColor = () => {
    switch (presence) {
      case 'ONLINE':
        return 'var(--status-online)';
      case 'IN_GAME':
        return 'var(--status-ingame)';
      case 'DISCONNECTED_IN_GAME':
        return 'var(--status-ingame)';
      case 'OFFLINE':
      default:
        return 'var(--status-offline)';
    }
  };

  const isOnline = presence === 'ONLINE' || presence === 'IN_GAME' || presence === 'DISCONNECTED_IN_GAME';

  return (
    <div
      className={`avatar-wrapper ${className}`}
      style={{ width: dimension, height: dimension, '--indicator-size': `${indicatorSize}px` } as React.CSSProperties}
    >
      {src ? (
        <img
          src={src}
          alt={alt || name || 'Avatar'}
          className="avatar-image"
          style={{ width: dimension, height: dimension } as React.CSSProperties}
        />
      ) : (
        <div
          className="avatar-placeholder"
          style={{
            width: dimension,
            height: dimension,
            fontSize: dimension * 0.4,
          } as React.CSSProperties}
        >
          {name ? getInitials(name) : '?'}
        </div>
      )}
      {presence !== 'OFFLINE' && (
        <span
          className="presence-indicator"
          style={{
            backgroundColor: getPresenceColor(),
            width: indicatorSize,
            height: indicatorSize,
          } as React.CSSProperties}
          title={presence === 'ONLINE' ? 'Online' : presence === 'IN_GAME' ? 'In Game' : 'Disconnected in Game'}
        />
      )}
    </div>
  );
}