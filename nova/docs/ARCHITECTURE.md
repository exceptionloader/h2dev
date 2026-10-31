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
 │ React SPA (Vite)             │  same-origin /api via Vite proxy (dev) or reverse proxy (prod)
 │ TanStack Query ⇄ REST        │
 │ WebSocket (M2) ⇄ gateway     │
 │ LiveKit client SDK (M3) ─────┼──────────────► LiveKit server (WebRTC media)
 └──────────────┬───────────────┘                      ▲
                │ HTTPS + cookie                       │ short-lived JWT, issued by backend
 ┌──────────────▼───────────────┐                      │ after a permission check
 │ FastAPI (single process)     │──────────────────────┘
 │  api/ → services/ → models/  │
 └──────┬───────────────┬───────┘
        │               │
   PostgreSQL     S3-compatible storage (M2: avatars, attachments)
                  Redis only once we run >1 backend process (pub/sub + rate limits)
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
| Real-time (M2/M3) | One authenticated WebSocket per client (cookie auth on upgrade + Origin check), server pushes events per subscribed server | Simple fan-out; Redis pub/sub only when a second process exists. |
| Voice (M3) | LiveKit; backend issues room tokens valid ~10 minutes after checking `CONNECT_VOICE`/`SPEAK` on that channel | LiveKit secret never reaches the browser. |
| Bots (later) | Bots are external processes talking to a scoped HTTP/WebSocket API with their own token; they get a member + roles like users | Never run third-party code in the main server; bots can't bypass permissions because they go through the same checks. |

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

Hierarchy rules (enforced by the role/moderation endpoints in M2, already tested):

- A member's rank = highest role `position`; the owner outranks everyone.
- You can only edit/assign roles **strictly below** your rank (`can_manage_role`).
- You can only grant permissions you hold yourself (`can_grant_permissions`) — the default
  Admin role does not hold `ADMINISTRATOR` and therefore can't create one.
- You can only kick/ban/re-role members ranked below you; nobody can act on the owner.

Non-members get **404** for every server endpoint, so server ids cannot be probed.

## 5. Database

Implemented now (migration `0001`):

```
users ─┬─< user_sessions                 (cascade on user delete)
       ├─< password_reset_tokens         (single-use, hashed, 30 min)
       ├─< servers.owner_id              (RESTRICT: transfer or delete servers first)
       └─< server_members >─ servers     (unique(server_id, user_id))
                 │                 │
                 └─< member_roles >─ server_roles   (role must belong to the same server — enforced in service code)
servers ─< channels (self-FK parent_id → category; categories can't nest — CHECK)
channels ─< channel_permission_overrides  (exactly one of role_id / member_id — CHECK; unique per target)
servers ─< audit_logs (actor_id SET NULL so history survives account deletion)
```

Conventions: UUID primary keys, `timestamptz` everywhere, named constraints (stable
migrations), enums as `CHECK`-constrained strings (cheap to extend), `ON DELETE CASCADE` from
server to everything it owns.

Planned tables and the milestone that adds them:

| Milestone | Tables |
| --- | --- |
| M2 | `messages` (channel_id, author_id, reply_to_id, edited_at, deleted_at, pinned_at), `message_reactions` (unique message/user/emoji), `attachments`, `server_invites` (code, uses, max_uses, expires_at), `server_bans` |
| M3 | `gaming_spaces`, `voice_zones` (space_id, channel_id, x/y/w/h), `gaming_sessions`, `gaming_session_members`, `session_notes` |
| M4 | `friendships` (requester, addressee, status, unique pair), `user_blocks`, `dm_channels`, `user_reports` |
| Later | `bot_applications`, `bot_installations`, `bot_permissions` |

## 6. Security checklist (status)

| Item | Status |
| --- | --- |
| Argon2id hashing, no plaintext secrets | ✅ |
| HttpOnly / SameSite cookies, `Secure` enforced in production | ✅ |
| Server-side session revocation (logout, password change, reset) | ✅ |
| Input validation (Pydantic, length limits, username charset) | ✅ |
| Rate limiting on auth endpoints | ✅ (single process) |
| Account enumeration resistance (login + reset) | ✅ |
| Server-side authorization on every server endpoint | ✅ |
| CSRF (SameSite + Origin check) | ✅ |
| Security headers (nosniff, frame deny, referrer policy) | ✅ — CSP via reverse proxy in production |
| XSS | ✅ React escapes output; no `dangerouslySetInnerHTML`. Message rendering (M2) must keep it that way. |
| Upload validation / size limits | ⏳ M2 |
| WebSocket auth | ⏳ M2 |
| LiveKit token authorization | ⏳ M3 |
| Abuse reports, blocking | ⏳ M4 |
| Real e-mail delivery for password reset | ⏳ before beta |
