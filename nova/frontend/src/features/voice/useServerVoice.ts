import { useEffect, useMemo } from "react";
import { useVoiceStates } from "@/features/servers/api";
import type { VoiceState } from "@/lib/types";
import { useRealtime } from "@/stores/realtime";

const EMPTY: Record<string, VoiceState> = {};

/** Voice states of a server: initial REST snapshot + live gateway updates. */
export function useServerVoice(serverId: string) {
  const { data } = useVoiceStates(serverId);
  const setServerVoice = useRealtime((s) => s.setServerVoice);
  useEffect(() => {
    if (data) setServerVoice(serverId, data);
  }, [data, serverId, setServerVoice]);
  const states = useRealtime((s) => s.voice[serverId] ?? EMPTY);
  const byChannel = useMemo(() => {
    const map: Record<string, VoiceState[]> = {};
    for (const v of Object.values(states)) (map[v.channel_id] ??= []).push(v);
    return map;
  }, [states]);
  return { states, byChannel };
}
