import { findClientById } from "../../client/repository/client.admin.repository.js";
import { findClientByQrToken } from "../../client/repository/client.repository.js";
import { findQrCodeByToken } from "../../client/repository/clientQrCode.repository.js";

export type ClipUploadAccessResult =
  | {
      ok: true;
      clientId: string;
      arenaName: string;
      qrCodeId: string | null;
      kitLabel: string;
    }
  | {
      ok: false;
      reason: "invalid_qr" | "inactive_qr" | "inactive_client" | "client_not_found";
      message: string;
    };

/** Resolve cliente/arena/kit exclusivamente pelo qrToken usado na gravação. */
export async function validateClipUploadByQrToken(
  qrToken: string,
): Promise<ClipUploadAccessResult> {
  const token = qrToken.trim();
  if (!token) {
    return {
      ok: false,
      reason: "invalid_qr",
      message: "QR Token é obrigatório.",
    };
  }

  const fromQr = await findClientByQrToken(token);
  if (!fromQr) {
    return {
      ok: false,
      reason: "invalid_qr",
      message: "QR Code inválido.",
    };
  }

  if (!fromQr.isQrActive) {
    return {
      ok: false,
      reason: "inactive_qr",
      message: "QR Code inativo.",
    };
  }

  const client = await findClientById(fromQr.id);
  if (!client) {
    return {
      ok: false,
      reason: "client_not_found",
      message: "Cliente não encontrado.",
    };
  }

  if (client.commercialStatus === "INATIVO") {
    return {
      ok: false,
      reason: "inactive_client",
      message: "Cliente inativo.",
    };
  }

  const qrRow = await findQrCodeByToken(token);
  const qrCodeId = qrRow?.id ?? null;
  const kitLabel = qrRow?.label ?? "Kit 1";

  return {
    ok: true,
    clientId: client.id,
    arenaName: client.nomeFantasia,
    qrCodeId,
    kitLabel,
  };
}
