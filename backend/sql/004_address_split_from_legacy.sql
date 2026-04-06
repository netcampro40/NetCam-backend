-- Migra coluna legada `address` (texto único) para campos estruturados, se existir.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'clients' AND column_name = 'address'
  ) THEN
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_cep TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_street TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_number TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_complement TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_neighborhood TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_city TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_state TEXT NOT NULL DEFAULT '';

    UPDATE clients
    SET addr_street = TRIM(address)
    WHERE TRIM(COALESCE(addr_street, '')) = ''
      AND address IS NOT NULL
      AND TRIM(address) <> '';

    ALTER TABLE clients DROP COLUMN address;
  END IF;
END $$;

-- Bases antigas sem colunas estruturadas (somente se a tabela clients existir).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'clients'
  ) THEN
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_cep TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_street TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_number TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_complement TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_neighborhood TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_city TEXT NOT NULL DEFAULT '';
    ALTER TABLE clients ADD COLUMN IF NOT EXISTS addr_state TEXT NOT NULL DEFAULT '';
  END IF;
END $$;
