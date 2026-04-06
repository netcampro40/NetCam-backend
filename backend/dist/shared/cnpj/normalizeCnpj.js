/** Remove não-dígitos. */
export function onlyDigits(s) {
    return s.replace(/\D/g, "");
}
/** CNPJ brasileiro: 14 dígitos após normalização. */
export function normalizeCnpj(s) {
    const d = onlyDigits(s);
    if (d.length !== 14)
        return null;
    return d;
}
