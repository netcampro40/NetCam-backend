import { eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { arenas } from "../../../shared/db/schema/arenas.js";
export async function findArenaByQrToken(qrToken) {
    const result = await db
        .select({
        id: arenas.id,
        name: arenas.name,
        isActive: arenas.isActive,
        qrToken: arenas.qrToken,
    })
        .from(arenas)
        .where(eq(arenas.qrToken, qrToken))
        .limit(1);
    return result[0] ?? null;
}
