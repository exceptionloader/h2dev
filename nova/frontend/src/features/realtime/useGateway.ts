import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router";
import { activityKeys } from "@/features/activity/api";
import { meKey } from "@/features/auth/api";
import { removeMessage, upsertMessage } from "@/features/chat/api";
import { moreKeys, serverKeys } from "@/features/servers/api";
import { addDM, removeDM, socialKeys } from "@/features/social/api";
import { GatewayClient, type GatewayEvent } from "@/lib/gateway";
import type { DirectMessage, GamingSession, Message, UserMe, UserPublic, VoiceState } from "@/lib/types";
import { useRealtime } from "@/stores/realtime";
import { toast } from "@/stores/toasts";

const SCOPE_KEYS: Record<string, (id: string) => readonly unknown[]> = {
  members: serverKeys.members,
  roles: serverKeys.roles,
  channels: serverKeys.channels,
  server: serverKeys.detail,
  spaces: activityKeys.spaces,
  voice: moreKeys.voice,
  bots: moreKeys.bots,
  reports: (id) => ["server", id, "reports"],
};

/** Connects to the gateway while logged in and feeds events into the query cache/stores. */
export function useGateway(enabled: boolean) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const pathname = location.pathname;

  useEffect(() => {
    if (!enabled) return;
    const rt = useRealtime.getState();
    const me = () => qc.getQueryData<UserMe | null>(meKey);

    const handle = ({ type, data }: GatewayEvent) => {
      switch (type) {
        case "message.created":
        case "message.updated":
          upsertMessage(qc, data as Message);
          break;
        case "message.deleted":
          removeMessage(qc, data.channel_id, data.id);
          break;
        case "server.changed":
          for (const scope of data.scopes as string[]) {
            const key = SCOPE_KEYS[scope];
            if (key) qc.invalidateQueries({ queryKey: key(data.server_id) });
          }
          if ((data.scopes as string[]).includes("server")) qc.invalidateQueries({ queryKey: serverKeys.list });
          break;
        case "server.removed": {
          qc.invalidateQueries({ queryKey: serverKeys.list });
          qc.removeQueries({ queryKey: ["server", data.server_id] });
          const reasons: Record<string, string> = {
            kicked: "Du wurdest vom Server entfernt.",
            banned: "Du wurdest vom Server gebannt.",
            deleted: "Der Server wurde gelöscht.",
          };
          if (reasons[data.reason]) toast({ title: reasons[data.reason]!, tone: "error" });
          if (window.location.pathname.startsWith(`/server/${data.server_id}`)) navigate("/", { replace: true });
          break;
        }
        case "member.joined":
          break; // members list refresh comes via server.changed
        case "presence.updated":
          rt.setPresence(data.user_id, data.presence);
          break;
        case "voice.state":
          rt.applyVoice(data.server_id, data.user_id, data.channel_id ? (data as VoiceState) : null);
          break;
        case "session.created": {
          const s = data as GamingSession;
          qc.invalidateQueries({ queryKey: activityKeys.mySessions });
          qc.invalidateQueries({ queryKey: activityKeys.serverSessions(s.server_id) });
          if (s.creator?.id !== me()?.id) {
            const free = s.max_participants - s.participants.length;
            toast({ title: `Neue Session: ${s.title}`, body: `${s.game} · ${free} frei · ${s.server_name}`, href: `/sessions/${s.id}` });
          }
          break;
        }
        case "session.updated": {
          const s = data as GamingSession;
          qc.setQueryData(activityKeys.session(s.id), s);
          qc.invalidateQueries({ queryKey: activityKeys.mySessions });
          qc.invalidateQueries({ queryKey: activityKeys.serverSessions(s.server_id) });
          break;
        }
        case "session.notes_changed":
          qc.invalidateQueries({ queryKey: activityKeys.notes(data.session_id) });
          break;
        case "friends.changed":
          qc.invalidateQueries({ queryKey: socialKeys.friends });
          break;
        case "friend.request": {
          const u = data.user as UserPublic;
          toast({ title: "Neue Freundschaftsanfrage", body: `${u.display_name} (@${u.username})`, href: "/freunde" });
          break;
        }
        case "dm.message": {
          const msg = data as DirectMessage;
          addDM(qc, msg);
          qc.invalidateQueries({ queryKey: socialKeys.dms });
          if (msg.author.id !== me()?.id && !window.location.pathname.startsWith(`/nachrichten/${msg.dm_id}`)) {
            toast({ title: msg.author.display_name, body: msg.content.slice(0, 120), href: `/nachrichten/${msg.dm_id}` });
          }
          break;
        }
        case "dm.deleted":
          removeDM(qc, data.dm_id, data.id);
          break;
      }
    };

    const client = new GatewayClient(handle, (status) => {
      rt.setStatus(status);
      // After a reconnect, refetch what may have been missed.
      if (status === "open") {
        qc.invalidateQueries({ queryKey: ["channel"] });
        qc.invalidateQueries({ queryKey: ["server"] });
        qc.invalidateQueries({ queryKey: ["dms"] });
      }
    });
    client.start();
    return () => client.stop();
  }, [enabled, qc, navigate]);

  return pathname;
}
