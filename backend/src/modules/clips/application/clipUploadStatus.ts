export const CLIP_UPLOAD_STATUS_UPLOADED = "uploaded";
export const CLIP_UPLOAD_STATUS_DELETING = "deleting";

export function isClipVisibleInGallery(uploadStatus: string): boolean {
  return uploadStatus === CLIP_UPLOAD_STATUS_UPLOADED;
}

export function isClipAvailableToClient(
  clip: { uploadStatus: string } | null | undefined,
): clip is { uploadStatus: string } {
  return clip != null && clip.uploadStatus === CLIP_UPLOAD_STATUS_UPLOADED;
}
