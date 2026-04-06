import { randomBytes } from "node:crypto";
/** Token legível e difícil de adivinhar (sem caracteres ambíguos). */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export function generateQrToken(length = 12) {
    const bytes = randomBytes(length);
    let out = "";
    for (let i = 0; i < length; i++) {
        out += ALPHABET[bytes[i] % ALPHABET.length];
    }
    return out;
}
