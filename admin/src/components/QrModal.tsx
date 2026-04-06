import { useRef } from "react";
import QRCode from "react-qr-code";
import type { Client } from "../api";

function downloadSvgFromContainer(container: HTMLElement | null, filename: string) {
  if (!container) return;
  const svg = container.querySelector("svg");
  if (!svg) return;
  const serializer = new XMLSerializer();
  const source = serializer.serializeToString(svg);
  const blob = new Blob([source], { type: "image/svg+xml;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

async function copyText(text: string) {
  await navigator.clipboard.writeText(text);
}

type Props = {
  client: Client;
  onClose: () => void;
};

export function QrModal({ client, onClose }: Props) {
  const qrWrapRef = useRef<HTMLDivElement>(null);
  const safeName = client.nomeFantasia.replace(/\s+/g, "-").slice(0, 40);

  return (
    <div
      role="dialog"
      aria-modal="true"
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.45)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 16,
        zIndex: 50,
      }}
      onClick={onClose}
    >
      <div
        style={{
          background: "#fff",
          borderRadius: 12,
          padding: 24,
          maxWidth: 380,
          width: "100%",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 style={{ marginTop: 0 }}>{client.nomeFantasia}</h3>
        <p style={{ fontSize: 13, color: "#52525b", marginBottom: 8 }}>
          O QR contém <strong>apenas o token atual</strong> — é o que o app NetCam lê ao escanear.
        </p>
        <div
          style={{
            fontFamily: "ui-monospace, monospace",
            fontSize: 13,
            fontWeight: 600,
            padding: "10px 12px",
            background: "#f4f4f5",
            borderRadius: 8,
            marginBottom: 12,
            wordBreak: "break-all",
            border: "1px solid #e4e4e7",
          }}
        >
          Token: {client.qrToken}
        </div>
        <div
          ref={qrWrapRef}
          style={{
            background: "#fff",
            padding: 16,
            display: "flex",
            justifyContent: "center",
            border: "1px solid #e4e4e7",
            borderRadius: 8,
          }}
        >
          <QRCode value={client.qrToken} size={220} />
        </div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginTop: 16 }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => copyText(client.qrToken)}
          >
            Copiar token
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => downloadSvgFromContainer(qrWrapRef.current, `netcam-qr-${safeName}.svg`)}
          >
            Baixar QR (SVG)
          </button>
          <button type="button" className="btn btn-primary" style={{ marginLeft: "auto" }} onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
