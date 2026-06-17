import { GetObjectCommand, HeadBucketCommand, S3Client } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { Upload } from "@aws-sdk/lib-storage";
import { env, hasAwsCredentials } from "../config/env.js";

export type S3UploadInput = {
  key: string;
  body: Buffer;
  contentType: string;
};

export type S3UploadResult = {
  bucket: string;
  key: string;
  etag?: string;
};

let cachedClient: S3Client | null = null;

export function getS3Client(): S3Client {
  if (!hasAwsCredentials()) {
    throw new Error("aws_credentials_missing");
  }
  if (!cachedClient) {
    cachedClient = new S3Client({
      region: env.aws.region,
      credentials: {
        accessKeyId: env.aws.accessKeyId,
        secretAccessKey: env.aws.secretAccessKey,
      },
    });
  }
  return cachedClient;
}

export async function uploadVideoToS3(input: S3UploadInput): Promise<S3UploadResult> {
  const client = getS3Client();
  const upload = new Upload({
    client,
    params: {
      Bucket: env.aws.s3Bucket,
      Key: input.key,
      Body: input.body,
      ContentType: input.contentType,
    },
  });
  const result = await upload.done();
  return {
    bucket: env.aws.s3Bucket,
    key: input.key,
    etag: result.ETag,
  };
}

export async function checkS3BucketAccess(): Promise<{ ok: true; bucket: string; region: string }> {
  if (!hasAwsCredentials()) {
    throw new Error("aws_credentials_missing");
  }
  const client = getS3Client();
  await client.send(new HeadBucketCommand({ Bucket: env.aws.s3Bucket }));
  return { ok: true, bucket: env.aws.s3Bucket, region: env.aws.region };
}

/** Segmento de pasta para kit/controle no S3 (ex.: kit-1). */
export function slugifyKitSegment(kitLabel: string, qrCodeId: string | null): string {
  const fromLabel = kitLabel
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  if (fromLabel) return fromLabel;
  if (qrCodeId) return qrCodeId;
  return "unknown-kit";
}

export function formatRecordedDateUtc(recordedAt: Date): string {
  return recordedAt.toISOString().slice(0, 10);
}

/**
 * clients/{clientId}/{YYYY-MM-DD}/{kitSegment}/{clipId}.mp4
 * A data vem do horário real da gravação (recordedAt).
 */
export function buildClipS3Key(
  clientId: string,
  recordedAt: Date,
  kitSegment: string,
  clipId: string,
  extension = "mp4",
): string {
  const date = formatRecordedDateUtc(recordedAt);
  const safeKit = slugifyKitSegment(kitSegment, null);
  return `clients/${clientId}/${date}/${safeKit}/${clipId}.${extension}`;
}

/**
 * clients/{clientId}/{YYYY-MM-DD}/{kitSegment}/{clipId}_preview.mp4
 */
export function buildClipPreviewS3Key(
  clientId: string,
  recordedAt: Date,
  kitSegment: string,
  clipId: string,
): string {
  const date = formatRecordedDateUtc(recordedAt);
  const safeKit = slugifyKitSegment(kitSegment, null);
  return `clients/${clientId}/${date}/${safeKit}/${clipId}_preview.mp4`;
}

export function buildPrivateS3Uri(bucket: string, key: string): string {
  return `s3://${bucket}/${key}`;
}

/** Tempo de validade da URL assinada para reprodução (15 minutos). */
export const CLIP_PLAY_URL_EXPIRES_SECONDS = 900;

/** Gera URL assinada temporária para GET do objeto no S3 (bucket privado). */
export async function createSignedClipPlayUrl(fileKey: string): Promise<string> {
  const client = getS3Client();
  const command = new GetObjectCommand({
    Bucket: env.aws.s3Bucket,
    Key: fileKey,
  });
  return getSignedUrl(client, command, { expiresIn: CLIP_PLAY_URL_EXPIRES_SECONDS });
}
