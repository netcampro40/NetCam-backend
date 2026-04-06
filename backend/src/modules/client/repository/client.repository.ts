import { eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { clients } from "../../../shared/db/schema/clients.js";

export async function findClientByQrToken(qrToken: string) {
  const result = await db
    .select({
      id: clients.id,
      nomeFantasia: clients.nomeFantasia,
      isActive: clients.isActive,
      qrToken: clients.qrToken,
      commercialStatus: clients.commercialStatus,
    })
    .from(clients)
    .where(eq(clients.qrToken, qrToken))
    .limit(1);

  return result[0] ?? null;
}
