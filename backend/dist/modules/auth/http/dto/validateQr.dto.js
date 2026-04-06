import { z } from "zod";
export const validateQrRequestSchema = z.object({
    qrToken: z.string().trim().min(6).max(128),
});
