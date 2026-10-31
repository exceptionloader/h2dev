# NOVA architecture

## 1. Smallest realistic MVP

The private beta targets five gaming communities. What they need to *replace* their current
tool for an evening of gaming:

1. Accounts, profiles, secure sessions
2. Servers with an owner, roles, permissions, invites, basic moderation
3. Text channels with real-time messages
4. Voice channels (LiveKit)
5. One differentiator that makes NOVA worth trying: **gaming spaces** (2D map of voice zones)
   and **instant sessions** ("Valorant Ranked – 2 needed")
6. Friends + DMs (minimal)

Everything else (marketplace, AI, screen share, discovery, Tauri packaging) waits until these
are stable. The roadmap in [ROADMAP.md](ROADMAP.md) orders the work so each milestone ships
something testable.

## 2. System overview

```
 Browser / later Tauri shell
 ┌──────────────────────────────┐
 │ React SPA (Vite)             │  same-origin /api via Vite proxy (dev) or Caddy (prod)
 │ TanStack Query ⇄ REST        │
 │ WebSocket ⇄ /api/v1/gateway  │
 │ livekit-client (lazy) ───────┼──────────────► LiveKit server (WebRTC media)
 └──────────────┬───────────────┘                      ▲
                │ HTTPS + cookie                       │ short-lived JWT, issued by backend
 ┌──────────────▼───────────────┐                      │ after a permission check
 │ FastAPI (single process)     │──────────────────────┘
 │  api/ → services/ → models/  │
 └──────┬───────────────┬───────┘
        │               │
   PostgreSQL     uploads: local volume or S3-compatible storage
                  Redis only once we run >1 backend process (pub/sub + rate limits)

 NOVA Bot (separate process) ── REST + gateway with "Authorization: Bot <token>"
```

A **modular monolith**: one FastAPI service, one database. No microservices, no Kubernetes.
That keeps a one-developer project operable, and the module boundaries (`services/*`) allow
splitting later if ever needed.

## 3. Key technical decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Sessions | Opaque random token in an `HttpOnly`, `SameSite=Lax` cookie; only its SHA-256 hash is stored (`user_sessions`) | Revocable instantly (logout, password change, ban), no token in JS-readable storage (XSS can't exfiltrate it). JWTs would need a denylist to get the same. |
| Passwords | Argon2id (`argon2-cffi`, RFC 9106 parameters), transparent rehash on login | Spec requirement; memory-hard. Unknown-user logins still run a hash (no timing oracle). |
| CSRF | `SameSite=Lax` + JSON-only bodies + Origin allow-list check on unsafe methods | Defence in depth without CSRF tokens in every form. |
| Permissions | 64-bit **bit field** per role (`server_roles.permissions`) and per override (`allow`/`deny`) instead of one row per permission | Atomic updates, trivial union/deny math, one column read per role. Readable names are produced by `app/core/permissions.py`. The spec's `role_permissions` table is therefore folded into this column. |
| Ownership | `servers.owner_id`, **not** a role | Role edits can never strip ownership; only an explicit transfer (M2) changes it. |
| Default role | Every server has exactly one `is_default` role ("Mitglied", enforced by a partial unique index); every member implicitly has it | Like an "everyone" role; private channels deny `VIEW_CHANNEL` on it. |
| Errors | `{code, detail}` with stable machine codes; the frontend maps codes to German text | UI language is independent of the API. |
| DB access | Async SQLAlchemy 2 + asyncpg | WebSockets and LiveKit webhooks share the event loop with HTTP. |
| Business logic | `services/` modules; routes only parse/serialize | Testable, keeps handlers thin. A separate repository layer was skipped on purpose — SQLAlchemy already is one, and extra indirection would slow a solo developer down. |
| Rate limiting | In-process sliding window (login, register, password reset) | Correct for one process. Must move to Redis before scaling out (documented in code). |
| Logging | JSON lines with request id, method, path, status, duration | Greppable locally, ingestible by any log stack later. |
| Real-time | One WebSocket per client (`/api/v1/gateway`): cookie auth + Origin check for browsers, bot token for bots. Server → client only; all actions go through REST. Message events are sent only to members who can **view that channel** (computed per message). Revoked sessions close their sockets. | Simple, secure fan-out. Hub state (connections, presence, voice states) is in-process → Redis pub/sub once there is a second process. |
| Voice | LiveKit; `POST /voice/channels/{id}/token` checks `CONNECT_VOICE`, sets `canPublish` from `SPEAK`, token valid 10 min. Clients report their voice state afterwards (display only). | LiveKit secret never reaches the browser; media access is enforced by LiveKit via the token. |
| Bots | External processes with a hashed token (`Authorization: Bot …`). Inside a server a bot is a normal member with a **managed role** holding exactly the permissions granted at install time (capped by the installer's own permissions, never `ADMINISTRATOR`). Bots are blocked from account/social endpoints. | Third-party code never runs in the main server; bots go through the same permission checks as humans. |
| Uploads | Files are read with a size cap, image types detected from magic bytes (client content type ignored), served by the API after a permission check with `Content-Security-Policy: sandbox` and `nosniff`; non-images are forced downloads. | Prevents stored XSS via uploads; works with local disk or any S3-compatible store. |

## 4. Permission model

Implemented in `backend/app/core/permissions.py` (pure functions, unit-tested).

```
effective(server)  = owner ? ALL
                   : default_role | roles…           (ADMINISTRATOR ⇒ ALL)
effective(channel) = start with effective(server)
                     apply default-role override     (deny, then allow)
                     apply union of member's role overrides (deny all, then allow all)
                     apply member-specific override  (deny, then allow)
                     no VIEW_CHANNEL ⇒ nothing
```

Hierarchy rules (enforced by the role, moderation, override and bot-install endpoints; tested):

- A member's rank = highest role `position`; the owner outranks everyone.
- You can only edit/assign roles **strictly below** your rank (`can_manage_role`), and you can only assign a role whose permissions you hold yourself.
- You can only grant permissions you hold yourself (`can_grant_permissions`) — the default
  Admin role does not hold `ADMINISTRATOR` and therefore can't create one.
- You can only kick/ban/re-role members ranked below you; nobody can act on the owner.

Non-members get **404** for every server endpoint, so server ids cannot be probed.

## 5. Database

Migrations: `0001` (core) and `0002` (community, social, activity, bots).

```
users ─┬─< user_sessions                 (cascade on user delete)
       ├─< password_reset_tokens         (single-use, hashed, 30 min)
       ├─< servers.owner_id              (RESTRICT: transfer or delete servers first)
       └─< server_members >─ servers     (unique(server_id, user_id))
                 │                 │
                 └─< member_roles >─ server_roles   (role must belong to the same server — enforced in service code)
servers ─┬─< channels (self-FK parent_id → category; categories can't nest — CHECK)
         │     └─< channel_permission_overrides (exactly one of role_id / member_id — CHECK)
         ├─< server_invites (code unique, uses / max_uses / expires_at)
         ├─< server_bans (PK server_id + user_id)
         ├─< audit_logs (actor_id SET NULL so history survives account deletion)
         ├─< gaming_spaces ─< voice_zones (each zone 1:1 with a voice channel, unique)
         ├─< gaming_sessions ─┬─< gaming_session_members (PK session + user)
         │                    └─< session_notes (note / link / task / followup)
         ├─< bot_installations (unique bot+server, managed role FK cascade)
         └─< user_reports (server-scoped queue; server_id NULL = platform report)
channels ─< messages ─┬─< message_reactions (PK message + user + emoji)
                      ├─< attachments (unlinked until the message is sent)
                      └── reply_to_id → messages (SET NULL)
friendships  (one row per pair via unique index on least/greatest of the two ids)
user_blocks  (PK blocker + blocked)
dm_channels  (ordered pair low < high, unique) ─< direct_messages
bot_applications (bot_user_id → users, unique; token hash unique)
```

Conventions: UUID primary keys, `timestamptz` everywhere, named constraints, enums as
`CHECK`-constrained strings, `ON DELETE CASCADE` from a server to everything it owns.
The spec's `role_permissions` / `bot_permissions` tables are folded into bit-field columns
(`server_roles.permissions`, `bot_installations.permissions`).

## 6. Security checklist (status)

| Item | Status |
| --- | --- |
| Argon2id hashing, no plaintext secrets, hashed session/reset/bot tokens | ✅ |
| HttpOnly / SameSite cookies, `Secure` enforced in production | ✅ |
| Server-side session revocation (logout, password change, reset) incl. open WebSockets | ✅ |
| Input validation (Pydantic, length limits, username charset) | ✅ |
| Rate limiting: login, register, reset, messages, invites, friend requests, reports, uploads | ✅ (single process) |
| Account enumeration resistance (login, reset, friend requests) | ✅ |
| Server-side authorization on every endpoint; non-members get 404 | ✅ |
| Role hierarchy + anti-escalation (create/edit/assign roles, overrides, bot installs) | ✅ tested |
| CSRF (SameSite + Origin check, JSON bodies) | ✅ |
| WebSocket auth + Origin check, per-channel event filtering | ✅ tested |
| Upload validation: size limits, magic-byte sniffing, sandboxed serving | ✅ tested |
| XSS: React escaping, links rendered as elements, no `dangerouslySetInnerHTML` | ✅ tested |
| Security headers (API) + CSP/HSTS in the Caddy config | ✅ (Caddy config not run in CI) |
| Voice tokens: short-lived, permission-checked, secret server-only | ✅ tested |
| Bots isolated (separate process, scoped managed role, no human endpoints) | ✅ tested |
| Blocking, reports, moderation queue, audit log | ✅ tested |
| Secrets via environment only (`.env.prod`, never committed) | ✅ |
