import { describe, expect, it } from "vitest";
import { resolveClipPlaybackFile } from "./resolveClipPlaybackFile.js";
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
    sizeBytes: 1000,
    durationSeconds: 10,
    sourcePlatform: "android",
    localClipId: null,
    uploadStatus: "uploaded",
    recordedAt: new Date(),
    createdAt: new Date(),
    uploadedAt: new Date(),
    expiresAt: null,
    ...overrides,
  };
}

describe("resolveClipPlaybackFile", () => {
  it("still prefers preview for playback and ignores thumbnail", () => {
    const playback = resolveClipPlaybackFile(makeRow());
    expect(playback).toEqual({ fileKey: "preview.mp4", source: "preview" });
  });

  it("falls back to original when preview is missing", () => {
    const playback = resolveClipPlaybackFile(
      makeRow({ previewFileKey: null, previewFileUrl: null }),
    );
    expect(playback).toEqual({ fileKey: "original.mp4", source: "original" });
  });
});
