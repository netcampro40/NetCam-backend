# NetCamPro — Painel admin (mínimo)

React + Vite + TypeScript. Opera arenas: criar, listar, ativar/desativar, ver token, regenerar token, exibir QR.

## Pré-requisitos

- Backend rodando (ex.: `http://127.0.0.1:3333`)
- Postgres com tabela `arenas` (use `npm run db:init` no `backend/`)

## Desenvolvimento

```bash
cd admin
npm install
npm run dev
```

Abre em `http://localhost:5173`. O Vite faz **proxy** de `/api` para `http://127.0.0.1:3333`.

Se no backend você definiu `ADMIN_API_KEY`, crie `admin/.env`:

```env
VITE_ADMIN_KEY=sua_chave_secreta
```

## Build de produção

```bash
npm run build
```

Defina `VITE_API_BASE` com a URL pública do backend (ex.: `https://api.seudominio.com`).

## API usada

Todas sob prefixo `/api/admin` (ver README do backend).
