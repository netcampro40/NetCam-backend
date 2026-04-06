import { findClientByQrToken } from "../../client/repository/client.repository.js";
const EXPIRES_IN_HOURS = 12;
export async function validateQrToken(qrToken) {
    const client = await findClientByQrToken(qrToken);
    if (!client) {
        return { authorized: false, reason: "invalid_qr" };
    }
    if (client.commercialStatus === "INATIVO") {
        return { authorized: false, reason: "inactive" };
    }
    if (!client.isActive) {
        return { authorized: false, reason: "inactive" };
    }
    return {
        authorized: true,
        arenaId: client.id,
        arenaName: client.nomeFantasia,
        expiresInHours: EXPIRES_IN_HOURS,
    };
}
