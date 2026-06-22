import { Pool } from "pg";
import {
  analyzeDatabaseUrlShape,
  isDatabaseUrlStructurallyValid,
  resolveDatabaseUrlFromEnv,
} from "../shared/db/databaseUrlShape.js";
import { classifyDatabaseError } from "../shared/db/databaseErrorClassification.js";

async function main() {
  console.log(JSON.stringify({ msg: "database_diagnostic_started" }));

  const shape = analyzeDatabaseUrlShape(process.env.DATABASE_URL);
  console.log(JSON.stringify(shape));

  if (!isDatabaseUrlStructurallyValid(shape)) {
    const failure = classifyDatabaseError(
      new Error("invalid_database_url_shape"),
      "invalid_database_url",
    );
    console.error(JSON.stringify(failure));
    process.exit(1);
  }

  const connectionString = resolveDatabaseUrlFromEnv(process.env.DATABASE_URL!);
  const pool = new Pool({ connectionString });

  let connectionOk = false;
  let databaseContextOk = false;
  let videoClipsExists = false;

  try {
    await pool.query("SELECT 1 AS connection_ok");
    connectionOk = true;

    await pool.query(
      "SELECT current_database() AS current_database, current_schema() AS current_schema",
    );
    databaseContextOk = true;

    const tableResult = await pool.query<{ video_clips_exists: boolean }>(
      "SELECT to_regclass('public.video_clips') IS NOT NULL AS video_clips_exists",
    );
    videoClipsExists = tableResult.rows[0]?.video_clips_exists === true;

    console.log(
      JSON.stringify({
        msg: "database_diagnostic_finished",
        success: true,
        connectionOk,
        databaseContextOk,
        videoClipsExists,
      }),
    );
  } catch (error) {
    console.error(JSON.stringify(classifyDatabaseError(error)));
    process.exit(1);
  } finally {
    await pool.end();
  }
}

main();
