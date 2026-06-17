import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import {
  listClients,
  patchClientQrCode,
  regenerateClientQrCodeToken,
  type Client,
  type ClientQrCode,
} from "../api";
import { QrModal } from "../components/QrModal";
import { formatCnpjDisplay, onlyDigits } from "../utils/cnpj";

function formatListLocation(c: Client): string {
  const cityUf = [c.addrCity, c.addrState].filter(Boolean).join("/");
  if (cityUf) return cityUf;
  const line = [c.addrStreet, c.addrNumber].filter(Boolean).join(", ");
  return line || "—";
}

const PLAN_LABELS: Record<string, string> = {
  ATE_5_QUADRAS: "Até 5 quadras",
  ATE_10_QUADRAS: "Até 10 quadras",
  ACIMA_10_QUADRAS: "Acima de 10 quadras",
};

type CommercialFilter = "all" | "ATIVO" | "INATIVO";

type QrModalState = {
  clientName: string;
  qrToken: string;
  label: string;
};

function matchesSearch(c: Client, rawQuery: string): boolean {
  const q = rawQuery.trim();
  if (!q) return true;
  const qDigits = onlyDigits(q);
  const qLower = q.toLowerCase();
  const cnpjMatch = qDigits.length > 0 && c.cnpj.includes(qDigits);
  const nameMatch = c.nomeFantasia.toLowerCase().includes(qLower);
  return cnpjMatch || nameMatch;
}

function qrSummary(c: Client): string {
  const codes = c.qrCodes ?? [];
  if (codes.length === 0) return "—";
  const active = codes.filter((q) => q.isActive).length;
  return `${active}/${codes.length} ativos`;
}

export function ClientListPage() {
  const location = useLocation();
  const [clients, setClients] = useState<Client[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success] = useState<string | null>(
    (location.state as { flash?: string } | null)?.flash ?? null,
  );
  const [search, setSearch] = useState("");
  const [commercialFilter, setCommercialFilter] = useState<CommercialFilter>("all");
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [qrModal, setQrModal] = useState<QrModalState | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setError(null);
    const data = await listClients();
    setClients(data);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        setLoading(true);
        await refresh();
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Erro ao carregar clientes");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  const visibleClients = useMemo(() => {
    return clients.filter((c) => {
      if (commercialFilter !== "all" && c.commercialStatus !== commercialFilter) {
        return false;
      }
      return matchesSearch(c, search);
    });
  }, [clients, commercialFilter, search]);

  function toggleExpanded(clientId: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(clientId)) next.delete(clientId);
      else next.add(clientId);
      return next;
    });
  }

  async function copyToken(token: string, key: string) {
    await navigator.clipboard.writeText(token);
    setCopiedKey(key);
    window.setTimeout(() => setCopiedKey(null), 2000);
  }

  async function toggleQrActive(client: Client, qr: ClientQrCode) {
    if (!qr.isActive && client.commercialStatus === "INATIVO") {
      setError(
        "Não é possível ativar QR de cliente com cadastro inativo. Edite o cliente e marque o cadastro como Ativo.",
      );
      return;
    }
    setError(null);
    try {
      const { client: updated } = await patchClientQrCode(client.id, qr.id, {
        isActive: !qr.isActive,
      });
      setClients((prev) => prev.map((c) => (c.id === updated.id ? updated : c)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao atualizar QR");
    }
  }

  async function regenQr(client: Client, qr: ClientQrCode) {
    const msg =
      `Regenerar o token de "${qr.label}" (${client.nomeFantasia})?\n\n` +
      `• O token ATUAL deixa de valer.\n` +
      `• O QR antigo para de funcionar no app.\n\n` +
      `Continuar?`;
    if (!confirm(msg)) return;
    setError(null);
    try {
      const { client: updated } = await regenerateClientQrCodeToken(client.id, qr.id);
      setClients((prev) => prev.map((c) => (c.id === updated.id ? updated : c)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao regenerar token");
    }
  }

  function renderQrRow(client: Client, qr: ClientQrCode) {
    const copyKey = `${client.id}-${qr.id}`;
    const blocked = client.commercialStatus === "INATIVO";

    return (
      <tr key={qr.id} className="qr-expand-row">
        <td colSpan={8}>
          <div className="qr-expand-inner">
            <span className="qr-expand-label">{qr.label}</span>
            <span className="qr-expand-token">{qr.qrToken}</span>
            <span>
              {blocked ? (
                <span className="tag tag-muted" title="Cadastro inativo">
                  Bloqueado
                </span>
              ) : qr.isActive ? (
                <span className="tag tag-ok">Ativo</span>
              ) : (
                <span className="tag tag-warn">Inativo</span>
              )}
            </span>
            <div className="qr-expand-actions">
              <button
                type="button"
                className="btn btn-tiny"
                onClick={() => copyToken(qr.qrToken, copyKey)}
              >
                {copiedKey === copyKey ? "Copiado!" : "Copiar"}
              </button>
              <button
                type="button"
                className="btn btn-tiny"
                onClick={() =>
                  setQrModal({
                    clientName: client.nomeFantasia,
                    qrToken: qr.qrToken,
                    label: qr.label,
                  })
                }
              >
                Ver QR
              </button>
              <button
                type="button"
                className="btn btn-tiny"
                onClick={() => toggleQrActive(client, qr)}
                disabled={blocked && !qr.isActive}
                title={blocked && !qr.isActive ? "Cadastro inativo" : undefined}
              >
                {qr.isActive ? "Desativar" : "Ativar"}
              </button>
              <button
                type="button"
                className="btn btn-tiny btn-danger"
                onClick={() => regenQr(client, qr)}
              >
                Regenerar
              </button>
            </div>
          </div>
        </td>
      </tr>
    );
  }

  return (
    <div className="admin-page admin-page-wide">
      <header className="admin-header">
        <div>
          <h1>NetCamPro — Admin</h1>
          <p className="admin-sub">Cadastro comercial de clientes, planos e tokens de acesso (QR).</p>
        </div>
        <Link to="/clients/new" className="btn btn-primary">
          Cadastrar cliente
        </Link>
      </header>

      <aside className="admin-hint">
        <strong>Resumo</strong>
        <ul>
          <li>
            Informe a <strong>quantidade de kits</strong> na ficha do cliente e use o botão{" "}
            <strong>Gerar QR Codes</strong> para criar os tokens de acesso.
          </li>
          <li>
            A lista principal mostra uma linha por cliente. Use a <strong>seta</strong> no fim da
            linha para ver e gerenciar cada QR individualmente.
          </li>
          <li>Clique no <strong>CNPJ</strong> para abrir a ficha completa do cliente.</li>
        </ul>
      </aside>

      {error && <div className="admin-error">{error}</div>}
      {success && <div className="admin-success">{success}</div>}

      <section className="admin-card">
        <h2>Clientes</h2>
        {!loading && clients.length > 0 && (
          <div className="list-toolbar">
            <label className="list-search">
              <span className="sr-only">Buscar por CNPJ ou nome fantasia</span>
              <input
                type="search"
                autoComplete="off"
                placeholder="Buscar por CNPJ ou nome fantasia…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                aria-label="Buscar por CNPJ ou nome fantasia"
              />
            </label>
            <div className="list-filter" role="group" aria-label="Filtrar por status do cadastro">
              <span className="list-filter-label">Cadastro:</span>
              {(
                [
                  { v: "all" as const, label: "Todos" },
                  { v: "ATIVO" as const, label: "Ativos" },
                  { v: "INATIVO" as const, label: "Inativos" },
                ] as const
              ).map(({ v, label }) => (
                <button
                  key={v}
                  type="button"
                  className={`btn btn-tiny filter-chip ${commercialFilter === v ? "filter-chip-on" : ""}`}
                  onClick={() => setCommercialFilter(v)}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        )}
        {loading ? (
          <p>Carregando…</p>
        ) : clients.length === 0 ? (
          <p>Nenhum cliente cadastrado.</p>
        ) : visibleClients.length === 0 ? (
          <p className="list-empty-filtered">Nenhum cliente corresponde à busca ou ao filtro.</p>
        ) : (
          <div className="table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>CNPJ</th>
                  <th>Nome fantasia</th>
                  <th>Razão social</th>
                  <th>Local</th>
                  <th>Plano</th>
                  <th>Status</th>
                  <th>Kits</th>
                  <th className="col-expand" aria-label="Expandir QRs" />
                </tr>
              </thead>
              <tbody>
                {visibleClients.map((c) => {
                  const expanded = expandedIds.has(c.id);
                  const qrCodes = c.qrCodes ?? [];
                  return (
                    <Fragment key={c.id}>
                      <tr className={expanded ? "client-row-expanded" : undefined}>
                        <td>
                          <Link to={`/clients/${c.id}`} className="link-cnpj">
                            {formatCnpjDisplay(c.cnpj)}
                          </Link>
                        </td>
                        <td>{c.nomeFantasia}</td>
                        <td className="cell-muted">{c.razaoSocial || "—"}</td>
                        <td className="cell-muted">{formatListLocation(c)}</td>
                        <td>{PLAN_LABELS[c.plan] ?? c.plan}</td>
                        <td>
                          <span className={c.commercialStatus === "ATIVO" ? "tag tag-ok" : "tag tag-off"}>
                            {c.commercialStatus === "ATIVO" ? "Ativo" : "Inativo"}
                          </span>
                          {qrCodes.length > 0 && (
                            <span className="tag tag-muted tag-subtle" title="QR Codes ativos / total">
                              {qrSummary(c)}
                            </span>
                          )}
                        </td>
                        <td>{c.kitsSold}</td>
                        <td className="col-expand">
                          {qrCodes.length > 0 ? (
                            <button
                              type="button"
                              className="btn-expand"
                              onClick={() => toggleExpanded(c.id)}
                              aria-expanded={expanded}
                              aria-label={
                                expanded
                                  ? `Ocultar QR Codes de ${c.nomeFantasia}`
                                  : `Mostrar QR Codes de ${c.nomeFantasia}`
                              }
                              title={expanded ? "Ocultar QR Codes" : "Mostrar QR Codes"}
                            >
                              {expanded ? "▲" : "▼"}
                            </button>
                          ) : (
                            <span className="cell-muted">—</span>
                          )}
                        </td>
                      </tr>
                      {expanded && qrCodes.map((qr) => renderQrRow(c, qr))}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {qrModal && (
        <QrModal
          title={qrModal.clientName}
          qrToken={qrModal.qrToken}
          label={qrModal.label}
          onClose={() => setQrModal(null)}
        />
      )}
    </div>
  );
}
