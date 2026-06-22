import { describe, expect, it } from "vitest";
import {
  analyzeDatabaseUrlShape,
  detectWrappingQuotes,
  isDatabaseUrlStructurallyValid,
  unwrapDatabaseUrl,
} from "./databaseUrlShape.js";

describe("databaseUrlShape", () => {
  it("reports missing url", () => {
    const shape = analyzeDatabaseUrlShape(undefined);
    expect(shape.present).toBe(false);
    expect(shape.parseable).toBe(false);
  });

  it("reports empty url", () => {
    const shape = analyzeDatabaseUrlShape("");
    expect(shape.present).toBe(true);
    expect(shape.parseable).toBe(false);
  });

  it("detects wrapping quotes", () => {
    expect(detectWrappingQuotes('"postgresql://user:pass@host:5432/db"')).toBe(true);
    const shape = analyzeDatabaseUrlShape('"postgresql://user:pass@host:5432/db"');
    expect(shape.hasWrappingQuotes).toBe(true);
    expect(isDatabaseUrlStructurallyValid(shape)).toBe(false);
  });

  it("detects outer whitespace", () => {
    const shape = analyzeDatabaseUrlShape(" postgresql://user:pass@host:5432/db ");
    expect(shape.hasOuterWhitespace).toBe(true);
    expect(isDatabaseUrlStructurallyValid(shape)).toBe(false);
  });

  it("rejects invalid protocol", () => {
    const shape = analyzeDatabaseUrlShape("mysql://user:pass@host:5432/db");
    expect(shape.parseable).toBe(true);
    expect(shape.validProtocol).toBe(false);
  });

  it("accepts structurally valid postgres url without logging secrets", () => {
    const shape = analyzeDatabaseUrlShape("postgresql://user:secret@host:5432/netcam?sslmode=require");
    expect(isDatabaseUrlStructurallyValid(shape)).toBe(true);
    expect(shape.sslModePresent).toBe(true);
    const serialized = JSON.stringify(shape);
    expect(serialized).not.toContain("secret");
    expect(serialized).not.toContain("host");
    expect(serialized).not.toContain("netcam");
  });

  it("unwraps quoted urls for connection use", () => {
    expect(unwrapDatabaseUrl('"postgresql://user:pass@host:5432/db"')).toBe(
      "postgresql://user:pass@host:5432/db",
    );
  });
});
