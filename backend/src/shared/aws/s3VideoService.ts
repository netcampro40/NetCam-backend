import { HeadBucketCommand, S3Client } from "@aws-sdk/client-s3";
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

export function buildClipS3Key(clientId: string, clipId: string, extension = "mp4"): string {
  const date = new Date().toISOString().slice(0, 10);
  return `clients/${clientId}/${date}/${clipId}.${extension}`;
}

export function buildPrivateS3Uri(bucket: string, key: string): string {
  return `s3://${bucket}/${key}`;
}
