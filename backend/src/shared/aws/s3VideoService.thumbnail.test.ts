import { describe, expect, it } from "vitest";
import { buildClipThumbnailS3Key } from "./s3VideoService.js";

describe("buildClipThumbnailS3Key", () => {
  it("follows the same folder pattern as preview and original", () => {
    const recordedAt = new Date("2026-06-19T14:30:00.000Z");
    const key = buildClipThumbnailS3Key(
      "client-uuid",
      recordedAt,
      "kit-1",
      "clip-uuid",
    );
    expect(key).toBe("clients/client-uuid/2026-06-19/kit-1/clip-uuid_thumbnail.jpg");
  });
});
