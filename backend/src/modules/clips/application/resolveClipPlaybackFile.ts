import type { VideoClipRow } from "../repository/videoClip.repository.js";

export type ClipPlaybackSource = "preview" | "original";

export type ClipPlaybackFile = {
  fileKey: string;
  source: ClipPlaybackSource;
};

/**
 * Escolhe a key S3 para reprodução:
 * previewFileKey → originalFileKey → fileKey (legado).
 */
export function resolveClipPlaybackFile(clip: VideoClipRow): ClipPlaybackFile | null {
  const previewKey = clip.previewFileKey?.trim();
  if (previewKey) {
    return { fileKey: previewKey, source: "preview" };
  }

  const originalKey = clip.originalFileKey?.trim();
  if (originalKey) {
    return { fileKey: originalKey, source: "original" };
  }

  const legacyKey = clip.fileKey?.trim();
  if (legacyKey) {
    return { fileKey: legacyKey, source: "original" };
  }

  return null;
}
