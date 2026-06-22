import { randomUUID } from "node:crypto";
import { extname } from "node:path";
import { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import multipart from "@fastify/multipart";
import { env, hasAwsCredentials } from "../../../shared/config/env.js";
import {
  buildClipPreviewS3Key,
  buildClipS3Key,
  buildPrivateS3Uri,
  checkS3BucketAccess,
  CLIP_PLAY_URL_EXPIRES_SECONDS,
  CLIP_ORIGINAL_DOWNLOAD_URL_EXPIRES_SECONDS,
  createSignedClipPlayUrl,
  createSignedClipOriginalDownloadUrl,
  createSignedThumbnailUrl,
  slugifyKitSegment,
  uploadVideoToS3,
} from "../../../shared/aws/s3VideoService.js";
import { validateClipUploadByQrToken } from "../application/validateClipUpload.usecase.js";
import { resolveClipPlaybackFile } from "../application/resolveClipPlaybackFile.js";
import { resolveClipOriginalFile } from "../application/resolveClipOriginalFile.js";
import { isClipAvailableToClient } from "../application/clipUploadStatus.js";
import { sanitizeDownloadFileName } from "../application/sanitizeDownloadFileName.js";
import { uploadThumbnailForClip } from "../application/uploadThumbnailForClip.js";
import { serializeGalleryClipsResponse } from "../application/serializeGalleryClipResponse.js";
import { validateThumbnailFile } from "../application/validateThumbnailFile.js";
import {
  insertVideoClip,
  findVideoClipById,
  updateVideoClipPreview,
  updateVideoClipThumbnail,
  listArenasWithClips,
  listClipDatesByClientAndKit,
  listClipsByClientKitAndDate,
  listKitsWithClipsByClientId,
  listVideoClipsByClientId,
  type VideoClipRow,
} from "../repository/videoClip.repository.js";

const ALLOWED_VIDEO_MIME_TYPES = new Set([
  "video/mp4",
  "video/quicktime",
  "video/mpeg",
  "video/webm",
  "video/3gpp",
  "application/octet-stream",
]);

function toIso(value: unknown): string {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toISOString();
  }
  if (typeof value === "string" && value.length > 0) {
    const d = new Date(value);
    if (!Number.isNaN(d.getTime())) return d.toISOString();
  }
  return new Date().toISOString();
}

function serializeClip(row: VideoClipRow) {
  return {
    id: row.id,
    clipId: row.id,
    clientId: row.clientId,
    qrCodeId: row.qrCodeId,
    arenaName: row.arenaName,
    kitLabel: row.kitLabel,
    fileKey: row.fileKey,
    fileUrl: row.fileUrl,
    originalFilename: row.originalFilename,
    mimeType: row.mimeType,
    sizeBytes: row.sizeBytes,
    durationSeconds: row.durationSeconds,
    sourcePlatform: row.sourcePlatform,
    localClipId: row.localClipId,
    uploadStatus: row.uploadStatus,
    recordedAt: toIso(row.recordedAt),
    createdAt: toIso(row.createdAt),
    expiresAt: row.expiresAt ? toIso(row.expiresAt) : null,
    uploadedAt: toIso(row.uploadedAt),
  };
}

function normalizePlatform(value: string | undefined): "ios" | "android" | null {
  const v = (value ?? "").trim().toLowerCase();
  if (v === "ios" || v === "android") return v;
  return null;
}

function parseRecordedAt(value: string | undefined): Date | null {
  if (!value?.trim()) return null;
  const d = new Date(value.trim());
  if (Number.isNaN(d.getTime())) return null;
  return d;
}

function resolveExtension(filename: string, mimeType: string): string {
  const fromName = extname(filename).replace(".", "").toLowerCase();
  if (fromName) return fromName;
  if (mimeType === "video/quicktime") return "mov";
  if (mimeType === "video/webm") return "webm";
  if (mimeType === "video/mpeg") return "mpeg";
  return "mp4";
}

function isAllowedVideoMime(mimeType: string): boolean {
  const normalized = mimeType.split(";")[0]?.trim().toLowerCase() ?? "";
  return ALLOWED_VIDEO_MIME_TYPES.has(normalized);
}

type UploadFields = {
  qrToken?: string;
  platform?: string;
  recordedAt?: string;
  durationSeconds?: string;
  localClipId?: string;
};

const CLIP_ID_UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function uploadPreviewForClip(
  params: {
    clientId: string;
    recordedAt: Date;
    kitLabel: string;
    qrCodeId: string | null;
    clipId: string;
  },
  previewBuffer: Buffer,
  previewMime: string,
): Promise<{ previewFileKey: string; previewFileUrl: string }> {
  const kitSegment = slugifyKitSegment(params.kitLabel, params.qrCodeId);
  const previewFileKey = buildClipPreviewS3Key(
    params.clientId,
    params.recordedAt,
    kitSegment,
    params.clipId,
  );
  const previewUpload = await uploadVideoToS3({
    key: previewFileKey,
    body: previewBuffer,
    contentType: previewMime.split(";")[0]?.trim() ?? "video/mp4",
  });
  const previewFileUrl = buildPrivateS3Uri(previewUpload.bucket, previewUpload.key);
  return { previewFileKey: previewUpload.key, previewFileUrl };
}

export async function clipsRoutes(app: FastifyInstance) {
  await app.register(multipart, {
    limits: {
      fileSize: env.clips.maxUploadBytes,
      files: 2,
    },
  });

  app.get("/s3-health", async (request, reply) => {
    try {
      if (!hasAwsCredentials()) {
        request.log.warn("s3_health_failed: missing env vars");
        return reply.status(503).send({
          ok: false,
          error: "aws_credentials_missing",
          message: "Variáveis AWS não configuradas no servidor.",
        });
      }
      const result = await checkS3BucketAccess();
      request.log.info({ bucket: result.bucket, region: result.region }, "s3_health_ok");
      return reply.send({
        ok: true,
        bucket: result.bucket,
        region: result.region,
      });
    } catch (e) {
      request.log.error({ err: e }, "s3_health_failed");
      return reply.status(503).send({
        ok: false,
        error: "s3_health_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/arenas", async (request, reply) => {
    try {
      const arenas = await listArenasWithClips();
      return reply.send({
        arenas: arenas.map((a) => ({
          clientId: a.clientId,
          arenaName: a.arenaName,
          nomeFantasia: a.arenaName,
          totalClips: a.totalClips,
        })),
      });
    } catch (e) {
      request.log.error({ err: e }, "list_arenas_failed");
      return reply.status(500).send({
        error: "list_arenas_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get(
    "/clients/:clientId/kits/:qrCodeId/dates/:date",
    async (request, reply) => {
      const { clientId, qrCodeId, date } = request.params as {
        clientId: string;
        qrCodeId: string;
        date: string;
      };
      try {
        const rows = await listClipsByClientKitAndDate(clientId, qrCodeId, date);
        const clips = await serializeGalleryClipsResponse(rows);
        for (const clip of clips) {
          if (clip.thumbnailUrl) {
            request.log.info({ clipId: clip.clipId, urlPresent: true }, "thumbnail_url_generated");
          }
        }
        return reply.send({ clips });
      } catch (e) {
        request.log.error({ err: e, clientId, qrCodeId, date }, "list_clips_by_date_failed");
        return reply.status(500).send({
          error: "list_clips_by_date_failed",
          message: e instanceof Error ? e.message : String(e),
        });
      }
    },
  );

  app.get("/clients/:clientId/kits/:qrCodeId/dates", async (request, reply) => {
    const { clientId, qrCodeId } = request.params as {
      clientId: string;
      qrCodeId: string;
    };
    try {
      const dates = await listClipDatesByClientAndKit(clientId, qrCodeId);
      return reply.send({ dates });
    } catch (e) {
      request.log.error({ err: e, clientId, qrCodeId }, "list_clip_dates_failed");
      return reply.status(500).send({
        error: "list_clip_dates_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/clients/:clientId/kits", async (request, reply) => {
    const { clientId } = request.params as { clientId: string };
    try {
      const kits = await listKitsWithClipsByClientId(clientId);
      return reply.send({ kits });
    } catch (e) {
      request.log.error({ err: e, clientId }, "list_kits_failed");
      return reply.status(500).send({
        error: "list_kits_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/client/:clientId", async (request, reply) => {
    const clientId = (request.params as { clientId: string }).clientId;
    try {
      const rows = await listVideoClipsByClientId(clientId);
      return reply.send({ clips: rows.map(serializeClip) });
    } catch (e) {
      request.log.error({ err: e, clientId }, "list_clips_failed");
      return reply.status(500).send({
        error: "list_clips_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/:clipId/download", async (request, reply) => {
    const clipId = ((request.params as { clipId: string }).clipId ?? "").trim();

    if (!clipId) {
      request.log.warn(
        { clipId, errorCode: "clip_id_required", phase: "validation" },
        "original_download_url_failed",
      );
      return reply.status(400).send({
        error: "clip_id_required",
        message: "clipId é obrigatório.",
      });
    }

    if (!CLIP_ID_UUID_REGEX.test(clipId)) {
      request.log.warn(
        { clipId, errorCode: "invalid_clip_id", phase: "validation" },
        "original_download_url_failed",
      );
      return reply.status(400).send({
        error: "invalid_clip_id",
        message: "clipId inválido.",
      });
    }

    if (!hasAwsCredentials()) {
      request.log.warn(
        { clipId, errorCode: "aws_credentials_missing", phase: "s3" },
        "original_download_url_failed",
      );
      return reply.status(503).send({
        error: "aws_credentials_missing",
        message: "Download indisponível: credenciais AWS não configuradas.",
      });
    }

    try {
      const clip = await findVideoClipById(clipId);
      if (!isClipAvailableToClient(clip)) {
        request.log.warn(
          { clipId, errorCode: "clip_not_found", phase: "database" },
          "original_download_url_failed",
        );
        return reply.status(404).send({
          error: "clip_not_found",
          message: "Clipe não encontrado.",
        });
      }

      request.log.info({ clipId, authenticatedClientId: clip.clientId }, "original_download_url_requested");

      const original = resolveClipOriginalFile(clip);
      if (!original) {
        request.log.warn(
          { clipId, errorCode: "original_file_missing", phase: "database" },
          "original_download_url_failed",
        );
        return reply.status(422).send({
          error: "original_file_missing",
          message: "Clipe sem arquivo original configurado para download.",
        });
      }

      const fileName = sanitizeDownloadFileName(clip.originalFilename);
      const downloadUrl = await createSignedClipOriginalDownloadUrl(original.fileKey, {
        contentType: clip.mimeType || undefined,
        fileName,
      });

      request.log.info(
        {
          clipId,
          urlPresent: true,
          expiresIn: CLIP_ORIGINAL_DOWNLOAD_URL_EXPIRES_SECONDS,
          sizeBytes: clip.sizeBytes,
        },
        "original_download_url_generated",
      );

      return reply.send({
        clipId,
        downloadUrl,
        source: "original",
        expiresIn: CLIP_ORIGINAL_DOWNLOAD_URL_EXPIRES_SECONDS,
        sizeBytes: clip.sizeBytes,
        contentType: clip.mimeType,
        fileName,
      });
    } catch (e) {
      request.log.error(
        { err: e, clipId, errorCode: "original_download_url_failed", phase: "s3" },
        "original_download_url_failed",
      );
      return reply.status(500).send({
        error: "original_download_url_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/:clipId/play", async (request, reply) => {
    const clipId = ((request.params as { clipId: string }).clipId ?? "").trim();

    request.log.info({ clipId }, "clip_play_url_requested");

    if (!clipId) {
      request.log.warn({ clipId }, "clip_play_url_failed");
      return reply.status(400).send({
        error: "clip_id_required",
        message: "clipId é obrigatório.",
      });
    }

    if (!CLIP_ID_UUID_REGEX.test(clipId)) {
      request.log.warn({ clipId }, "clip_play_url_failed");
      return reply.status(400).send({
        error: "invalid_clip_id",
        message: "clipId inválido.",
      });
    }

    if (!hasAwsCredentials()) {
      request.log.warn({ clipId }, "clip_play_url_failed");
      return reply.status(503).send({
        error: "aws_credentials_missing",
        message: "Reprodução indisponível: credenciais AWS não configuradas.",
      });
    }

    try {
      const clip = await findVideoClipById(clipId);
      if (!isClipAvailableToClient(clip)) {
        request.log.warn({ clipId }, "clip_play_url_failed");
        return reply.status(404).send({
          error: "clip_not_found",
          message: "Clipe não encontrado.",
        });
      }

      const playback = resolveClipPlaybackFile(clip);
      if (!playback) {
        request.log.warn({ clipId }, "clip_play_url_failed");
        return reply.status(422).send({
          error: "file_key_missing",
          message: "Clipe sem arquivo configurado para reprodução.",
        });
      }

      const playUrl = await createSignedClipPlayUrl(playback.fileKey);
      request.log.info(
        { clipId, fileKey: playback.fileKey, source: playback.source },
        "clip_play_url_success",
      );

      return reply.send({
        clipId,
        playUrl,
        expiresIn: CLIP_PLAY_URL_EXPIRES_SECONDS,
        source: playback.source,
      });
    } catch (e) {
      request.log.error({ err: e, clipId }, "clip_play_url_failed");
      return reply.status(500).send({
        error: "clip_play_url_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/:clipId/preview", async (request: FastifyRequest, reply: FastifyReply) => {
    const clipId = ((request.params as { clipId: string }).clipId ?? "").trim();

    if (!clipId || !CLIP_ID_UUID_REGEX.test(clipId)) {
      return reply.status(400).send({
        error: "invalid_clip_id",
        message: "clipId inválido.",
      });
    }

    if (!hasAwsCredentials()) {
      return reply.status(503).send({
        error: "aws_credentials_missing",
        message: "Upload de preview indisponível: credenciais AWS não configuradas.",
      });
    }

    let previewBuffer: Buffer | null = null;
    let previewMime = "video/mp4";

    try {
      for await (const part of request.parts()) {
        if (part.type === "file" && part.fieldname === "preview") {
          previewMime = part.mimetype ?? "video/mp4";
          previewBuffer = await part.toBuffer();
        }
      }
    } catch (e) {
      request.log.error({ err: e, clipId }, "preview_upload_parse_failed");
      return reply.status(400).send({
        error: "invalid_multipart",
        message: e instanceof Error ? e.message : String(e),
      });
    }

    if (!previewBuffer || previewBuffer.length === 0) {
      return reply.status(400).send({
        error: "preview_required",
        message: "Arquivo preview é obrigatório.",
      });
    }

    if (!isAllowedVideoMime(previewMime)) {
      return reply.status(415).send({
        error: "unsupported_media_type",
        message: `Tipo de preview não permitido: ${previewMime}`,
      });
    }

    try {
      const clip = await findVideoClipById(clipId);
      if (!clip) {
        return reply.status(404).send({
          error: "clip_not_found",
          message: "Clipe não encontrado.",
        });
      }

      const previewUpload = await uploadPreviewForClip(
        {
          clientId: clip.clientId,
          recordedAt: clip.recordedAt,
          kitLabel: clip.kitLabel,
          qrCodeId: clip.qrCodeId,
          clipId,
        },
        previewBuffer,
        previewMime,
      );

      const row = await updateVideoClipPreview(
        clipId,
        previewUpload.previewFileKey,
        previewUpload.previewFileUrl,
      );
      if (!row) {
        return reply.status(500).send({
          error: "preview_update_failed",
          message: "Falha ao salvar preview no banco.",
        });
      }

      request.log.info({ clipId, previewFileKey: previewUpload.previewFileKey }, "s3_preview_upload_success");

      return reply.send({
        clipId,
        previewFileKey: previewUpload.previewFileKey,
        previewFileUrl: previewUpload.previewFileUrl,
      });
    } catch (e) {
      request.log.error({ err: e, clipId }, "preview_upload_failed");
      return reply.status(500).send({
        error: "preview_upload_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/:clipId/thumbnail", async (request: FastifyRequest, reply: FastifyReply) => {
    const clipId = ((request.params as { clipId: string }).clipId ?? "").trim();

    if (!clipId || !CLIP_ID_UUID_REGEX.test(clipId)) {
      return reply.status(400).send({
        error: "invalid_clip_id",
        message: "clipId inválido.",
      });
    }

    if (!hasAwsCredentials()) {
      return reply.status(503).send({
        error: "aws_credentials_missing",
        message: "Upload de thumbnail indisponível: credenciais AWS não configuradas.",
      });
    }

    let thumbnailBuffer: Buffer | null = null;
    let thumbnailMime = "image/jpeg";
    let thumbnailFilename = "thumbnail.jpg";

    try {
      for await (const part of request.parts()) {
        if (part.type === "file" && part.fieldname === "thumbnail") {
          thumbnailMime = part.mimetype ?? "image/jpeg";
          thumbnailFilename = part.filename ?? thumbnailFilename;
          thumbnailBuffer = await part.toBuffer();
        }
      }
    } catch (e) {
      request.log.error(
        { err: e, clipId, errorCode: "invalid_multipart", phase: "validation" },
        "thumbnail_upload_failed",
      );
      return reply.status(400).send({
        error: "invalid_multipart",
        message: e instanceof Error ? e.message : String(e),
      });
    }

    const validation = validateThumbnailFile({
      buffer: thumbnailBuffer ?? Buffer.alloc(0),
      mimeType: thumbnailMime,
      filename: thumbnailFilename,
      maxBytes: env.clips.maxThumbnailBytes,
    });

    if (!validation.ok) {
      request.log.warn(
        { clipId, errorCode: validation.errorCode, phase: "validation" },
        "thumbnail_upload_failed",
      );
      const status = validation.errorCode === "file_too_large" ? 413 : 415;
      return reply.status(status).send({
        error: validation.errorCode,
        message: validation.message,
      });
    }

    try {
      const clip = await findVideoClipById(clipId);
      if (!clip) {
        request.log.warn({ clipId, errorCode: "clip_not_found", phase: "validation" }, "thumbnail_upload_failed");
        return reply.status(404).send({
          error: "clip_not_found",
          message: "Clipe não encontrado.",
        });
      }

      request.log.info(
        {
          clipId,
          bytes: thumbnailBuffer!.length,
          mimeType: validation.normalizedMime,
        },
        "thumbnail_upload_started",
      );

      const thumbnailUpload = await uploadThumbnailForClip(
        {
          clientId: clip.clientId,
          recordedAt: clip.recordedAt,
          kitLabel: clip.kitLabel,
          qrCodeId: clip.qrCodeId,
          clipId,
        },
        thumbnailBuffer!,
      );

      request.log.info(
        { clipId, key: thumbnailUpload.thumbnailFileKey },
        "thumbnail_upload_s3_success",
      );

      const row = await updateVideoClipThumbnail(
        clipId,
        thumbnailUpload.thumbnailFileKey,
        thumbnailUpload.thumbnailFileUrl,
      );

      if (!row) {
        request.log.error(
          { clipId, errorCode: "thumbnail_update_failed", phase: "database" },
          "thumbnail_upload_failed",
        );
        return reply.status(500).send({
          error: "thumbnail_update_failed",
          message: "Falha ao salvar thumbnail no banco.",
        });
      }

      const thumbnailUrl = await createSignedThumbnailUrl(thumbnailUpload.thumbnailFileKey);
      request.log.info({ clipId, urlPresent: true }, "thumbnail_url_generated");
      request.log.info({ clipId }, "thumbnail_upload_completed");

      return reply.send({
        clipId,
        thumbnailUrl,
      });
    } catch (e) {
      const errorCode =
        e instanceof Error && e.message === "aws_credentials_missing"
          ? "aws_credentials_missing"
          : "thumbnail_upload_failed";
      request.log.error(
        { err: e, clipId, errorCode, phase: "s3" },
        "thumbnail_upload_failed",
      );
      return reply.status(500).send({
        error: errorCode,
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/upload", async (request: FastifyRequest, reply: FastifyReply) => {
    if (!hasAwsCredentials()) {
      return reply.status(503).send({
        error: "aws_credentials_missing",
        message: "Upload indisponível: credenciais AWS não configuradas.",
      });
    }

    const fields: UploadFields = {};
    let videoBuffer: Buffer | null = null;
    let videoMime = "";
    let originalFilename = "clip.mp4";
    let previewBuffer: Buffer | null = null;
    let previewMime = "video/mp4";

    try {
      for await (const part of request.parts()) {
        if (part.type === "file") {
          if (part.fieldname === "video") {
            videoMime = part.mimetype ?? "application/octet-stream";
            originalFilename = part.filename ?? originalFilename;
            videoBuffer = await part.toBuffer();
            continue;
          }
          if (part.fieldname === "preview") {
            previewMime = part.mimetype ?? "video/mp4";
            previewBuffer = await part.toBuffer();
            continue;
          }
          continue;
        }
        const value = String(part.value ?? "");
        if (part.fieldname === "qrToken") fields.qrToken = value;
        if (part.fieldname === "platform") fields.platform = value;
        if (part.fieldname === "recordedAt") fields.recordedAt = value;
        if (part.fieldname === "durationSeconds") fields.durationSeconds = value;
        if (part.fieldname === "localClipId") fields.localClipId = value;
      }
    } catch (e) {
      request.log.error({ err: e }, "upload_parse_failed");
      return reply.status(400).send({
        error: "invalid_multipart",
        message: e instanceof Error ? e.message : String(e),
      });
    }

    const qrToken = fields.qrToken?.trim() ?? "";
    if (!qrToken) {
      return reply.status(400).send({
        error: "qr_token_required",
        message: "QR Token é obrigatório.",
      });
    }

    const platform = normalizePlatform(fields.platform);
    if (!platform) {
      return reply.status(400).send({ error: "invalid_platform", message: "Use ios ou android." });
    }

    const recordedAt = parseRecordedAt(fields.recordedAt);
    if (!recordedAt) {
      return reply.status(400).send({
        error: "invalid_recorded_at",
        message: "recordedAt é obrigatório e deve ser uma data/hora ISO válida.",
      });
    }

    if (!videoBuffer || videoBuffer.length === 0) {
      return reply.status(400).send({ error: "video_required" });
    }

    if (videoBuffer.length > env.clips.maxUploadBytes) {
      return reply.status(413).send({
        error: "file_too_large",
        message: `Tamanho máximo: ${env.clips.maxUploadBytes} bytes.`,
      });
    }

    if (!isAllowedVideoMime(videoMime)) {
      return reply.status(415).send({
        error: "unsupported_media_type",
        message: `Tipo não permitido: ${videoMime}`,
      });
    }

    const access = await validateClipUploadByQrToken(qrToken);
    if (!access.ok) {
      return reply.status(403).send({
        error: access.reason,
        message: access.message,
      });
    }

    const clipId = randomUUID();
    const extension = resolveExtension(originalFilename, videoMime);
    const kitSegment = slugifyKitSegment(access.kitLabel, access.qrCodeId);
    const fileKey = buildClipS3Key(access.clientId, recordedAt, kitSegment, clipId, extension);

    let durationSeconds: number | null = null;
    if (fields.durationSeconds) {
      const parsed = parseInt(fields.durationSeconds, 10);
      if (!Number.isNaN(parsed) && parsed >= 0) durationSeconds = parsed;
    }

    const localClipId = fields.localClipId?.trim() || null;

    request.log.info(
      {
        clientId: access.clientId,
        clipId,
        fileKey,
        sizeBytes: videoBuffer.length,
        mimeType: videoMime,
        platform,
        recordedAt: recordedAt.toISOString(),
      },
      "s3_upload_started",
    );

    try {
      const uploadResult = await uploadVideoToS3({
        key: fileKey,
        body: videoBuffer,
        contentType: videoMime.split(";")[0]?.trim() ?? "video/mp4",
      });

      request.log.info(
        {
          clientId: access.clientId,
          clipId,
          fileKey: uploadResult.key,
          etag: uploadResult.etag,
        },
        "s3_upload_success",
      );

      const fileUrl = buildPrivateS3Uri(uploadResult.bucket, uploadResult.key);

      let previewFileKey: string | null = null;
      let previewFileUrl: string | null = null;
      if (previewBuffer && previewBuffer.length > 0) {
        if (!isAllowedVideoMime(previewMime)) {
          return reply.status(415).send({
            error: "unsupported_media_type",
            message: `Tipo de preview não permitido: ${previewMime}`,
          });
        }
        const previewUpload = await uploadPreviewForClip(
          {
            clientId: access.clientId,
            recordedAt,
            kitLabel: access.kitLabel,
            qrCodeId: access.qrCodeId,
            clipId,
          },
          previewBuffer,
          previewMime,
        );
        previewFileKey = previewUpload.previewFileKey;
        previewFileUrl = previewUpload.previewFileUrl;
        request.log.info({ clipId, previewFileKey }, "s3_preview_upload_success");
      }

      const row = await insertVideoClip({
        id: clipId,
        clientId: access.clientId,
        qrCodeId: access.qrCodeId,
        arenaName: access.arenaName,
        kitLabel: access.kitLabel,
        fileKey: uploadResult.key,
        fileUrl,
        originalFileKey: uploadResult.key,
        originalFileUrl: fileUrl,
        previewFileKey,
        previewFileUrl,
        originalFilename,
        mimeType: videoMime.split(";")[0]?.trim() ?? "video/mp4",
        sizeBytes: videoBuffer.length,
        durationSeconds,
        sourcePlatform: platform,
        localClipId,
        recordedAt,
      });

      request.log.info(
        { clipId: row.id, clientId: access.clientId, fileKey: row.fileKey },
        "clip_saved_to_database",
      );

      return reply.status(201).send({ clipId: row.id, clip: serializeClip(row) });
    } catch (e) {
      request.log.error(
        { err: e, clientId: access.clientId, clipId, fileKey },
        "s3_upload_failed",
      );
      return reply.status(500).send({
        error: "upload_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });
}
