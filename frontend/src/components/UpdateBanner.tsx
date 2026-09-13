import React, { useEffect, useState } from 'react';

const COUNTDOWN_SECONDS = 20;

interface UpdateBannerProps {
  backendVersion: string | null;
  onLater: () => void;
}

/**
 * New-release banner: counts down to an automatic reload (which revalidates
 * the no-cache entry point and lands on the latest immutable bundles),
 * with Update now / Later escape hatches.
 */
export default function UpdateBanner({ backendVersion, onLater }: UpdateBannerProps) {
  const [secondsLeft, setSecondsLeft] = useState(COUNTDOWN_SECONDS);

  useEffect(() => {
    if (secondsLeft <= 0) {
      window.location.reload();
      return;
    }
    const id = setTimeout(() => setSecondsLeft((s) => s - 1), 1000);
    return () => clearTimeout(id);
  }, [secondsLeft]);

  const reloadNow = () => window.location.reload();

  return (
    <div
      role="alert"
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        zIndex: 9999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '12px',
        padding: '10px 16px',
        background: '#1d4ed8',
        color: '#fff',
        fontSize: '14px',
        boxShadow: '0 2px 8px rgba(0,0,0,0.35)',
      }}
    >
      <span>
        A new version{backendVersion ? ` (v${backendVersion})` : ''} is available — updating in{' '}
        {secondsLeft}s.
      </span>
      <button
        onClick={reloadNow}
        style={{
          padding: '4px 14px',
          borderRadius: '6px',
          border: 'none',
          background: '#fff',
          color: '#1d4ed8',
          fontWeight: 700,
          cursor: 'pointer',
        }}
      >
        Update now
      </button>
      <button
        onClick={onLater}
        style={{
          padding: '4px 14px',
          borderRadius: '6px',
          border: '1px solid rgba(255,255,255,0.6)',
          background: 'transparent',
          color: '#fff',
          cursor: 'pointer',
        }}
      >
        Later
      </button>
    </div>
  );
}
