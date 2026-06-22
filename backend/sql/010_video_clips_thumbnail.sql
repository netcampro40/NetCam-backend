-- Thumbnail JPEG opcional por clipe (capa da Galeria Online).
-- Clipes antigos permanecem com thumbnail_* NULL.
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS thumbnail_file_key TEXT;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS thumbnail_file_url TEXT;
