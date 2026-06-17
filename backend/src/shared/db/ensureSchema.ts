import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Pool } from "pg";
import { env } from "../config/env.js";

const backendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

let schemaReady: Promise<void> | null = null;

/** Garante migrações SQL idempotentes no startup. */
export function ensureDatabaseSchema(): Promise<void> {
  if (!schemaReady) {
    schemaReady = (async () => {
      const pool = new Pool({ connectionString: env.databaseUrl });
      try {
        const migrations = [
          "006_client_qr_codes.sql",
          "007_video_clips.sql",
          "008_video_clips_recorded_at.sql",
          "009_video_clips_original_preview.sql",
        ];
        for (const file of migrations) {
          const sql = readFileSync(resolve(backendRoot, "sql", file), "utf-8");
          await pool.query(sql);
        }
      } finally {
        await pool.end();
      }
    })();
  }
  return schemaReady;
}
