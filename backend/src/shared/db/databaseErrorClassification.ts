export type DatabaseErrorClassification =
  | "invalid_database_url"
  | "dns_resolution_failed"
  | "connection_refused"
  | "connection_timeout"
  | "ssl_required"
  | "ssl_certificate_error"
  | "authentication_failed"
  | "database_not_found"
  | "schema_or_table_missing"
  | "network_access_denied"
  | "unknown_database_error";

export type SanitizedDatabaseErrorLog = {
  msg: "database_diagnostic_failed";
  classification: DatabaseErrorClassification;
  name?: string;
  code?: string;
  severity?: string;
  routine?: string;
  errno?: string | number;
  syscall?: string;
};

type ErrorLike = {
  name?: string;
  code?: string | number;
  severity?: string;
  routine?: string;
  errno?: string | number;
  syscall?: string;
  message?: string;
  cause?: unknown;
};

function asErrorLike(value: unknown): ErrorLike | null {
  if (typeof value !== "object" || value === null) return null;
  return value as ErrorLike;
}

export function collectErrorChain(error: unknown): ErrorLike[] {
  const chain: ErrorLike[] = [];
  const seen = new Set<unknown>();
  let current: unknown = error;

  while (current != null && !seen.has(current)) {
    seen.add(current);
    const item = asErrorLike(current);
    if (item) chain.push(item);
    current = item?.cause;
  }

  return chain;
}

function messageHints(message: string): DatabaseErrorClassification | null {
  const lower = message.toLowerCase();

  if (lower.includes("no pg_hba.conf entry") && lower.includes("no encryption")) {
    return "ssl_required";
  }
  if (
    lower.includes("self signed") ||
    lower.includes("self-signed") ||
    lower.includes("unable to verify the first certificate") ||
    lower.includes("certificate")
  ) {
    return "ssl_certificate_error";
  }
  if (lower.includes("password authentication failed")) {
    return "authentication_failed";
  }
  if (lower.includes("does not exist") && lower.includes("database")) {
    return "database_not_found";
  }
  if (lower.includes("relation") && lower.includes("does not exist")) {
    return "schema_or_table_missing";
  }
  if (lower.includes("pg_hba.conf") || lower.includes("no encryption")) {
    return "ssl_required";
  }

  return null;
}

function classifyFromCode(code: string): DatabaseErrorClassification | null {
  switch (code) {
    case "ENOTFOUND":
      return "dns_resolution_failed";
    case "ECONNREFUSED":
      return "connection_refused";
    case "ETIMEDOUT":
    case "ECONNRESET":
      return "connection_timeout";
    case "28P01":
      return "authentication_failed";
    case "3D000":
      return "database_not_found";
    case "42P01":
      return "schema_or_table_missing";
    case "28000":
      return "authentication_failed";
    case "42501":
      return "network_access_denied";
    default:
      return null;
  }
}

export function classifyDatabaseError(
  error: unknown,
  fallback: DatabaseErrorClassification = "unknown_database_error",
): SanitizedDatabaseErrorLog {
  const chain = collectErrorChain(error);
  let classification = fallback;

  for (const item of chain) {
    const code = item.code != null ? String(item.code) : undefined;
    if (code) {
      const byCode = classifyFromCode(code);
      if (byCode) {
        classification = byCode;
        break;
      }
    }

    if (typeof item.message === "string") {
      const byMessage = messageHints(item.message);
      if (byMessage) {
        classification = byMessage;
        break;
      }
    }
  }

  const primary = chain[0];
  return {
    msg: "database_diagnostic_failed",
    classification,
    ...(primary?.name ? { name: primary.name } : {}),
    ...(primary?.code != null ? { code: String(primary.code) } : {}),
    ...(primary?.severity ? { severity: primary.severity } : {}),
    ...(primary?.routine ? { routine: primary.routine } : {}),
    ...(primary?.errno != null ? { errno: primary.errno } : {}),
    ...(primary?.syscall ? { syscall: primary.syscall } : {}),
  };
}

export function sanitizeForLogs(value: unknown): Record<string, unknown> {
  const sanitized = classifyDatabaseError(value);
  const serialized = JSON.stringify(sanitized);
  const forbiddenPatterns = [
    /postgres(?:ql)?:\/\//i,
    /password=/i,
    /@/,
    /:\/\/[^"'\s]+/,
  ];
  for (const pattern of forbiddenPatterns) {
    if (pattern.test(serialized)) {
      throw new Error("sanitized_log_contains_sensitive_data");
    }
  }
  return sanitized;
}
