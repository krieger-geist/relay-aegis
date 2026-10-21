import type { ResolutionProgress } from '@/types/domain';

/**
 * Explains why an open incident is still open although its application may already look healthy:
 * it closes only after the configured number of consecutive healthy checks recorded after detection.
 */
export function resolutionNotice(progress: ResolutionProgress | undefined): string | null {
  if (!progress || !progress.open || progress.latestOutcome !== 'healthy' || progress.consecutiveHealthyChecks < 1) return null;
  const { consecutiveHealthyChecks: seen, requiredHealthyChecks: required } = progress;
  if (seen >= required) return `The application passed ${required} consecutive health checks; this incident is closing as observed recovered.`;
  return `The application is responding again: healthy in ${seen} of ${required} consecutive checks since this incident opened. `
    + `It closes automatically after ${required}. Checks run about once a minute, or use Run live check on the application. `
    + 'A failed check before then restarts the count.';
}
