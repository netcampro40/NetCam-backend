import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { Pool } from "pg";
import { env } from "../shared/config/env.js";

/**
 * Ordem intencional:
 * 1) 003 — Se só existir `arenas`, renomeia/evolui para `clients` (não cria tabela vazia antes).
 * 2) 001 — CREATE TABLE clients se ainda não existir (instalação limpa).
 * 3) 004 — Migra coluna legada `address` → campos estruturados; adiciona colunas faltantes.
 * 4) 005 — Backfill de schema para bases com `clients` antigo/incompleto.
 * 5) 002 — Seed de exemplo (só insere se CNPJ e qr_token ainda não existirem; seguro repetir).
 */
async function main() {
  const pool = new Pool({
    connectionString: env.databaseUrl,
  });

  const migrateArenasSql = readFileSync(
    resolve(process.cwd(), "sql/003_migrate_arenas_to_clients.sql"),
    "utf-8",
  );
  const initSql = readFileSync(resolve(process.cwd(), "sql/001_init_clients.sql"), "utf-8");
  const addressSql = readFileSync(
    resolve(process.cwd(), "sql/004_address_split_from_legacy.sql"),
    "utf-8",
  );
  const backfillClientsSchemaSql = readFileSync(
    resolve(process.cwd(), "sql/005_backfill_clients_schema.sql"),
    "utf-8",
  );
  const seedSql = readFileSync(resolve(process.cwd(), "sql/002_seed_clients.sql"), "utf-8");
  const clientQrCodesSql = readFileSync(
    resolve(process.cwd(), "sql/006_client_qr_codes.sql"),
    "utf-8",
  );
  const videoClipsSql = readFileSync(
    resolve(process.cwd(), "sql/007_video_clips.sql"),
    "utf-8",
  );
  const videoClipsRecordedAtSql = readFileSync(
    resolve(process.cwd(), "sql/008_video_clips_recorded_at.sql"),
    "utf-8",
  );

  try {
    await pool.query(migrateArenasSql);
    await pool.query(initSql);
    await pool.query(addressSql);
    await pool.query(backfillClientsSchemaSql);
    await pool.query(clientQrCodesSql);
    await pool.query(videoClipsSql);
    await pool.query(videoClipsRecordedAtSql);
    await pool.query(seedSql);
    console.log(
      "Database: migrate arenas → init clients → address → backfill → QR codes → video clips → recorded_at → seed — OK.",
    );
  } finally {
    await pool.end();
  }
}

main().catch((error) => {
  console.error("Failed to initialize database:", error);
  process.exit(1);
});
