-- Original (alta qualidade) + preview (reprodução rápida) por clipe.
-- file_key/file_url legados permanecem; original_* recebe backfill dos existentes.
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS original_file_key TEXT;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS original_file_url TEXT;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS preview_file_key TEXT;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS preview_file_url TEXT;

UPDATE video_clips
SET
  original_file_key = file_key,
  original_file_url = file_url
WHERE original_file_key IS NULL
  AND file_key IS NOT NULL
  AND file_key <> '';
