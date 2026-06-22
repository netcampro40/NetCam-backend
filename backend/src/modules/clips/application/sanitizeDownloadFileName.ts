/** Nome seguro para Content-Disposition (sem path traversal ou caracteres inválidos). */
export function sanitizeDownloadFileName(fileName: string): string {
  const base = fileName.split(/[/\\]/).pop()?.trim() ?? "clip";
  const sanitized = base.replace(/[^\w.\-() ]+/g, "_").replace(/^\.+/, "");
  if (!sanitized || sanitized === "_") return "clip.mp4";
  return sanitized.slice(0, 200);
}
