import {
  bigint,
  index,
  integer,
  pgTable,
  text,
  timestamp,
  uuid,
} from "drizzle-orm/pg-core";
import { clientQrCodes } from "./clientQrCodes.js";
import { clients } from "./clients.js";

/** Metadados de clipe de vídeo armazenado no S3. */
export const videoClips = pgTable(
  "video_clips",
  {
    id: uuid("id").defaultRandom().primaryKey(),
    clientId: uuid("client_id")
      .notNull()
      .references(() => clients.id, { onDelete: "cascade" }),
    qrCodeId: uuid("qr_code_id").references(() => clientQrCodes.id, {
      onDelete: "set null",
    }),
    arenaName: text("arena_name").notNull().default(""),
    kitLabel: text("kit_label").notNull().default(""),
    fileKey: text("file_key").notNull().unique(),
    fileUrl: text("file_url").notNull().default(""),
    originalFilename: text("original_filename").notNull().default(""),
    mimeType: text("mime_type").notNull().default(""),
    sizeBytes: bigint("size_bytes", { mode: "number" }).notNull().default(0),
    durationSeconds: integer("duration_seconds"),
    sourcePlatform: text("source_platform").notNull().default("android"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    expiresAt: timestamp("expires_at", { withTimezone: true }),
    uploadedAt: timestamp("uploaded_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => ({
    clientIdIdx: index("idx_video_clips_client_id").on(table.clientId),
    uploadedAtIdx: index("idx_video_clips_uploaded_at").on(table.uploadedAt),
    qrCodeIdIdx: index("idx_video_clips_qr_code_id").on(table.qrCodeId),
  }),
);
