-- Migração opcional: tabela legada `arenas` → `clients`.
-- Deve rodar ANTES de 001_init_clients.sql quando só existe `arenas` (evita criar clients vazio e perder o rename).

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
  has_arenas boolean;
  has_clients boolean;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'arenas'
  ) INTO has_arenas;

  SELECT EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'clients'
  ) INTO has_clients;

  IF has_arenas AND has_clients THEN
    INSERT INTO clients (
      cnpj,
      razao_social,
      nome_fantasia,
      qr_token,
      is_active,
      commercial_status,
      created_at,
      updated_at
    )
    SELECT
      '9' || LPAD((ROW_NUMBER() OVER (ORDER BY created_at))::text, 13, '0'),
      COALESCE(name, ''),
      COALESCE(name, ''),
      qr_token,
      is_active,
      CASE WHEN is_active THEN 'ATIVO' ELSE 'INATIVO' END,
      created_at,
      updated_at
    FROM arenas
    ON CONFLICT (qr_token) DO NOTHING;

    DROP TABLE arenas CASCADE;
  ELSIF has_arenas AND NOT has_clients THEN
    ALTER TABLE arenas RENAME TO clients;
    ALTER TABLE clients RENAME COLUMN name TO nome_fantasia;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS cnpj TEXT;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS razao_social TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_cep TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_street TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_number TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_complement TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_neighborhood TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_city TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_state TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS phone TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS email TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS plan TEXT NOT NULL DEFAULT 'ATE_5_QUADRAS';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS billing_type TEXT NOT NULL DEFAULT 'MENSAL';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS plan_value_cents INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS kits_sold INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS commercial_status TEXT NOT NULL DEFAULT 'ATIVO';

    UPDATE clients c
    SET cnpj = LPAD(s.rn::text, 14, '0')
    FROM (
      SELECT id, ROW_NUMBER() OVER (ORDER BY created_at) AS rn FROM clients
    ) s
    WHERE c.id = s.id AND (c.cnpj IS NULL OR c.cnpj = '');

    ALTER TABLE clients ALTER COLUMN cnpj SET NOT NULL;
    CREATE UNIQUE INDEX IF NOT EXISTS clients_cnpj_key ON clients (cnpj);

    DROP TRIGGER IF EXISTS trg_arenas_updated_at ON clients;
    DROP TRIGGER IF EXISTS trg_clients_updated_at ON clients;
    CREATE TRIGGER trg_clients_updated_at
    BEFORE UPDATE ON clients
    FOR EACH ROW
    EXECUTE PROCEDURE set_updated_at();

    ALTER INDEX IF EXISTS idx_arenas_qr_token RENAME TO idx_clients_qr_token;
    ALTER INDEX IF EXISTS idx_arenas_is_active RENAME TO idx_clients_is_active;
  END IF;
END $$;
