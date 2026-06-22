import { describe, expect, it } from "vitest";
import {
  classifyDatabaseError,
  collectErrorChain,
  sanitizeForLogs,
} from "./databaseErrorClassification.js";

function makePgError(code: string, message?: string) {
  return {
    name: "error",
    code,
    severity: "ERROR",
    routine: "auth_failed",
    message,
  };
}

function makeSystemError(code: string) {
  const error = new Error("system failure") as Error & {
    code?: string;
    errno?: number;
    syscall?: string;
  };
  error.code = code;
  error.errno = -3008;
  error.syscall = "connect";
  return error;
}

describe("databaseErrorClassification", () => {
  it("classifies authentication failure 28P01", () => {
    const result = classifyDatabaseError(makePgError("28P01"));
    expect(result.classification).toBe("authentication_failed");
    expect(result.code).toBe("28P01");
  });

  it("classifies database not found 3D000", () => {
    const result = classifyDatabaseError(makePgError("3D000"));
    expect(result.classification).toBe("database_not_found");
  });

  it("classifies missing relation 42P01", () => {
    const result = classifyDatabaseError(makePgError("42P01"));
    expect(result.classification).toBe("schema_or_table_missing");
  });

  it("classifies ENOTFOUND as dns failure", () => {
    const result = classifyDatabaseError(makeSystemError("ENOTFOUND"));
    expect(result.classification).toBe("dns_resolution_failed");
  });

  it("classifies ECONNREFUSED", () => {
    const result = classifyDatabaseError(makeSystemError("ECONNREFUSED"));
    expect(result.classification).toBe("connection_refused");
  });

  it("classifies ETIMEDOUT", () => {
    const result = classifyDatabaseError(makeSystemError("ETIMEDOUT"));
    expect(result.classification).toBe("connection_timeout");
  });

  it("classifies ssl required from pg_hba message without logging raw text", () => {
    const result = classifyDatabaseError({
      name: "error",
      message:
        'no pg_hba.conf entry for host "203.0.113.10", user "dbuser", database "netcam", no encryption',
    });
    expect(result.classification).toBe("ssl_required");
    const serialized = JSON.stringify(result);
    expect(serialized).not.toContain("203.0.113.10");
    expect(serialized).not.toContain("dbuser");
    expect(serialized).not.toContain("pg_hba.conf");
  });

  it("classifies certificate errors", () => {
    const result = classifyDatabaseError(new Error("self signed certificate in certificate chain"));
    expect(result.classification).toBe("ssl_certificate_error");
    const serialized = JSON.stringify(result);
    expect(serialized).not.toContain("self signed certificate");
  });

  it("walks error.cause chain", () => {
    const root = makeSystemError("ECONNREFUSED");
    const wrapped = new Error("wrapper", { cause: root });
    expect(collectErrorChain(wrapped)).toHaveLength(2);
    expect(classifyDatabaseError(wrapped).classification).toBe("connection_refused");
  });

  it("sanitizeForLogs never returns url password or hostname", () => {
    const result = sanitizeForLogs(makePgError("28P01", "password authentication failed for user"));
    const serialized = JSON.stringify(result);
    expect(serialized).not.toMatch(/postgres(?:ql)?:\/\//i);
    expect(serialized).not.toContain("password authentication failed");
  });
});
