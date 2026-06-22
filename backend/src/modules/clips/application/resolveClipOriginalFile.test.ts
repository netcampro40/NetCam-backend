import { describe, expect, it } from "vitest";
import { resolveClipOriginalFile } from "./resolveClipOriginalFile.js";
import type { VideoClipRow } from "../repository/videoClip.repository.js";

function makeRow(overrides: Partial<VideoClipRow> = {}): VideoClipRow {
  return {
    id: "clip-1",
    clientId: "client-1",
    qrCodeId: "qr-1",
    arenaName: "Arena",
    kitLabel: "kit-1",
    fileKey: "legacy.mp4",
    fileUrl: "s3://bucket/legacy.mp4",
    originalFileKey: "original.mp4",
    originalFileUrl: "s3://bucket/original.mp4",
    previewFileKey: "preview.mp4",
    previewFileUrl: "s3://bucket/preview.mp4",
    thumbnailFileKey: "thumb.jpg",
    thumbnailFileUrl: "s3://bucket/thumb.jpg",
    originalFilename: "clip.mp4",
    mimeType: "video/mp4",
    sizeBytes: 108_000_000,
    durationSeconds: 10,
    sourcePlatform: "ios",
    localClipId: null,
    uploadStatus: "uploaded",
    recordedAt: new Date(),
    createdAt: new Date(),
    uploadedAt: new Date(),
    expiresAt: null,
    ...overrides,
  };
}

describe("resolveClipOriginalFile", () => {
  it("returns originalFileKey when present", () => {
    const result = resolveClipOriginalFile(makeRow());
    expect(result).toEqual({ fileKey: "original.mp4" });
  });

  it("falls back to legacy fileKey when originalFileKey is missing", () => {
    const result = resolveClipOriginalFile(
      makeRow({ originalFileKey: null, originalFileUrl: null }),
    );
    expect(result).toEqual({ fileKey: "legacy.mp4" });
  });

  it("never uses preview as fallback", () => {
    const result = resolveClipOriginalFile(
      makeRow({
        originalFileKey: null,
        originalFileUrl: null,
        fileKey: "",
        fileUrl: "",
        previewFileKey: "preview-only.mp4",
      }),
    );
    expect(result).toBeNull();
  });

  it("returns null when no original is configured", () => {
    const result = resolveClipOriginalFile(
      makeRow({
        originalFileKey: null,
        originalFileUrl: null,
        fileKey: "   ",
        fileUrl: "",
      }),
    );
    expect(result).toBeNull();
  });
});
