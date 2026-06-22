# NetCamPro — Backend

## API pública (app Android)

- `POST /auth/validate-qr` — valida `qrToken` (contrato da Etapa 2A).

## API de clipes (`/api/clips`)

### Upload de thumbnail (opcional)

`POST /api/clips/:clipId/thumbnail`

Envia a capa JPEG de um clipe já existente. A thumbnail é opcional e não altera original, preview nem status principal do clipe.

**Multipart**

| Campo | Tipo | Obrigatório | Descrição |
|-------|------|-------------|-----------|
| `thumbnail` | arquivo | sim | JPEG (`.jpg` / `.jpeg`, `image/jpeg`) |

**Limites**

- Tamanho máximo: **2 MB**
- Apenas `image/jpeg` com assinatura JPEG válida

**Resposta 200**

```json
{
  "clipId": "uuid",
  "thumbnailUrl": "https://..."
}
```

`thumbnailUrl` é uma URL assinada temporária (1 hora). A chave estável fica apenas no banco (`thumbnail_file_key`).

**Erros comuns**

| HTTP | `error` | Descrição |
|------|---------|-----------|
| 400 | `invalid_clip_id` | UUID inválido |
| 400 | `invalid_multipart` | Falha ao ler multipart |
| 404 | `clip_not_found` | Clipe inexistente |
| 413 | `file_too_large` | Acima de 2 MB |
| 415 | `unsupported_media_type` | MIME diferente de JPEG |
| 415 | `invalid_thumbnail_extension` | Extensão diferente de `.jpg`/`.jpeg` |
| 415 | `invalid_thumbnail_content` | Conteúdo não é JPEG |
| 500 | `thumbnail_update_failed` | Falha ao persistir após upload S3 |
| 503 | `aws_credentials_missing` | AWS não configurada |

**Logs estruturados**

- `thumbnail_upload_started`
- `thumbnail_upload_s3_success`
- `thumbnail_upload_completed`
- `thumbnail_upload_failed` (`phase=validation|s3|database`)
- `thumbnail_url_generated` (`urlPresent=true`)

### Listagem da Galeria Online

`GET /api/clips/clients/:clientId/kits/:qrCodeId/dates/:date`

Cada item em `clips[]` inclui:

- `thumbnailUrl`: URL assinada quando existe thumbnail; `null` para clipes antigos sem capa.

O endpoint `GET /api/clips/:clipId/play` **não muda**: continua retornando `playUrl` do preview (ou original como fallback).

### Download do original (corte no app)

`GET /api/clips/:clipId/download`

Fornece URL assinada temporária **somente do arquivo original** em alta qualidade. O backend não realiza corte; o aplicativo baixa o original e aplica o intervalo escolhido localmente.

**Autenticação**

Mesmas regras da Galeria Online e do `/play`: `clipId` UUID válido e clipe existente. Não envie `clientId` do app para autorização — o acesso é resolvido pelo registro do clipe no banco.

**Resposta 200**

```json
{
  "clipId": "uuid",
  "downloadUrl": "https://...",
  "source": "original",
  "expiresIn": 900,
  "sizeBytes": 108000000,
  "contentType": "video/mp4",
  "fileName": "clip.mp4"
}
```

- `downloadUrl`: presigned GET direto no S3 (15 minutos)
- `sizeBytes`: do banco (`size_bytes`), sem HEAD no S3
- Preview e thumbnail **nunca** são usados como fallback

**Erros comuns**

| HTTP | `error` | Descrição |
|------|---------|-----------|
| 400 | `clip_id_required` | clipId ausente |
| 400 | `invalid_clip_id` | UUID inválido |
| 404 | `clip_not_found` | Clipe inexistente |
| 422 | `original_file_missing` | Sem `original_file_key` nem `file_key` legado |
| 500 | `original_download_url_failed` | Falha ao assinar URL |
| 503 | `aws_credentials_missing` | AWS não configurada |

**Logs**

- `original_download_url_requested` (`clipId`, `authenticatedClientId`)
- `original_download_url_generated` (`urlPresent=true`, `expiresIn`, `sizeBytes`)
- `original_download_url_failed` (`phase=validation|database|s3`)

### Retenção na nuvem (7 dias)

Clipes totalmente sincronizados (`upload_status = uploaded`) são mantidos por **7 dias completos** a partir de `uploaded_at`, em UTC. Não usa `recorded_at`, para não penalizar uploads atrasados por falta de Wi-Fi.

**Semântica de `uploaded_at`:** o registro é inserido em `POST /api/clips/upload` somente depois do upload do original no S3 (e do preview, quando enviado no mesmo multipart). `uploaded_at` recebe `NOW()` no insert e **não é atualizado** por `POST /:clipId/preview` nem `POST /:clipId/thumbnail`. A retenção conta, portanto, desde a disponibilização do clipe na Galeria Online (insert com status `uploaded`), não desde a gravação local nem desde uploads posteriores de preview/thumbnail.

Durante a limpeza, clipes expirados passam para `upload_status = deleting` **antes** das exclusões no S3. Nesse estado não aparecem na galeria e `/play`/`/download` retornam `clip_not_found`. Falhas parciais mantêm `deleting` para retomada na próxima execução.

Após expirar, um job remove do S3:

- original;
- preview (se existir);
- thumbnail (se existir);

e exclui o registro do banco (**hard delete**, padrão do projeto). Cortes feitos no iOS permanecem apenas na Fototeca local — não há arquivo de corte remoto.

**Comando**

```bash
npm run cleanup:expired-clips
```

**Dry-run (sem apagar nada)**

```bash
npm run cleanup:expired-clips -- --dry-run
```

**GitHub Actions (agendado)**

Workflow: [`.github/workflows/cleanup-expired-clips.yml`](../.github/workflows/cleanup-expired-clips.yml)

| Modo | Quando | Comando |
|------|--------|---------|
| Real | Agenda diária **03:17 UTC** | `npm run cleanup:expired-clips` |
| Dry-run | Execução manual (`workflow_dispatch`, padrão `dry_run=true`) | `npm run cleanup:expired-clips -- --dry-run` |
| Real manual | Execução manual com `dry_run=false` | `npm run cleanup:expired-clips` |

**Secrets do repositório** (Settings → Secrets and variables → Actions):

- `ADMIN_API_KEY`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_REGION`
- `AWS_S3_BUCKET`
- `DATABASE_URL`

Logs: aba **Actions** do repositório → workflow *Cleanup expired clips* → run correspondente.

**Diagnóstico de conexão PostgreSQL (somente leitura)**

Workflow manual: [`.github/workflows/diagnose-database.yml`](../.github/workflows/diagnose-database.yml)

```bash
gh workflow run diagnose-database.yml -R netcampro40/NetCam-backend --ref main
```

Usa apenas o Secret `DATABASE_URL`. Registra formato sanitizado da URL, testa `SELECT 1`, contexto do banco e existência de `public.video_clips`, sem expor hostname, credenciais ou connection string.

Execuções simultâneas são serializadas (`concurrency: cleanup-expired-clips`).

**Logs**

- `expired_clip_cleanup_started`
- `expired_clip_cleanup_batch_loaded`
- `expired_clip_cleanup_item_claimed`
- `expired_clip_cleanup_item_resumed`
- `expired_clip_cleanup_item_started`
- `expired_clip_cleanup_s3_object_deleted`
- `expired_clip_cleanup_item_completed`
- `expired_clip_cleanup_item_failed`
- `expired_clip_cleanup_finished`

**Falhas parciais:** o clipe permanece em `deleting` (nunca volta para `uploaded`); S3 falhou → retry na próxima execução; objeto ausente → idempotente; um clipe com falha não interrompe o lote. O job pagina por cursor (`uploaded_at`, `id`) e tenta cada candidato no máximo uma vez por execução.

**Lifecycle S3 opcional:** regra de segurança com expiração > 10 dias pode complementar o job, mas não substitui a limpeza do banco.

## API admin (painel web)

Prefixo: `/api/admin`

| Método | Rota | Descrição |
|--------|------|-----------|
| `GET` | `/api/admin/arenas` | Lista arenas |
| `POST` | `/api/admin/arenas` | Cria arena (`{ "name": "..." }`, gera token) |
| `PATCH` | `/api/admin/arenas/:id` | Atualiza status (`{ "isActive": true/false }`) |
| `POST` | `/api/admin/arenas/:id/regenerate-token` | Novo `qrToken` |

### Segurança mínima

- Variável `ADMIN_API_KEY`: se definida, todas as rotas `/api/admin/*` exigem header `X-Admin-Key` com o mesmo valor.
- Se vazia, rotas admin ficam abertas — **apenas para desenvolvimento local**.

### CORS

`@fastify/cors` habilitado para o painel web em outra origem (ex.: Vite `:5173`).
