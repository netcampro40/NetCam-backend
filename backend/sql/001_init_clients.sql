CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS clients (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  cnpj TEXT NOT NULL UNIQUE,
  razao_social TEXT NOT NULL DEFAULT '',
  nome_fantasia TEXT NOT NULL,
  addr_cep TEXT NOT NULL DEFAULT '',
  addr_street TEXT NOT NULL DEFAULT '',
  addr_number TEXT NOT NULL DEFAULT '',
  addr_complement TEXT NOT NULL DEFAULT '',
  addr_neighborhood TEXT NOT NULL DEFAULT '',
  addr_city TEXT NOT NULL DEFAULT '',
  addr_state TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  plan TEXT NOT NULL DEFAULT 'ATE_5_QUADRAS',
  billing_type TEXT NOT NULL DEFAULT 'MENSAL',
  plan_value_cents INTEGER NOT NULL DEFAULT 0,
  kits_sold INTEGER NOT NULL DEFAULT 0,
  commercial_status TEXT NOT NULL DEFAULT 'ATIVO',
  qr_token TEXT NOT NULL UNIQUE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_clients_qr_token ON clients (qr_token);
CREATE INDEX IF NOT EXISTS idx_clients_is_active ON clients (is_active);
CREATE INDEX IF NOT EXISTS idx_clients_cnpj ON clients (cnpj);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clients_updated_at ON clients;
CREATE TRIGGER trg_clients_updated_at
BEFORE UPDATE ON clients
FOR EACH ROW
EXECUTE PROCEDURE set_updated_at();
