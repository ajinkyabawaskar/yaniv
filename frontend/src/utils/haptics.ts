/**
 * Tactile Haptics Engine
 * Provides haptic vibrations on supported devices according to UI/UX spec Section 7.
 */

export const hapticLightTick = () => {
  if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
    try {
      navigator.vibrate(10);
    } catch (e) {}
  }
};

export const hapticFirmSnap = () => {
  if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
    try {
      navigator.vibrate(25);
    } catch (e) {}
  }
};

export const hapticDoubleError = () => {
  if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
    try {
      navigator.vibrate([30, 40, 30]);
    } catch (e) {}
  }
};

export default {
  hapticLightTick,
  hapticFirmSnap,
  hapticDoubleError,
};
