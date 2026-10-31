import { ChevronDown, Flag, Gamepad2, LayoutDashboard, LogOut, Lock, Map as MapIcon, MicOff, Plus, ScrollText, Settings, UserPlus } from "lucide-react";
import { useMemo, useState } from "react";
import { NavLink, useNavigate } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Spinner } from "@/components/ui/Feedback";
import { useSpaces } from "@/features/activity/api";
import { CreateSpaceModal } from "@/features/activity/CreateSpaceModal";
import { InviteModal } from "@/features/invites/InviteModal";
import { useServerVoice } from "@/features/voice/useServerVoice";
import { useVoice } from "@/features/voice/voiceStore";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Channel, ServerDetail } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useChannels, useMemberActions, useMembers } from "./api";
import { CHANNEL_ICON } from "./channelIcons";
import { CreateChannelModal } from "./CreateChannelModal";

const item =
  "flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted transition-colors hover:bg-card hover:text-fg";
const itemActive = "bg-card text-fg";

function ChannelLink({ server, channel }: { server: ServerDetail; channel: Channel }) {
  const Icon = CHANNEL_ICON[channel.type];
  const { byChannel } = useServerVoice(server.id);
  const { data: members = [] } = useMembers(server.id);
  const speaking = useVoice((s) => s.speaking);
  const inVoice = channel.type === "voice" ? (byChannel[channel.id] ?? []) : [];
  const users = new Map(members.map((m) => [m.user.id, m.user]));
  return (
    <div>
      <NavLink to={`/server/${server.id}/kanal/${channel.id}`} className={({ isActive }) => cn(item, isActive && itemActive)}>
        <Icon className="size-4 shrink-0" aria-hidden />
        <span className="truncate">{channel.name}</span>
        {channel.is_private && <Lock className="ml-auto size-3.5 shrink-0" aria-label="Privat" />}
      </NavLink>
      {inVoice.length > 0 && (
        <ul className="mb-1 ml-6 space-y-0.5" aria-label={`Im Sprachkanal ${channel.name}`}>
          {inVoice.map((v) => {
            const u = users.get(v.user_id);
            return (
              <li key={v.user_id} className="flex items-center gap-2 px-2 py-0.5 text-xs text-muted">
                <span className={cn("rounded-full ring-2", speaking.includes(v.user_id) ? "ring-success" : "ring-transparent")}>
                  <Avatar id={v.user_id} name={u?.display_name ?? "?"} src={u?.avatar_url} size="sm" className="[&>div]:size-5 [&>img]:size-5 [&>div]:text-[8px]" />
                </span>
                <span className="truncate">{u?.display_name ?? "…"}</span>
                {(v.muted || v.deafened) && <MicOff className="ml-auto size-3 text-danger" aria-label="Stumm" />}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

export function ServerSidebar({ server }: { server: ServerDetail }) {
  const { data: channels, isPending } = useChannels(server.id);
  const { data: spaces = [] } = useSpaces(server.id);
  const [createFor, setCreateFor] = useState<string | null | undefined>(undefined);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [menu, setMenu] = useState(false);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [spaceOpen, setSpaceOpen] = useState(false);
  const { leave } = useMemberActions(server.id);
  const navigate = useNavigate();
  const p = server.my_permissions;
  const canManageChannels = can(p, "MANAGE_CHANNELS");
  const isModerator = can(p, "MANAGE_MESSAGES") || can(p, "KICK_MEMBERS");

  const { categories, uncategorized, byParent } = useMemo(() => {
    const list = channels ?? [];
    const spaceCats = new Set(spaces.map((s) => s.zones[0]?.channel_id && list.find((c) => c.id === s.zones[0]!.channel_id)?.parent_id));
    const cats = list.filter((c) => c.type === "category" && !spaceCats.has(c.id));
    const allCats = new Set(list.filter((c) => c.type === "category").map((c) => c.id));
    const grouped = new Map<string, Channel[]>();
    const loose: Channel[] = [];
    for (const c of list) {
      if (c.type === "category") continue;
      if (c.parent_id && allCats.has(c.parent_id)) grouped.set(c.parent_id, [...(grouped.get(c.parent_id) ?? []), c]);
      else loose.push(c);
    }
    return { categories: cats, uncategorized: loose, byParent: grouped };
  }, [channels, spaces]);

  return (
    <aside aria-label="Server-Navigation" className="flex w-60 shrink-0 flex-col border-r border-line bg-surface">
      <div className="relative shrink-0 border-b border-line">
        <button
          onClick={() => setMenu((v) => !v)}
          aria-expanded={menu}
          className="flex h-14 w-full items-center gap-2 px-4 text-left hover:bg-card"
        >
          <h2 className="flex-1 truncate font-semibold">{server.name}</h2>
          <ChevronDown className={cn("size-4 text-muted transition-transform", menu && "rotate-180")} />
        </button>
        {menu && (
          <div className="absolute inset-x-2 top-14 z-30 rounded-xl border border-line bg-surface p-1 shadow-2xl" role="menu">
            {can(p, "CREATE_INVITES") && (
              <button role="menuitem" className={cn(item, "w-full text-accent")} onClick={() => { setInviteOpen(true); setMenu(false); }}>
                <UserPlus className="size-4" /> Leute einladen
              </button>
            )}
            <NavLink role="menuitem" to={`/server/${server.id}/einstellungen`} className={item} onClick={() => setMenu(false)}>
              <Settings className="size-4" /> Servereinstellungen
            </NavLink>
            {!server.is_owner && (
              <button
                role="menuitem"
                className={cn(item, "w-full text-danger")}
                onClick={() => {
                  setMenu(false);
                  if (window.confirm(`„${server.name}“ wirklich verlassen?`))
                    leave.mutate(undefined, {
                      onSuccess: () => navigate("/", { replace: true }),
                      onError: (e) => toast({ title: e.message, tone: "error" }),
                    });
                }}
              >
                <LogOut className="size-4" /> Server verlassen
              </button>
            )}
          </div>
        )}
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto p-2">
        <div className="space-y-0.5">
          <NavLink to={`/server/${server.id}`} end className={({ isActive }) => cn(item, isActive && itemActive)}>
            <LayoutDashboard className="size-4" aria-hidden /> Übersicht
          </NavLink>
          <NavLink to={`/server/${server.id}/sessions`} className={({ isActive }) => cn(item, isActive && itemActive)}>
            <Gamepad2 className="size-4" aria-hidden /> Sessions
          </NavLink>
          {can(p, "CREATE_INVITES") && (
            <button onClick={() => setInviteOpen(true)} className={cn(item, "w-full")}>
              <UserPlus className="size-4" aria-hidden /> Einladen
            </button>
          )}
        </div>

        <div>
          <div className="flex items-center px-2 pb-1">
            <span className="flex-1 text-xs font-semibold tracking-wide text-muted uppercase">Gaming Spaces</span>
            {canManageChannels && (
              <button onClick={() => setSpaceOpen(true)} className="rounded p-0.5 text-muted hover:text-fg" aria-label="Gaming Space erstellen" title="Gaming Space erstellen">
                <Plus className="size-4" />
              </button>
            )}
          </div>
          {spaces.length === 0 && <p className="px-2 text-xs text-muted/70">{canManageChannels ? "Noch kein Space – erstelle einen!" : "Noch keine Spaces."}</p>}
          {spaces.map((s) => (
            <NavLink key={s.id} to={`/server/${server.id}/space/${s.id}`} className={({ isActive }) => cn(item, isActive && itemActive)}>
              <MapIcon className="size-4 text-accent" aria-hidden /> <span className="truncate">{s.name}</span>
            </NavLink>
          ))}
        </div>

        {isPending && <Spinner className="mx-auto size-5" />}

        {uncategorized.length > 0 && (
          <div className="space-y-0.5">
            {uncategorized.map((c) => <ChannelLink key={c.id} server={server} channel={c} />)}
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
                  <button onClick={() => setCreateFor(cat.id)} className="rounded p-0.5 text-muted hover:text-fg" aria-label={`Kanal in ${cat.name} erstellen`} title="Kanal erstellen">
                    <Plus className="size-4" />
                  </button>
                )}
              </div>
              {!isCollapsed && (
                <div className="space-y-0.5">
                  {(byParent.get(cat.id) ?? []).map((c) => <ChannelLink key={c.id} server={server} channel={c} />)}
                </div>
              )}
            </div>
          );
        })}

        {canManageChannels && (
          <button onClick={() => setCreateFor(categories[0]?.id ?? null)} className={cn(item, "w-full")}>
            <Plus className="size-4" aria-hidden /> Kanal erstellen
          </button>
        )}

        <div className="space-y-0.5 border-t border-line pt-3">
          <div className="px-2 pb-1 text-xs font-semibold tracking-wide text-muted uppercase">Verwaltung</div>
          <NavLink to={`/server/${server.id}/einstellungen`} className={({ isActive }) => cn(item, isActive && itemActive)}>
            <Settings className="size-4" aria-hidden /> Einstellungen & Rollen
          </NavLink>
          {isModerator && (
            <NavLink to={`/server/${server.id}/meldungen`} className={({ isActive }) => cn(item, isActive && itemActive)}>
              <Flag className="size-4" aria-hidden /> Meldungen
            </NavLink>
          )}
          {can(p, "VIEW_AUDIT_LOG") && (
            <NavLink to={`/server/${server.id}/audit-log`} className={({ isActive }) => cn(item, isActive && itemActive)}>
              <ScrollText className="size-4" aria-hidden /> Audit-Log
            </NavLink>
          )}
        </div>
      </div>

      <CreateChannelModal
        serverId={server.id}
        categories={(channels ?? []).filter((c) => c.type === "category")}
        defaultParentId={createFor ?? null}
        open={createFor !== undefined}
        onClose={() => setCreateFor(undefined)}
      />
      <InviteModal serverId={server.id} open={inviteOpen} onClose={() => setInviteOpen(false)} />
      <CreateSpaceModal serverId={server.id} open={spaceOpen} onClose={() => setSpaceOpen(false)} />
    </aside>
  );
}
