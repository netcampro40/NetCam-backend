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
