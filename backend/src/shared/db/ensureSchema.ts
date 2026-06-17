import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Pool } from "pg";
import { env } from "../config/env.js";

const backendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

let schemaReady: Promise<void> | null = null;

/** Garante que a tabela client_qr_codes existe (migração 006). Idempotente. */
export function ensureDatabaseSchema(): Promise<void> {
  if (!schemaReady) {
    schemaReady = (async () => {
      const pool = new Pool({ connectionString: env.databaseUrl });
      try {
        const sql = readFileSync(resolve(backendRoot, "sql/006_client_qr_codes.sql"), "utf-8");
        await pool.query(sql);
      } finally {
        await pool.end();
      }
    })();
  }
  return schemaReady;
}
