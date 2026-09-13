/**
 * Version comparison for backend-drift detection.
 * Compares dot-separated numeric versions ("2.0.14"); non-numeric segments
 * count as 0 so a malformed string never triggers a reload on its own.
 *
 * Returns 1 if a > b, -1 if a < b, 0 if equal.
 */
export function compareVersions(a: string, b: string): number {
  const parse = (v: string): number[] =>
    v.split('.').map((part) => {
      const n = parseInt(part, 10);
      return Number.isNaN(n) ? 0 : n;
    });

  const pa = parse(a);
  const pb = parse(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) return x > y ? 1 : -1;
  }
  return 0;
}
