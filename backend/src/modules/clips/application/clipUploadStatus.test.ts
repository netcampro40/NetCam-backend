import { describe, expect, it } from "vitest";
import {
  CLIP_UPLOAD_STATUS_DELETING,
  CLIP_UPLOAD_STATUS_UPLOADED,
  isClipAvailableToClient,
  isClipVisibleInGallery,
} from "./clipUploadStatus.js";

describe("clipUploadStatus", () => {
  it("treats only uploaded clips as gallery-visible", () => {
    expect(isClipVisibleInGallery(CLIP_UPLOAD_STATUS_UPLOADED)).toBe(true);
    expect(isClipVisibleInGallery(CLIP_UPLOAD_STATUS_DELETING)).toBe(false);
  });

  it("rejects deleting clips for client playback and download", () => {
    expect(isClipAvailableToClient({ uploadStatus: CLIP_UPLOAD_STATUS_UPLOADED })).toBe(true);
    expect(isClipAvailableToClient({ uploadStatus: CLIP_UPLOAD_STATUS_DELETING })).toBe(false);
    expect(isClipAvailableToClient(null)).toBe(false);
  });
});
