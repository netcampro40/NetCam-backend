export type DatabaseUrlShapeLog = {
  msg: "database_url_shape";
  present: boolean;
  parseable: boolean;
  validProtocol: boolean;
  hasUsername: boolean;
  hasPassword: boolean;
  hasDatabaseName: boolean;
  hasWrappingQuotes: boolean;
  hasOuterWhitespace: boolean;
  sslModePresent: boolean;
};

const EMPTY_SHAPE: Omit<DatabaseUrlShapeLog, "msg"> = {
  present: false,
  parseable: false,
  validProtocol: false,
  hasUsername: false,
  hasPassword: false,
  hasDatabaseName: false,
  hasWrappingQuotes: false,
  hasOuterWhitespace: false,
  sslModePresent: false,
};

export function detectWrappingQuotes(value: string): boolean {
  const trimmed = value.trim();
  return (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  );
}

export function unwrapDatabaseUrl(value: string): string {
  let normalized = value.trim();
  if (detectWrappingQuotes(normalized)) {
    normalized = normalized.slice(1, -1).trim();
  }
  return normalized;
}

export function analyzeDatabaseUrlShape(rawFromEnv: string | undefined): DatabaseUrlShapeLog {
  if (rawFromEnv === undefined) {
    return { msg: "database_url_shape", ...EMPTY_SHAPE };
  }

  const hasOuterWhitespace = rawFromEnv !== rawFromEnv.trim();
  const hasWrappingQuotes = detectWrappingQuotes(rawFromEnv);
  const normalized = unwrapDatabaseUrl(rawFromEnv);

  if (normalized.length === 0) {
    return {
      msg: "database_url_shape",
      present: true,
      parseable: false,
      validProtocol: false,
      hasUsername: false,
      hasPassword: false,
      hasDatabaseName: false,
      hasWrappingQuotes,
      hasOuterWhitespace,
      sslModePresent: false,
    };
  }

  try {
    const url = new URL(normalized);
    const validProtocol = url.protocol === "postgres:" || url.protocol === "postgresql:";
    const hasUsername = url.username.length > 0;
    const hasPassword = url.password.length > 0;
    const databasePath = url.pathname.replace(/^\//, "");
    const hasDatabaseName = databasePath.length > 0;
    const sslModePresent =
      url.searchParams.has("sslmode") || normalized.toLowerCase().includes("sslmode=");

    return {
      msg: "database_url_shape",
      present: true,
      parseable: true,
      validProtocol,
      hasUsername,
      hasPassword,
      hasDatabaseName,
      hasWrappingQuotes,
      hasOuterWhitespace,
      sslModePresent,
    };
  } catch {
    return {
      msg: "database_url_shape",
      present: true,
      parseable: false,
      validProtocol: false,
      hasUsername: false,
      hasPassword: false,
      hasDatabaseName: false,
      hasWrappingQuotes,
      hasOuterWhitespace,
      sslModePresent: normalized.toLowerCase().includes("sslmode="),
    };
  }
}

export function isDatabaseUrlStructurallyValid(shape: DatabaseUrlShapeLog): boolean {
  return (
    shape.present &&
    shape.parseable &&
    shape.validProtocol &&
    shape.hasUsername &&
    shape.hasPassword &&
    shape.hasDatabaseName &&
    !shape.hasWrappingQuotes &&
    !shape.hasOuterWhitespace
  );
}

export function resolveDatabaseUrlFromEnv(rawFromEnv: string): string {
  return unwrapDatabaseUrl(rawFromEnv);
}
