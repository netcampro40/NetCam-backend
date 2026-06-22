import {
  buildClipThumbnailS3Key,
  buildPrivateS3Uri,
  slugifyKitSegment,
  uploadVideoToS3,
} from "../../../shared/aws/s3VideoService.js";

export type UploadThumbnailParams = {
  clientId: string;
  recordedAt: Date;
  kitLabel: string;
  qrCodeId: string | null;
  clipId: string;
};

export async function uploadThumbnailForClip(
  params: UploadThumbnailParams,
  thumbnailBuffer: Buffer,
): Promise<{ thumbnailFileKey: string; thumbnailFileUrl: string }> {
  const kitSegment = slugifyKitSegment(params.kitLabel, params.qrCodeId);
  const thumbnailFileKey = buildClipThumbnailS3Key(
    params.clientId,
    params.recordedAt,
    kitSegment,
    params.clipId,
  );

  const upload = await uploadVideoToS3({
    key: thumbnailFileKey,
    body: thumbnailBuffer,
    contentType: "image/jpeg",
    cacheControl: "private, max-age=86400",
  });

  const thumbnailFileUrl = buildPrivateS3Uri(upload.bucket, upload.key);
  return { thumbnailFileKey: upload.key, thumbnailFileUrl };
}
