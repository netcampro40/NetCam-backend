import { describe, expect, it, vi, beforeEach } from "vitest";
import type { VideoClipRow } from "../repository/videoClip.repository.js";
import type { RetentionCleanupCursor } from "../repository/videoClip.repository.js";
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

function sortForRetention(clips: VideoClipRow[]): VideoClipRow[] {
  return [...clips].sort((a, b) => {
    const byUploadedAt = a.uploadedAt.getTime() - b.uploadedAt.getTime();
    if (byUploadedAt !== 0) return byUploadedAt;
    return a.id.localeCompare(b.id);
  });
}

function pageAfterCursor(
  allClips: VideoClipRow[],
  cursor: RetentionCleanupCursor,
  limit: number,
): VideoClipRow[] {
  const sorted = sortForRetention(allClips);
  const remaining =
    cursor === null
      ? sorted
      : sorted.filter(
          (clip) =>
            clip.uploadedAt.getTime() > cursor.uploadedAt.getTime() ||
            (clip.uploadedAt.getTime() === cursor.uploadedAt.getTime() && clip.id > cursor.id),
        );
  return remaining.slice(0, limit);
}

function mockCursorPagination(allClips: VideoClipRow[], batchSize = 50) {
  listClipsForRetentionCleanup.mockImplementation(
    async (_cutoff: Date, limit: number, cursor: RetentionCleanupCursor) =>
      pageAfterCursor(allClips, cursor, limit ?? batchSize),
  );
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

  it("dry-run visits each candidate once and reports total count", async () => {
    const candidates = [
      makeRow("clip-a", "2026-06-01T00:00:00.000Z"),
      makeRow("clip-b", "2026-06-02T00:00:00.000Z"),
      makeRow("clip-c", "2026-06-03T00:00:00.000Z"),
    ];
    mockCursorPagination(candidates, 2);

    const result = await runExpiredClipCleanup({ dryRun: true, now, batchSize: 2, logger: silentLogger });

    expect(result.scannedCount).toBe(3);
    expect(result.completedCount).toBe(3);
    expect(claimClipForDeletion).not.toHaveBeenCalled();
    expect(deleteS3Object).not.toHaveBeenCalled();
    expect(deleteVideoClipById).not.toHaveBeenCalled();
    expect(listClipsForRetentionCleanup).toHaveBeenCalledTimes(2);
  });

  it("claims uploaded clip before S3 delete", async () => {
    mockCursorPagination([makeRow("clip-b", "2026-06-01T00:00:00.000Z")]);

    await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(claimClipForDeletion).toHaveBeenCalledWith("clip-b");
    expect(deleteS3Object).toHaveBeenCalled();
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-b");
    expect(claimClipForDeletion.mock.invocationCallOrder[0]).toBeLessThan(
      deleteS3Object.mock.invocationCallOrder[0]!,
    );
  });

  it("skips uploaded clip when atomic claim fails but advances cursor", async () => {
    mockCursorPagination([
      makeRow("clip-race", "2026-06-01T00:00:00.000Z"),
      makeRow("clip-next", "2026-06-02T00:00:00.000Z"),
    ]);
    claimClipForDeletion.mockImplementation(async (id: string) => id !== "clip-race");

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.scannedCount).toBe(2);
    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-next");
    expect(deleteVideoClipById).not.toHaveBeenCalledWith("clip-race");
  });

  it("attempts a failing deleting clip only once per execution", async () => {
    mockCursorPagination([
      makeRow("clip-fail", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING),
    ]);
    deleteS3Object.mockRejectedValue(new Error("s3_down"));

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.scannedCount).toBe(1);
    expect(result.failedCount).toBe(1);
    expect(deleteVideoClipById).not.toHaveBeenCalled();
    expect(listClipsForRetentionCleanup).toHaveBeenCalledTimes(1);
  });

  it("processes the next candidate after a deleting clip fails", async () => {
    mockCursorPagination([
      makeRow("clip-fail", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING),
      makeRow("clip-ok", "2026-06-02T00:00:00.000Z"),
    ]);
    deleteS3Object.mockImplementation(async (key: string) => {
      if (key.includes("clip-fail")) throw new Error("s3_down");
      return "deleted";
    });

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.failedCount).toBe(1);
    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-ok");
    expect(deleteVideoClipById).not.toHaveBeenCalledWith("clip-fail");
  });

  it("terminates when fifty deleting clips fail and still reaches later candidates", async () => {
    const failingDeleting = Array.from({ length: 50 }, (_, index) =>
      makeRow(
        `clip-fail-${String(index).padStart(2, "0")}`,
        `2026-06-01T00:00:${String(index).padStart(2, "0")}.000Z`,
        CLIP_UPLOAD_STATUS_DELETING,
      ),
    );
    const behind = makeRow("clip-behind", "2026-06-10T00:00:00.000Z");
    mockCursorPagination([...failingDeleting, behind], 50);
    deleteS3Object.mockRejectedValue(new Error("s3_down"));

    const result = await runExpiredClipCleanup({ now, batchSize: 50, logger: silentLogger });

    expect(result.scannedCount).toBe(51);
    expect(result.failedCount).toBe(51);
    expect(result.completedCount).toBe(0);
    expect(deleteVideoClipById).not.toHaveBeenCalled();
    expect(listClipsForRetentionCleanup).toHaveBeenCalledTimes(2);
  });

  it("does not starve candidates behind a full batch of failures", async () => {
    const failingDeleting = Array.from({ length: 50 }, (_, index) =>
      makeRow(
        `clip-fail-${String(index).padStart(2, "0")}`,
        `2026-06-01T00:00:${String(index).padStart(2, "0")}.000Z`,
        CLIP_UPLOAD_STATUS_DELETING,
      ),
    );
    const behind = makeRow("clip-behind", "2026-06-10T00:00:00.000Z");
    mockCursorPagination([...failingDeleting, behind], 50);
    deleteS3Object.mockImplementation(async (key: string) => {
      if (key.includes("clip-behind")) return "deleted";
      throw new Error("s3_down");
    });

    const result = await runExpiredClipCleanup({ now, batchSize: 50, logger: silentLogger });

    expect(result.scannedCount).toBe(51);
    expect(result.failedCount).toBe(50);
    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-behind");
  });

  it("retries the same deleting clip on a new execution", async () => {
    const clip = makeRow("clip-db-fail", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING);

    mockCursorPagination([clip]);
    deleteVideoClipById.mockResolvedValueOnce(false);
    const first = await runExpiredClipCleanup({ now, logger: silentLogger });
    expect(first.failedCount).toBe(1);

    mockCursorPagination([clip]);
    deleteVideoClipById.mockResolvedValueOnce(true);
    const second = await runExpiredClipCleanup({ now, logger: silentLogger });
    expect(second.completedCount).toBe(1);
    expect(claimClipForDeletion).not.toHaveBeenCalled();
  });

  it("uses id as tie-breaker when uploaded_at is equal", async () => {
    const sameTimestamp = "2026-06-01T12:00:00.000Z";
    mockCursorPagination([
      makeRow("clip-aaa", sameTimestamp),
      makeRow("clip-bbb", sameTimestamp),
      makeRow("clip-ccc", sameTimestamp),
    ]);

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.scannedCount).toBe(3);
    expect(deleteVideoClipById).toHaveBeenCalledTimes(3);
    const processedIds = deleteVideoClipById.mock.calls.map(([id]) => id);
    expect(processedIds).toEqual(["clip-aaa", "clip-bbb", "clip-ccc"]);
  });

  it("does not process the same clip twice in one run", async () => {
    const clip = makeRow("clip-once", "2026-06-01T00:00:00.000Z", CLIP_UPLOAD_STATUS_DELETING);
    mockCursorPagination([clip]);
    deleteVideoClipById.mockResolvedValue(false);

    await runExpiredClipCleanup({ now, logger: silentLogger });

    const startedLogs = silentLogger.info.mock.calls
      .map(([payload]) => JSON.parse(String(payload)))
      .filter((entry) => entry.msg === "expired_clip_cleanup_item_started" && entry.clipId === "clip-once");
    expect(startedLogs).toHaveLength(1);
  });

  it("treats already missing S3 object as success path", async () => {
    mockCursorPagination([makeRow("clip-missing", "2026-06-01T00:00:00.000Z")]);
    deleteS3Object.mockResolvedValue("already_missing");

    const result = await runExpiredClipCleanup({ now, logger: silentLogger });

    expect(result.completedCount).toBe(1);
    expect(deleteVideoClipById).toHaveBeenCalledWith("clip-missing");
  });
});
