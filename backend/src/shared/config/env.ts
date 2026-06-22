export const env = {
  port: Number(process.env.PORT ?? 3333),
  databaseUrl:
    process.env.DATABASE_URL ?? "postgresql://postgres:postgres@localhost:5432/netcam",
  /** Se vazio, rotas /api/admin ficam abertas (apenas desenvolvimento). */
  adminApiKey: process.env.ADMIN_API_KEY ?? "",
  aws: {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID ?? "",
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY ?? "",
    region: process.env.AWS_REGION ?? "us-east-2",
    s3Bucket: process.env.AWS_S3_BUCKET ?? "netcampro-videos",
  },
  clips: {
    maxUploadBytes: 500 * 1024 * 1024,
    maxThumbnailBytes: 2 * 1024 * 1024,
  },
};

export function hasAwsCredentials(): boolean {
  return Boolean(
    env.aws.accessKeyId &&
      env.aws.secretAccessKey &&
      env.aws.region &&
      env.aws.s3Bucket,
  );
}
