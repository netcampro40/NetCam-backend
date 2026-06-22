export const DEFAULT_CLIP_RETENTION_DAYS = 7;

/** Cutoff UTC: clipes com uploadedAt estritamente anterior a este instante expiram. */
export function computeRetentionCutoff(
  now: Date,
  retentionDays: number = DEFAULT_CLIP_RETENTION_DAYS,
): Date {
  const cutoff = new Date(now.getTime());
  cutoff.setUTCDate(cutoff.getUTCDate() - retentionDays);
  return cutoff;
}

export function isClipExpiredByUploadedAt(
  uploadedAt: Date,
  cutoff: Date,
): boolean {
  return uploadedAt.getTime() < cutoff.getTime();
}
