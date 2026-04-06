/**
 * Consulta pública de CNPJ via BrasilAPI (sem chave).
 * Doc: https://brasilapi.com.br/docs#tag/CNPJ
 */
export type CnpjLookupResult = {
  razaoSocial: string;
  nomeFantasia: string;
  phone: string;
  email: string;
  addrCep: string;
  addrStreet: string;
  addrNumber: string;
  addrComplement: string;
  addrNeighborhood: string;
  addrCity: string;
  addrState: string;
};

type BrasilApiCnpj = {
  razao_social?: string;
  nome_fantasia?: string;
  email?: string | null;
  ddd_telefone_1?: string | null;
  telefone_1?: string | null;
  ddd_telefone_2?: string | null;
  telefone_2?: string | null;
  logradouro?: string | null;
  numero?: string | null;
  complemento?: string | null;
  bairro?: string | null;
  municipio?: string | null;
  uf?: string | null;
  cep?: string | null;
};

function onlyDigits(s: string): string {
  return s.replace(/\D/g, "");
}

function buildPhone(j: BrasilApiCnpj): string {
  const a =
    j.ddd_telefone_1 && j.telefone_1
      ? `(${j.ddd_telefone_1}) ${j.telefone_1}`
      : "";
  const b =
    j.ddd_telefone_2 && j.telefone_2
      ? `(${j.ddd_telefone_2}) ${j.telefone_2}`
      : "";
  return [a, b].filter(Boolean).join(" / ");
}

export async function fetchCnpjFromBrasilApi(
  cnpj14: string,
): Promise<CnpjLookupResult | null> {
  const url = `https://brasilapi.com.br/api/cnpj/v1/${cnpj14}`;
  const res = await fetch(url, { method: "GET" });
  if (res.status === 404) return null;
  if (!res.ok) {
    throw new Error(`brasilapi_status_${res.status}`);
  }
  const j = (await res.json()) as BrasilApiCnpj;
  const cep = onlyDigits(j.cep ?? "").slice(0, 8);
  const uf = (j.uf ?? "").trim().toUpperCase().slice(0, 2);
  return {
    razaoSocial: (j.razao_social ?? "").trim(),
    nomeFantasia: (j.nome_fantasia ?? "").trim(),
    phone: buildPhone(j),
    email: (j.email ?? "").trim(),
    addrCep: cep,
    addrStreet: (j.logradouro ?? "").trim(),
    addrNumber: (j.numero ?? "").trim(),
    addrComplement: (j.complemento ?? "").trim(),
    addrNeighborhood: (j.bairro ?? "").trim(),
    addrCity: (j.municipio ?? "").trim(),
    addrState: uf,
  };
}
