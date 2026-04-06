import { defineConfig } from "drizzle-kit";
export default defineConfig({
    dialect: "postgresql",
    schema: "./src/shared/db/schema/arenas.ts",
    out: "./drizzle",
    dbCredentials: {
        url: process.env.DATABASE_URL ?? "postgresql://postgres:postgres@localhost:5432/netcam",
    },
});
