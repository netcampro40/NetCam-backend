import { desc, eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { videoClips } from "../../../shared/db/schema/videoClips.js";

export type VideoClipRow = {
  id: string;
  clientId: string;
  qrCodeId: string | null;
  arenaName: string;
  kitLabel: string;
  fileKey: string;
  fileUrl: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  durationSeconds: number | null;
  sourcePlatform: string;
  createdAt: Date;
  expiresAt: Date | null;
  uploadedAt: Date;
};

const rowSelect = {
  id: videoClips.id,
  clientId: videoClips.clientId,
  qrCodeId: videoClips.qrCodeId,
  arenaName: videoClips.arenaName,
  kitLabel: videoClips.kitLabel,
  fileKey: videoClips.fileKey,
  fileUrl: videoClips.fileUrl,
  originalFilename: videoClips.originalFilename,
  mimeType: videoClips.mimeType,
  sizeBytes: videoClips.sizeBytes,
  durationSeconds: videoClips.durationSeconds,
  sourcePlatform: videoClips.sourcePlatform,
  createdAt: videoClips.createdAt,
  expiresAt: videoClips.expiresAt,
  uploadedAt: videoClips.uploadedAt,
};

export type NewVideoClipInput = {
  id?: string;
  clientId: string;
  qrCodeId?: string | null;
  arenaName: string;
  kitLabel: string;
  fileKey: string;
  fileUrl: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  durationSeconds?: number | null;
  sourcePlatform: string;
  expiresAt?: Date | null;
};

export async function insertVideoClip(input: NewVideoClipInput): Promise<VideoClipRow> {
  const [row] = await db
    .insert(videoClips)
    .values({
      id: input.id,
      clientId: input.clientId,
      qrCodeId: input.qrCodeId ?? null,
      arenaName: input.arenaName,
      kitLabel: input.kitLabel,
      fileKey: input.fileKey,
      fileUrl: input.fileUrl,
      originalFilename: input.originalFilename,
      mimeType: input.mimeType,
      sizeBytes: input.sizeBytes,
      durationSeconds: input.durationSeconds ?? null,
      sourcePlatform: input.sourcePlatform,
      expiresAt: input.expiresAt ?? null,
    })
    .returning(rowSelect);
  if (!row) throw new Error("failed_to_insert_video_clip");
  return row;
}

export async function listVideoClipsByClientId(clientId: string): Promise<VideoClipRow[]> {
  return db
    .select(rowSelect)
    .from(videoClips)
    .where(eq(videoClips.clientId, clientId))
    .orderBy(desc(videoClips.uploadedAt));
}

export async function findVideoClipById(id: string): Promise<VideoClipRow | null> {
  const [row] = await db.select(rowSelect).from(videoClips).where(eq(videoClips.id, id)).limit(1);
  return row ?? null;
}
