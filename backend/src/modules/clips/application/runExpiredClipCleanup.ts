import type { VideoClipRow } from "../repository/videoClip.repository.js";
import { collectClipDeletionObjects } from "./collectClipDeletionObjects.js";
import { computeRetentionCutoff } from "./clipRetention.js";
import {
  deleteVideoClipById,
  listExpiredUploadedClips,
} from "../repository/videoClip.repository.js";
import { deleteS3Object } from "../../../shared/aws/s3VideoService.js";
import { env, hasAwsCredentials } from "../../../shared/config/env.js";

export type ExpiredClipCleanupOptions = {
  retentionDays?: number;
  batchSize?: number;
  dryRun?: boolean;
  now?: Date;
  logger?: Pick<Console, "info" | "warn" | "error">;
};

export type ExpiredClipCleanupResult = {
  scannedCount: number;
  completedCount: number;
  failedCount: number;
  durationMs: number;
  dryRun: boolean;
};

const DEFAULT_BATCH_SIZE = 50;

type Logger = Pick<Console, "info" | "warn" | "error">;

async function deleteClipObjects(
  clip: VideoClipRow,
  dryRun: boolean,
  log: Logger,
): Promise<{ deletedObjectCount: number; failed: boolean }> {
  const objects = collectClipDeletionObjects(clip);
  let deletedObjectCount = 0;
  let failed = false;

  for (const object of objects) {
    if (dryRun) {
      deletedObjectCount += 1;
      continue;
    }

    try {
      const result = await deleteS3Object(object.key);
      log.info(
        JSON.stringify({
          msg: "expired_clip_cleanup_s3_object_deleted",
          clipId: clip.id,
          objectType: object.objectType,
          result,
        }),
      );
      deletedObjectCount += 1;
    } catch (error) {
      failed = true;
      log.error(
        JSON.stringify({
          msg: "expired_clip_cleanup_item_failed",
          clipId: clip.id,
          phase: object.objectType,
          errorCode: error instanceof Error ? error.name : "s3_delete_failed",
        }),
      );
    }
  }

  return { deletedObjectCount, failed };
}

export async function runExpiredClipCleanup(
  options: ExpiredClipCleanupOptions = {},
): Promise<ExpiredClipCleanupResult> {
  const startedAt = Date.now();
  const log = options.logger ?? console;
  const retentionDays = options.retentionDays ?? env.clips.retentionDays;
  const batchSize = options.batchSize ?? DEFAULT_BATCH_SIZE;
  const dryRun = options.dryRun ?? false;
  const now = options.now ?? new Date();
  const cutoff = computeRetentionCutoff(now, retentionDays);

  let scannedCount = 0;
  let completedCount = 0;
  let failedCount = 0;
  let offset = 0;

  log.info(
    JSON.stringify({
      msg: "expired_clip_cleanup_started",
      retentionDays,
      cutoffTimestamp: cutoff.toISOString(),
      batchSize,
      dryRun,
    }),
  );

  if (!dryRun && !hasAwsCredentials()) {
    throw new Error("aws_credentials_missing");
  }

  while (true) {
    const batch = await listExpiredUploadedClips(cutoff, batchSize, dryRun ? offset : 0);
    if (batch.length === 0) break;

    log.info(
      JSON.stringify({
        msg: "expired_clip_cleanup_batch_loaded",
        count: batch.length,
        ...(dryRun ? { offset } : {}),
      }),
    );

    for (const clip of batch) {
      scannedCount += 1;

      log.info(
        JSON.stringify({
          msg: "expired_clip_cleanup_item_started",
          clipId: clip.id,
          uploadedAt: clip.uploadedAt.toISOString(),
        }),
      );

      const { deletedObjectCount, failed: s3Failed } = await deleteClipObjects(clip, dryRun, log);
      if (s3Failed) {
        failedCount += 1;
        continue;
      }

      if (dryRun) {
        completedCount += 1;
        log.info(
          JSON.stringify({
            msg: "expired_clip_cleanup_item_completed",
            clipId: clip.id,
            deletedObjectCount,
            dryRun: true,
          }),
        );
        continue;
      }

      try {
        const removed = await deleteVideoClipById(clip.id);
        if (!removed) {
          failedCount += 1;
          log.error(
            JSON.stringify({
              msg: "expired_clip_cleanup_item_failed",
              clipId: clip.id,
              phase: "database",
              errorCode: "clip_delete_failed",
            }),
          );
          continue;
        }

        completedCount += 1;
        log.info(
          JSON.stringify({
            msg: "expired_clip_cleanup_item_completed",
            clipId: clip.id,
            deletedObjectCount,
          }),
        );
      } catch (error) {
        failedCount += 1;
        log.error(
          JSON.stringify({
            msg: "expired_clip_cleanup_item_failed",
            clipId: clip.id,
            phase: "database",
            errorCode: error instanceof Error ? error.message : "database_delete_failed",
          }),
        );
      }
    }

    if (dryRun) {
      offset += batch.length;
    }
    if (batch.length < batchSize) break;
  }

  const durationMs = Date.now() - startedAt;
  log.info(
    JSON.stringify({
      msg: "expired_clip_cleanup_finished",
      scannedCount,
      completedCount,
      failedCount,
      durationMs,
      dryRun,
    }),
  );

  return {
    scannedCount,
    completedCount,
    failedCount,
    durationMs,
    dryRun,
  };
}
