import { describe, expect, it, vi, beforeEach } from "vitest";
import type { VideoClipRow } from "../repository/videoClip.repository.js";

vi.mock("../../../shared/aws/s3VideoService.js", () => ({
  createSignedThumbnailUrl: vi.fn(async (key: string) => `https://signed.example/${encodeURIComponent(key)}`),
}));

import { serializeGalleryClipResponse } from "./serializeGalleryClipResponse.js";
import { createSignedThumbnailUrl } from "../../../shared/aws/s3VideoService.js";

function makeRow(overrides: Partial<VideoClipRow> = {}): VideoClipRow {
  return {
    id: "53627ea5-642d-41aa-8866-351fcac2eb7e",
    clientId: "client-1",
    qrCodeId: "qr-1",
    arenaName: "Arena",
    kitLabel: "kit-1",
    fileKey: "clients/client-1/2026-06-19/kit-1/clip.mp4",
    fileUrl: "s3://bucket/clients/client-1/2026-06-19/kit-1/clip.mp4",
    originalFileKey: "clients/client-1/2026-06-19/kit-1/clip.mp4",
    originalFileUrl: "s3://bucket/clients/client-1/2026-06-19/kit-1/clip.mp4",
    previewFileKey: "clients/client-1/2026-06-19/kit-1/clip_preview.mp4",
    previewFileUrl: "s3://bucket/clients/client-1/2026-06-19/kit-1/clip_preview.mp4",
    thumbnailFileKey: null,
    thumbnailFileUrl: null,
    originalFilename: "clip.mp4",
    mimeType: "video/mp4",
    sizeBytes: 1000,
    durationSeconds: 10,
    sourcePlatform: "android",
    localClipId: "local-1",
    uploadStatus: "uploaded",
    recordedAt: new Date("2026-06-19T12:00:00.000Z"),
    createdAt: new Date("2026-06-19T12:01:00.000Z"),
    expiresAt: null,
    uploadedAt: new Date("2026-06-19T12:01:00.000Z"),
    ...overrides,
  };
}

describe("serializeGalleryClipResponse", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns thumbnailUrl when thumbnailFileKey exists", async () => {
    const row = makeRow({
      thumbnailFileKey: "clients/client-1/2026-06-19/kit-1/clip_thumbnail.jpg",
      thumbnailFileUrl: "s3://bucket/clients/client-1/2026-06-19/kit-1/clip_thumbnail.jpg",
    });

    const response = await serializeGalleryClipResponse(row);

    expect(response.thumbnailUrl).toContain("https://signed.example/");
    expect(createSignedThumbnailUrl).toHaveBeenCalledWith(row.thumbnailFileKey);
    expect(response.clipId).toBe(row.id);
  });

  it("returns null thumbnailUrl for clips without thumbnail", async () => {
    const response = await serializeGalleryClipResponse(makeRow());

    expect(response.thumbnailUrl).toBeNull();
    expect(createSignedThumbnailUrl).not.toHaveBeenCalled();
  });

  it("does not expose internal s3 uri as thumbnailUrl", async () => {
    const response = await serializeGalleryClipResponse(
      makeRow({
        thumbnailFileKey: "clients/client-1/2026-06-19/kit-1/clip_thumbnail.jpg",
        thumbnailFileUrl: "s3://bucket/clients/client-1/2026-06-19/kit-1/clip_thumbnail.jpg",
      }),
    );

    expect(response.thumbnailUrl).not.toContain("s3://");
  });
});
