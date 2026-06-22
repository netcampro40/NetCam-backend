import { describe, expect, it } from "vitest";
import { collectClipDeletionObjects } from "./collectClipDeletionObjects.js";
import type { VideoClipRow } from "../repository/videoClip.repository.js";

function makeRow(overrides: Partial<VideoClipRow> = {}): VideoClipRow {
  return {
    id: "clip-1",
    clientId: "client-1",
    qrCodeId: "qr-1",
    arenaName: "Arena",
    kitLabel: "kit-1",
    fileKey: "clients/c1/2026-06-01/kit/clip.mp4",
    fileUrl: "s3://bucket/clients/c1/2026-06-01/kit/clip.mp4",
    originalFileKey: "clients/c1/2026-06-01/kit/clip.mp4",
    originalFileUrl: "s3://bucket/clients/c1/2026-06-01/kit/clip.mp4",
    previewFileKey: "clients/c1/2026-06-01/kit/clip_preview.mp4",
    previewFileUrl: "s3://bucket/clients/c1/2026-06-01/kit/clip_preview.mp4",
    thumbnailFileKey: "clients/c1/2026-06-01/kit/clip_thumbnail.jpg",
    thumbnailFileUrl: "s3://bucket/clients/c1/2026-06-01/kit/clip_thumbnail.jpg",
    originalFilename: "clip.mp4",
    mimeType: "video/mp4",
    sizeBytes: 1000,
    durationSeconds: 10,
    sourcePlatform: "ios",
    localClipId: null,
    uploadStatus: "uploaded",
    recordedAt: new Date("2026-05-01T00:00:00.000Z"),
    createdAt: new Date("2026-06-01T00:00:00.000Z"),
    uploadedAt: new Date("2026-06-01T00:00:00.000Z"),
    expiresAt: null,
    ...overrides,
  };
}

describe("collectClipDeletionObjects", () => {
  it("collects original, preview and thumbnail keys", () => {
    const objects = collectClipDeletionObjects(makeRow());
    expect(objects.map((o) => o.objectType)).toEqual(["original", "preview", "thumbnail"]);
  });

  it("deduplicates when fileKey equals originalFileKey", () => {
    const objects = collectClipDeletionObjects(makeRow());
    const originalCount = objects.filter((o) => o.objectType === "original").length;
    expect(originalCount).toBe(1);
  });

  it("skips missing preview and thumbnail without blocking", () => {
    const objects = collectClipDeletionObjects(
      makeRow({ previewFileKey: null, previewFileUrl: null, thumbnailFileKey: null, thumbnailFileUrl: null }),
    );
    expect(objects).toEqual([
      { objectType: "original", key: "clients/c1/2026-06-01/kit/clip.mp4" },
    ]);
  });
});
