import { ChevronDown, LayoutDashboard, Lock, Plus, ScrollText, Settings } from "lucide-react";
import { useMemo, useState } from "react";
import { NavLink } from "react-router";
import { Spinner } from "@/components/ui/Feedback";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Channel, ServerDetail } from "@/lib/types";
import { useChannels } from "./api";
import { CHANNEL_ICON } from "./channelIcons";
import { CreateChannelModal } from "./CreateChannelModal";

const item =
  "flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted transition-colors hover:bg-card hover:text-fg";
const itemActive = "bg-card text-fg";

function ChannelLink({ serverId, channel }: { serverId: string; channel: Channel }) {
  const Icon = CHANNEL_ICON[channel.type];
  return (
    <NavLink
      to={`/server/${serverId}/kanal/${channel.id}`}
      className={({ isActive }) => cn(item, isActive && itemActive)}
    >
      <Icon className="size-4 shrink-0" aria-hidden />
      <span className="truncate">{channel.name}</span>
      {channel.is_private && <Lock className="ml-auto size-3.5 shrink-0" aria-label="Privat" />}
    </NavLink>
  );
}

export function ServerSidebar({ server }: { server: ServerDetail }) {
  const { data: channels, isPending } = useChannels(server.id);
  const [createFor, setCreateFor] = useState<string | null | undefined>(undefined);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const canManageChannels = can(server.my_permissions, "MANAGE_CHANNELS");

  const { categories, uncategorized, byParent } = useMemo(() => {
    const list = channels ?? [];
    const cats = list.filter((c) => c.type === "category");
    const visibleCatIds = new Set(cats.map((c) => c.id));
    const grouped = new Map<string, Channel[]>();
    const loose: Channel[] = [];
    for (const c of list) {
      if (c.type === "category") continue;
      if (c.parent_id && visibleCatIds.has(c.parent_id)) {
        grouped.set(c.parent_id, [...(grouped.get(c.parent_id) ?? []), c]);
      } else {
        loose.push(c);
      }
    }
    return { categories: cats, uncategorized: loose, byParent: grouped };
  }, [channels]);

  return (
    <aside aria-label="Server-Navigation" className="flex w-60 shrink-0 flex-col border-r border-line bg-surface">
      <div className="flex h-14 shrink-0 items-center border-b border-line px-4">
        <h2 className="truncate font-semibold">{server.name}</h2>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto p-2">
        <NavLink to={`/server/${server.id}`} end className={({ isActive }) => cn(item, isActive && itemActive)}>
          <LayoutDashboard className="size-4" aria-hidden /> Übersicht
        </NavLink>

        {isPending && <Spinner className="mx-auto size-5" />}

        {uncategorized.length > 0 && (
          <div className="space-y-0.5">
            {uncategorized.map((c) => (
              <ChannelLink key={c.id} serverId={server.id} channel={c} />
            ))}
          </div>
        )}

        {categories.map((cat) => {
          const isCollapsed = collapsed[cat.id];
          return (
            <div key={cat.id}>
              <div className="group flex items-center px-1 pb-1">
                <button
                  onClick={() => setCollapsed((s) => ({ ...s, [cat.id]: !s[cat.id] }))}
                  aria-expanded={!isCollapsed}
                  className="flex flex-1 items-center gap-1 text-xs font-semibold tracking-wide text-muted uppercase hover:text-fg"
                >
                  <ChevronDown className={cn("size-3 transition-transform", isCollapsed && "-rotate-90")} />
                  <span className="truncate">{cat.name}</span>
                </button>
                {canManageChannels && (
                  <button
                    onClick={() => setCreateFor(cat.id)}
                    className="rounded p-0.5 text-muted hover:text-fg"
                    aria-label={`Kanal in ${cat.name} erstellen`}
                    title="Kanal erstellen"
                  >
                    <Plus className="size-4" />
                  </button>
                )}
              </div>
              {!isCollapsed && (
                <div className="space-y-0.5">
                  {(byParent.get(cat.id) ?? []).map((c) => (
                    <ChannelLink key={c.id} serverId={server.id} channel={c} />
                  ))}
                </div>
              )}
            </div>
          );
        })}

        {canManageChannels && (
          <button onClick={() => setCreateFor(null)} className={cn(item, "w-full")}>
            <Plus className="size-4" aria-hidden /> Kanal erstellen
          </button>
        )}

        <div className="space-y-0.5 border-t border-line pt-3">
          <div className="px-2 pb-1 text-xs font-semibold tracking-wide text-muted uppercase">Verwaltung</div>
          <NavLink to={`/server/${server.id}/einstellungen`} className={({ isActive }) => cn(item, isActive && itemActive)}>
            <Settings className="size-4" aria-hidden /> Einstellungen & Rollen
          </NavLink>
          {can(server.my_permissions, "VIEW_AUDIT_LOG") && (
            <NavLink to={`/server/${server.id}/audit-log`} className={({ isActive }) => cn(item, isActive && itemActive)}>
              <ScrollText className="size-4" aria-hidden /> Audit-Log
            </NavLink>
          )}
        </div>
      </div>

      <CreateChannelModal
        serverId={server.id}
        categories={categories}
        defaultParentId={createFor ?? null}
        open={createFor !== undefined}
        onClose={() => setCreateFor(undefined)}
      />
    </aside>
  );
}
