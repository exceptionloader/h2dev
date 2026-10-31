# NOVA roadmap

Each milestone ends with working, tested features — no buttons that pretend to work.

## ✅ Milestone 1 — Foundation (done)

- Repository structure, Docker Compose, environment config, JSON logging
- FastAPI app, async SQLAlchemy, Alembic migration `0001`
- Accounts: register, login (username or e-mail), logout, profile (display name, status,
  custom status), password change, password reset (link logged in dev)
- Server creation → creator becomes owner; default roles Admin / Moderator / Mitglied;
  default channels (#allgemein, #ankündigungen read-only, 🔊 Lobby)
- Server edit (MANAGE_SERVER), delete (owner + typed confirmation)
- Permission engine with channel overrides + hierarchy rules
- Channels: categories, text, voice, announcement, private; visibility filtered per member
- Audit log (server create/update, channel create)
- Frontend: design system, app shell (nav rail, server sidebar, member list, status bar),
  auth pages, home, server overview/settings/audit log, profile settings
- Tests: 46 backend (pytest, real PostgreSQL), 8 frontend (vitest)

**Known limitations**
- Only the owner can be in a server until invites exist (M2).
- Roles are read-only in the UI; no role CRUD endpoints yet (M2).
- Status is a stored preference, not live presence (M3).
- No avatars/uploads yet; password-reset mail is only logged.
- Rate limiter is per process.

## Milestone 2 — Community core (week 2)

1. Invites: create (CREATE_INVITES), expiry/max uses, join page `/einladung/:code`
2. Members: kick, ban/unban (hierarchy enforced), `server_bans`
3. Roles: create/edit/delete/reorder/assign with `can_manage_role` / `can_grant_permissions`
4. Ownership transfer (owner re-enters password; audit-logged)
5. Messages: send/edit own/delete (own or MANAGE_MESSAGES), replies, reactions, pins
6. WebSocket gateway: cookie-authenticated, per-server event fan-out (new/edited/deleted messages)
7. Uploads to S3-compatible storage (MinIO in Compose): avatars + attachments, type sniffing,
   size limits, private bucket + signed URLs
8. Tests: role escalation attempts, ban evasion, ownership transfer, message permissions

## Milestone 3 — Voice, spaces, sessions (week 3)

1. LiveKit in Compose; `POST /voice/token` checks CONNECT_VOICE/SPEAK, 10-min tokens
2. Voice bar: join/leave, mute, deafen, connection state, reconnect, errors
3. Live presence (online/offline/idle) via the gateway
4. Gaming spaces: SVG map with voice zones (Lobby, Gaming, Chill, AFK…), click-to-join
5. Instant sessions: create/join/leave/close, slots, linked voice room, shown on Home
6. Session notes (notes, links, tasks)

## Milestone 4 — Social + beta hardening (week 4)

1. Friends: requests, accept/decline, remove, block; DMs
2. User reports + moderator queue
3. Real e-mail provider, production Dockerfile/reverse proxy (HTTPS, CSP), backups
4. Load test with ~50 concurrent users, fix hot spots
5. Onboard five gaming groups, feedback loop

## Later

First-party bot + bot API, discovery, Tauri desktop packaging, optional privacy-respecting AI
summaries (opt-in, never on voice without consent), screen sharing.
