import { randomUUID } from "node:crypto";
import { extname } from "node:path";
import { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import multipart from "@fastify/multipart";
import { env, hasAwsCredentials } from "../../../shared/config/env.js";
import {
  buildClipS3Key,
  buildPrivateS3Uri,
  checkS3BucketAccess,
  uploadVideoToS3,
} from "../../../shared/aws/s3VideoService.js";
import { validateClipUploadAccess } from "../application/validateClipUpload.usecase.js";
import {
  insertVideoClip,
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
  clientId?: string;
  qrToken?: string;
  platform?: string;
  kitLabel?: string;
  durationSeconds?: string;
};

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
        if (part.fieldname === "clientId") fields.clientId = value;
        if (part.fieldname === "qrToken") fields.qrToken = value;
        if (part.fieldname === "platform") fields.platform = value;
        if (part.fieldname === "kitLabel") fields.kitLabel = value;
        if (part.fieldname === "durationSeconds") fields.durationSeconds = value;
      }
    } catch (e) {
      request.log.error({ err: e }, "upload_parse_failed");
      return reply.status(400).send({
        error: "invalid_multipart",
        message: e instanceof Error ? e.message : String(e),
      });
    }

    const clientId = fields.clientId?.trim() ?? "";
    if (!clientId) {
      return reply.status(400).send({ error: "client_id_required" });
    }

    const platform = normalizePlatform(fields.platform);
    if (!platform) {
      return reply.status(400).send({ error: "invalid_platform", message: "Use ios ou android." });
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

    const access = await validateClipUploadAccess(
      clientId,
      fields.qrToken,
      fields.kitLabel,
    );
    if (!access.ok) {
      return reply.status(403).send({
        error: access.reason,
        message: access.message,
      });
    }

    const clipId = randomUUID();
    const extension = resolveExtension(originalFilename, videoMime);
    const fileKey = buildClipS3Key(clientId, clipId, extension);

    let durationSeconds: number | null = null;
    if (fields.durationSeconds) {
      const parsed = parseInt(fields.durationSeconds, 10);
      if (!Number.isNaN(parsed) && parsed >= 0) durationSeconds = parsed;
    }

    request.log.info(
      {
        clientId,
        clipId,
        fileKey,
        sizeBytes: videoBuffer.length,
        mimeType: videoMime,
        platform,
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
          clientId,
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
      });

      request.log.info({ clipId: row.id, clientId, fileKey: row.fileKey }, "clip_saved_to_database");

      return reply.status(201).send({ clip: serializeClip(row) });
    } catch (e) {
      request.log.error({ err: e, clientId, clipId, fileKey }, "s3_upload_failed");
      return reply.status(500).send({
        error: "upload_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });
}
