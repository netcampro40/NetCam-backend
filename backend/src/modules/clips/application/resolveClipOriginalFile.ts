import type { VideoClipRow } from "../repository/videoClip.repository.js";

export type ClipOriginalFile = {
  fileKey: string;
};

/**
 * Resolve exclusivamente a key S3 do original (alta qualidade).
 * originalFileKey → fileKey (legado). Nunca usa preview nem thumbnail.
 */
export function resolveClipOriginalFile(clip: VideoClipRow): ClipOriginalFile | null {
  const originalKey = clip.originalFileKey?.trim();
  if (originalKey) {
    return { fileKey: originalKey };
  }

  const legacyKey = clip.fileKey?.trim();
  if (legacyKey) {
    return { fileKey: legacyKey };
  }

  return null;
}
