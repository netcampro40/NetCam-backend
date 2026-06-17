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
      reason: "client_not_found" | "inactive_client" | "invalid_qr" | "inactive_qr";
      message: string;
    };

export async function validateClipUploadAccess(
  clientId: string,
  qrToken?: string,
  kitLabelFromRequest?: string,
): Promise<ClipUploadAccessResult> {
  const client = await findClientById(clientId);
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

  let qrCodeId: string | null = null;
  let kitLabel = kitLabelFromRequest?.trim() ?? "";

  if (qrToken && qrToken.trim().length > 0) {
    const token = qrToken.trim();
    const fromQr = await findClientByQrToken(token);
    if (!fromQr || fromQr.id !== clientId) {
      return {
        ok: false,
        reason: "invalid_qr",
        message: "QR Code inválido para este cliente.",
      };
    }
    if (!fromQr.isQrActive) {
      return {
        ok: false,
        reason: "inactive_qr",
        message: "QR Code inativo.",
      };
    }

    const qrRow = await findQrCodeByToken(token);
    if (qrRow) {
      qrCodeId = qrRow.id;
      if (!kitLabel) kitLabel = qrRow.label;
    }
  }

  return {
    ok: true,
    clientId: client.id,
    arenaName: client.nomeFantasia,
    qrCodeId,
    kitLabel,
  };
}
