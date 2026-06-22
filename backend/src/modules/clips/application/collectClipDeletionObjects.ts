import type { VideoClipRow } from "../repository/videoClip.repository.js";

export type ClipObjectType = "original" | "preview" | "thumbnail";

export type ClipDeletionObject = {
  objectType: ClipObjectType;
  key: string;
};

/**
 * Resolve chaves S3 a apagar a partir do registro persistido.
 * Deduplica chaves idênticas (ex.: fileKey == originalFileKey).
 */
export function collectClipDeletionObjects(clip: VideoClipRow): ClipDeletionObject[] {
  const seen = new Set<string>();
  const objects: ClipDeletionObject[] = [];

  const add = (objectType: ClipObjectType, key: string | null | undefined) => {
    const normalized = key?.trim();
    if (!normalized || seen.has(normalized)) return;
    seen.add(normalized);
    objects.push({ objectType, key: normalized });
  };

  add("original", clip.originalFileKey);
  add("original", clip.fileKey);

  const originalKeys = new Set(
    [clip.originalFileKey, clip.fileKey].map((k) => k?.trim()).filter(Boolean) as string[],
  );

  const previewKey = clip.previewFileKey?.trim();
  if (previewKey && !originalKeys.has(previewKey)) {
    add("preview", previewKey);
  }

  add("thumbnail", clip.thumbnailFileKey);

  return objects;
}
