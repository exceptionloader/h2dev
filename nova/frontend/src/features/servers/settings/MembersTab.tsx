import { Ban, Crown, UserMinus } from "lucide-react";
import { Badge, Panel } from "@/components/ui/Feedback";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useMe } from "@/features/auth/api";
import { formatDate } from "@/lib/format";
import { can } from "@/lib/permissions";
import type { ServerDetail } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useBans, useMemberActions, useMembers, useRoles } from "../api";

export function MembersTab({ server }: { server: ServerDetail }) {
  const { data: members = [] } = useMembers(server.id);
  const { data: roles = [] } = useRoles(server.id);
  const { data: me } = useMe();
  const actions = useMemberActions(server.id);
  const p = server.my_permissions;
  const assignable = roles.filter((r) => !r.is_default && !r.managed);
  const fail = (e: Error) => toast({ title: e.message, tone: "error" });

  return (
    <div className="space-y-6">
      <Panel>
        <h2 className="mb-3 font-semibold">Mitglieder ({members.length})</h2>
        <ul className="divide-y divide-line">
          {members.map((m) => (
            <li key={m.id} className="flex flex-wrap items-center gap-3 py-2.5">
              <UserAvatar user={m.user} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5 text-sm font-medium">
                  {m.user.display_name}
                  {m.is_owner && <Crown className="size-3.5 text-warning" aria-label="Owner" />}
                  {m.user.is_bot && <span className="rounded bg-primary/20 px-1 text-[10px] font-bold text-primary">BOT</span>}
                </div>
                <div className="text-xs text-muted">@{m.user.username} · seit {formatDate(m.joined_at)}</div>
              </div>
              <div className="flex flex-wrap gap-1">
                {roles.filter((r) => m.role_ids.includes(r.id)).map((r) => (
                  <span key={r.id} className="inline-flex items-center">
                    <Badge color={r.color}>{r.name}</Badge>
                    {can(p, "MANAGE_ROLES") && !r.managed && (
                      <button onClick={() => actions.setRole.mutate({ userId: m.user.id, roleId: r.id, assign: false }, { onError: fail })} className="-ml-1 px-1 text-xs text-muted hover:text-danger" aria-label={`${r.name} entfernen`}>×</button>
                    )}
                  </span>
                ))}
              </div>
              {can(p, "MANAGE_ROLES") && !m.user.is_bot && (
                <select
                  value=""
                  onChange={(e) => e.target.value && actions.setRole.mutate({ userId: m.user.id, roleId: e.target.value, assign: true }, { onError: fail })}
                  className="h-8 rounded-md border border-line bg-base px-2 text-xs"
                  aria-label={`Rolle für ${m.user.display_name} hinzufügen`}
                >
                  <option value="">+ Rolle</option>
                  {assignable.filter((r) => !m.role_ids.includes(r.id)).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              )}
              {!m.is_owner && m.user.id !== me?.id && (
                <div className="flex gap-1">
                  {can(p, "KICK_MEMBERS") && (
                    <button onClick={() => { if (window.confirm(`${m.user.display_name} entfernen?`)) actions.kick.mutate(m.user.id, { onError: fail }); }} className="rounded-md p-1.5 text-muted hover:bg-card hover:text-fg" aria-label={`${m.user.display_name} entfernen`} title="Entfernen"><UserMinus className="size-4" /></button>
                  )}
                  {can(p, "BAN_MEMBERS") && (
                    <button onClick={() => { const reason = window.prompt("Grund (optional):"); if (reason !== null) actions.ban.mutate({ user_id: m.user.id, reason: reason || undefined }, { onError: fail }); }} className="rounded-md p-1.5 text-muted hover:bg-card hover:text-danger" aria-label={`${m.user.display_name} bannen`} title="Bannen"><Ban className="size-4" /></button>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      </Panel>
      {can(p, "BAN_MEMBERS") && <BansPanel server={server} />}
    </div>
  );
}

function BansPanel({ server }: { server: ServerDetail }) {
  const { data: bans = [] } = useBans(server.id, true);
  const { unban } = useMemberActions(server.id);
  return (
    <Panel>
      <h2 className="mb-3 font-semibold">Gebannt ({bans.length})</h2>
      {bans.length === 0 && <p className="text-sm text-muted">Niemand gebannt.</p>}
      <ul className="divide-y divide-line">
        {bans.map((b) => (
          <li key={b.user.id} className="flex items-center gap-3 py-2">
            <UserAvatar user={b.user} showPresence={false} />
            <div className="min-w-0 flex-1 text-sm">
              <div className="font-medium">{b.user.display_name}</div>
              <div className="text-xs text-muted">{b.reason || "Kein Grund angegeben"} · {formatDate(b.created_at)}</div>
            </div>
            <button onClick={() => unban.mutate(b.user.id)} className="text-sm text-accent hover:underline">Entbannen</button>
          </li>
        ))}
      </ul>
    </Panel>
  );
}
