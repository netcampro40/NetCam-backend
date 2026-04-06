import { desc, eq } from "drizzle-orm";
import { db } from "../../../shared/db/client.js";
import { clients } from "../../../shared/db/schema/clients.js";
const rowSelect = {
    id: clients.id,
    cnpj: clients.cnpj,
    razaoSocial: clients.razaoSocial,
    nomeFantasia: clients.nomeFantasia,
    addrCep: clients.addrCep,
    addrStreet: clients.addrStreet,
    addrNumber: clients.addrNumber,
    addrComplement: clients.addrComplement,
    addrNeighborhood: clients.addrNeighborhood,
    addrCity: clients.addrCity,
    addrState: clients.addrState,
    phone: clients.phone,
    email: clients.email,
    plan: clients.plan,
    billingType: clients.billingType,
    planValueCents: clients.planValueCents,
    kitsSold: clients.kitsSold,
    commercialStatus: clients.commercialStatus,
    qrToken: clients.qrToken,
    isActive: clients.isActive,
    createdAt: clients.createdAt,
    updatedAt: clients.updatedAt,
};
export async function listAllClients() {
    return db.select(rowSelect).from(clients).orderBy(desc(clients.createdAt));
}
export async function insertClient(input) {
    const [row] = await db
        .insert(clients)
        .values({
        cnpj: input.cnpj,
        razaoSocial: input.razaoSocial,
        nomeFantasia: input.nomeFantasia,
        addrCep: input.addrCep,
        addrStreet: input.addrStreet,
        addrNumber: input.addrNumber,
        addrComplement: input.addrComplement,
        addrNeighborhood: input.addrNeighborhood,
        addrCity: input.addrCity,
        addrState: input.addrState,
        phone: input.phone,
        email: input.email,
        plan: input.plan,
        billingType: input.billingType,
        planValueCents: input.planValueCents,
        kitsSold: input.kitsSold,
        commercialStatus: input.commercialStatus,
        qrToken: input.qrToken,
        isActive: input.isActive,
    })
        .returning(rowSelect);
    if (!row)
        throw new Error("Falha ao criar cliente");
    return row;
}
function omitUndefined(patch) {
    return Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined));
}
export async function updateClient(id, patch) {
    const clean = omitUndefined(patch);
    if (Object.keys(clean).length === 0) {
        return findClientById(id);
    }
    const [row] = await db
        .update(clients)
        .set(clean)
        .where(eq(clients.id, id))
        .returning(rowSelect);
    return row ?? null;
}
export async function setClientActive(id, isActive) {
    return updateClient(id, { isActive });
}
export async function updateClientQrToken(id, qrToken) {
    const [row] = await db
        .update(clients)
        .set({ qrToken })
        .where(eq(clients.id, id))
        .returning(rowSelect);
    return row ?? null;
}
export async function findClientById(id) {
    const [row] = await db
        .select(rowSelect)
        .from(clients)
        .where(eq(clients.id, id))
        .limit(1);
    return row ?? null;
}
export async function findClientByCnpj(cnpj) {
    const [row] = await db
        .select(rowSelect)
        .from(clients)
        .where(eq(clients.cnpj, cnpj))
        .limit(1);
    return row ?? null;
}
/** Remove o registro do banco (exclusão permanente). Retorna true se havia linha com esse id. */
export async function deleteClientById(id) {
    const result = await db.delete(clients).where(eq(clients.id, id)).returning({ id: clients.id });
    return result.length > 0;
}
