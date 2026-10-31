# NOVA

Desktop-first gaming & community platform: servers, roles, text and voice — built around
shared experiences (gaming spaces, instant sessions) instead of just channel lists.

> **Status:** MVP feature-complete for the private beta (milestones 1–4).
> See [docs/ROADMAP.md](docs/ROADMAP.md) for what's done, what's verified and the known limitations,
> and [docs/BETA.md](docs/BETA.md) for deploying and running the beta.

| Part | Stack |
| --- | --- |
| Backend | Python 3.12+, FastAPI, SQLAlchemy 2 (async), Alembic, PostgreSQL, Argon2id, WebSockets |
| Voice | LiveKit (WebRTC); backend issues short-lived tokens after permission checks |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, React Router, TanStack Query, Zustand, Motion, Lucide, livekit-client |
| Bot | Separate Python process using the public API + gateway (first-party "NOVA Bot") |
| Infra | Docker Compose (dev + prod), Caddy (HTTPS), S3-compatible or local uploads, SMTP |

Architecture and design decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Features

- **Accounts:** register, login, logout, profile (display name, avatar, status, custom status),
  password change, password reset by e-mail, live online presence
- **Servers:** create (creator = owner), edit, icon, delete, public/private, invites with expiry
  and usage limits, discover public servers, leave, kick, ban/unban, ownership transfer
  (password confirmation), audit log
- **Roles & permissions:** default roles + custom roles (name, color, permissions, order),
  role assignment, per-channel role overrides, strict hierarchy and anti-escalation rules
- **Channels:** categories, text, voice, announcement (read-only for members), private channels
- **Chat (real-time):** send, edit, delete, replies, reactions, pins, file/image attachments,
  safe link rendering
- **Voice:** join/leave, mute, deafen, connection status, reconnects, errors, who-is-in-voice,
  speaking indicators
- **Gaming spaces:** 2D map of voice zones (Lobby, Gaming, Chill, Strategie, AFK);
  click or drag your avatar onto a zone to join
- **Instant sessions:** "Valorant Ranked – 2 needed", slots, join/leave/close, linked voice
  channel, live notifications, home dashboard, session notes (notes, links, tasks, follow-ups),
  invite friends (DM with auto server invite)
- **Friends & DMs:** requests, accept/decline, remove, block, online list, direct messages
- **Moderation:** report messages/users, per-server moderation queue, audit log
- **Bots:** bot applications with tokens, installation with scoped permissions (managed role),
  first-party NOVA Bot (`/würfel`, `/münze`, `/teams`, `/sessions`, welcome messages)

## Quick start (Docker)

```bash
cd nova
docker compose up --build
```

| URL | What |
| --- | --- |
| http://localhost:5173 | App |
| http://localhost:8000/api/docs | API docs (Swagger) |
| http://localhost:8025 | Mailpit – all outgoing mail (password resets) lands here |

LiveKit runs in dev mode (`devkey`/`secret`), the first-party bot starts automatically and can
be installed on any server under *Servereinstellungen → Bots*.

Backend tests inside the stack: `docker compose exec backend pytest`

## Running without Docker

Requirements: Python 3.12+, [uv](https://docs.astral.sh/uv/), Node.js 22+, PostgreSQL 16;
optionally a LiveKit server for voice.

```bash
# Database (once)
psql -U postgres -c "CREATE ROLE nova LOGIN PASSWORD 'nova' CREATEDB;"
psql -U postgres -c "CREATE DATABASE nova OWNER nova;"
psql -U postgres -c "CREATE DATABASE nova_test OWNER nova;"

# Backend
cd nova/backend
cp ../.env.example .env        # adjust if needed
uv sync
uv run alembic upgrade head
uv run uvicorn app.main:app --reload            # http://localhost:8000

# Frontend (second terminal)
cd nova/frontend
npm install
npm run dev                                      # http://localhost:5173 (proxies /api + WebSocket)

# NOVA Bot (optional, third terminal) – token must match NOVA_FIRST_PARTY_BOT_TOKEN
cd nova/bot
uv sync
NOVA_BOT_TOKEN=nb_dev_only_first_party_bot_token uv run python -m nova_bot.main
```

Without LiveKit everything works except actually joining voice: the UI then shows
"Verbindung zum Sprachserver fehlgeschlagen".

## Checks

```bash
cd nova/backend  && uv run pytest && uv run ruff check . && uv run ruff format --check . && uv run alembic check
cd nova/bot      && uv run pytest
cd nova/frontend && npm test && npm run typecheck && npm run build
```

Load test (50 users, all connected and chatting) — see `scripts/loadtest.py` for usage.

## Repository layout

```
nova/
├── docker-compose.yml         dev stack (db, backend, frontend, livekit, mailpit, bot)
├── docker-compose.prod.yml    production stack (Caddy HTTPS, LiveKit, backups via scripts/)
├── docs/                      architecture, roadmap, beta operations
├── backend/
│   ├── app/
│   │   ├── main.py            app, middleware (request log, CSRF origin check, headers)
│   │   ├── core/              config, database, security, permissions, errors, rate limits
│   │   ├── api/               HTTP + WebSocket routes (thin)
│   │   ├── services/          business logic (auth, servers, members, channels, messages,
│   │   │                      voice, activity, social, reports, bots, storage, mail)
│   │   ├── realtime/          connection hub (presence, voice states) + event fan-out
│   │   ├── models/ schemas/
│   ├── alembic/               migrations 0001 (core) and 0002 (community, social, activity, bots)
│   └── tests/
├── bot/                       first-party NOVA Bot (separate process)
├── frontend/src/
│   ├── app/ components/ lib/ stores/ styles/
│   └── features/              auth, servers, channels, chat, voice, activity, social,
│                              moderation, invites, discover, realtime, settings, home
└── scripts/                   loadtest.py, backup.sh
```

UI labels are German; code, identifiers and docs are English.
