import { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import { env } from "../../../shared/config/env.js";
import { normalizeCnpj } from "../../../shared/cnpj/normalizeCnpj.js";
import { fetchCnpjFromBrasilApi } from "./brasilApiCnpj.js";
import {
  deleteClientById,
  findClientByCnpj,
  findClientById,
  insertClient,
  listAllClients,
  updateClient,
  updateClientQrToken,
  type ClientRow,
} from "../../client/repository/client.admin.repository.js";
import {
  ensureClientQrCodesForKitCount,
  findQrCodeById,
  listQrCodesByClientId,
  listQrCodesByClientIds,
  updateQrCodeActive,
  updateQrCodeToken,
  type ClientQrCodeRow,
} from "../../client/repository/clientQrCode.repository.js";
import { generateUniqueQrToken } from "../../../shared/security/uniqueQrToken.js";

const planSchema = z.enum(["ATE_5_QUADRAS", "ATE_10_QUADRAS", "ACIMA_10_QUADRAS"]);
const billingSchema = z.enum(["MENSAL", "ANUAL"]);
const commercialStatusSchema = z.enum(["ATIVO", "INATIVO"]);

const strOpt = z.string().max(500).optional().default("");

const createBodySchema = z.object({
  cnpj: z.string().min(8),
  razaoSocial: strOpt,
  nomeFantasia: z.string().trim().min(1).max(200),
  addrCep: z.string().max(16).optional().default(""),
  addrStreet: z.string().max(500).optional().default(""),
  addrNumber: z.string().max(50).optional().default(""),
  addrComplement: z.string().max(200).optional().default(""),
  addrNeighborhood: z.string().max(200).optional().default(""),
  addrCity: z.string().max(200).optional().default(""),
  addrState: z.string().max(2).optional().default(""),
  phone: z.string().max(80).optional().default(""),
  email: z.string().max(320).optional().default(""),
  plan: planSchema.optional().default("ATE_5_QUADRAS"),
  billingType: billingSchema.optional().default("MENSAL"),
  planValueCents: z.number().int().min(0).optional().default(0),
  kitsSold: z.number().int().min(0).optional().default(0),
  commercialStatus: commercialStatusSchema.optional().default("ATIVO"),
  isActive: z.boolean().optional().default(true),
});

const patchBodySchema = z
  .object({
    cnpj: z.string().min(8).optional(),
    razaoSocial: z.string().max(500).optional(),
    nomeFantasia: z.string().trim().min(1).max(200).optional(),
    addrCep: z.string().max(16).optional(),
    addrStreet: z.string().max(500).optional(),
    addrNumber: z.string().max(50).optional(),
    addrComplement: z.string().max(200).optional(),
    addrNeighborhood: z.string().max(200).optional(),
    addrCity: z.string().max(200).optional(),
    addrState: z.string().max(2).optional(),
    phone: z.string().max(80).optional(),
    email: z.string().max(320).optional(),
    plan: planSchema.optional(),
    billingType: billingSchema.optional(),
    planValueCents: z.number().int().min(0).optional(),
    kitsSold: z.number().int().min(0).optional(),
    commercialStatus: commercialStatusSchema.optional(),
    isActive: z.boolean().optional(),
  })
  .refine((o) => Object.keys(o).length > 0, { message: "empty_patch" });

const patchQrCodeBodySchema = z.object({
  isActive: z.boolean(),
});

const generateQrCodesBodySchema = z
  .object({
    kitsSold: z.number().int().min(0).optional(),
  })
  .optional();

function toIso(value: unknown): string {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toISOString();
  }
  if (typeof value === "string" && value.length > 0) {
    const d = new Date(value);
    if (!Number.isNaN(d.getTime())) return d.toISOString();
  }
  return new Date().toISOString();
}

function serializeQrCode(row: ClientQrCodeRow) {
  return {
    id: row.id,
    clientId: row.clientId,
    qrToken: row.qrToken,
    label: row.label,
    isActive: row.isActive,
    createdAt: toIso(row.createdAt),
    updatedAt: toIso(row.updatedAt),
  };
}

function serializeClient(row: ClientRow, qrCodes: ClientQrCodeRow[] = []) {
  return {
    id: row.id,
    cnpj: row.cnpj,
    razaoSocial: row.razaoSocial,
    nomeFantasia: row.nomeFantasia,
    addrCep: row.addrCep,
    addrStreet: row.addrStreet,
    addrNumber: row.addrNumber,
    addrComplement: row.addrComplement,
    addrNeighborhood: row.addrNeighborhood,
    addrCity: row.addrCity,
    addrState: row.addrState,
    phone: row.phone,
    email: row.email,
    plan: row.plan,
    billingType: row.billingType,
    planValueCents: row.planValueCents,
    kitsSold: row.kitsSold,
    commercialStatus: row.commercialStatus,
    qrToken: row.qrToken,
    isActive: row.isActive,
    qrCodes: qrCodes.map(serializeQrCode),
    createdAt: toIso(row.createdAt),
    updatedAt: toIso(row.updatedAt),
  };
}

function groupQrCodesByClient(rows: ClientQrCodeRow[]): Map<string, ClientQrCodeRow[]> {
  const map = new Map<string, ClientQrCodeRow[]>();
  for (const row of rows) {
    const list = map.get(row.clientId) ?? [];
    list.push(row);
    map.set(row.clientId, list);
  }
  return map;
}

async function loadClientWithQrCodes(id: string) {
  const row = await findClientById(id);
  if (!row) return null;
  const qrCodes = await listQrCodesByClientId(id);
  return { row, qrCodes };
}

async function generateMissingQrCodes(
  request: FastifyRequest,
  clientId: string,
  kitsSold: number,
  clientRow: ClientRow,
) {
  const existing = await listQrCodesByClientId(clientId);
  const qrCodesToCreate = Math.max(0, kitsSold - existing.length);

  request.log.info(
    {
      clientId,
      kits_sold: kitsSold,
      existingQrCodes: existing.length,
      qrCodesToCreate,
    },
    "generate_qr_codes_requested",
  );

  if (kitsSold <= 0) {
    return {
      status: 400 as const,
      error: "no_active_kits",
      message: "A arena ainda não possui kits ativos.",
    };
  }

  if (existing.length >= kitsSold) {
    request.log.info(
      { clientId, kits_sold: kitsSold, existingQrCodes: existing.length },
      "generate_qr_codes_already_complete",
    );
    return {
      status: 200 as const,
      row: clientRow,
      qrCodes: existing,
      created: 0,
      message: "QR Codes já estão completos para a quantidade de kits.",
    };
  }

  const isActiveQr =
    clientRow.commercialStatus === "INATIVO" ? false : clientRow.isActive;

  const { qrCodes, created } = await ensureClientQrCodesForKitCount(
    clientId,
    kitsSold,
    isActiveQr,
    (token, label) => {
      request.log.info({ clientId, label, token }, "generate_qr_codes_created_token");
    },
  );

  let row = clientRow;
  if (qrCodes.length > 0 && qrCodes[0]!.qrToken !== row.qrToken) {
    const updated = await updateClientQrToken(clientId, qrCodes[0]!.qrToken);
    if (updated) row = updated;
  }

  request.log.info(
    {
      clientId,
      kits_sold: kitsSold,
      existingQrCodes: existing.length,
      qrCodesToCreate,
      qrCodesCreated: created,
      finishedTotal: qrCodes.length,
      tokens: qrCodes.map((q) => ({ id: q.id, label: q.label, token: q.qrToken })),
    },
    "generate_qr_codes_finished",
  );

  return {
    status: 201 as const,
    row,
    qrCodes,
    created,
    message:
      created > 0
        ? `${created} QR Code(s) gerado(s) com sucesso.`
        : "QR Codes já estão completos para a quantidade de kits.",
  };
}

export async function adminRoutes(app: FastifyInstance) {
  app.addHook("preHandler", async (request: FastifyRequest, reply: FastifyReply) => {
    const expected = env.adminApiKey;
    if (!expected) return;
    if (request.headers["x-admin-key"] !== expected) {
      return reply.code(401).send({ error: "unauthorized" });
    }
  });

  app.get("/lookup-cnpj/:cnpj", async (request, reply) => {
    const raw = (request.params as { cnpj: string }).cnpj;
    const digits = normalizeCnpj(raw);
    if (!digits) {
      return reply.status(400).send({ error: "invalid_cnpj" });
    }
    try {
      const data = await fetchCnpjFromBrasilApi(digits);
      if (!data) return reply.status(404).send({ error: "cnpj_not_found" });
      return reply.send({ data });
    } catch (e) {
      request.log.warn({ err: e }, "lookup_cnpj_failed");
      return reply.status(502).send({ error: "lookup_failed" });
    }
  });

  app.get("/clients", async (request, reply) => {
    try {
      const rows = await listAllClients();
      const qrRows = await listQrCodesByClientIds(rows.map((r) => r.id));
      const qrByClient = groupQrCodesByClient(qrRows);
      return reply.send({
        clients: rows.map((row) => serializeClient(row, qrByClient.get(row.id) ?? [])),
      });
    } catch (e) {
      request.log.error({ err: e }, "list_clients_failed");
      return reply.status(500).send({
        error: "list_clients_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.get("/clients/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    try {
      const loaded = await loadClientWithQrCodes(id);
      if (!loaded) return reply.status(404).send({ error: "not_found" });
      return reply.send({ client: serializeClient(loaded.row, loaded.qrCodes) });
    } catch (e) {
      request.log.error({ err: e }, "get_client_failed");
      return reply.status(500).send({
        error: "get_client_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/clients", async (request, reply) => {
    const parsed = createBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send({ error: "invalid_body" });
    }
    const cnpj = normalizeCnpj(parsed.data.cnpj);
    if (!cnpj) {
      return reply.status(400).send({ error: "invalid_cnpj" });
    }
    const existing = await findClientByCnpj(cnpj);
    if (existing) {
      return reply.status(409).send({ error: "cnpj_already_registered" });
    }

    if (
      parsed.data.commercialStatus === "INATIVO" &&
      parsed.data.isActive === true
    ) {
      return reply.status(400).send({
        error: "cannot_activate_qr_inactive_client",
        message:
          "Cadastro inativo não pode ter o QR ativo. Desative o QR ou marque o cliente como ativo.",
      });
    }

    const isActiveQr =
      parsed.data.commercialStatus === "INATIVO" ? false : parsed.data.isActive;

    const kitsSold = parsed.data.kitsSold;

    request.log.info(
      { kitsSold, nomeFantasia: parsed.data.nomeFantasia, cnpj },
      "create_client_received",
    );

    try {
      const primaryToken = await generateUniqueQrToken();

      const row = await insertClient({
        cnpj,
        razaoSocial: parsed.data.razaoSocial,
        nomeFantasia: parsed.data.nomeFantasia,
        addrCep: parsed.data.addrCep.replace(/\D/g, "").slice(0, 8),
        addrStreet: parsed.data.addrStreet,
        addrNumber: parsed.data.addrNumber,
        addrComplement: parsed.data.addrComplement,
        addrNeighborhood: parsed.data.addrNeighborhood,
        addrCity: parsed.data.addrCity,
        addrState: parsed.data.addrState.toUpperCase().slice(0, 2),
        phone: parsed.data.phone,
        email: parsed.data.email,
        plan: parsed.data.plan,
        billingType: parsed.data.billingType,
        planValueCents: parsed.data.planValueCents,
        kitsSold,
        commercialStatus: parsed.data.commercialStatus,
        qrToken: primaryToken,
        isActive: isActiveQr,
      });

      return reply.status(201).send({ client: serializeClient(row, []) });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "";
      request.log.error({ err: e }, "insert_client_failed");
      if (msg.includes("unique") || msg.includes("duplicate")) {
        return reply.status(409).send({ error: "cnpj_already_registered" });
      }
      return reply.status(500).send({
        error: "insert_client_failed",
        message: msg || String(e),
      });
    }
  });

  app.patch("/clients/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    const parsed = patchBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send({ error: "invalid_body" });
    }
    const body = parsed.data;
    const existing = await findClientById(id);
    if (!existing) {
      return reply.status(404).send({ error: "not_found" });
    }

    const patch: Record<string, string | number | boolean> = {};

    if (body.cnpj !== undefined) {
      const normalized = normalizeCnpj(body.cnpj);
      if (!normalized) return reply.status(400).send({ error: "invalid_cnpj" });
      const other = await findClientByCnpj(normalized);
      if (other && other.id !== id) {
        return reply.status(409).send({ error: "cnpj_already_registered" });
      }
      patch.cnpj = normalized;
    }
    if (body.razaoSocial !== undefined) patch.razaoSocial = body.razaoSocial;
    if (body.nomeFantasia !== undefined) patch.nomeFantasia = body.nomeFantasia;
    if (body.addrCep !== undefined) {
      patch.addrCep = body.addrCep.replace(/\D/g, "").slice(0, 8);
    }
    if (body.addrStreet !== undefined) patch.addrStreet = body.addrStreet;
    if (body.addrNumber !== undefined) patch.addrNumber = body.addrNumber;
    if (body.addrComplement !== undefined) patch.addrComplement = body.addrComplement;
    if (body.addrNeighborhood !== undefined) patch.addrNeighborhood = body.addrNeighborhood;
    if (body.addrCity !== undefined) patch.addrCity = body.addrCity;
    if (body.addrState !== undefined) patch.addrState = body.addrState.toUpperCase().slice(0, 2);
    if (body.phone !== undefined) patch.phone = body.phone;
    if (body.email !== undefined) patch.email = body.email;
    if (body.plan !== undefined) patch.plan = body.plan;
    if (body.billingType !== undefined) patch.billingType = body.billingType;
    if (body.planValueCents !== undefined) patch.planValueCents = body.planValueCents;
    if (body.kitsSold !== undefined) patch.kitsSold = body.kitsSold;
    if (body.commercialStatus !== undefined) patch.commercialStatus = body.commercialStatus;
    if (body.isActive !== undefined) patch.isActive = body.isActive;

    const nextCommercial =
      body.commercialStatus !== undefined
        ? body.commercialStatus
        : existing.commercialStatus;

    if (nextCommercial === "INATIVO") {
      if (body.isActive === true) {
        return reply.status(400).send({
          error: "cannot_activate_qr_inactive_client",
          message:
            "Não é possível ativar o QR enquanto o cadastro do cliente estiver inativo. Ative o cadastro comercial primeiro.",
        });
      }
      patch.isActive = false;
    }

    try {
      const row = await updateClient(id, patch);
      if (!row) return reply.status(404).send({ error: "not_found" });

      const qrCodes = await listQrCodesByClientId(id);
      return reply.send({ client: serializeClient(row, qrCodes) });
    } catch (e) {
      request.log.error({ err: e }, "patch_client_failed");
      return reply.status(500).send({
        error: "patch_client_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/clients/:id/qr-codes/generate", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    const parsed = generateQrCodesBodySchema.safeParse(request.body ?? {});

    const client = await findClientById(id);
    if (!client) return reply.status(404).send({ error: "not_found" });

    const kitsSold =
      parsed.success && parsed.data?.kitsSold !== undefined
        ? parsed.data.kitsSold
        : client.kitsSold;

    request.log.info(
      { clientId: id, kits_sold: kitsSold, bodyKitsSold: parsed.success ? parsed.data?.kitsSold : undefined },
      "generate_qr_codes_received",
    );

    try {
      const result = await generateMissingQrCodes(request, id, kitsSold, client);

      if (result.status === 400) {
        return reply.status(400).send({
          error: result.error,
          message: result.message,
        });
      }

      const httpStatus = result.created > 0 ? 201 : 200;
      return reply.status(httpStatus).send({
        client: serializeClient(result.row, result.qrCodes),
        created: result.created,
        message: result.message,
      });
    } catch (e) {
      request.log.error({ err: e }, "generate_qr_codes_failed");
      return reply.status(500).send({
        error: "generate_qr_codes_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });

  app.post("/clients/:id/regenerate-token", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    const loaded = await loadClientWithQrCodes(id);
    if (!loaded) return reply.status(404).send({ error: "not_found" });

    const firstQr = loaded.qrCodes[0];
    if (firstQr) {
      try {
        const token = await generateUniqueQrToken();
        const updatedQr = await updateQrCodeToken(firstQr.id, token);
        if (!updatedQr) return reply.status(404).send({ error: "not_found" });
        await updateClientQrToken(id, token);
        const qrCodes = await listQrCodesByClientId(id);
        return reply.send({ client: serializeClient(loaded.row, qrCodes) });
      } catch (e) {
        request.log.error({ err: e }, "regenerate_token_failed");
        return reply.status(500).send({ error: "token_collision" });
      }
    }

    try {
      const token = await generateUniqueQrToken();
      const row = await updateClientQrToken(id, token);
      if (!row) return reply.status(404).send({ error: "not_found" });
      return reply.send({ client: serializeClient(row, []) });
    } catch (e) {
      request.log.error({ err: e }, "regenerate_token_failed");
      return reply.status(500).send({ error: "token_collision" });
    }
  });

  app.patch("/clients/:clientId/qr-codes/:qrId", async (request, reply) => {
    const { clientId, qrId } = request.params as { clientId: string; qrId: string };
    const parsed = patchQrCodeBodySchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.status(400).send({ error: "invalid_body" });
    }

    const client = await findClientById(clientId);
    if (!client) return reply.status(404).send({ error: "not_found" });

    if (client.commercialStatus === "INATIVO" && parsed.data.isActive) {
      return reply.status(400).send({
        error: "cannot_activate_qr_inactive_client",
        message: "Não é possível ativar QR de cliente com cadastro inativo.",
      });
    }

    const qr = await findQrCodeById(qrId);
    if (!qr || qr.clientId !== clientId) {
      return reply.status(404).send({ error: "qr_not_found" });
    }

    const updated = await updateQrCodeActive(qrId, parsed.data.isActive);
    if (!updated) return reply.status(404).send({ error: "qr_not_found" });

    const qrCodes = await listQrCodesByClientId(clientId);
    return reply.send({ qrCode: serializeQrCode(updated), client: serializeClient(client, qrCodes) });
  });

  app.post("/clients/:clientId/qr-codes/:qrId/regenerate-token", async (request, reply) => {
    const { clientId, qrId } = request.params as { clientId: string; qrId: string };

    const client = await findClientById(clientId);
    if (!client) return reply.status(404).send({ error: "not_found" });

    const qr = await findQrCodeById(qrId);
    if (!qr || qr.clientId !== clientId) {
      return reply.status(404).send({ error: "qr_not_found" });
    }

    try {
      const token = await generateUniqueQrToken();
      const updated = await updateQrCodeToken(qrId, token);
      if (!updated) return reply.status(404).send({ error: "qr_not_found" });

      if (client.qrToken === qr.qrToken) {
        await updateClientQrToken(clientId, token);
      }

      const refreshedClient = await findClientById(clientId);
      const qrCodes = await listQrCodesByClientId(clientId);
      return reply.send({
        qrCode: serializeQrCode(updated),
        client: serializeClient(refreshedClient!, qrCodes),
      });
    } catch (e) {
      request.log.error({ err: e }, "regenerate_qr_token_failed");
      return reply.status(500).send({ error: "token_collision" });
    }
  });

  app.delete("/clients/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    try {
      const removed = await deleteClientById(id);
      if (!removed) return reply.status(404).send({ error: "not_found" });
      return reply.status(204).send();
    } catch (e) {
      request.log.error({ err: e }, "delete_client_failed");
      return reply.status(500).send({
        error: "delete_client_failed",
        message: e instanceof Error ? e.message : String(e),
      });
    }
  });
}
