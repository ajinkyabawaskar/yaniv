import { SCORE_LIMITS, DEFAULT_SCORE_LIMIT, isSupportedScoreLimit, canChooseScoreLimit } from './scoreLimits';

describe('the limits a table may be played to', () => {
  it('offers 100 and 200, in that order', () => {
    expect(SCORE_LIMITS).toEqual([100, 200]);
  });

  it('defaults to 100, matching a room created without a choice', () => {
    expect(DEFAULT_SCORE_LIMIT).toBe(100);
  });

  it('accepts only the offered limits', () => {
    expect(isSupportedScoreLimit(100)).toBe(true);
    expect(isSupportedScoreLimit(200)).toBe(true);
    expect(isSupportedScoreLimit(150)).toBe(false);
    expect(isSupportedScoreLimit(0)).toBe(false);
    expect(isSupportedScoreLimit(null)).toBe(false);
    expect(isSupportedScoreLimit(undefined)).toBe(false);
  });
});

describe('who may change the limit, and when', () => {
  it('lets the host choose while the table is still in the lobby', () => {
    expect(canChooseScoreLimit(true, 'LOBBY')).toBe(true);
  });

  it('does not offer the choice to a guest', () => {
    expect(canChooseScoreLimit(false, 'LOBBY')).toBe(false);
  });

  it('does not offer the choice once the game has been dealt', () => {
    expect(canChooseScoreLimit(true, 'WAIT_FOR_TURN')).toBe(false);
  });

  it('does not offer the choice between rounds, though the pre-start panel is on screen', () => {
    // ROUND_OVER and GAME_OVER both render the same panel as the waiting room, so
    // gating on "not started" would let the host move the finish line mid-game.
    expect(canChooseScoreLimit(true, 'ROUND_OVER')).toBe(false);
    expect(canChooseScoreLimit(true, 'GAME_OVER')).toBe(false);
  });

  it('does not offer the choice before the first state arrives', () => {
    expect(canChooseScoreLimit(true, null)).toBe(false);
  });
});
