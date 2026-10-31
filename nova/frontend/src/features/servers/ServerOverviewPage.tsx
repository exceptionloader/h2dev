import { CalendarDays, Crown, Globe, Hash, LayoutDashboard, Lock, Map as MapIcon, Users } from "lucide-react";
import { motion } from "motion/react";
import { Link } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Badge, PageHeader, Panel } from "@/components/ui/Feedback";
import { formatDate } from "@/lib/format";
import { useChannels, useRoles } from "./api";
import { CHANNEL_ICON } from "./channelIcons";
import { useServerContext } from "./useServerContext";

export function ServerOverviewPage() {
  const server = useServerContext();
  const { data: channels = [] } = useChannels(server.id);
  const { data: roles = [] } = useRoles(server.id);
  // Same order as the sidebar: uncategorized first, then category by category.
  const categoryOrder = new Map(
    channels.filter((c) => c.type === "category").map((c, i) => [c.id, i + 1]),
  );
  const contentChannels = channels
    .filter((c) => c.type !== "category")
    .map((c) => ({ c, group: (c.parent_id && categoryOrder.get(c.parent_id)) || 0 }))
    .sort((a, b) => a.group - b.group || a.c.position - b.c.position)
    .map(({ c }) => c);

  return (
    <>
      <PageHeader icon={LayoutDashboard} title="Übersicht" />
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="flex-1 space-y-6 overflow-y-auto p-6"
      >
        <section className="relative overflow-hidden rounded-2xl border border-line bg-card p-6">
          <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_85%_0%,rgb(139_92_246/0.22),transparent_55%)]" />
          <div className="relative flex items-center gap-5">
            <Avatar id={server.id} name={server.name} src={server.icon_url} square size="lg" />
            <div className="min-w-0">
              <h2 className="truncate text-2xl font-bold">{server.name}</h2>
              {server.description && <p className="mt-1 text-muted">{server.description}</p>}
              <div className="mt-3 flex flex-wrap gap-2">
                <Badge>
                  {server.visibility === "public" ? <Globe className="size-3" /> : <Lock className="size-3" />}
                  {server.visibility === "public" ? "Öffentlich" : "Privat"}
                </Badge>
                <Badge>
                  <Users className="size-3" /> {server.member_count} Mitglied{server.member_count === 1 ? "" : "er"}
                </Badge>
                <Badge>
                  <CalendarDays className="size-3" /> seit {formatDate(server.created_at)}
                </Badge>
                {server.is_owner && (
                  <Badge color="#F59E0B">
                    <Crown className="size-3" /> Du bist Owner
                  </Badge>
                )}
              </div>
            </div>
          </div>
        </section>

        <div className="grid gap-6 xl:grid-cols-2">
          <Panel>
            <h3 className="mb-3 font-semibold">Kanäle</h3>
            <ul className="space-y-1">
              {contentChannels.map((c) => {
                const Icon = CHANNEL_ICON[c.type];
                return (
                  <li key={c.id}>
                    <Link
                      to={`/server/${server.id}/kanal/${c.id}`}
                      className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted hover:bg-card hover:text-fg"
                    >
                      <Icon className="size-4" aria-hidden /> {c.name}
                      {c.topic && <span className="truncate text-xs opacity-70">– {c.topic}</span>}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </Panel>

          <Panel>
            <h3 className="mb-3 font-semibold">Rollen</h3>
            <div className="flex flex-wrap gap-2">
              <Badge color="#F59E0B">Owner</Badge>
              {roles.map((r) => (
                <Badge key={r.id} color={r.color}>
                  {r.name}
                </Badge>
              ))}
            </div>
            <p className="mt-3 text-xs text-muted">
              Owner ist keine Rolle, sondern das Eigentum am Server – es kann nur per Übertragung
              wechseln.
            </p>
          </Panel>
        </div>

        <Panel className="border-dashed">
          <div className="flex items-start gap-3">
            <MapIcon className="mt-0.5 size-5 text-accent" aria-hidden />
            <div>
              <h3 className="font-semibold">Gaming Spaces & Sessions</h3>
              <p className="mt-1 text-sm text-muted">
                Interaktive 2D-Räume mit Voice-Zonen und spontanen Gaming-Sessions kommen in
                Meilenstein 3. <Hash className="inline size-3" /> Textchat folgt in Meilenstein 2.
              </p>
            </div>
          </div>
        </Panel>
      </motion.div>
    </>
  );
}
