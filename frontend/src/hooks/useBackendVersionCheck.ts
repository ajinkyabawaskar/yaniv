import { useCallback, useEffect, useRef, useState } from 'react';
import { FRONTEND_VERSION, versionApi } from '../utils/api';
import { compareVersions } from '../utils/version';

const POLL_INTERVAL_MS = 60_000;
const SNOOZE_MS = 30 * 60_000;

/**
 * Detects backend drifted forwards past this build and asks for a reload.
 *
 * The entry point (index.html) is served `no-cache` so a reload always
 * revalidates it; hashed bundles are immutable; unhashed media is
 * release-pinned (?v=). Together that means `window.location.reload()`
 * is a complete cache bust onto the latest release.
 *
 * Checks on mount, every minute, on tab refocus and on reconnect. Silent on
 * network errors (a restarting server must not raise the banner). Skipped
 * entirely on dev builds, where REACT_APP_VERSION is unset.
 */
export function useBackendVersionCheck() {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [backendVersion, setBackendVersion] = useState<string | null>(null);
  const snoozedUntil = useRef(0);

  const check = useCallback(async () => {
    if (FRONTEND_VERSION === 'dev') return;
    if (Date.now() < snoozedUntil.current) return;
    try {
      const { version } = await versionApi.getVersion();
      if (typeof version === 'string' && compareVersions(version, FRONTEND_VERSION) > 0) {
        setBackendVersion(version);
        setUpdateAvailable(true);
      }
    } catch {
      // Offline or server mid-restart — try again on the next poll.
    }
  }, []);

  useEffect(() => {
    check();
    const id = setInterval(check, POLL_INTERVAL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') check();
    };
    const onOnline = () => check();
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', onOnline);
    };
  }, [check]);

  const snooze = useCallback(() => {
    snoozedUntil.current = Date.now() + SNOOZE_MS;
    setUpdateAvailable(false);
  }, []);

  return { updateAvailable, backendVersion, snooze };
}
