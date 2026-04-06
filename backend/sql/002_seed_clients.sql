-- Seed de exemplo: idempotente. Não insere se já existir linha com o mesmo CNPJ ou o mesmo qr_token.
-- Evita duplicate key em qr_token quando a base já foi migrada de arenas (mesmo token, CNPJ sintético).

INSERT INTO clients (
  cnpj,
  razao_social,
  nome_fantasia,
  addr_cep,
  addr_street,
  addr_number,
  addr_complement,
  addr_neighborhood,
  addr_city,
  addr_state,
  phone,
  email,
  plan,
  billing_type,
  plan_value_cents,
  kits_sold,
  commercial_status,
  qr_token,
  is_active
)
SELECT
  '11111111000191',
  'Arena Exemplo LTDA',
  'Arena Exemplo Ativa',
  '01310100',
  'Rua Exemplo',
  '100',
  '',
  'Centro',
  'São Paulo',
  'SP',
  '1133334444',
  'contato@arenaexemplo.com',
  'ATE_5_QUADRAS',
  'MENSAL',
  9900,
  12,
  'ATIVO',
  '8F4K2L9X7M2P',
  TRUE
WHERE NOT EXISTS (
  SELECT 1 FROM clients WHERE cnpj = '11111111000191' OR qr_token = '8F4K2L9X7M2P'
);

INSERT INTO clients (
  cnpj,
  razao_social,
  nome_fantasia,
  addr_cep,
  addr_street,
  addr_number,
  addr_complement,
  addr_neighborhood,
  addr_city,
  addr_state,
  phone,
  email,
  plan,
  billing_type,
  plan_value_cents,
  kits_sold,
  commercial_status,
  qr_token,
  is_active
)
SELECT
  '22222222000100',
  'Arena Inativa SA',
  'Arena Exemplo Inativa',
  '',
  '',
  '',
  '',
  '',
  '',
  '',
  '',
  '',
  'ATE_10_QUADRAS',
  'ANUAL',
  0,
  0,
  'INATIVO',
  'INATIVA12345',
  FALSE
WHERE NOT EXISTS (
  SELECT 1 FROM clients WHERE cnpj = '22222222000100' OR qr_token = 'INATIVA12345'
);
