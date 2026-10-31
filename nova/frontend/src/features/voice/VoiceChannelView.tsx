import { HeadphoneOff, Mic, MicOff, PhoneCall, PhoneOff } from "lucide-react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { useMembers } from "@/features/servers/api";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Channel, ServerDetail } from "@/lib/types";
import { useServerVoice } from "./useServerVoice";
import { useVoice } from "./voiceStore";

export function VoiceChannelView({ server, channel }: { server: ServerDetail; channel: Channel }) {
  const { byChannel } = useServerVoice(server.id);
  const { data: members = [] } = useMembers(server.id);
  const voice = useVoice();
  const here = voice.target?.channelId === channel.id && ["connected", "connecting", "reconnecting"].includes(voice.status);
  const states = byChannel[channel.id] ?? [];
  const canConnect = can(channel.my_permissions, "CONNECT_VOICE");
  const userById = new Map(members.map((m) => [m.user.id, m.user]));

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-8 p-6">
      <div className="flex flex-wrap justify-center gap-6">
        {states.length === 0 && <p className="text-muted">Noch niemand hier. Sei die erste Person!</p>}
        {states.map((s) => {
          const user = userById.get(s.user_id);
          const speaking = voice.speaking.includes(s.user_id);
          return (
            <div key={s.user_id} className="flex w-28 flex-col items-center gap-2 text-center">
              <div className={cn("rounded-full p-1 ring-2 transition", speaking ? "ring-success" : "ring-transparent")}>
                <Avatar id={s.user_id} name={user?.display_name ?? "?"} src={user?.avatar_url} size="lg" />
              </div>
              <div className="w-full truncate text-sm font-medium">{user?.display_name ?? "Unbekannt"}</div>
              <div className="flex gap-1 text-muted">
                {s.deafened ? <HeadphoneOff className="size-4 text-danger" aria-label="Taub" /> : s.muted ? <MicOff className="size-4 text-danger" aria-label="Stumm" /> : <Mic className="size-4" aria-label="Mikrofon an" />}
              </div>
            </div>
          );
        })}
      </div>
      {here ? (
        <Button variant="danger" onClick={() => void voice.leave()}>
          <PhoneOff className="size-4" /> Verlassen
        </Button>
      ) : (
        <div className="text-center">
          <Button
            onClick={() =>
              void voice.join({ channelId: channel.id, channelName: channel.name, serverId: server.id, serverName: server.name })
            }
            disabled={!canConnect}
            loading={voice.status === "connecting" && voice.target?.channelId === channel.id}
          >
            <PhoneCall className="size-4" /> Beitreten
          </Button>
          {!canConnect && <p className="mt-2 text-sm text-warning">Du darfst diesem Sprachkanal nicht beitreten.</p>}
          {voice.error && voice.status === "error" && <p className="mt-2 max-w-sm text-sm text-danger">{voice.error}</p>}
        </div>
      )}
    </div>
  );
}
