import { Bot as BotIcon, Copy, Trash2 } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Feedback";
import { FormError } from "@/components/ui/Field";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { InviteModal, inviteLink } from "@/features/invites/InviteModal";
import { formatDateTime } from "@/lib/format";
import { PERMISSION_LABELS, can, type PermissionName } from "@/lib/permissions";
import type { ServerDetail } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useAvailableBots, useBotInstall, useDeleteInvite, useInstalledBots, useInvites } from "../api";

export function InvitesTab({ server }: { server: ServerDetail }) {
  const { data: invites = [] } = useInvites(server.id);
  const del = useDeleteInvite(server.id);
  const [open, setOpen] = useState(false);
  return (
    <Panel>
      <div className="mb-3 flex items-center">
        <h2 className="flex-1 font-semibold">Einladungen</h2>
        {can(server.my_permissions, "CREATE_INVITES") && <Button size="sm" onClick={() => setOpen(true)}>Neue Einladung</Button>}
      </div>
      {invites.length === 0 && <p className="text-sm text-muted">Keine aktiven Einladungen.</p>}
      <ul className="divide-y divide-line">
        {invites.map((i) => (
          <li key={i.code} className="flex items-center gap-3 py-2 text-sm">
            <code className="rounded bg-base px-2 py-0.5">{i.code}</code>
            <span className="flex-1 text-xs text-muted">
              von {i.creator?.display_name ?? "?"} · {i.uses}{i.max_uses ? `/${i.max_uses}` : ""} genutzt · {i.expires_at ? `bis ${formatDateTime(i.expires_at)}` : "unbegrenzt"}
            </span>
            <button onClick={() => { void navigator.clipboard?.writeText(inviteLink(i.code)); toast({ title: "Link kopiert", tone: "success" }); }} className="text-muted hover:text-fg" aria-label="Link kopieren"><Copy className="size-4" /></button>
            <button onClick={() => del.mutate(i.code)} className="text-muted hover:text-danger" aria-label="Einladung löschen"><Trash2 className="size-4" /></button>
          </li>
        ))}
      </ul>
      <InviteModal serverId={server.id} open={open} onClose={() => setOpen(false)} />
    </Panel>
  );
}

const BOT_PERMS: PermissionName[] = ["VIEW_CHANNEL", "SEND_MESSAGES", "ADD_REACTIONS", "ATTACH_FILES", "MANAGE_MESSAGES", "KICK_MEMBERS"];

export function BotsTab({ server }: { server: ServerDetail }) {
  const allowed = can(server.my_permissions, "MANAGE_BOTS");
  const { data: installed = [] } = useInstalledBots(server.id);
  const { data: available = [] } = useAvailableBots(server.id, allowed);
  const { install, uninstall } = useBotInstall(server.id);
  const [perms, setPerms] = useState<Record<string, string[]>>({});
  const notInstalled = available.filter((b) => !b.installed);

  return (
    <div className="space-y-6">
      <Panel>
        <h2 className="mb-1 font-semibold">Installierte Bots</h2>
        <p className="mb-3 text-xs text-muted">Bots laufen außerhalb von NOVA und haben nur die Rechte ihrer Bot-Rolle.</p>
        {installed.length === 0 && <p className="text-sm text-muted">Noch keine Bots.</p>}
        <ul className="divide-y divide-line">
          {installed.map((i) => (
            <li key={i.bot.id} className="flex items-center gap-3 py-2">
              <UserAvatar user={i.bot.user} showPresence={false} />
              <div className="min-w-0 flex-1 text-sm">
                <div className="font-medium">{i.bot.name}</div>
                <div className="truncate text-xs text-muted">{i.permissions.map((p) => PERMISSION_LABELS[p as PermissionName] ?? p).join(", ") || "keine Rechte"}</div>
              </div>
              {allowed && <Button size="sm" variant="ghost" onClick={() => uninstall.mutate(i.bot.id)}>Entfernen</Button>}
            </li>
          ))}
        </ul>
      </Panel>
      {allowed && (
        <Panel>
          <h2 className="mb-3 flex items-center gap-2 font-semibold"><BotIcon className="size-4 text-accent" /> Bot hinzufügen</h2>
          <FormError message={install.error?.message} />
          {notInstalled.length === 0 && <p className="text-sm text-muted">Keine weiteren Bots verfügbar.</p>}
          <ul className="space-y-3">
            {notInstalled.map((b) => {
              const chosen = perms[b.id] ?? ["VIEW_CHANNEL", "SEND_MESSAGES"];
              return (
                <li key={b.id} className="rounded-xl border border-line bg-base/40 p-3">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{b.name}</span>
                    {b.is_first_party && <span className="rounded bg-primary/20 px-1.5 text-[10px] font-bold text-primary">OFFIZIELL</span>}
                  </div>
                  {b.description && <p className="text-sm text-muted">{b.description}</p>}
                  <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                    {BOT_PERMS.map((p) => (
                      <label key={p} className="flex items-center gap-1.5 text-xs">
                        <input
                          type="checkbox"
                          className="accent-primary"
                          checked={chosen.includes(p)}
                          disabled={!can(server.my_permissions, p)}
                          onChange={(e) => setPerms((s) => ({ ...s, [b.id]: e.target.checked ? [...chosen, p] : chosen.filter((x) => x !== p) }))}
                        />
                        {PERMISSION_LABELS[p]}
                      </label>
                    ))}
                  </div>
                  <Button size="sm" className="mt-3" loading={install.isPending} onClick={() => install.mutate({ bot_id: b.id, permissions: chosen })}>Installieren</Button>
                </li>
              );
            })}
          </ul>
        </Panel>
      )}
    </div>
  );
}
