import { Gamepad2, MessageSquare, Plus, Server as ServerIcon, Sparkles, Users } from "lucide-react";
import { motion } from "motion/react";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Panel, Spinner } from "@/components/ui/Feedback";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useMySessions } from "@/features/activity/api";
import { CreateSessionModal } from "@/features/activity/CreateSessionModal";
import { SessionCard } from "@/features/activity/SessionCard";
import { useMe } from "@/features/auth/api";
import { useServers } from "@/features/servers/api";
import { useFriends, useOpenDM } from "@/features/social/api";
import { useRealtime } from "@/stores/realtime";
import { useUi } from "@/stores/ui";

function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return "Noch wach";
  if (h < 11) return "Guten Morgen";
  if (h < 18) return "Hey";
  return "Guten Abend";
}

function OnlineFriends() {
  const { data } = useFriends();
  const presence = useRealtime((s) => s.presence);
  const openDM = useOpenDM();
  const navigate = useNavigate();
  const online = (data?.friends ?? []).filter((f) => (presence[f.id] ?? f.presence) !== "offline");
  return (
    <aside aria-label="Freunde online" className="hidden w-64 shrink-0 overflow-y-auto border-l border-line bg-surface p-4 lg:block">
      <h3 className="mb-3 text-xs font-semibold tracking-wide text-muted uppercase">Freunde online — {online.length}</h3>
      {online.length === 0 ? (
        <div className="rounded-xl border border-dashed border-line p-4 text-center text-sm text-muted">
          <Users className="mx-auto mb-2 size-5 text-accent" aria-hidden />
          Gerade niemand online.{" "}
          <Link to="/freunde" className="text-accent hover:underline">Freunde hinzufügen</Link>
        </div>
      ) : (
        <ul className="space-y-1">
          {online.map((f) => (
            <li key={f.id} className="group flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-card">
              <UserAvatar user={f} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{f.display_name}</div>
                <div className="truncate text-xs text-muted">{f.custom_status || `@${f.username}`}</div>
              </div>
              <button
                onClick={() => openDM.mutate(f.id, { onSuccess: (dm) => navigate(`/nachrichten/${dm.id}`) })}
                className="text-muted opacity-0 group-hover:opacity-100 hover:text-fg"
                aria-label={`${f.display_name} schreiben`}
              >
                <MessageSquare className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}

export function HomePage() {
  const { data: me } = useMe();
  const { data: servers, isPending } = useServers();
  const { data: sessions = [], isPending: sessionsPending } = useMySessions();
  const openCreate = useUi((s) => s.setCreateServerOpen);
  const [sessionOpen, setSessionOpen] = useState(false);

  return (
    <div className="flex min-w-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col">
        <PageHeader icon={Sparkles} title="Home" />
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex-1 space-y-8 overflow-y-auto p-6">
          <div>
            <h2 className="text-2xl font-bold">{greeting()}, {me?.display_name} 👋</h2>
            <p className="mt-1 text-muted">Was spielt ihr heute?</p>
          </div>

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold tracking-wide text-muted uppercase">Live-Aktivitäten</h3>
              {servers && servers.length > 0 && (
                <Button size="sm" onClick={() => setSessionOpen(true)}><Gamepad2 className="size-4" /> Session starten</Button>
              )}
            </div>
            {sessionsPending ? <Spinner /> : sessions.length > 0 ? (
              <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
                {sessions.slice(0, 6).map((s) => <SessionCard key={s.id} session={s} showServer />)}
              </div>
            ) : (
              <Panel className="border-dashed">
                <div className="flex items-start gap-4">
                  <div className="rounded-xl bg-primary/15 p-3"><Gamepad2 className="size-6 text-primary" aria-hidden /></div>
                  <div>
                    <h4 className="font-semibold">Gerade keine offenen Sessions</h4>
                    <p className="mt-1 text-sm text-muted">Starte eine Runde wie „Valorant Ranked – 2 Spieler gesucht“. Alle auf dem Server sehen sie sofort.</p>
                  </div>
                </div>
              </Panel>
            )}
          </section>

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold tracking-wide text-muted uppercase">Deine Server</h3>
              {servers && servers.length > 0 && (
                <Button size="sm" variant="secondary" onClick={() => openCreate(true)}><Plus className="size-4" /> Server erstellen</Button>
              )}
            </div>
            {isPending ? <Spinner /> : servers && servers.length > 0 ? (
              <div className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-3">
                {servers.map((s) => (
                  <Link key={s.id} to={`/server/${s.id}`} className="group flex items-center gap-4 rounded-2xl border border-line bg-surface p-4 transition-colors hover:border-primary/50 hover:bg-card">
                    <Avatar id={s.id} name={s.name} src={s.icon_url} square />
                    <div className="min-w-0">
                      <div className="truncate font-semibold">{s.name}</div>
                      <div className="truncate text-sm text-muted">{s.description || "Keine Beschreibung"}</div>
                    </div>
                  </Link>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={ServerIcon}
                title="Noch keine Server"
                action={
                  <div className="flex gap-2">
                    <Button onClick={() => openCreate(true)}><Plus className="size-4" /> Server erstellen</Button>
                    <Link to="/entdecken" className="inline-flex h-10 items-center rounded-lg border border-line px-4 text-sm hover:bg-card">Server entdecken</Link>
                  </div>
                }
              >
                Erstelle einen Server für deine Gaming-Gruppe oder tritt über eine Einladung bei.
              </EmptyState>
            )}
          </section>
        </motion.div>
      </div>
      <OnlineFriends />
      <CreateSessionModal open={sessionOpen} onClose={() => setSessionOpen(false)} />
    </div>
  );
}
