import { asc, eq, inArray } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { clientQrCodes } from "../../../shared/db/schema/clientQrCodes.js";
import { clients } from "../../../shared/db/schema/clients.js";
import {
  generateUniqueQrTokens,
} from "../../../shared/security/uniqueQrToken.js";

export type ClientQrCodeRow = {
  id: string;
  clientId: string;
  qrToken: string;
  label: string;
  isActive: boolean;
  createdAt: Date;
  updatedAt: Date;
};

const rowSelect = {
  id: clientQrCodes.id,
  clientId: clientQrCodes.clientId,
  qrToken: clientQrCodes.qrToken,
  label: clientQrCodes.label,
  isActive: clientQrCodes.isActive,
  createdAt: clientQrCodes.createdAt,
  updatedAt: clientQrCodes.updatedAt,
};

export type NewClientQrCodeInput = {
  clientId: string;
  qrToken: string;
  label: string;
  isActive: boolean;
};

export async function insertClientQrCodes(
  inputs: NewClientQrCodeInput[],
): Promise<ClientQrCodeRow[]> {
  if (inputs.length === 0) return [];
  return db.insert(clientQrCodes).values(inputs).returning(rowSelect);
}

export async function listQrCodesByClientId(clientId: string): Promise<ClientQrCodeRow[]> {
  return db
    .select(rowSelect)
    .from(clientQrCodes)
    .where(eq(clientQrCodes.clientId, clientId))
    .orderBy(asc(clientQrCodes.createdAt));
}

export async function listQrCodesByClientIds(
  clientIds: string[],
): Promise<ClientQrCodeRow[]> {
  if (clientIds.length === 0) return [];
  return db
    .select(rowSelect)
    .from(clientQrCodes)
    .where(inArray(clientQrCodes.clientId, clientIds))
    .orderBy(asc(clientQrCodes.clientId), asc(clientQrCodes.createdAt));
}

export async function findQrCodeById(id: string): Promise<ClientQrCodeRow | null> {
  const [row] = await db
    .select(rowSelect)
    .from(clientQrCodes)
    .where(eq(clientQrCodes.id, id))
    .limit(1);
  return row ?? null;
}

export async function findQrCodeByToken(qrToken: string): Promise<ClientQrCodeRow | null> {
  const [row] = await db
    .select(rowSelect)
    .from(clientQrCodes)
    .where(eq(clientQrCodes.qrToken, qrToken))
    .limit(1);
  return row ?? null;
}

export async function findClientByQrCodeToken(qrToken: string) {
  const [row] = await db
    .select({
      id: clients.id,
      nomeFantasia: clients.nomeFantasia,
      commercialStatus: clients.commercialStatus,
      isQrActive: clientQrCodes.isActive,
    })
    .from(clientQrCodes)
    .innerJoin(clients, eq(clientQrCodes.clientId, clients.id))
    .where(eq(clientQrCodes.qrToken, qrToken))
    .limit(1);
  return row ?? null;
}

export async function updateQrCodeActive(
  id: string,
  isActive: boolean,
): Promise<ClientQrCodeRow | null> {
  const [row] = await db
    .update(clientQrCodes)
    .set({ isActive })
    .where(eq(clientQrCodes.id, id))
    .returning(rowSelect);
  return row ?? null;
}

export async function updateQrCodeToken(
  id: string,
  qrToken: string,
): Promise<ClientQrCodeRow | null> {
  const [row] = await db
    .update(clientQrCodes)
    .set({ qrToken })
    .where(eq(clientQrCodes.id, id))
    .returning(rowSelect);
  return row ?? null;
}

export type EnsureQrCodesResult = {
  qrCodes: ClientQrCodeRow[];
  created: number;
};

/**
 * Garante que o cliente tenha exatamente `kitsSold` QR Codes (cria os que faltam).
 * Não remove QRs existentes se kitsSold diminuir (kits já impressos).
 */
export async function ensureClientQrCodesForKitCount(
  clientId: string,
  kitsSold: number,
  isActive: boolean,
  onTokenCreated?: (token: string, label: string) => void,
): Promise<EnsureQrCodesResult> {
  const existing = await listQrCodesByClientId(clientId);

  if (kitsSold <= 0) {
    return { qrCodes: existing, created: 0 };
  }

  const missing = kitsSold - existing.length;
  if (missing <= 0) {
    return { qrCodes: existing, created: 0 };
  }

  const startIndex = existing.length;
  const tokens = await generateUniqueQrTokens(missing);
  const inputs = tokens.map((token, i) => {
    const label = `Kit ${startIndex + i + 1}`;
    onTokenCreated?.(token, label);
    return {
      clientId,
      qrToken: token,
      label,
      isActive,
    };
  });
  const newRows = await insertClientQrCodes(inputs);

  return {
    qrCodes: [...existing, ...newRows],
    created: newRows.length,
  };
}
