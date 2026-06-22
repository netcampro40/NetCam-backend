import { describe, expect, it, vi, beforeEach } from "vitest";

vi.mock("@aws-sdk/s3-request-presigner", () => ({
  getSignedUrl: vi.fn(async () => "https://signed.example/download"),
}));

vi.mock("../config/env.js", () => ({
  env: {
    aws: {
      accessKeyId: "test",
      secretAccessKey: "test",
      region: "us-east-2",
      s3Bucket: "test-bucket",
    },
  },
  hasAwsCredentials: () => true,
}));

import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { GetObjectCommand } from "@aws-sdk/client-s3";
import {
  CLIP_ORIGINAL_DOWNLOAD_URL_EXPIRES_SECONDS,
  createSignedClipOriginalDownloadUrl,
} from "./s3VideoService.js";

describe("createSignedClipOriginalDownloadUrl", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("signs the original key with download disposition", async () => {
    const url = await createSignedClipOriginalDownloadUrl("clients/c1/2026-06-19/kit/clip.mp4", {
      contentType: "video/mp4",
      fileName: "clip.mp4",
    });

    expect(url).toBe("https://signed.example/download");
    expect(getSignedUrl).toHaveBeenCalledWith(
      expect.anything(),
      expect.any(GetObjectCommand),
      { expiresIn: CLIP_ORIGINAL_DOWNLOAD_URL_EXPIRES_SECONDS },
    );

    const command = vi.mocked(getSignedUrl).mock.calls[0]?.[1] as GetObjectCommand;
    expect(command.input.Key).toBe("clients/c1/2026-06-19/kit/clip.mp4");
    expect(command.input.ResponseContentDisposition).toContain("attachment");
    expect(command.input.ResponseContentType).toBe("video/mp4");
  });
});
