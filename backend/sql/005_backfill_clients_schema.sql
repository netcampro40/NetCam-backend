-- Backfill seguro para bases locais que já tinham tabela `clients`
-- com schema parcial/desatualizado.
-- Não remove dados nem dropa tabela; apenas adiciona/normaliza colunas esperadas.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'clients'
  ) THEN
    -- Colunas-base esperadas pelo backend/admin.
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS cnpj TEXT;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS razao_social TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS nome_fantasia TEXT NOT NULL DEFAULT '';
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
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS qr_token TEXT;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

    -- Normalizações mínimas para suportar constraints sem perda de dados.
    UPDATE clients c
    SET cnpj = LPAD(s.rn::text, 14, '0')
    FROM (
      SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS rn
      FROM clients
    ) s
    WHERE c.id = s.id
      AND (c.cnpj IS NULL OR TRIM(c.cnpj) = '');

    UPDATE clients
    SET qr_token = 'MIGRATED_' || SUBSTRING(md5(id::text || NOW()::text) FROM 1 FOR 12)
    WHERE qr_token IS NULL OR TRIM(qr_token) = '';

    ALTER TABLE clients ALTER COLUMN cnpj SET NOT NULL;
    ALTER TABLE clients ALTER COLUMN qr_token SET NOT NULL;

    -- Índices/uniqueness usados pela aplicação.
    CREATE UNIQUE INDEX IF NOT EXISTS clients_cnpj_key ON clients (cnpj);
    CREATE UNIQUE INDEX IF NOT EXISTS clients_qr_token_key ON clients (qr_token);
    CREATE INDEX IF NOT EXISTS idx_clients_qr_token ON clients (qr_token);
    CREATE INDEX IF NOT EXISTS idx_clients_is_active ON clients (is_active);
    CREATE INDEX IF NOT EXISTS idx_clients_cnpj ON clients (cnpj);

    -- Trigger de updated_at.
    CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER AS $fn$
    BEGIN
      NEW.updated_at = NOW();
      RETURN NEW;
    END;
    $fn$ LANGUAGE plpgsql;

    DROP TRIGGER IF EXISTS trg_clients_updated_at ON clients;
    CREATE TRIGGER trg_clients_updated_at
    BEFORE UPDATE ON clients
    FOR EACH ROW
    EXECUTE PROCEDURE set_updated_at();
  END IF;
END $$;
