import { Ban, Check, MessageSquare, UserMinus, UserPlus, Users, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { PRESENCE_LABEL } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Spinner } from "@/components/ui/Feedback";
import { FormError, TextField } from "@/components/ui/Field";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { cn } from "@/lib/cn";
import type { UserPublic } from "@/lib/types";
import { useRealtime } from "@/stores/realtime";
import { toast } from "@/stores/toasts";
import { useFriendActions, useFriends, useOpenDM } from "./api";

type Tab = "online" | "all" | "pending" | "blocked";

function Row({ user, children, sub }: { user: UserPublic; children: React.ReactNode; sub?: string }) {
  return (
    <li className="flex items-center gap-3 rounded-xl px-3 py-2 hover:bg-card">
      <UserAvatar user={user} size="md" />
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{user.display_name}</div>
        <div className="truncate text-xs text-muted">{sub ?? `@${user.username}`}</div>
      </div>
      <div className="flex gap-1">{children}</div>
    </li>
  );
}

const iconBtn = "rounded-full bg-card p-2 text-muted hover:text-fg";

export function FriendsPage() {
  const { data, isPending } = useFriends();
  const actions = useFriendActions();
  const openDM = useOpenDM();
  const navigate = useNavigate();
  const presence = useRealtime((s) => s.presence);
  const [tab, setTab] = useState<Tab>("online");
  const [username, setUsername] = useState("");

  const live = (u: UserPublic) => presence[u.id] ?? u.presence;
  const fail = (e: Error) => toast({ title: e.message, tone: "error" });
  const add = (e: FormEvent) => {
    e.preventDefault();
    actions.request.mutate(username.trim(), {
      onSuccess: () => { toast({ title: "Anfrage gesendet", tone: "success" }); setUsername(""); },
    });
  };
  const message = (userId: string) =>
    openDM.mutate(userId, { onSuccess: (dm) => navigate(`/nachrichten/${dm.id}`), onError: fail });

  const friends = data?.friends ?? [];
  const online = friends.filter((f) => live(f) !== "offline");
  const pendingCount = (data?.incoming.length ?? 0);
  const tabs: Array<[Tab, string]> = [["online", `Online (${online.length})`], ["all", `Alle (${friends.length})`], ["pending", `Ausstehend${pendingCount ? ` (${pendingCount})` : ""}`], ["blocked", "Blockiert"]];

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={Users} title="Freunde" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-3xl space-y-6">
          <form onSubmit={add} className="rounded-2xl border border-line bg-surface p-4">
            <FormError message={actions.request.error?.message} />
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <TextField label="Freund hinzufügen" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Benutzername, z. B. basti" maxLength={32} />
              </div>
              <Button type="submit" disabled={!username.trim()} loading={actions.request.isPending}>
                <UserPlus className="size-4" /> Anfrage senden
              </Button>
            </div>
          </form>

          <div className="flex gap-1 border-b border-line" role="tablist">
            {tabs.map(([key, label]) => (
              <button key={key} role="tab" aria-selected={tab === key} onClick={() => setTab(key)} className={cn("border-b-2 px-3 py-2 text-sm", tab === key ? "border-primary text-fg" : "border-transparent text-muted hover:text-fg")}>
                {label}
              </button>
            ))}
          </div>

          {isPending && <Spinner />}
          {(tab === "online" || tab === "all") && (
            (tab === "online" ? online : friends).length === 0 ? (
              <EmptyState icon={Users} title={tab === "online" ? "Gerade niemand online" : "Noch keine Freunde"}>Füge Leute über ihren Benutzernamen hinzu.</EmptyState>
            ) : (
              <ul>
                {(tab === "online" ? online : friends).map((f) => (
                  <Row key={f.id} user={f} sub={f.custom_status || PRESENCE_LABEL[live(f)]}>
                    <button className={iconBtn} onClick={() => message(f.id)} aria-label={`${f.display_name} schreiben`} title="Nachricht"><MessageSquare className="size-4" /></button>
                    <button className={iconBtn} onClick={() => { if (window.confirm(`${f.display_name} entfernen?`)) actions.remove.mutate(f.id, { onError: fail }); }} aria-label="Freund entfernen" title="Entfernen"><UserMinus className="size-4" /></button>
                    <button className={cn(iconBtn, "hover:text-danger")} onClick={() => { if (window.confirm(`${f.display_name} blockieren?`)) actions.block.mutate(f.id, { onError: fail }); }} aria-label="Blockieren" title="Blockieren"><Ban className="size-4" /></button>
                  </Row>
                ))}
              </ul>
            )
          )}
          {tab === "pending" && (
            <div className="space-y-4">
              {data?.incoming.length === 0 && data?.outgoing.length === 0 && <p className="text-sm text-muted">Keine offenen Anfragen.</p>}
              {data?.incoming.length ? <h3 className="text-xs font-semibold text-muted uppercase">Eingehend</h3> : null}
              <ul>
                {data?.incoming.map((r) => (
                  <Row key={r.id} user={r.user}>
                    <button className={cn(iconBtn, "hover:text-success")} onClick={() => actions.accept.mutate(r.id, { onError: fail })} aria-label="Annehmen" title="Annehmen"><Check className="size-4" /></button>
                    <button className={cn(iconBtn, "hover:text-danger")} onClick={() => actions.decline.mutate(r.id, { onError: fail })} aria-label="Ablehnen" title="Ablehnen"><X className="size-4" /></button>
                  </Row>
                ))}
              </ul>
              {data?.outgoing.length ? <h3 className="text-xs font-semibold text-muted uppercase">Gesendet</h3> : null}
              <ul>
                {data?.outgoing.map((r) => (
                  <Row key={r.id} user={r.user} sub="Anfrage gesendet">
                    <button className={iconBtn} onClick={() => actions.decline.mutate(r.id, { onError: fail })} aria-label="Zurückziehen" title="Zurückziehen"><X className="size-4" /></button>
                  </Row>
                ))}
              </ul>
            </div>
          )}
          {tab === "blocked" && (
            <ul>
              {data?.blocked.length === 0 && <li className="text-sm text-muted">Niemand blockiert.</li>}
              {data?.blocked.map((u) => (
                <Row key={u.id} user={u}>
                  <Button size="sm" variant="secondary" onClick={() => actions.unblock.mutate(u.id, { onError: fail })}>Entsperren</Button>
                </Row>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
