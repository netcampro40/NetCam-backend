-- Evolução da tabela video_clips para fluxo com recordedAt e galeria online.
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS local_clip_id TEXT;
ALTER TABLE video_clips ADD COLUMN IF NOT EXISTS upload_status TEXT NOT NULL DEFAULT 'uploaded';

UPDATE video_clips
SET recorded_at = uploaded_at
WHERE recorded_at IS NULL;

ALTER TABLE video_clips ALTER COLUMN recorded_at SET DEFAULT NOW();
ALTER TABLE video_clips ALTER COLUMN recorded_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_video_clips_recorded_at ON video_clips (recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_clips_client_qr_recorded ON video_clips (client_id, qr_code_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_clips_upload_status ON video_clips (upload_status);
