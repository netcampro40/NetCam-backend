import { eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { clients } from "../../../shared/db/schema/clients.js";
import { findClientByQrCodeToken } from "./clientQrCode.repository.js";
/** Busca cliente pelo token — primeiro na tabela de QRs individuais, depois legado em clients.qr_token. */
export async function findClientByQrToken(qrToken) {
    const fromQrTable = await findClientByQrCodeToken(qrToken);
    if (fromQrTable)
        return fromQrTable;
    const [legacy] = await db
        .select({
        id: clients.id,
        nomeFantasia: clients.nomeFantasia,
        commercialStatus: clients.commercialStatus,
        isActive: clients.isActive,
    })
        .from(clients)
        .where(eq(clients.qrToken, qrToken))
        .limit(1);
    if (!legacy)
        return null;
    return {
        id: legacy.id,
        nomeFantasia: legacy.nomeFantasia,
        commercialStatus: legacy.commercialStatus,
        isQrActive: legacy.isActive,
    };
}
