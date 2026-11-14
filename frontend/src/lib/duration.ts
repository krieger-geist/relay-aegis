/** A measured duration for people: "850 ms", "21 s", "3 min 20 s". Null means it was not measured, never zero. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return 'Not measured';
  if (ms < 1000) return `${ms} ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 120) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}
