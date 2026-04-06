import { desc, eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { arenas } from "../../../shared/db/schema/arenas.js";
export async function listAllArenas() {
    return db
        .select({
        id: arenas.id,
        name: arenas.name,
        qrToken: arenas.qrToken,
        isActive: arenas.isActive,
        createdAt: arenas.createdAt,
        updatedAt: arenas.updatedAt,
    })
        .from(arenas)
        .orderBy(desc(arenas.createdAt));
}
export async function insertArena(name, qrToken) {
    const [row] = await db
        .insert(arenas)
        .values({ name, qrToken, isActive: true })
        .returning({
        id: arenas.id,
        name: arenas.name,
        qrToken: arenas.qrToken,
        isActive: arenas.isActive,
        createdAt: arenas.createdAt,
        updatedAt: arenas.updatedAt,
    });
    if (!row)
        throw new Error("Falha ao criar arena");
    return row;
}
export async function setArenaActive(id, isActive) {
    const [row] = await db
        .update(arenas)
        .set({ isActive })
        .where(eq(arenas.id, id))
        .returning({
        id: arenas.id,
        name: arenas.name,
        qrToken: arenas.qrToken,
        isActive: arenas.isActive,
        createdAt: arenas.createdAt,
        updatedAt: arenas.updatedAt,
    });
    return row ?? null;
}
export async function updateArenaQrToken(id, qrToken) {
    const [row] = await db
        .update(arenas)
        .set({ qrToken })
        .where(eq(arenas.id, id))
        .returning({
        id: arenas.id,
        name: arenas.name,
        qrToken: arenas.qrToken,
        isActive: arenas.isActive,
        createdAt: arenas.createdAt,
        updatedAt: arenas.updatedAt,
    });
    return row ?? null;
}
export async function findArenaById(id) {
    const [row] = await db
        .select({
        id: arenas.id,
        name: arenas.name,
        qrToken: arenas.qrToken,
        isActive: arenas.isActive,
        createdAt: arenas.createdAt,
        updatedAt: arenas.updatedAt,
    })
        .from(arenas)
        .where(eq(arenas.id, id))
        .limit(1);
    return row ?? null;
}
