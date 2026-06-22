import { describe, expect, it } from "vitest";
import {
  computeRetentionCutoff,
  isClipExpiredByUploadedAt,
  DEFAULT_CLIP_RETENTION_DAYS,
} from "./clipRetention.js";

describe("clipRetention", () => {
  it("computes cutoff in UTC seven days before now", () => {
    const now = new Date("2026-06-19T12:00:00.000Z");
    const cutoff = computeRetentionCutoff(now, DEFAULT_CLIP_RETENTION_DAYS);
    expect(cutoff.toISOString()).toBe("2026-06-12T12:00:00.000Z");
  });

  it("does not expire clip uploaded within retention window", () => {
    const now = new Date("2026-06-19T12:00:00.000Z");
    const cutoff = computeRetentionCutoff(now, 7);
    const uploadedAt = new Date("2026-06-18T10:00:00.000Z");
    expect(isClipExpiredByUploadedAt(uploadedAt, cutoff)).toBe(false);
  });

  it("expires clip uploaded before cutoff even if recordedAt is older", () => {
    const now = new Date("2026-06-19T12:00:00.000Z");
    const cutoff = computeRetentionCutoff(now, 7);
    const uploadedAt = new Date("2026-06-10T12:00:00.000Z");
    expect(isClipExpiredByUploadedAt(uploadedAt, cutoff)).toBe(true);
  });

  it("does not expire clip uploaded exactly at cutoff boundary", () => {
    const now = new Date("2026-06-19T12:00:00.000Z");
    const cutoff = computeRetentionCutoff(now, 7);
    expect(isClipExpiredByUploadedAt(cutoff, cutoff)).toBe(false);
  });
});
