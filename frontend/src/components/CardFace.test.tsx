/**
 * CardFace: rank + suit text fallback is painted underneath the SVG face —
 * visible while it loads or when it fails (blank-white-card devices), and
 * covered by paint order once the image decodes. No load-event gating: a
 * missed onLoad must never stick the fallback on while a freshly mounted
 * copy of the same card shows the real art.
 */
import React, { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import CardFace from './CardFace';

(global as any).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => {
    root.unmount();
  });
  container.remove();
});

const renderFace = (src = '/cards/5_of_hearts.svg') => {
  act(() => {
    root.render(
      <CardFace
        rank="FIVE"
        suit="HEARTS"
        src={src}
        alt="FIVE of HEARTS"
      />
    );
  });
};

test('fallback renders underneath the image immediately', () => {
  renderFace();
  // jsdom never loads images, so the fallback must be visible immediately —
  // alongside the image, which covers it by paint order once decoded.
  const fallback = container.querySelector('.card-face-fallback');
  expect(fallback).not.toBeNull();
  expect(fallback?.textContent).toContain('5');
  expect(fallback?.textContent).toContain('♥');
  // Hearts render red.
  expect((fallback as HTMLElement)?.style.color).toBe('rgb(220, 38, 38)');
  expect(container.querySelector('img[alt="FIVE of HEARTS"]')).not.toBeNull();
});

test('fallback stays as the underlay — no load-event gating', () => {
  renderFace();
  const img = container.querySelector('img[alt="FIVE of HEARTS"]') as HTMLImageElement;
  expect(img).not.toBeNull();
  act(() => {
    // A load event (or none at all — jsdom never fires one) changes nothing:
    // paint order covers the fallback, not JS state.
    img.dispatchEvent(new Event('load'));
  });
  expect(container.querySelector('.card-face-fallback')).not.toBeNull();
  expect(container.querySelector('img[alt="FIVE of HEARTS"]')).not.toBeNull();
});

test('fallback stays and image is removed when the image fails', () => {
  renderFace();
  const img = container.querySelector('img[alt="FIVE of HEARTS"]') as HTMLImageElement;
  act(() => {
    img.dispatchEvent(new Event('error'));
  });
  expect(container.querySelector('.card-face-fallback')).not.toBeNull();
  expect(container.querySelector('img')).toBeNull();
});

test('a new src clears a previous error so the new image can paint', () => {
  renderFace();
  const img = container.querySelector('img[alt="FIVE of HEARTS"]') as HTMLImageElement;
  act(() => {
    img.dispatchEvent(new Event('error'));
  });
  expect(container.querySelector('img')).toBeNull();
  renderFace('/cards/6_of_hearts.svg');
  expect(container.querySelector('img')).not.toBeNull();
  expect(container.querySelector('.card-face-fallback')).not.toBeNull();
});

test('black suits render dark, not red', () => {
  act(() => {
    root.render(
      <CardFace rank="KING" suit="SPADES" src="/cards/king_of_spades.svg" alt="KING of SPADES" />
    );
  });
  const fallback = container.querySelector('.card-face-fallback') as HTMLElement;
  expect(fallback?.textContent).toContain('K');
  expect(fallback?.textContent).toContain('♠');
  expect(fallback?.style.color).toBe('rgb(31, 41, 55)');
});
