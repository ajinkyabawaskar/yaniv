import FingerprintJS from '@fingerprintjs/fingerprintjs';

/**
 * Generate a browser fingerprint using FingerprintJS library.
 * This uses a combination of browser/device characteristics for identification.
 */
export async function fingerprint(): Promise<string> {
  const fp = await FingerprintJS.load();
  const result = await fp.get();
  return result.visitorId;
}

/**
 * Get or create a persistent fingerprint in localStorage.
 */
export async function getPersistentFingerprint(): Promise<string> {
  const stored = localStorage.getItem('deviceFingerprint');
  if (stored) {
    return stored;
  }

  const newFingerprint = await fingerprint();
  localStorage.setItem('deviceFingerprint', newFingerprint);
  return newFingerprint;
}
