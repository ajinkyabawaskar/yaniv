import React, { useEffect, useState } from 'react';
import './CardFace.css';

const RANK_LABEL: Record<string, string> = {
  ACE: 'A',
  TWO: '2',
  THREE: '3',
  FOUR: '4',
  FIVE: '5',
  SIX: '6',
  SEVEN: '7',
  EIGHT: '8',
  NINE: '9',
  TEN: '10',
  JACK: 'J',
  QUEEN: 'Q',
  KING: 'K',
};

const SUIT_SYMBOL: Record<string, string> = {
  HEARTS: '♥',
  DIAMONDS: '♦',
  CLUBS: '♣',
  SPADES: '♠',
};

export const cardRankLabel = (rank: string): string => RANK_LABEL[rank] ?? rank;
export const cardSuitSymbol = (suit: string): string => SUIT_SYMBOL[suit] ?? '';

export const isRedSuit = (suit: string): boolean => suit === 'HEARTS' || suit === 'DIAMONDS';

interface CardFaceProps {
  rank: string;
  suit: string;
  src: string;
  alt: string;
  /** Class applied to the wrapper (carries the sizing, e.g. "card-img" contexts use fill). */
  className?: string;
  /** Class applied to the inner <img> (defaults to "card-img"). */
  imgClassName?: string;
  draggable?: boolean;
}

/**
 * A card face with a text fallback.
 *
 * Some devices fail to decode (or ever load) the SVG faces and leave a blank
 * white card. The fallback — rank + suit in the suit colour — paints underneath
 * the image, so it shows while the image is still loading AND if the image
 * errors out. Once the image loads it covers the fallback exactly as before.
 */
export default function CardFace({
  rank,
  suit,
  src,
  alt,
  className = '',
  imgClassName = 'card-img',
  draggable,
}: CardFaceProps) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  // A new src is a new load attempt: show the fallback until it resolves.
  useEffect(() => {
    setLoaded(false);
    setFailed(false);
  }, [src]);

  const showFallback = !loaded || failed;
  const color = isRedSuit(suit) ? '#dc2626' : '#1f2937';

  return (
    <span
      className={`card-face ${className}`}
      role="img"
      aria-label={alt}
      data-rank={rank}
      data-suit={suit}
    >
      {showFallback && (
        <span className="card-face-fallback" aria-hidden="true" style={{ color }}>
          <span className="card-face-corner card-face-corner-tl">
            <span className="card-face-rank">{cardRankLabel(rank)}</span>
            <span className="card-face-suit">{cardSuitSymbol(suit)}</span>
          </span>
          <span className="card-face-center">{cardSuitSymbol(suit)}</span>
          <span className="card-face-corner card-face-corner-br">
            <span className="card-face-rank">{cardRankLabel(rank)}</span>
            <span className="card-face-suit">{cardSuitSymbol(suit)}</span>
          </span>
        </span>
      )}
      {!failed && (
        <img
          src={src}
          alt={alt}
          className={`${imgClassName}${loaded ? '' : ' card-face-img-loading'}`}
          draggable={draggable}
          onLoad={() => setLoaded(true)}
          onError={() => setFailed(true)}
        />
      )}
    </span>
  );
}
