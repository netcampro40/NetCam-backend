-- Múltiplos QR Codes por cliente (um por kit).
CREATE TABLE IF NOT EXISTS client_qr_codes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
  qr_token TEXT NOT NULL UNIQUE,
  label TEXT NOT NULL DEFAULT '',
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_client_qr_codes_client_id ON client_qr_codes (client_id);
CREATE INDEX IF NOT EXISTS idx_client_qr_codes_qr_token ON client_qr_codes (qr_token);
CREATE INDEX IF NOT EXISTS idx_client_qr_codes_is_active ON client_qr_codes (is_active);

DROP TRIGGER IF EXISTS trg_client_qr_codes_updated_at ON client_qr_codes;
CREATE TRIGGER trg_client_qr_codes_updated_at
BEFORE UPDATE ON client_qr_codes
FOR EACH ROW
EXECUTE PROCEDURE set_updated_at();

-- Migra tokens legados (um por cliente) para a nova tabela.
INSERT INTO client_qr_codes (client_id, qr_token, label, is_active)
SELECT c.id, c.qr_token, 'Kit 1', c.is_active
FROM clients c
WHERE NOT EXISTS (
  SELECT 1 FROM client_qr_codes q WHERE q.client_id = c.id
);
