-- Clipes de vídeo enviados pelos apps (metadados; arquivo no S3).
CREATE TABLE IF NOT EXISTS video_clips (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
  qr_code_id UUID REFERENCES client_qr_codes(id) ON DELETE SET NULL,
  arena_name TEXT NOT NULL DEFAULT '',
  kit_label TEXT NOT NULL DEFAULT '',
  file_key TEXT NOT NULL UNIQUE,
  file_url TEXT NOT NULL DEFAULT '',
  original_filename TEXT NOT NULL DEFAULT '',
  mime_type TEXT NOT NULL DEFAULT '',
  size_bytes BIGINT NOT NULL DEFAULT 0,
  duration_seconds INTEGER,
  source_platform TEXT NOT NULL DEFAULT 'android',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_video_clips_client_id ON video_clips (client_id);
CREATE INDEX IF NOT EXISTS idx_video_clips_uploaded_at ON video_clips (uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_clips_qr_code_id ON video_clips (qr_code_id);
