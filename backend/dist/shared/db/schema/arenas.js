import { boolean, index, pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";
export const arenas = pgTable("arenas", {
    id: uuid("id").defaultRandom().primaryKey(),
    name: text("name").notNull(),
    qrToken: text("qr_token").notNull().unique(),
    isActive: boolean("is_active").notNull().default(true),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
}, (table) => ({
    qrTokenIdx: index("idx_arenas_qr_token").on(table.qrToken),
    isActiveIdx: index("idx_arenas_is_active").on(table.isActive),
}));
