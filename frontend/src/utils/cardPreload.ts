/**
 * Card SVG Preload Utility
 * Preloads all 52 standard playing card SVGs into browser cache
 * so they render instantly during gameplay.
 *
 * A single preload entry point exists on purpose: MainView (the logged-in
 * shell) calls preloadAllCards once, so every path into a table — lobby, host,
 * deep-link /join — warms the same cache exactly one time. The login screen
 * does not preload, and no code injects per-card <link rel=preload> head tags
 * anymore (Image() preloads are a lower-priority, less backend-clogging signal).
 */
import { assetUrl } from './api';

// All 13 ranks × 4 suits = 52 cards
const RANKS = ['ace', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'jack', 'queen', 'king'] as const;
const SUITS = ['hearts', 'diamonds', 'clubs', 'spades'] as const;

/**
 * Generate all card image paths, release-pinned (?v=) so immutable caching
 * can never serve last-release's art after a version-drift reload.
 */
export function getAllCardPaths(): string[] {
  const paths: string[] = [];

  // Standard 52 cards
  for (const suit of SUITS) {
    for (const rank of RANKS) {
      paths.push(assetUrl(`/cards/${rank}_of_${suit}.svg`));
    }
  }

  return paths;
}

/**
 * Preload all card SVGs using the browser's native preload
 * Returns a promise that resolves when all preloads complete
 */
export function preloadAllCards(): Promise<void> {
  const paths = getAllCardPaths();

  // Use Image() constructor for preloading - more reliable than link[rel=preload]
  // NOTE: no per-card logging here — 52 console writes on the main thread
  // during mount stole frame budget from the first deal animation.
  const promises = paths.map((path) => {
    return new Promise<void>((resolve) => {
      const img = new Image();
      img.onload = () => {
        resolve();
      };
      img.onerror = () => {
        console.warn(`[CardPreload] Failed to load: ${path}`);
        resolve(); // Don't fail if one card fails
      };
      img.src = path;
    });
  });

  return Promise.all(promises).then(() => {});
}
