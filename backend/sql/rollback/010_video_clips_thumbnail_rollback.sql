-- Rollback da migração 010 (thumbnail opcional).
ALTER TABLE video_clips DROP COLUMN IF EXISTS thumbnail_file_url;
ALTER TABLE video_clips DROP COLUMN IF EXISTS thumbnail_file_key;
