import { describe, expect, it, vi, beforeEach } from "vitest";
import type { VideoClipRow } from "../repository/videoClip.repository.js";
import { CLIP_UPLOAD_STATUS_DELETING, CLIP_UPLOAD_STATUS_UPLOADED } from "./clipUploadStatus.js";

const { listClipsForRetentionCleanup, claimClipForDeletion, deleteVideoClipById, deleteS3Object } =
  vi.hoisted(() => ({
    listClipsForRetentionCleanup: vi.fn(),
    claimClipForDeletion: vi.fn(),
    deleteVideoClipById: vi.fn(),
    deleteS3Object: vi.fn(),
  }));

vi.mock("../repository/videoClip.repository.js", () => ({
  listClipsForRetentionCleanup,
  claimClipForDeletion,
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

function makeRow(
  id: string,
  uploadedAt: string,
  uploadStatus: string = CLIP_UPLOAD_STATUS_UPLOADED,
): VideoClipRow {
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
    uploadStatus,
    recordedAt: new Date("2026-05-01T00:00:00.000Z"),
    createdAt: new Date(uploadedAt),
    uploadedAt: new Date(uploadedAt),
    expiresAt: null,
  };
}

const silentLogger = { info: vi.fn(), warn: vi.fn(), error: vi.fn() };
const now = new Date("2026-06-19T12:00:00.000Z");

describe("runExpiredClipCleanup", () => {
  beforeEach(() => {
    listClipsForRetentionCleanup.mockReset();
    claimClipForDeletion.mockReset();
    deleteVideoClipById.mockReset();
    deleteS3Object.mockReset();
    deleteS3Object.mockResolvedValue("deleted");
    deleteVideoClipById.mockResolvedValue(true);
    claimClipForDeletion.mockResolvedValue(true);
  });

  it("completes with zero clips", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([]);
    const result = await runExpiredClipCleanup({ now, logger: silentLogger });
    expect(result.scannedCount).toBe(0);
    expect(result.completedCount).toBe(0);
    expect(result.failedCount).toBe(0);
  });

  it("dry-run does not claim, delete S3 or database", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([makeRow("clip-a", "2026-06-01T00:00:00.000Z")]);
    listClipsForRetentionCleanup.mockResolvedValueOnce([]);

    const result = await runExpiredClipCleanup({ dryRun: true, now, logger: silentLogger });

    expect(result.completedCount).toBe(1);
    expect(claimClipForDeletion).not.toHaveBeenCalled();
    expect(deleteS3Object).not.toHaveBeenCalled();
    expect(deleteVideoClipById).not.toHaveBeenCalled();
  });

  it("claims uploaded clip before S3 delete", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([makeRow("clip-b", "2026-06-01T00:00:00.000Z")]);

    await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(claimClipForDeletion).toHaveBeenCalledWith("clip-b");
    expect(deleteS3Object).toHaveBeenCalled();
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-b");
    expect(claimClipForDeletion.mock.invocationCallOrder[0]).toBeLessThan(
      deleteS3Object.mock.invocationCallOrder[0]!,
    );
  });

  it("skips uploaded clip when atomic claim fails", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([makeRow("clip-race", "2026-06-01T00:00:00.000Z")]);
    claimClipForDeletion.mockResolvedValueOnce(false);

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.scannedCount).toBe(1);
    expect(result.completedCount).toBe(0);
    expect(deleteS3Object).not.toHaveBeenCalled();
    expect(deleteVideoClipById).not.toHaveBeenCalled();
  });

  it("resumes deleting clip without claim", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([
      makeRow("clip-resume", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING),
    ]);

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(claimClipForDeletion).not.toHaveBeenCalled();
    expect(deleteS3Object).toHaveBeenCalled();
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-resume");
    expect(result.completedCount).toBe(1);
  });

  it("keeps deleting status when S3 delete fails after partial success", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([makeRow("clip-fail", "2026-06-01T00:00:00.000Z")]);
    deleteS3Object.mockRejectedValueOnce(new Error("s3_down"));

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(claimClipForDeletion).toHaveBeenCalledWith("clip-fail");
    expect(deleteVideoClipById).not.toHaveBeenCalled();
    expect(result.failedCount).toBe(1);
  });

  it("continues batch when one clip fails S3 delete", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([
      makeRow("clip-fail", "2026-06-01T00:00:00.000Z"),
      makeRow("clip-ok", "2026-06-01T01:00:00.000Z"),
    ]);

    deleteS3Object.mockRejectedValueOnce(new Error("s3_down"));

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.failedCount).toBe(1);
    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-ok");
    expect(deleteVideoClipById).not.toHaveBeenCalledWith("clip-fail");
  });

  it("retries hard delete on next run for deleting clip", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([
      makeRow("clip-db-fail", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING),
    ]);
    deleteVideoClipById.mockResolvedValueOnce(false);

    const first = await runExpiredClipCleanup({ now, logger: silentLogger });
    expect(first.failedCount).toBe(1);

    listClipsForRetentionCleanup.mockResolvedValueOnce([
      makeRow("clip-db-fail", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING),
    ]);
    deleteVideoClipById.mockResolvedValueOnce(true);

    const second = await runExpiredClipCleanup({ now, logger: silentLogger });
    expect(second.completedCount).toBe(1);
    expect(claimClipForDeletion).not.toHaveBeenCalled();
  });

  it("treats already missing S3 object as success path", async () => {
    listClipsForRetentionCleanup.mockResolvedValueOnce([
      makeRow("clip-missing", "2026-06-01T00:00:00.000Z"),
    ]);
    deleteS3Object.mockResolvedValue("already_missing");

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-missing");
  });
});
