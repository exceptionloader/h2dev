import { Gamepad2, Plus, Server as ServerIcon, Sparkles, Users } from "lucide-react";
import { motion } from "motion/react";
import { Link } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Panel, Spinner } from "@/components/ui/Feedback";
import { useMe } from "@/features/auth/api";
import { useServers } from "@/features/servers/api";
import { useUi } from "@/stores/ui";

function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return "Noch wach";
  if (h < 11) return "Guten Morgen";
  if (h < 18) return "Hey";
  return "Guten Abend";
}

export function HomePage() {
  const { data: me } = useMe();
  const { data: servers, isPending } = useServers();
  const openCreate = useUi((s) => s.setCreateServerOpen);

  return (
    <div className="flex min-w-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col">
        <PageHeader icon={Sparkles} title="Home" />
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex-1 space-y-8 overflow-y-auto p-6">
          <div>
            <h2 className="text-2xl font-bold">
              {greeting()}, {me?.display_name} 👋
            </h2>
            <p className="mt-1 text-muted">Was spielt ihr heute?</p>
          </div>

          <section>
            <h3 className="mb-3 text-sm font-semibold tracking-wide text-muted uppercase">Live-Aktivitäten</h3>
            <Panel className="border-dashed">
              <div className="flex items-start gap-4">
                <div className="rounded-xl bg-primary/15 p-3">
                  <Gamepad2 className="size-6 text-primary" aria-hidden />
                </div>
                <div>
                  <h4 className="font-semibold">Instant Gaming Sessions</h4>
                  <p className="mt-1 text-sm text-muted">
                    Bald findest du hier spontane Runden wie „Valorant Ranked – 2 Spieler gesucht“
                    aus deinen Servern – mit freien Plätzen und direktem Voice-Beitritt. Kommt in
                    Meilenstein 3.
                  </p>
                </div>
              </div>
            </Panel>
          </section>

          <section>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold tracking-wide text-muted uppercase">Deine Server</h3>
              {servers && servers.length > 0 && (
                <Button size="sm" variant="secondary" onClick={() => openCreate(true)}>
                  <Plus className="size-4" /> Server erstellen
                </Button>
              )}
            </div>
            {isPending ? (
              <Spinner />
            ) : servers && servers.length > 0 ? (
              <div className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-3">
                {servers.map((s) => (
                  <Link
                    key={s.id}
                    to={`/server/${s.id}`}
                    className="group flex items-center gap-4 rounded-2xl border border-line bg-surface p-4 transition-colors hover:border-primary/50 hover:bg-card"
                  >
                    <Avatar id={s.id} name={s.name} src={s.icon_url} square />
                    <div className="min-w-0">
                      <div className="truncate font-semibold group-hover:text-fg">{s.name}</div>
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
                  <Button onClick={() => openCreate(true)}>
                    <Plus className="size-4" /> Ersten Server erstellen
                  </Button>
                }
              >
                Erstelle einen Server für deine Gaming-Gruppe. Du wirst automatisch Owner.
              </EmptyState>
            )}
          </section>
        </motion.div>
      </div>

      <aside aria-label="Freunde" className="hidden w-64 shrink-0 border-l border-line bg-surface p-4 lg:block">
        <h3 className="mb-3 text-xs font-semibold tracking-wide text-muted uppercase">Freunde online</h3>
        <div className="rounded-xl border border-dashed border-line p-4 text-center text-sm text-muted">
          <Users className="mx-auto mb-2 size-5 text-accent" aria-hidden />
          Freundesliste, Anfragen und Online-Status folgen in Meilenstein 4.
        </div>
      </aside>
    </div>
  );
}
