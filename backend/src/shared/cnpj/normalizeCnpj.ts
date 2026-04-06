/** Remove não-dígitos. */
export function onlyDigits(s: string): string {
  return s.replace(/\D/g, "");
}

/** CNPJ brasileiro: 14 dígitos após normalização. */
export function normalizeCnpj(s: string): string | null {
  const d = onlyDigits(s);
  if (d.length !== 14) return null;
  return d;
}
