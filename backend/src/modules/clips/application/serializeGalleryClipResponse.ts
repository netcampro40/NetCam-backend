import type { VideoClipRow } from "../repository/videoClip.repository.js";
import { createSignedThumbnailUrl } from "../../../shared/aws/s3VideoService.js";

function toIso(value: unknown): string {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toISOString();
  }
  if (typeof value === "string" && value.length > 0) {
    const d = new Date(value);
    if (!Number.isNaN(d.getTime())) return d.toISOString();
  }
  return new Date().toISOString();
}

export type GalleryClipResponse = {
  id: string;
  clipId: string;
  recordedAt: string;
  uploadedAt: string;
  durationSeconds: number | null;
  fileKey: string;
  fileUrl: string;
  sizeBytes: number;
  platform: string;
  thumbnailUrl: string | null;
};

export async function serializeGalleryClipResponse(row: VideoClipRow): Promise<GalleryClipResponse> {
  const thumbnailKey = row.thumbnailFileKey?.trim() ?? "";
  let thumbnailUrl: string | null = null;

  if (thumbnailKey) {
    thumbnailUrl = await createSignedThumbnailUrl(thumbnailKey);
  }

  return {
    id: row.id,
    clipId: row.id,
    recordedAt: toIso(row.recordedAt),
    uploadedAt: toIso(row.uploadedAt),
    durationSeconds: row.durationSeconds,
    fileKey: row.fileKey,
    fileUrl: row.fileUrl,
    sizeBytes: row.sizeBytes,
    platform: row.sourcePlatform,
    thumbnailUrl,
  };
}

export async function serializeGalleryClipsResponse(
  rows: VideoClipRow[],
): Promise<GalleryClipResponse[]> {
  return Promise.all(rows.map((row) => serializeGalleryClipResponse(row)));
}
