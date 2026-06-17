import { and, asc, count, desc, eq, gte, lt, sql } from "drizzle-orm";
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
  localClipId: string | null;
  uploadStatus: string;
  recordedAt: Date;
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
  localClipId: videoClips.localClipId,
  uploadStatus: videoClips.uploadStatus,
  recordedAt: videoClips.recordedAt,
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
  localClipId?: string | null;
  uploadStatus?: string;
  recordedAt: Date;
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
      localClipId: input.localClipId ?? null,
      uploadStatus: input.uploadStatus ?? "uploaded",
      recordedAt: input.recordedAt,
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
    .orderBy(desc(videoClips.recordedAt));
}

export async function findVideoClipById(id: string): Promise<VideoClipRow | null> {
  const [row] = await db.select(rowSelect).from(videoClips).where(eq(videoClips.id, id)).limit(1);
  return row ?? null;
}

export type ArenaClipsSummary = {
  clientId: string;
  arenaName: string;
  totalClips: number;
};

export async function listArenasWithClips(): Promise<ArenaClipsSummary[]> {
  const rows = await db
    .select({
      clientId: videoClips.clientId,
      arenaName: videoClips.arenaName,
      totalClips: count(),
    })
    .from(videoClips)
    .where(eq(videoClips.uploadStatus, "uploaded"))
    .groupBy(videoClips.clientId, videoClips.arenaName)
    .orderBy(videoClips.arenaName);

  return rows.map((r) => ({
    clientId: r.clientId,
    arenaName: r.arenaName,
    totalClips: Number(r.totalClips),
  }));
}

export type KitClipsSummary = {
  qrCodeId: string | null;
  kitLabel: string;
  totalClips: number;
};

export async function listKitsWithClipsByClientId(clientId: string): Promise<KitClipsSummary[]> {
  const rows = await db
    .select({
      qrCodeId: videoClips.qrCodeId,
      kitLabel: videoClips.kitLabel,
      totalClips: count(),
    })
    .from(videoClips)
    .where(and(eq(videoClips.clientId, clientId), eq(videoClips.uploadStatus, "uploaded")))
    .groupBy(videoClips.qrCodeId, videoClips.kitLabel)
    .orderBy(videoClips.kitLabel);

  return rows.map((r) => ({
    qrCodeId: r.qrCodeId,
    kitLabel: r.kitLabel,
    totalClips: Number(r.totalClips),
  }));
}

export type DateClipsSummary = {
  date: string;
  totalClips: number;
};

export async function listClipDatesByClientAndKit(
  clientId: string,
  qrCodeId: string,
): Promise<DateClipsSummary[]> {
  const rows = await db
    .select({
      date: sql<string>`to_char(${videoClips.recordedAt} AT TIME ZONE 'UTC', 'YYYY-MM-DD')`,
      totalClips: count(),
    })
    .from(videoClips)
    .where(
      and(
        eq(videoClips.clientId, clientId),
        eq(videoClips.qrCodeId, qrCodeId),
        eq(videoClips.uploadStatus, "uploaded"),
      ),
    )
    .groupBy(sql`to_char(${videoClips.recordedAt} AT TIME ZONE 'UTC', 'YYYY-MM-DD')`)
    .orderBy(desc(sql`to_char(${videoClips.recordedAt} AT TIME ZONE 'UTC', 'YYYY-MM-DD')`));

  return rows.map((r) => ({
    date: r.date,
    totalClips: Number(r.totalClips),
  }));
}

function parseUtcDateRange(date: string): { start: Date; end: Date } | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const start = new Date(`${date}T00:00:00.000Z`);
  if (Number.isNaN(start.getTime())) return null;
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 1);
  return { start, end };
}

export async function listClipsByClientKitAndDate(
  clientId: string,
  qrCodeId: string,
  date: string,
): Promise<VideoClipRow[]> {
  const range = parseUtcDateRange(date);
  if (!range) return [];

  return db
    .select(rowSelect)
    .from(videoClips)
    .where(
      and(
        eq(videoClips.clientId, clientId),
        eq(videoClips.qrCodeId, qrCodeId),
        eq(videoClips.uploadStatus, "uploaded"),
        gte(videoClips.recordedAt, range.start),
        lt(videoClips.recordedAt, range.end),
      ),
    )
    .orderBy(asc(videoClips.recordedAt));
}
