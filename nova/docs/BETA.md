# Running the private beta

## 1. Server

A single small VPS is enough for five communities (e.g. 4 vCPU / 8 GB RAM, ~€10–20/month
at an EU provider such as Hetzner — keeps data in the EU for GDPR).

1. Two DNS records → the VPS: `nova.example.com` and `voice.nova.example.com`.
2. Open ports: 80, 443 (web), 7881/tcp and 50000–50100/udp (voice).
3. Install Docker, clone the repo, then:

```bash
cd nova
cp .env.prod.example .env.prod      # fill in every value; generate secrets with: openssl rand -hex 32
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend
```

4. Mail: use an SMTP provider (EU-based recommended) so password resets work.
5. Backups: add `scripts/backup.sh` to cron and copy `backups/` off the server.

## 2. Before inviting testers (checklist)

- [ ] Register two accounts, create a server, invite the second account.
- [ ] Two people join the same voice channel and can **hear each other**; mute/deafen work.
- [ ] Gaming space: both join a zone; avatars appear in the zone.
- [ ] Password reset mail arrives.
- [ ] Install NOVA Bot (*Servereinstellungen → Bots*) and try `/hilfe`.
- [ ] Backup ran once and the dump restores (`gunzip -c … | psql`).

## 3. Onboarding the five groups

1. Create one NOVA server per community (or let their admin create it) and hand the admin
   the owner role via *Servereinstellungen → Owner*.
2. Share an invite link with 7-day expiry.
3. Ask each group to run at least one evening through NOVA: a session, the space, voice.

## 4. Feedback loop

- A `#feedback` channel on a shared NOVA server for all testers.
- Weekly 20-minute call with each group admin.
- Track: bugs (with screenshots), what they still did in other tools, voice quality.
- Watch the backend logs for `level: ERROR` and the moderation queue for abuse.
