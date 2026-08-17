/**
 * Card SVG Preload Utility
 * Preloads all 52 standard playing card SVGs into browser cache
 * so they render instantly during gameplay.
 */

// All 13 ranks × 4 suits = 52 cards
const RANKS = ['ace', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'jack', 'queen', 'king'] as const;
const SUITS = ['hearts', 'diamonds', 'clubs', 'spades'] as const;

// Optional: include jokers if your game uses them
const JOKERS = ['black_joker', 'red_joker'] as const;

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

  // Jokers (if used)
  for (const joker of JOKERS) {
    paths.push(`/cards/${joker}.svg`);
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
  const promises = paths.map((path) => {
    return new Promise<void>((resolve) => {
      const img = new Image();
      img.onload = () => resolve();
      img.onerror = () => resolve(); // Don't fail if one card fails
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
import { useEffect, useState, useCallback } from 'react';

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

    await Promise.all(paths.map((path) => {
      return new Promise<void>((resolve) => {
        const img = new Image();
        img.onload = () => {
          loaded++;
          setProgress(Math.round((loaded / total) * 100));
          resolve();
        };
        img.onerror = () => {
          loaded++;
          setProgress(Math.round((loaded / total) * 100));
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