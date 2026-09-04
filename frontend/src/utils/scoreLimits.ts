/**
 * What a table may be played to.
 *
 * Mirrors `ScoreLimits.java`, which is authoritative: the server refuses anything else,
 * whether it arrives from the create-room API or the host's picker. `ScoreLimitsContractTest`
 * fails if the two lists drift.
 *
 * 100 and 200 are not the same game at different lengths. Halving fires on exact multiples
 * of 50 and runs before the elimination test, so the limit itself is the one total you can
 * land on and survive — see docs/game-engine.md.
 */
export const SCORE_LIMITS = [100, 200] as const;

export type ScoreLimit = (typeof SCORE_LIMITS)[number];

/** What a room is played to when the host never chooses. */
export const DEFAULT_SCORE_LIMIT = 100;

export function isSupportedScoreLimit(value: number | null | undefined): boolean {
  return value != null && (SCORE_LIMITS as readonly number[]).includes(value);
}

/**
 * Whether the picker should be interactive for this viewer.
 *
 * Deliberately keyed on LOBBY rather than on "the game has not started": ROUND_OVER and
 * GAME_OVER render the same panel as the waiting room, and offering the control there
 * would invite the host to move the finish line with the game already under way. The
 * server rejects it regardless; this is about not offering it.
 */
export function canChooseScoreLimit(isHost: boolean, currentState: string | null | undefined): boolean {
  return isHost && currentState === 'LOBBY';
}
