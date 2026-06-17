import { boolean, index, pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";
import { clients } from "./clients.js";

/** QR Code individual vinculado a um cliente (um por kit). */
export const clientQrCodes = pgTable(
  "client_qr_codes",
  {
    id: uuid("id").defaultRandom().primaryKey(),
    clientId: uuid("client_id")
      .notNull()
      .references(() => clients.id, { onDelete: "cascade" }),
    qrToken: text("qr_token").notNull().unique(),
    label: text("label").notNull().default(""),
    isActive: boolean("is_active").notNull().default(true),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => ({
    clientIdIdx: index("idx_client_qr_codes_client_id").on(table.clientId),
    qrTokenIdx: index("idx_client_qr_codes_qr_token").on(table.qrToken),
    isActiveIdx: index("idx_client_qr_codes_is_active").on(table.isActive),
  }),
);
