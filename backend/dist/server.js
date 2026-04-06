import { buildApp } from "./app.js";
import { env } from "./shared/config/env.js";
const app = await buildApp();
app
    .listen({
    port: env.port,
    host: "0.0.0.0",
})
    .catch((error) => {
    app.log.error(error);
    process.exit(1);
});
