export type ClientPlan = "ATE_5_QUADRAS" | "ATE_10_QUADRAS" | "ACIMA_10_QUADRAS";
export type BillingType = "MENSAL" | "ANUAL";
export type CommercialStatus = "ATIVO" | "INATIVO";

export type Client = {
  id: string;
  cnpj: string;
  razaoSocial: string;
  nomeFantasia: string;
  addrCep: string;
  addrStreet: string;
  addrNumber: string;
  addrComplement: string;
  addrNeighborhood: string;
  addrCity: string;
  addrState: string;
  phone: string;
  email: string;
  plan: ClientPlan;
  billingType: BillingType;
  planValueCents: number;
  kitsSold: number;
  commercialStatus: CommercialStatus;
  qrToken: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
};

export type CnpjLookupData = {
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

const apiBase = import.meta.env.VITE_API_BASE ?? "";
const adminKey = import.meta.env.VITE_ADMIN_KEY ?? "";

function buildHeaders(json = true): HeadersInit {
  const h: Record<string, string> = {};
  if (json) h["Content-Type"] = "application/json";
  if (adminKey) h["X-Admin-Key"] = adminKey;
  return h;
}

async function parseError(res: Response): Promise<string> {
  try {
    const j = (await res.json()) as { error?: string; message?: string };
    if (j.message) return `${j.error ?? "error"}: ${j.message}`;
    return j.error ?? res.statusText;
  } catch {
    return await res.text();
  }
}

export async function listClients(): Promise<Client[]> {
  const res = await fetch(`${apiBase}/api/admin/clients`, {
    headers: buildHeaders(false),
  });
  if (!res.ok) throw new Error(await parseError(res));
  const data = (await res.json()) as { clients: Client[] };
  return data.clients;
}

export async function getClient(id: string): Promise<Client> {
  const res = await fetch(`${apiBase}/api/admin/clients/${id}`, {
    headers: buildHeaders(false),
  });
  if (!res.ok) throw new Error(await parseError(res));
  const data = (await res.json()) as { client: Client };
  return data.client;
}

export type CreateClientBody = {
  cnpj: string;
  razaoSocial?: string;
  nomeFantasia: string;
  addrCep?: string;
  addrStreet?: string;
  addrNumber?: string;
  addrComplement?: string;
  addrNeighborhood?: string;
  addrCity?: string;
  addrState?: string;
  phone?: string;
  email?: string;
  plan?: ClientPlan;
  billingType?: BillingType;
  planValueCents?: number;
  kitsSold?: number;
  commercialStatus?: CommercialStatus;
  isActive?: boolean;
};

export async function createClient(body: CreateClientBody): Promise<Client> {
  const res = await fetch(`${apiBase}/api/admin/clients`, {
    method: "POST",
    headers: buildHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await parseError(res));
  const data = (await res.json()) as { client: Client };
  return data.client;
}

export type PatchClientBody = Partial<{
  cnpj: string;
  razaoSocial: string;
  nomeFantasia: string;
  addrCep: string;
  addrStreet: string;
  addrNumber: string;
  addrComplement: string;
  addrNeighborhood: string;
  addrCity: string;
  addrState: string;
  phone: string;
  email: string;
  plan: ClientPlan;
  billingType: BillingType;
  planValueCents: number;
  kitsSold: number;
  commercialStatus: CommercialStatus;
  isActive: boolean;
}>;

export async function patchClient(id: string, body: PatchClientBody): Promise<Client> {
  const res = await fetch(`${apiBase}/api/admin/clients/${id}`, {
    method: "PATCH",
    headers: buildHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await parseError(res));
  const data = (await res.json()) as { client: Client };
  return data.client;
}

export async function regenerateClientToken(id: string): Promise<Client> {
  const res = await fetch(`${apiBase}/api/admin/clients/${id}/regenerate-token`, {
    method: "POST",
    headers: buildHeaders(false),
  });
  if (!res.ok) throw new Error(await parseError(res));
  const data = (await res.json()) as { client: Client };
  return data.client;
}

export async function deleteClient(id: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/admin/clients/${id}`, {
    method: "DELETE",
    headers: buildHeaders(false),
  });
  if (res.status === 204) return;
  if (!res.ok) throw new Error(await parseError(res));
}

export async function lookupCnpj(cnpjDigitsOrFormatted: string): Promise<CnpjLookupData | null> {
  const res = await fetch(
    `${apiBase}/api/admin/lookup-cnpj/${encodeURIComponent(cnpjDigitsOrFormatted)}`,
    { headers: buildHeaders(false) },
  );
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(await parseError(res));
  const json = (await res.json()) as { data: CnpjLookupData };
  return json.data;
}
