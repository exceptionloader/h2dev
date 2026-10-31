# NOVA roadmap & status

Each milestone ends with working, tested features — no buttons that pretend to work.
"Verified" below means: covered by automated tests **and** exercised in a real browser
(two users in parallel) unless stated otherwise.

## ✅ Milestone 1 — Foundation

Repository, Docker Compose, config, JSON logging, FastAPI + async SQLAlchemy + Alembic,
accounts (register, login, logout, profile, password change/reset), servers with ownership,
default roles, permission engine, channels, audit log, design system and app shell.

## ✅ Milestone 2 — Community core

- Invites (expiry, max uses, preview page `/einladung/:code`), public servers + discover
- Leave, kick, ban/unban with hierarchy rules; ownership transfer with password confirmation
- Roles: create, edit (name, color, permissions), delete, reorder, assign/unassign — with
  anti-escalation rules; per-channel role overrides (tri-state UI)
- Channel edit/delete
- Messages: send, edit, delete, replies, reactions, pins, attachments, pagination
- WebSocket gateway with permission-filtered fan-out, presence, session-bound sockets
- Uploads (avatars, server icons, attachments) to local disk or S3 (S3 tested with moto)
- SMTP mail (Mailpit in dev)

## ✅ Milestone 3 — Voice, spaces, sessions

- LiveKit token endpoint (CONNECT_VOICE / SPEAK), voice bar (join, leave, mute, deafen,
  status, reconnect, errors), voice states shown in sidebar, channel view and spaces
- Live presence (online / idle / dnd / offline, invisible respected)
- Gaming spaces: map with five voice zones, click-to-join and drag-to-join
- Instant sessions: create, join, leave, close, slots, voice link, live notifications,
  home dashboard; session notes (notes, links, tasks, follow-ups)

## ✅ Milestone 4 — Social, moderation, bots, beta hardening

- Friends (requests, accept/decline, remove), blocking, direct messages, invite friends to
  sessions (DM + automatic server invite)
- Reports (messages/users) + per-server moderation queue
- Bot applications + tokens, scoped installation, first-party NOVA Bot as separate process
- Production compose (Caddy HTTPS + CSP/HSTS, LiveKit, bot), backup script, load test

## Verification summary

| What | How |
| --- | --- |
| Backend (90 tests) | pytest against real PostgreSQL: auth, sessions, permissions, escalation, ownership, invites, bans, messages, uploads, S3, gateway filtering, presence, voice tokens, spaces, sessions, notes, friends, blocks, DMs, reports, bots, mail |
| Bot (4 tests) | command parsing; plus a live run answering `/würfel` and `/teams` through the real API |
| Frontend (10 tests) | vitest: error texts, permissions, safe links/XSS, gateway reconnect, login flow |
| Browser E2E | Playwright, two users: invite → realtime chat, reply, reaction, image → bot command → roles → space → session + notes → friends + DM → report → ban |
| Load | 50 users connected and chatting: 250/250 messages reached all 50 members; p95 send ≈ 0.6 s on one dev process (load generator on the same machine) |

## Known limitations (honest list)

- **Voice audio was not tested end-to-end here.** The sandbox could not run a LiveKit server,
  so the browser test only verified the token flow and the error handling. Please test
  `docker compose up` with two people talking before the beta.
- **Production stack not built here** (Docker Hub rate limit in the sandbox). Compose files
  validate; the Caddy config and `LIVEKIT_KEYS` setup need a first deploy to confirm.
- One backend process only: hub state (connections, presence, voice states) and rate limits
  live in memory. Running several processes requires Redis pub/sub first.
- Voice states are client-reported for display; a LiveKit webhook would make them authoritative.
- Unsent attachments (uploaded but never posted) are not cleaned up automatically yet.
- No group DMs, no typing indicators, no message search, no notification settings.
- Platform-level reports (about DMs/users without a server) are stored but have no admin UI.
- `MOVE_MEMBERS` exists as a permission but there is no "move user to channel" action yet.

## Postponed by design (spec section 17)

Public bot marketplace, AI features, screen sharing, video calls, Tauri desktop packaging,
complex discovery, advanced game integrations, monetization, 3D worlds.
