import { FastifyInstance } from "fastify";
import { validateQrRequestSchema } from "./dto/validateQr.dto.js";
import { validateQrToken } from "../application/validateQr.usecase.js";

export async function authRoutes(app: FastifyInstance) {
  app.post("/auth/validate-qr", async (request, reply) => {
    const parsedBody = validateQrRequestSchema.safeParse(request.body);

    if (!parsedBody.success) {
      return reply.status(400).send({
        authorized: false,
        reason: "bad_request",
      });
    }

    const result = await validateQrToken(parsedBody.data.qrToken);
    return reply.status(200).send(result);
  });
}
