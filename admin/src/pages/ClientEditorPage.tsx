import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import {
  createClient,
  deleteClient,
  generateClientQrCodes,
  getClient,
  lookupCnpj,
  patchClient,
  patchClientQrCode,
  regenerateClientQrCodeToken,
  type Client,
  type ClientPlan,
  type BillingType,
  type CommercialStatus,
  type ClientQrCode,
} from "../api";
import { QrModal } from "../components/QrModal";
import { formatCepDisplay, formatCnpjDisplay, onlyDigits } from "../utils/cnpj";

type FormState = {
  cnpj: string;
  razaoSocial: string;
  nomeFantasia: string;
  addrCep: string;
  addrStreet: string;
  addrNumber: string;
  addrComplement: string;
  addrNeighborhood: string;
  addrCity: string;
  addrState: string;
  phone: string;
  email: string;
  plan: ClientPlan;
  billingType: BillingType;
  planReais: string;
  kitsSold: string;
  commercialStatus: CommercialStatus;
};

function emptyForm(): FormState {
  return {
    cnpj: "",
    razaoSocial: "",
    nomeFantasia: "",
    addrCep: "",
    addrStreet: "",
    addrNumber: "",
    addrComplement: "",
    addrNeighborhood: "",
    addrCity: "",
    addrState: "",
    phone: "",
    email: "",
    plan: "ATE_5_QUADRAS",
    billingType: "MENSAL",
    planReais: "",
    kitsSold: "0",
    commercialStatus: "ATIVO",
  };
}

function clientToForm(c: Client): FormState {
  return {
    cnpj: c.cnpj,
    razaoSocial: c.razaoSocial,
    nomeFantasia: c.nomeFantasia,
    addrCep: c.addrCep,
    addrStreet: c.addrStreet,
    addrNumber: c.addrNumber,
    addrComplement: c.addrComplement,
    addrNeighborhood: c.addrNeighborhood,
    addrCity: c.addrCity,
    addrState: c.addrState,
    phone: c.phone,
    email: c.email,
    plan: c.plan,
    billingType: c.billingType,
    planReais: (c.planValueCents / 100).toFixed(2).replace(".", ","),
    kitsSold: String(c.kitsSold),
    commercialStatus: c.commercialStatus,
  };
}

function resolveKitsCount(formValue: string, savedKits: number): number {
  const trimmed = formValue.trim();
  if (trimmed === "") return savedKits;
  const parsed = parseInt(trimmed, 10);
  return Number.isNaN(parsed) ? savedKits : parsed;
}

type QrFeedback = { type: "error" | "success"; text: string } | null;

function parsePlanReais(s: string): number {
  const n = parseFloat(s.replace(",", "."));
  if (Number.isNaN(n) || n < 0) return 0;
  return Math.round(n * 100);
}

export function ClientEditorPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const isNew = id === "new";

  const [form, setForm] = useState<FormState>(emptyForm);
  const [client, setClient] = useState<Client | null>(null);
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(
    (location.state as { flash?: string } | null)?.flash ?? null,
  );
  const [lookupStatus, setLookupStatus] = useState<"idle" | "loading" | "ok" | "fail">("idle");
  const [qrModal, setQrModal] = useState<ClientQrCode | null>(null);
  const [deleteModal, setDeleteModal] = useState<"closed" | "step1" | "step2">("closed");
  const [deletePhrase, setDeletePhrase] = useState("");
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [generatingQr, setGeneratingQr] = useState(false);
  const [qrFeedback, setQrFeedback] = useState<QrFeedback>(null);

  const lookupTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastFetchedCnpj = useRef<string>("");

  const load = useCallback(async () => {
    if (!id || isNew) return;
    setLoading(true);
    setError(null);
    try {
      const c = await getClient(id);
      setClient(c);
      setForm(clientToForm(c));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Cliente não encontrado");
    } finally {
      setLoading(false);
    }
  }, [id, isNew]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const digits = onlyDigits(form.cnpj);
    if (digits.length !== 14) {
      setLookupStatus("idle");
      lastFetchedCnpj.current = "";
      return;
    }
    if (lookupTimer.current) clearTimeout(lookupTimer.current);
    lookupTimer.current = setTimeout(async () => {
      if (lastFetchedCnpj.current === digits) return;
      lastFetchedCnpj.current = digits;
      setLookupStatus("loading");
      try {
        const data = await lookupCnpj(digits);
        if (!data) {
          setLookupStatus("fail");
          return;
        }
        setForm((f) => ({
          ...f,
          razaoSocial: data.razaoSocial || f.razaoSocial,
          phone: data.phone || f.phone,
          email: data.email || f.email,
          addrCep: data.addrCep || f.addrCep,
          addrStreet: data.addrStreet || f.addrStreet,
          addrNumber: data.addrNumber || f.addrNumber,
          addrComplement: data.addrComplement || f.addrComplement,
          addrNeighborhood: data.addrNeighborhood || f.addrNeighborhood,
          addrCity: data.addrCity || f.addrCity,
          addrState: data.addrState || f.addrState,
          nomeFantasia:
            f.nomeFantasia.trim() !== ""
              ? f.nomeFantasia
              : data.nomeFantasia || f.nomeFantasia,
        }));
        setLookupStatus("ok");
      } catch {
        setLookupStatus("fail");
      }
    }, 450);
    return () => {
      if (lookupTimer.current) clearTimeout(lookupTimer.current);
    };
  }, [form.cnpj]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    const kits = parseInt(form.kitsSold, 10);
    if (Number.isNaN(kits) || kits < 0) {
      setError("Quantidade de kits inválida");
      return;
    }
    if (!form.nomeFantasia.trim()) {
      setError("Nome fantasia é obrigatório");
      return;
    }
    if (isNew && onlyDigits(form.cnpj).length !== 14) {
      setError("CNPJ deve ter 14 dígitos");
      return;
    }
    const planValueCents = parsePlanReais(form.planReais);

    const addrPayload = {
      addrCep: onlyDigits(form.addrCep).slice(0, 8),
      addrStreet: form.addrStreet,
      addrNumber: form.addrNumber,
      addrComplement: form.addrComplement,
      addrNeighborhood: form.addrNeighborhood,
      addrCity: form.addrCity,
      addrState: form.addrState.toUpperCase().slice(0, 2),
    };

    setSaving(true);
    try {
      if (isNew) {
        const created = await createClient({
          cnpj: form.cnpj,
          razaoSocial: form.razaoSocial,
          nomeFantasia: form.nomeFantasia.trim(),
          ...addrPayload,
          phone: form.phone,
          email: form.email,
          plan: form.plan,
          billingType: form.billingType,
          planValueCents,
          kitsSold: kits,
          commercialStatus: form.commercialStatus,
          isActive: form.commercialStatus === "ATIVO",
        });
        navigate(`/clients/${created.id}`, {
          replace: true,
          state: {
            flash: `Cliente "${created.nomeFantasia}" cadastrado. Use "Gerar QR Codes" para criar os tokens de acesso.`,
          },
        });
        return;
      }
      if (!id) return;
      const updated = await patchClient(id, {
        cnpj: form.cnpj,
        razaoSocial: form.razaoSocial,
        nomeFantasia: form.nomeFantasia.trim(),
        ...addrPayload,
        phone: form.phone,
        email: form.email,
        plan: form.plan,
        billingType: form.billingType,
        planValueCents,
        kitsSold: kits,
        commercialStatus: form.commercialStatus,
      });
      setClient(updated);
      setForm(clientToForm(updated));
      setSuccess("Cliente salvo.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao salvar");
    } finally {
      setSaving(false);
    }
  }

  async function toggleQrAccess(qr: ClientQrCode) {
    if (!client) return;
    if (!qr.isActive && form.commercialStatus === "INATIVO") {
      setError(
        "Não é possível ativar o QR enquanto o cadastro estiver inativo. Marque o cadastro como Ativo e salve antes.",
      );
      return;
    }
    setError(null);
    try {
      const { client: updated } = await patchClientQrCode(client.id, qr.id, {
        isActive: !qr.isActive,
      });
      setClient(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao atualizar");
    }
  }

  async function regenToken(qr: ClientQrCode) {
    if (!client) return;
    const msg =
      `Regenerar o token de "${qr.label}"?\n\nO QR atual deixa de funcionar no app.\n\nContinuar?`;
    if (!confirm(msg)) return;
    setError(null);
    try {
      const { client: updated } = await regenerateClientQrCodeToken(client.id, qr.id);
      setClient(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao regenerar");
    }
  }

  async function copyQrToken(token: string) {
    await navigator.clipboard.writeText(token);
  }

  async function handleGenerateQrCodes() {
    console.log("[admin] botão Gerar QR Codes clicado");

    if (!client) {
      console.warn("[admin] generate_qr: client is null");
      setQrFeedback({ type: "error", text: "Cliente não carregado. Recarregue a página." });
      return;
    }

    const kits = resolveKitsCount(form.kitsSold, client.kitsSold);
    console.log("[admin] generate_qr", {
      clientId: client.id,
      formKitsSold: form.kitsSold,
      savedKitsSold: client.kitsSold,
      kitsUsed: kits,
    });

    if (kits <= 0) {
      setError(null);
      setSuccess(null);
      setQrFeedback({ type: "error", text: "A arena ainda não possui kits ativos." });
      return;
    }

    setGeneratingQr(true);
    setError(null);
    setSuccess(null);
    setQrFeedback(null);

    try {
      let workingClient = client;
      if (kits !== client.kitsSold) {
        console.log("[admin] generate_qr: salvando kitsSold antes de gerar", { kits });
        workingClient = await patchClient(client.id, { kitsSold: kits });
        setClient(workingClient);
        setForm(clientToForm(workingClient));
      }

      const result = await generateClientQrCodes(workingClient.id, kits);
      console.log("[admin] generate_qr: sucesso", {
        clientId: workingClient.id,
        created: result.created,
        qrCodes: result.client.qrCodes?.length ?? 0,
        message: result.message,
      });

      setClient(result.client);
      setQrFeedback({ type: "success", text: result.message });
      setSuccess(result.message);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Erro ao gerar QR Codes";
      console.error("[admin] generate_qr: falha", message);
      setQrFeedback({ type: "error", text: message });
      setError(message);
    } finally {
      setGeneratingQr(false);
    }
  }

  function openDeleteFlow() {
    setDeletePhrase("");
    setDeleteModal("step1");
  }

  function closeDeleteFlow() {
    setDeleteModal("closed");
    setDeletePhrase("");
  }

  async function confirmDeletePermanent() {
    if (!client) return;
    const expected = client.nomeFantasia.trim();
    if (deletePhrase.trim() !== expected) return;
    setDeleteBusy(true);
    setError(null);
    try {
      await deleteClient(client.id);
      closeDeleteFlow();
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao excluir cliente");
    } finally {
      setDeleteBusy(false);
    }
  }

  const deletePhraseMatches =
    client !== null && deletePhrase.trim() === client.nomeFantasia.trim();

  if (loading) {
    return (
      <div className="admin-page">
        <p>Carregando…</p>
      </div>
    );
  }

  const title = isNew ? "Cadastrar cliente" : "Ficha do cliente";

  return (
    <div className="admin-page">
      <div className="admin-breadcrumb">
        <Link to="/">← Voltar à lista</Link>
      </div>
      <h1>{title}</h1>
      {error && <div className="admin-error">{error}</div>}
      {success && <div className="admin-success">{success}</div>}

      <form onSubmit={handleSubmit} className="admin-form">
        <section className="admin-card form-section">
          <h2>Identificação</h2>
          <label className="field">
            <span>CNPJ *</span>
            <input
              type="text"
              inputMode="numeric"
              autoComplete="off"
              placeholder="00.000.000/0000-00"
              value={formatCnpjDisplay(form.cnpj)}
              onChange={(e) =>
                setForm((f) => ({ ...f, cnpj: onlyDigits(e.target.value).slice(0, 14) }))
              }
            />
            <small className="field-hint">
              {lookupStatus === "loading" && "Consultando dados públicos do CNPJ…"}
              {lookupStatus === "ok" && "Dados preenchidos a partir da consulta (editáveis)."}
              {lookupStatus === "fail" && "Não foi possível obter dados automáticos para este CNPJ."}
            </small>
          </label>
          <label className="field">
            <span>Razão social</span>
            <input
              type="text"
              value={form.razaoSocial}
              onChange={(e) => setForm((f) => ({ ...f, razaoSocial: e.target.value }))}
            />
          </label>
          <label className="field">
            <span>Nome fantasia *</span>
            <input
              type="text"
              value={form.nomeFantasia}
              onChange={(e) => setForm((f) => ({ ...f, nomeFantasia: e.target.value }))}
            />
          </label>
        </section>

        <section className="admin-card form-section">
          <h2>Contato</h2>
          <label className="field">
            <span>Telefone</span>
            <input
              type="text"
              value={form.phone}
              onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))}
            />
          </label>
          <label className="field">
            <span>E-mail</span>
            <input
              type="email"
              value={form.email}
              onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
            />
          </label>
        </section>

        <section className="admin-card form-section">
          <h2>Endereço</h2>
          <label className="field">
            <span>CEP</span>
            <input
              type="text"
              inputMode="numeric"
              placeholder="00000-000"
              value={formatCepDisplay(form.addrCep)}
              onChange={(e) =>
                setForm((f) => ({ ...f, addrCep: onlyDigits(e.target.value).slice(0, 8) }))
              }
            />
          </label>
          <label className="field">
            <span>Rua / logradouro</span>
            <input
              type="text"
              value={form.addrStreet}
              onChange={(e) => setForm((f) => ({ ...f, addrStreet: e.target.value }))}
            />
          </label>
          <div className="field-row">
            <label className="field field-inline">
              <span>Número</span>
              <input
                type="text"
                value={form.addrNumber}
                onChange={(e) => setForm((f) => ({ ...f, addrNumber: e.target.value }))}
              />
            </label>
            <label className="field field-inline">
              <span>UF</span>
              <input
                type="text"
                maxLength={2}
                placeholder="SP"
                value={form.addrState}
                onChange={(e) =>
                  setForm((f) => ({
                    ...f,
                    addrState: e.target.value.toUpperCase().slice(0, 2),
                  }))
                }
              />
            </label>
          </div>
          <label className="field">
            <span>Complemento</span>
            <input
              type="text"
              value={form.addrComplement}
              onChange={(e) => setForm((f) => ({ ...f, addrComplement: e.target.value }))}
            />
          </label>
          <label className="field">
            <span>Bairro</span>
            <input
              type="text"
              value={form.addrNeighborhood}
              onChange={(e) => setForm((f) => ({ ...f, addrNeighborhood: e.target.value }))}
            />
          </label>
          <label className="field">
            <span>Cidade</span>
            <input
              type="text"
              value={form.addrCity}
              onChange={(e) => setForm((f) => ({ ...f, addrCity: e.target.value }))}
            />
          </label>
        </section>

        <section className="admin-card form-section">
          <h2>Comercial</h2>
          <label className="field">
            <span>Plano</span>
            <select
              value={form.plan}
              onChange={(e) => setForm((f) => ({ ...f, plan: e.target.value as ClientPlan }))}
            >
              <option value="ATE_5_QUADRAS">Até 5 quadras</option>
              <option value="ATE_10_QUADRAS">Até 10 quadras</option>
              <option value="ACIMA_10_QUADRAS">Acima de 10 quadras</option>
            </select>
          </label>
          <label className="field">
            <span>Tipo de cobrança</span>
            <select
              value={form.billingType}
              onChange={(e) =>
                setForm((f) => ({ ...f, billingType: e.target.value as BillingType }))
              }
            >
              <option value="MENSAL">Mensal</option>
              <option value="ANUAL">Anual</option>
            </select>
          </label>
          <label className="field">
            <span>Valor do plano (R$)</span>
            <input
              type="text"
              inputMode="decimal"
              placeholder="0,00"
              value={form.planReais}
              onChange={(e) => setForm((f) => ({ ...f, planReais: e.target.value }))}
            />
          </label>
          <label className="field">
            <span>Quantidade de kits vendidos</span>
            <input
              type="number"
              min={0}
              step={1}
              value={form.kitsSold}
              onChange={(e) => setForm((f) => ({ ...f, kitsSold: e.target.value }))}
            />
            <small className="field-hint">
              Informe quantos kits a arena possui. Os QR Codes são gerados manualmente na seção
              abaixo.
            </small>
          </label>
          <label className="field">
            <span>Status (cadastro)</span>
            <select
              value={form.commercialStatus}
              onChange={(e) =>
                setForm((f) => ({
                  ...f,
                  commercialStatus: e.target.value as CommercialStatus,
                }))
              }
            >
              <option value="ATIVO">Ativo</option>
              <option value="INATIVO">Inativo</option>
            </select>
          </label>
        </section>

        {!isNew && client && (
          <section className="admin-card form-section">
            <h2>Acesso QR / app</h2>
            <p className="field-hint">
              Cada kit possui um QR Code individual. Use o botão abaixo para gerar os tokens com
              base na quantidade de kits informada no formulário.
            </p>
            <div className="btn-row">
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => void handleGenerateQrCodes()}
                disabled={generatingQr || saving}
              >
                {generatingQr ? "Gerando…" : "Gerar QR Codes"}
              </button>
            </div>
            {qrFeedback && (
              <div className={qrFeedback.type === "error" ? "admin-error qr-feedback" : "admin-success qr-feedback"}>
                {qrFeedback.text}
              </div>
            )}
            {form.commercialStatus === "INATIVO" && (
              <p className="field-hint field-hint-warn">
                <strong>Cadastro inativo:</strong> nenhum QR pode ficar ativo enquanto o cadastro
                estiver inativo.
              </p>
            )}
            {(client.qrCodes ?? []).length === 0 ? (
              <p className="field-hint">Nenhum QR Code cadastrado para este cliente.</p>
            ) : (
              <div className="qr-editor-list">
                {(client.qrCodes ?? []).map((qr) => (
                  <div key={qr.id} className="qr-editor-item">
                    <div className="qr-editor-item-head">
                      <strong>{qr.label}</strong>
                      {form.commercialStatus === "INATIVO" ? (
                        <span className="tag tag-muted">Bloqueado</span>
                      ) : qr.isActive ? (
                        <span className="tag tag-ok">Ativo</span>
                      ) : (
                        <span className="tag tag-warn">Inativo</span>
                      )}
                    </div>
                    <div className="token-box">{qr.qrToken}</div>
                    <div className="btn-row">
                      <button
                        type="button"
                        className="btn btn-secondary btn-tiny"
                        onClick={() => copyQrToken(qr.qrToken)}
                      >
                        Copiar token
                      </button>
                      <button
                        type="button"
                        className="btn btn-secondary btn-tiny"
                        onClick={() => setQrModal(qr)}
                      >
                        Ver QR code
                      </button>
                      <button
                        type="button"
                        className="btn btn-secondary btn-tiny"
                        onClick={() => toggleQrAccess(qr)}
                        disabled={form.commercialStatus === "INATIVO" && !qr.isActive}
                      >
                        {qr.isActive ? "Desativar" : "Ativar"}
                      </button>
                      <button
                        type="button"
                        className="btn btn-danger btn-tiny"
                        onClick={() => regenToken(qr)}
                      >
                        Regenerar token
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}

        {!isNew && client && (
          <section className="admin-card danger-zone">
            <h2>Zona de perigo</h2>
            <p className="danger-zone-text">
              Remover o cliente apaga o cadastro no servidor de forma <strong>permanente</strong>. Esta ação
              não pode ser desfeita.
            </p>
            <button type="button" className="btn btn-destructive" onClick={openDeleteFlow}>
              Excluir cadastro permanentemente
            </button>
          </section>
        )}

        <div className="form-footer">
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? "Salvando…" : isNew ? "Cadastrar cliente" : "Salvar alterações"}
          </button>
        </div>
      </form>

      {client && qrModal && (
        <QrModal
          title={client.nomeFantasia}
          qrToken={qrModal.qrToken}
          label={qrModal.label}
          onClose={() => setQrModal(null)}
        />
      )}

      {client && deleteModal !== "closed" && (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => !deleteBusy && closeDeleteFlow()}
        >
          <div
            className="delete-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-modal-title"
            onClick={(e) => e.stopPropagation()}
          >
            {deleteModal === "step1" && (
              <>
                <h3 id="delete-modal-title">Excluir cliente permanentemente?</h3>
                <ul className="delete-modal-list">
                  <li>
                    A exclusão é <strong>permanente</strong>: o cadastro será removido do banco de dados.
                  </li>
                  <li>
                    Todo o registro comercial deste cliente deixará de existir no painel administrativo.
                  </li>
                  <li>
                    O <strong>token e o QR</strong> vinculados a este cliente deixarão de existir — o app{" "}
                    <strong>não poderá mais validar</strong> esse QR (como se o acesso fosse revogado).
                  </li>
                </ul>
                <div className="delete-modal-actions">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={deleteBusy}
                    onClick={closeDeleteFlow}
                  >
                    Cancelar
                  </button>
                  <button
                    type="button"
                    className="btn btn-destructive"
                    disabled={deleteBusy}
                    onClick={() => setDeleteModal("step2")}
                  >
                    Entendi, continuar…
                  </button>
                </div>
              </>
            )}
            {deleteModal === "step2" && (
              <>
                <h3 id="delete-modal-title">Confirmação final</h3>
                <p className="delete-modal-confirm-intro">
                  Para confirmar a exclusão irreversível, digite o <strong>nome fantasia</strong> exatamente
                  como está cadastrado:
                </p>
                <p className="delete-modal-expected-name">{client.nomeFantasia}</p>
                <label className="field">
                  <span>Nome fantasia (confirmação)</span>
                  <input
                    type="text"
                    autoComplete="off"
                    value={deletePhrase}
                    onChange={(e) => setDeletePhrase(e.target.value)}
                    placeholder="Digite o nome acima"
                    disabled={deleteBusy}
                  />
                </label>
                <div className="delete-modal-actions">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={deleteBusy}
                    onClick={() => {
                      setDeleteModal("step1");
                      setDeletePhrase("");
                    }}
                  >
                    Voltar
                  </button>
                  <button
                    type="button"
                    className="btn btn-destructive"
                    disabled={deleteBusy || !deletePhraseMatches}
                    onClick={() => void confirmDeletePermanent()}
                  >
                    {deleteBusy ? "Excluindo…" : "Excluir permanentemente"}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
