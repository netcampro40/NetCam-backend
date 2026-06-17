import { eq } from "drizzle-orm";
import { db } from "../db/client.js";
import { clientQrCodes } from "../db/schema/clientQrCodes.js";
import { clients } from "../db/schema/clients.js";
import { generateQrToken } from "./generateQrToken.js";

async function tokenExists(token: string): Promise<boolean> {
  const [inClients] = await db
    .select({ id: clients.id })
    .from(clients)
    .where(eq(clients.qrToken, token))
    .limit(1);
  if (inClients) return true;

  const [inQrCodes] = await db
    .select({ id: clientQrCodes.id })
    .from(clientQrCodes)
    .where(eq(clientQrCodes.qrToken, token))
    .limit(1);
  return Boolean(inQrCodes);
}

export async function generateUniqueQrToken(maxAttempts = 8): Promise<string> {
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const token = generateQrToken();
    if (!(await tokenExists(token))) return token;
  }
  throw new Error("token_collision");
}

export async function generateUniqueQrTokens(count: number): Promise<string[]> {
  const tokens: string[] = [];
  for (let i = 0; i < count; i++) {
    tokens.push(await generateUniqueQrToken());
  }
  return tokens;
}
