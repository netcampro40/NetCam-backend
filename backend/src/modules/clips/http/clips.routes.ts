import { randomUUID } from "node:crypto";
import { extname } from "node:path";
import { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import multipart from "@fastify/multipart";
import { env, hasAwsCredentials } from "../../../shared/config/env.js";
import {
  buildClipS3Key,
  buildPrivateS3Uri,
  checkS3BucketAccess,
  CLIP_PLAY_URL_EXPIRES_SECONDS,
  createSignedClipPlayUrl,
  slugifyKitSegment,
  uploadVideoToS3,
} from "../../../shared/aws/s3VideoService.js";
import { validateClipUploadByQrToken } from "../application/validateClipUpload.usecase.js";
import {
  insertVideoClip,
  findVideoClipById,
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

function serializeGalleryClip(row: VideoClipRow) {
  return {
    id: row.id,
    clipId: row.id,
    recordedAt: toIso(row.recordedAt),
    uploadedAt: toIso(row.uploadedAt),
    durationSeconds: row.durationSeconds,
    fileKey: row.fileKey,
    fileUrl: row.fileUrl,
    sizeBytes: row.sizeBytes,
    platform: row.sourcePlatform,
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

export async function clipsRoutes(app: FastifyInstance) {
  await app.register(multipart, {
    limits: {
      fileSize: env.clips.maxUploadBytes,
      files: 1,
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
        return reply.send({ clips: rows.map(serializeGalleryClip) });
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
      if (!clip) {
        request.log.warn({ clipId }, "clip_play_url_failed");
        return reply.status(404).send({
          error: "clip_not_found",
          message: "Clipe não encontrado.",
        });
      }

      const fileKey = clip.fileKey?.trim() ?? "";
      if (!fileKey) {
        request.log.warn({ clipId }, "clip_play_url_failed");
        return reply.status(422).send({
          error: "file_key_missing",
          message: "Clipe sem fileKey configurado.",
        });
      }

      const playUrl = await createSignedClipPlayUrl(fileKey);
      request.log.info({ clipId, fileKey }, "clip_play_url_success");

      return reply.send({
        clipId,
        playUrl,
        expiresIn: CLIP_PLAY_URL_EXPIRES_SECONDS,
      });
    } catch (e) {
      request.log.error({ err: e, clipId }, "clip_play_url_failed");
      return reply.status(500).send({
        error: "clip_play_url_failed",
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

    try {
      for await (const part of request.parts()) {
        if (part.type === "file") {
          if (part.fieldname !== "video") {
            continue;
          }
          videoMime = part.mimetype ?? "application/octet-stream";
          originalFilename = part.filename ?? originalFilename;
          videoBuffer = await part.toBuffer();
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
      const row = await insertVideoClip({
        id: clipId,
        clientId: access.clientId,
        qrCodeId: access.qrCodeId,
        arenaName: access.arenaName,
        kitLabel: access.kitLabel,
        fileKey: uploadResult.key,
        fileUrl,
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
