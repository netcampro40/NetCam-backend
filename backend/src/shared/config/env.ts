export const env = {
  port: Number(process.env.PORT ?? 3333),
  databaseUrl:
    process.env.DATABASE_URL ?? "postgresql://postgres:postgres@localhost:5432/netcam",
  /** Se vazio, rotas /api/admin ficam abertas (apenas desenvolvimento). */
  adminApiKey: process.env.ADMIN_API_KEY ?? "",
};
