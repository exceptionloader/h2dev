# NOVA

Desktop-first gaming & community platform: servers, roles, text and voice — built around
shared experiences (gaming spaces, instant sessions) instead of just channel lists.

> **Status:** Milestone 1 (foundation) — accounts, profiles, servers with ownership, default
> roles, permission engine, channels, audit log. See [docs/ROADMAP.md](docs/ROADMAP.md).

| Part | Stack |
| --- | --- |
| Backend | Python 3.12+, FastAPI, SQLAlchemy 2 (async), Alembic, PostgreSQL, Argon2id |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, React Router, TanStack Query, Zustand, Motion, Lucide |
| Local infra | Docker Compose (PostgreSQL, backend, frontend) |

Architecture and design decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start (Docker)

```bash
cd nova
docker compose up --build
```

- App: http://localhost:5173
- API docs (Swagger): http://localhost:8000/api/docs
- Health: http://localhost:8000/api/health

Migrations run automatically when the backend container starts. Code changes reload
automatically (backend via `uvicorn --reload`, frontend via Vite HMR).

Run the backend tests inside the stack (uses the separate `nova_test` database):

```bash
docker compose exec backend pytest
```

> Password reset e-mails are **not sent yet** — in development the reset link is written
> to the backend log (`docker compose logs backend | grep mail.outgoing`).

## Running without Docker

Requirements: Python 3.12+, [uv](https://docs.astral.sh/uv/), Node.js 22+, PostgreSQL 16.

```bash
# Database (once)
psql -U postgres -c "CREATE ROLE nova LOGIN PASSWORD 'nova' CREATEDB;"
psql -U postgres -c "CREATE DATABASE nova OWNER nova;"
psql -U postgres -c "CREATE DATABASE nova_test OWNER nova;"

# Backend
cd nova/backend
cp ../.env.example .env
uv sync
uv run alembic upgrade head
uv run uvicorn app.main:app --reload            # http://localhost:8000

# Frontend (second terminal)
cd nova/frontend
npm install
npm run dev                                      # http://localhost:5173 (proxies /api)
```

## Checks

```bash
# Backend
cd nova/backend
uv run pytest            # 46 tests: auth, sessions, permissions, ownership, channel access
uv run ruff check . && uv run ruff format --check .

# Frontend
cd nova/frontend
npm test                 # vitest
npm run typecheck
npm run build
```

## Database migrations

```bash
cd nova/backend
uv run alembic revision --autogenerate -m "describe change"   # review the generated file!
uv run alembic upgrade head
uv run alembic downgrade -1
uv run alembic check      # fails if models and migrations diverge
```

## Repository layout

```
nova/
├── docker-compose.yml
├── docs/                     architecture, roadmap
├── backend/
│   ├── app/
│   │   ├── main.py           app factory, middleware (request log, CSRF origin check, headers)
│   │   ├── core/             config, database, security (Argon2id/tokens), permissions, errors
│   │   ├── api/              HTTP routes only — thin, delegate to services
│   │   ├── services/         business logic (auth, servers/channels, audit, mail)
│   │   ├── models/           SQLAlchemy models
│   │   ├── schemas/          Pydantic request/response models
│   │   └── realtime/         WebSocket gateway (milestone 3)
│   ├── alembic/              migrations
│   └── tests/
└── frontend/
    └── src/
        ├── app/              App, router, query client
        ├── components/       ui/ (Button, Field, Modal, Avatar…) and layout/ (shell, nav, bars)
        ├── features/         auth, servers, channels, home, settings, roadmap
        ├── lib/              api client, types, permissions, formatting, error texts
        ├── stores/           Zustand UI state
        └── styles/           Tailwind theme (design tokens)
```

UI labels are German; code, identifiers and docs are English.
