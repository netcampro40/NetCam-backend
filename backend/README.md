# NetCamPro — Backend

## API pública (app Android)

- `POST /auth/validate-qr` — valida `qrToken` (contrato da Etapa 2A).

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
