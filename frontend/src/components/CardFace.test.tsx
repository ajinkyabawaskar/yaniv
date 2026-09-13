/**
 * CardFace: rank + suit text fallback shows while the SVG is loading or when
 * it fails (blank-white-card devices), and hides once the image loads.
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

const renderFace = () => {
  act(() => {
    root.render(
      <CardFace
        rank="FIVE"
        suit="HEARTS"
        src="/cards/5_of_hearts.svg"
        alt="FIVE of HEARTS"
      />
    );
  });
};

test('fallback shows rank and suit before the image loads', () => {
  renderFace();
  // jsdom never loads images, so the fallback must be visible immediately.
  const fallback = container.querySelector('.card-face-fallback');
  expect(fallback).not.toBeNull();
  expect(fallback?.textContent).toContain('5');
  expect(fallback?.textContent).toContain('♥');
  // Hearts render red.
  expect((fallback as HTMLElement)?.style.color).toBe('rgb(220, 38, 38)');
  expect(container.querySelector('img[alt="FIVE of HEARTS"]')).not.toBeNull();
});

test('fallback hides once the image loads', () => {
  renderFace();
  const img = container.querySelector('img[alt="FIVE of HEARTS"]') as HTMLImageElement;
  expect(img).not.toBeNull();
  act(() => {
    img.dispatchEvent(new Event('load'));
  });
  expect(container.querySelector('.card-face-fallback')).toBeNull();
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
