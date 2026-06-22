import { describe, expect, it } from "vitest";
import { sanitizeDownloadFileName } from "./sanitizeDownloadFileName.js";

describe("sanitizeDownloadFileName", () => {
  it("keeps a safe filename", () => {
    expect(sanitizeDownloadFileName("my-clip.mp4")).toBe("my-clip.mp4");
  });

  it("strips path traversal", () => {
    expect(sanitizeDownloadFileName("../../etc/passwd")).toBe("passwd");
  });

  it("falls back for empty names", () => {
    expect(sanitizeDownloadFileName("")).toBe("clip.mp4");
  });
});
