import { describe, expect, it, vi, beforeEach } from "vitest";
import type { VideoClipRow } from "../repository/videoClip.repository.js";

const { listExpiredUploadedClips, deleteVideoClipById, deleteS3Object } = vi.hoisted(() => ({
  listExpiredUploadedClips: vi.fn(),
  deleteVideoClipById: vi.fn(),
  deleteS3Object: vi.fn(),
}));

vi.mock("../repository/videoClip.repository.js", () => ({
  listExpiredUploadedClips,
  deleteVideoClipById,
}));

vi.mock("../../../shared/aws/s3VideoService.js", () => ({
  deleteS3Object,
}));

vi.mock("../../../shared/config/env.js", () => ({
  env: { clips: { retentionDays: 7 } },
  hasAwsCredentials: () => true,
}));

import { runExpiredClipCleanup } from "./runExpiredClipCleanup.js";

function makeRow(id: string, uploadedAt: string): VideoClipRow {
  return {
    id,
    clientId: "client-1",
    qrCodeId: "qr-1",
    arenaName: "Arena",
    kitLabel: "kit-1",
    fileKey: `clients/c1/2026-06-01/kit/${id}.mp4`,
    fileUrl: `s3://bucket/clients/c1/2026-06-01/kit/${id}.mp4`,
    originalFileKey: `clients/c1/2026-06-01/kit/${id}.mp4`,
    originalFileUrl: `s3://bucket/clients/c1/2026-06-01/kit/${id}.mp4`,
    previewFileKey: `clients/c1/2026-06-01/kit/${id}_preview.mp4`,
    previewFileUrl: `s3://bucket/clients/c1/2026-06-01/kit/${id}_preview.mp4`,
    thumbnailFileKey: null,
    thumbnailFileUrl: null,
    originalFilename: "clip.mp4",
    mimeType: "video/mp4",
    sizeBytes: 1000,
    durationSeconds: 10,
    sourcePlatform: "ios",
    localClipId: null,
    uploadStatus: "uploaded",
    recordedAt: new Date("2026-05-01T00:00:00.000Z"),
    createdAt: new Date(uploadedAt),
    uploadedAt: new Date(uploadedAt),
    expiresAt: null,
  };
}

describe("runExpiredClipCleanup", () => {
  beforeEach(() => {
    listExpiredUploadedClips.mockReset();
    deleteVideoClipById.mockReset();
    deleteS3Object.mockReset();
    deleteS3Object.mockResolvedValue("deleted");
    deleteVideoClipById.mockResolvedValue(true);
  });

  it("completes with zero clips", async () => {
    listExpiredUploadedClips.mockResolvedValueOnce([]);
    const result = await runExpiredClipCleanup({
      now: new Date("2026-06-19T12:00:00.000Z"),
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });
    expect(result.scannedCount).toBe(0);
    expect(result.completedCount).toBe(0);
    expect(result.failedCount).toBe(0);
  });

  it("dry-run does not delete S3 or database", async () => {
    listExpiredUploadedClips.mockResolvedValueOnce([makeRow("clip-a", "2026-06-01T00:00:00.000Z")]);
    listExpiredUploadedClips.mockResolvedValueOnce([]);

    const result = await runExpiredClipCleanup({
      dryRun: true,
      now: new Date("2026-06-19T12:00:00.000Z"),
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    expect(result.completedCount).toBe(1);
    expect(deleteS3Object).not.toHaveBeenCalled();
    expect(deleteVideoClipById).not.toHaveBeenCalled();
  });

  it("deletes S3 objects then database row", async () => {
    listExpiredUploadedClips.mockResolvedValueOnce([makeRow("clip-b", "2026-06-01T00:00:00.000Z")]);
    listExpiredUploadedClips.mockResolvedValueOnce([]);

    const result = await runExpiredClipCleanup({
      now: new Date("2026-06-19T12:00:00.000Z"),
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    expect(deleteS3Object).toHaveBeenCalled();
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-b");
    expect(result.completedCount).toBe(1);
  });

  it("continues batch when one clip fails S3 delete", async () => {
    listExpiredUploadedClips.mockResolvedValueOnce([
      makeRow("clip-fail", "2026-06-01T00:00:00.000Z"),
      makeRow("clip-ok", "2026-06-01T01:00:00.000Z"),
    ]);
    listExpiredUploadedClips.mockResolvedValueOnce([]);

    deleteS3Object.mockRejectedValueOnce(new Error("s3_down"));

    const result = await runExpiredClipCleanup({
      now: new Date("2026-06-19T12:00:00.000Z"),
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    expect(result.failedCount).toBe(1);
    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-ok");
    expect(deleteVideoClipById).not.toHaveBeenCalledWith("clip-fail");
  });

  it("treats already missing S3 object as success path when delete returns already_missing", async () => {
    listExpiredUploadedClips.mockResolvedValueOnce([makeRow("clip-missing", "2026-06-01T00:00:00.000Z")]);
    listExpiredUploadedClips.mockResolvedValueOnce([]);
    deleteS3Object.mockResolvedValue("already_missing");

    const result = await runExpiredClipCleanup({
      now: new Date("2026-06-19T12:00:00.000Z"),
      logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
    });

    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-missing");
  });
});
