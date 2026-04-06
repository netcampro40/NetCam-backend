import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  listClients,
  patchClient,
  regenerateClientToken,
  type Client,
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

function matchesSearch(c: Client, rawQuery: string): boolean {
  const q = rawQuery.trim();
  if (!q) return true;
  const qDigits = onlyDigits(q);
  const qLower = q.toLowerCase();
  const cnpjMatch = qDigits.length > 0 && c.cnpj.includes(qDigits);
  const nameMatch = c.nomeFantasia.toLowerCase().includes(qLower);
  return cnpjMatch || nameMatch;
}

export function ClientListPage() {
  const [clients, setClients] = useState<Client[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [commercialFilter, setCommercialFilter] = useState<CommercialFilter>("all");
  const [qrModal, setQrModal] = useState<Client | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

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

  async function toggleActive(c: Client) {
    setError(null);
    const turningOn = !c.isActive;
    if (turningOn && c.commercialStatus === "INATIVO") {
      setError(
        "Não é possível ativar o QR para cliente com cadastro inativo. Edite o cliente e marque o cadastro como Ativo.",
      );
      return;
    }
    try {
      await patchClient(c.id, { isActive: !c.isActive });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao atualizar acesso");
    }
  }

  async function regen(c: Client) {
    const msg =
      `Regenerar o token de "${c.nomeFantasia}"?\n\n` +
      `• O token ATUAL deixa de valer.\n` +
      `• QRs antigos param de funcionar no app.\n\n` +
      `Continuar?`;
    if (!confirm(msg)) return;
    setError(null);
    try {
      await regenerateClientToken(c.id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao regenerar token");
    }
  }

  async function copyToken(token: string, id: string) {
    await navigator.clipboard.writeText(token);
    setCopiedId(id);
    window.setTimeout(() => setCopiedId(null), 2000);
  }

  return (
    <div className="admin-page">
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
            O <strong>token</strong> é o conteúdo do QR lido pelo app; <strong>Desativar</strong> bloqueia a validação no
            servidor mesmo com token correto.
          </li>
          <li>
            <strong>Status</strong> na lista é o cadastro comercial (ativo/inativo). O acesso QR usa &quot;Ativar /
            Desativar&quot; nas ações. Com cadastro inativo, o QR não pode ficar ativo.
          </li>
          <li>Clique no <strong>CNPJ</strong> para abrir a ficha completa do cliente.</li>
        </ul>
      </aside>

      {error && <div className="admin-error">{error}</div>}

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
                  <th>Token atual</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {visibleClients.map((c) => (
                  <tr key={c.id}>
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
                      {c.commercialStatus === "INATIVO" ? (
                        <span className="tag tag-muted" title="Cadastro inativo: QR não pode validar no app">
                          QR bloqueado
                        </span>
                      ) : !c.isActive ? (
                        <span className="tag tag-warn" title="Token / QR desligado no servidor">
                          QR off
                        </span>
                      ) : (
                        <span className="tag tag-ok tag-subtle" title="QR ativo no servidor">
                          QR on
                        </span>
                      )}
                    </td>
                    <td>{c.kitsSold}</td>
                    <td>
                      <div className="token-cell">{c.qrToken}</div>
                      <button
                        type="button"
                        className="btn btn-tiny"
                        onClick={() => copyToken(c.qrToken, c.id)}
                      >
                        {copiedId === c.id ? "Copiado!" : "Copiar"}
                      </button>
                    </td>
                    <td className="cell-actions">
                      <button type="button" className="btn btn-tiny" onClick={() => setQrModal(c)}>
                        Ver QR
                      </button>
                      <button
                        type="button"
                        className="btn btn-tiny"
                        onClick={() => toggleActive(c)}
                        disabled={c.commercialStatus === "INATIVO" && !c.isActive}
                        title={
                          c.commercialStatus === "INATIVO" && !c.isActive
                            ? "Cadastro inativo: não é possível ativar o QR"
                            : undefined
                        }
                      >
                        {c.isActive ? "Desativar QR" : "Ativar QR"}
                      </button>
                      <button type="button" className="btn btn-tiny btn-danger" onClick={() => regen(c)}>
                        Novo token
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {qrModal && <QrModal client={qrModal} onClose={() => setQrModal(null)} />}
    </div>
  );
}
