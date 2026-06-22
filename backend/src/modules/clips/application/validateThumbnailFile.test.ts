import { describe, expect, it } from "vitest";
import { validateThumbnailFile, THUMBNAIL_MAX_BYTES } from "./validateThumbnailFile.js";

const JPEG_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46]);

describe("validateThumbnailFile", () => {
  it("accepts valid JPEG", () => {
    const result = validateThumbnailFile({
      buffer: JPEG_BYTES,
      mimeType: "image/jpeg",
      filename: "thumb.jpg",
    });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.normalizedMime).toBe("image/jpeg");
    }
  });

  it("rejects PNG mime type", () => {
    const result = validateThumbnailFile({
      buffer: JPEG_BYTES,
      mimeType: "image/png",
      filename: "thumb.png",
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("unsupported_media_type");
    }
  });

  it("rejects invalid mime type", () => {
    const result = validateThumbnailFile({
      buffer: JPEG_BYTES,
      mimeType: "application/octet-stream",
      filename: "thumb.jpg",
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("unsupported_media_type");
    }
  });

  it("rejects file above limit", () => {
    const result = validateThumbnailFile({
      buffer: Buffer.alloc(THUMBNAIL_MAX_BYTES + 1, 0xff),
      mimeType: "image/jpeg",
      filename: "thumb.jpg",
      maxBytes: THUMBNAIL_MAX_BYTES,
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("file_too_large");
    }
  });

  it("rejects empty buffer", () => {
    const result = validateThumbnailFile({
      buffer: Buffer.alloc(0),
      mimeType: "image/jpeg",
      filename: "thumb.jpg",
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("thumbnail_required");
    }
  });

  it("rejects invalid extension when filename is provided", () => {
    const result = validateThumbnailFile({
      buffer: JPEG_BYTES,
      mimeType: "image/jpeg",
      filename: "thumb.png",
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("invalid_thumbnail_extension");
    }
  });

  it("rejects content without JPEG magic bytes", () => {
    const result = validateThumbnailFile({
      buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47]),
      mimeType: "image/jpeg",
      filename: "thumb.jpg",
    });
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errorCode).toBe("invalid_thumbnail_content");
    }
  });

  it("accepts image/jpeg with charset in mime type", () => {
    const result = validateThumbnailFile({
      buffer: JPEG_BYTES,
      mimeType: "image/jpeg; charset=binary",
      filename: "thumb.jpeg",
    });
    expect(result.ok).toBe(true);
  });
});
