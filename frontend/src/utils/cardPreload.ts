/**
 * Card SVG Preload Utility
 * Preloads all 52 standard playing card SVGs into browser cache
 * so they render instantly during gameplay.
 */
import { useState, useCallback } from 'react';

// All 13 ranks × 4 suits = 52 cards
const RANKS = ['ace', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'jack', 'queen', 'king'] as const;
const SUITS = ['hearts', 'diamonds', 'clubs', 'spades'] as const;

/**
 * Generate all card image paths
 */
export function getAllCardPaths(): string[] {
  const paths: string[] = [];

  // Standard 52 cards
  for (const suit of SUITS) {
    for (const rank of RANKS) {
      paths.push(`/cards/${rank}_of_${suit}.svg`);
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

/**
 * Preload all card SVGs using link[rel=preload] in document head
 * Alternative method - adds preload hints to browser
 */
export function preloadCardsViaLink(): void {
  const paths = getAllCardPaths();

  paths.forEach((path) => {
    // Check if already preloaded
    const existing = document.querySelector(`link[rel="preload"][href="${path}"]`);
    if (existing) return;

    const link = document.createElement('link');
    link.rel = 'preload';
    link.as = 'image';
    link.type = 'image/svg+xml';
    link.href = path;
    document.head.appendChild(link);
  });
}

/**
 * Preload a specific subset of cards (e.g., only cards that might appear soon)
 * Useful for progressive preloading
 */
export function preloadCardSubset(cardIds: string[]): Promise<void> {
  const promises = cardIds.map((cardId) => {
    return new Promise<void>((resolve) => {
      const img = new Image();
      img.onload = () => resolve();
      img.onerror = () => resolve();
      img.src = cardId; // cardId should be full path like "/cards/ace_of_hearts.svg"
    });
  });

  return Promise.all(promises).then(() => {});
}

/**
 * React hook for preloading cards with loading state
 */
export function useCardPreload() {
  const [isPreloading, setIsPreloading] = useState(false);
  const [isPreloaded, setIsPreloaded] = useState(false);
  const [progress, setProgress] = useState(0);

  const preload = useCallback(async () => {
    if (isPreloaded || isPreloading) return;

    setIsPreloading(true);
    setProgress(0);

    const paths = getAllCardPaths();
    const total = paths.length;
    let loaded = 0;
    let lastReported = 0;

    // Progress is coarsened to 10% steps: the old code called setProgress
    // (=> a React render) on EVERY one of the 52 onloads, re-rendering the
    // mount tree 52x during the most jank-sensitive moment of app start.
    const bump = () => {
      loaded++;
      const pct = Math.round((loaded / total) * 100);
      if (pct - lastReported >= 10 || loaded === total) {
        lastReported = pct;
        setProgress(pct);
      }
    };

    await Promise.all(paths.map((path) => {
      return new Promise<void>((resolve) => {
        const img = new Image();
        img.onload = () => {
          bump();
          resolve();
        };
        img.onerror = () => {
          bump();
          resolve();
        };
        img.src = path;
      });
    }));

    setIsPreloading(false);
    setIsPreloaded(true);
    setProgress(100);
  }, [isPreloaded, isPreloading]);

  return { preload, isPreloading, isPreloaded, progress };
}