import cors from "@fastify/cors";
import Fastify from "fastify";
import { adminRoutes } from "./modules/admin/http/admin.routes.js";
import { authRoutes } from "./modules/auth/http/auth.routes.js";
import { ensureDatabaseSchema } from "./shared/db/ensureSchema.js";
export async function buildApp() {
    await ensureDatabaseSchema();
    const app = Fastify({
        logger: true,
    });
    app.setErrorHandler((error, request, reply) => {
        request.log.error({ err: error }, "request_failed");
        const status = typeof error === "object" &&
            error !== null &&
            "statusCode" in error &&
            typeof error.statusCode === "number"
            ? error.statusCode
            : 500;
        if (reply.sent)
            return;
        reply.status(status).send({
            error: "internal_error",
            message: error instanceof Error ? error.message : String(error),
        });
    });
    await app.register(cors, {
        origin: true,
        methods: ["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
        allowedHeaders: ["Content-Type", "X-Admin-Key"],
    });
    await app.register(authRoutes);
    await app.register(adminRoutes, { prefix: "/api/admin" });
    return app;
}
