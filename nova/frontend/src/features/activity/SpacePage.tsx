import { Coffee, Gamepad2, Lock, Map as MapIcon, Moon, PhoneOff, Swords, Trash2, Users } from "lucide-react";
import { motion } from "motion/react";
import { useState, type DragEvent } from "react";
import { useNavigate, useParams } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, FullPageSpinner, PageHeader } from "@/components/ui/Feedback";
import { useMe } from "@/features/auth/api";
import { useMembers } from "@/features/servers/api";
import { useServerContext } from "@/features/servers/useServerContext";
import { useServerVoice } from "@/features/voice/useServerVoice";
import { useVoice } from "@/features/voice/voiceStore";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Zone } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useSpaceActions, useSpaces } from "./api";

const ZONE_ICON = { lobby: Users, gaming: Gamepad2, chill: Coffee, strategy: Swords, afk: Moon, custom: MapIcon };
const ZONE_HINT = {
  lobby: "Ankommen & quatschen",
  gaming: "Zusammen zocken",
  chill: "Entspannt abhängen",
  strategy: "Taktik besprechen",
  afk: "Kurz weg",
  custom: "",
};

export function SpacePage() {
  const server = useServerContext();
  const { spaceId } = useParams();
  const { data: spaces, isPending } = useSpaces(server.id);
  const { byChannel } = useServerVoice(server.id);
  const { data: members = [] } = useMembers(server.id);
  const { data: me } = useMe();
  const voice = useVoice();
  const { remove } = useSpaceActions(server.id);
  const navigate = useNavigate();
  const [dragOver, setDragOver] = useState<string | null>(null);

  if (isPending) return <FullPageSpinner />;
  const space = spaces?.find((s) => s.id === spaceId);
  if (!space) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <EmptyState icon={MapIcon} title="Space nicht gefunden" />
      </div>
    );
  }

  const users = new Map(members.map((m) => [m.user.id, m.user]));
  const active = voice.status === "connected" || voice.status === "connecting" || voice.status === "reconnecting";
  const myZone = space.zones.find((z) => z.channel_id === voice.target?.channelId && active);

  const join = (zone: Zone) => {
    if (!zone.can_connect) {
      toast({ title: "Du darfst dieser Zone nicht beitreten.", tone: "error" });
      return;
    }
    void voice.join({ channelId: zone.channel_id, channelName: `${space.name} · ${zone.name}`, serverId: server.id, serverName: server.name });
  };

  const onDrop = (e: DragEvent, zone: Zone) => {
    e.preventDefault();
    setDragOver(null);
    if (e.dataTransfer.getData("text/nova-avatar") === me?.id) join(zone);
  };

  return (
    <>
      <PageHeader
        icon={MapIcon}
        title={space.name}
        subtitle={space.description}
        actions={
          can(server.my_permissions, "MANAGE_CHANNELS") && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                if (window.confirm(`Space „${space.name}“ und seine Sprachkanäle löschen?`))
                  remove.mutate(space.id, { onSuccess: () => navigate(`/server/${server.id}`, { replace: true }) });
              }}
            >
              <Trash2 className="size-4" /> Löschen
            </Button>
          )
        }
      />
      <div className="flex flex-1 flex-col gap-4 overflow-y-auto p-6">
        <div className="flex flex-wrap items-center gap-3 text-sm text-muted">
          {me && (
            <div
              draggable
              onDragStart={(e) => e.dataTransfer.setData("text/nova-avatar", me.id)}
              className="flex cursor-grab items-center gap-2 rounded-full border border-dashed border-primary/60 bg-primary/10 py-1 pr-3 pl-1 text-fg active:cursor-grabbing"
              title="Zieh deinen Avatar auf eine Zone"
            >
              <Avatar id={me.id} name={me.display_name} src={me.avatar_url} size="sm" />
              <span className="text-xs">Zieh mich auf eine Zone – oder klick eine an</span>
            </div>
          )}
          {myZone && (
            <Button size="sm" variant="secondary" onClick={() => void voice.leave()}>
              <PhoneOff className="size-4" /> {myZone.name} verlassen
            </Button>
          )}
        </div>

        <div
          className="relative mx-auto aspect-[16/10] w-full max-w-5xl overflow-hidden rounded-3xl border border-line bg-surface"
          style={{
            backgroundImage:
              "radial-gradient(circle at 1px 1px, rgb(148 163 184 / 0.12) 1px, transparent 0)",
            backgroundSize: "24px 24px",
          }}
          role="list"
          aria-label={`Karte von ${space.name}`}
        >
          {space.zones.map((zone) => {
            const Icon = ZONE_ICON[zone.kind];
            const states = byChannel[zone.channel_id] ?? [];
            const mine = myZone?.id === zone.id;
            const color = zone.color ?? "#8B5CF6";
            const connecting = voice.status === "connecting" && voice.target?.channelId === zone.channel_id;
            return (
              <motion.button
                key={zone.id}
                role="listitem"
                whileHover={{ scale: 1.01 }}
                onClick={() => !mine && join(zone)}
                onDragOver={(e) => { e.preventDefault(); setDragOver(zone.id); }}
                onDragLeave={() => setDragOver(null)}
                onDrop={(e) => onDrop(e, zone)}
                aria-label={`${zone.name}: ${states.length} Personen${mine ? ", du bist hier" : ""}`}
                className={cn(
                  "absolute flex flex-col rounded-2xl border-2 p-3 text-left transition-colors",
                  !zone.can_connect && "cursor-not-allowed opacity-60",
                )}
                style={{
                  left: `${zone.x}%`,
                  top: `${zone.y}%`,
                  width: `${zone.width}%`,
                  height: `${zone.height}%`,
                  borderColor: mine || dragOver === zone.id ? color : `${color}55`,
                  backgroundColor: mine ? `${color}26` : dragOver === zone.id ? `${color}1f` : `${color}0d`,
                }}
              >
                <div className="flex items-center gap-2">
                  <span className="rounded-lg p-1.5" style={{ backgroundColor: `${color}33`, color }}>
                    <Icon className="size-4" />
                  </span>
                  <div className="min-w-0">
                    <div className="truncate font-semibold">{zone.name}</div>
                    <div className="truncate text-xs text-muted">{ZONE_HINT[zone.kind]}</div>
                  </div>
                  {!zone.can_connect && <Lock className="ml-auto size-4 text-muted" />}
                  <span className="ml-auto flex items-center gap-1 rounded-full bg-base/60 px-2 py-0.5 text-xs">
                    {states.length > 0 && <span className="size-1.5 animate-pulse rounded-full bg-success" />}
                    {states.length}
                  </span>
                </div>
                <div className="mt-auto flex flex-wrap gap-1.5 pt-2">
                  {states.map((s) => {
                    const u = users.get(s.user_id);
                    return (
                      <motion.span
                        key={s.user_id}
                        layout
                        initial={{ scale: 0 }}
                        animate={{ scale: 1 }}
                        className={cn("rounded-full ring-2", voice.speaking.includes(s.user_id) ? "ring-success" : "ring-transparent")}
                        title={u?.display_name}
                      >
                        <Avatar id={s.user_id} name={u?.display_name ?? "?"} src={u?.avatar_url} size="sm" />
                      </motion.span>
                    );
                  })}
                  {connecting && <span className="text-xs text-muted">Verbinde …</span>}
                  {!states.length && !connecting && zone.can_connect && (
                    <span className="text-xs text-muted/70">Klicken zum Beitreten</span>
                  )}
                </div>
              </motion.button>
            );
          })}
        </div>
        {voice.status === "error" && voice.error && <p className="text-center text-sm text-danger">{voice.error}</p>}
      </div>
    </>
  );
}
