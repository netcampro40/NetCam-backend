import { runExpiredClipCleanup } from "../modules/clips/application/runExpiredClipCleanup.js";

async function main() {
  const dryRun = process.argv.includes("--dry-run");

  try {
    const result = await runExpiredClipCleanup({ dryRun });
    if (result.failedCount > 0 && !dryRun) {
      process.exitCode = 1;
    }
  } catch (error) {
    console.error(
      JSON.stringify({
        msg: "expired_clip_cleanup_failed",
        error: error instanceof Error ? error.message : String(error),
      }),
    );
    process.exit(1);
  }
}

main();
