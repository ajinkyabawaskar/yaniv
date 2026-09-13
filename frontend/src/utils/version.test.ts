import { compareVersions } from './version';
import { assetUrl } from './api';

describe('compareVersions', () => {
  it('returns 0 for equal versions', () => {
    expect(compareVersions('2.0.14', '2.0.14')).toBe(0);
  });

  it('detects a newer backend patch/minor/major', () => {
    expect(compareVersions('2.0.15', '2.0.14')).toBe(1);
    expect(compareVersions('2.1.0', '2.0.14')).toBe(1);
    expect(compareVersions('3.0.0', '2.0.14')).toBe(1);
  });

  it('detects an older backend', () => {
    expect(compareVersions('2.0.13', '2.0.14')).toBe(-1);
    expect(compareVersions('1.2.1', '2.0.14')).toBe(-1);
  });

  it('treats missing segments as zero', () => {
    expect(compareVersions('2.0', '2.0.0')).toBe(0);
    expect(compareVersions('2', '2.0.0')).toBe(0);
  });

  it('treats non-numeric segments as zero instead of reloading', () => {
    expect(compareVersions('dev', '2.0.14')).toBe(-1);
    expect(compareVersions('2.0.x', '2.0.0')).toBe(0);
  });
});

describe('assetUrl', () => {
  it('returns the path unchanged for dev builds', () => {
    expect(assetUrl('/cards/ace_of_hearts.svg', 'dev')).toBe('/cards/ace_of_hearts.svg');
  });

  it('pins the path to the release version', () => {
    expect(assetUrl('/cards/ace_of_hearts.svg', '2.0.14')).toBe('/cards/ace_of_hearts.svg?v=2.0.14');
  });

  it('appends with & when a query string already exists', () => {
    expect(assetUrl('/cards/ace_of_hearts.svg?v=1', '2.0.14')).toBe('/cards/ace_of_hearts.svg?v=1&v=2.0.14');
  });
});
