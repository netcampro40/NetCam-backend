const JPEG_MAGIC = Buffer.from([0xff, 0xd8, 0xff]);

export const THUMBNAIL_MAX_BYTES = 2 * 1024 * 1024;

export type ThumbnailValidationResult =
  | { ok: true; normalizedMime: "image/jpeg" }
  | { ok: false; errorCode: string; message: string };

function normalizeMime(mimeType: string): string {
  return mimeType.split(";")[0]?.trim().toLowerCase() ?? "";
}

function hasAllowedExtension(filename: string): boolean {
  const lower = filename.trim().toLowerCase();
  return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
}

function hasJpegMagicBytes(buffer: Buffer): boolean {
  if (buffer.length < JPEG_MAGIC.length) return false;
  return buffer.subarray(0, JPEG_MAGIC.length).equals(JPEG_MAGIC);
}

export function validateThumbnailFile(input: {
  buffer: Buffer;
  mimeType: string;
  filename?: string;
  maxBytes?: number;
}): ThumbnailValidationResult {
  const maxBytes = input.maxBytes ?? THUMBNAIL_MAX_BYTES;

  if (!input.buffer || input.buffer.length === 0) {
    return {
      ok: false,
      errorCode: "thumbnail_required",
      message: "Arquivo thumbnail é obrigatório.",
    };
  }

  if (input.buffer.length > maxBytes) {
    return {
      ok: false,
      errorCode: "file_too_large",
      message: `Thumbnail excede o tamanho máximo de ${maxBytes} bytes.`,
    };
  }

  const mime = normalizeMime(input.mimeType);
  if (mime !== "image/jpeg") {
    return {
      ok: false,
      errorCode: "unsupported_media_type",
      message: `Tipo de thumbnail não permitido: ${input.mimeType || "desconhecido"}. Use image/jpeg.`,
    };
  }

  if (input.filename && !hasAllowedExtension(input.filename)) {
    return {
      ok: false,
      errorCode: "invalid_thumbnail_extension",
      message: "Extensão de thumbnail inválida. Use .jpg ou .jpeg.",
    };
  }

  if (!hasJpegMagicBytes(input.buffer)) {
    return {
      ok: false,
      errorCode: "invalid_thumbnail_content",
      message: "Conteúdo do arquivo não é um JPEG válido.",
    };
  }

  return { ok: true, normalizedMime: "image/jpeg" };
}
