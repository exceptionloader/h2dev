import { create } from "zustand";
import type { GatewayStatus } from "@/lib/gateway";
import type { Presence, UserPublic, VoiceState } from "@/lib/types";

interface RealtimeState {
  status: GatewayStatus;
  presence: Record<string, Presence>;
  /** serverId → userId → voice state */
  voice: Record<string, Record<string, VoiceState>>;
  setStatus: (s: GatewayStatus) => void;
  setPresence: (userId: string, p: Presence) => void;
  setServerVoice: (serverId: string, states: VoiceState[]) => void;
  applyVoice: (serverId: string, userId: string, state: VoiceState | null) => void;
}

export const useRealtime = create<RealtimeState>((set) => ({
  status: "closed",
  presence: {},
  voice: {},
  setStatus: (status) => set({ status }),
  setPresence: (userId, p) => set((s) => ({ presence: { ...s.presence, [userId]: p } })),
  setServerVoice: (serverId, states) =>
    set((s) => ({ voice: { ...s.voice, [serverId]: Object.fromEntries(states.map((v) => [v.user_id, v])) } })),
  applyVoice: (serverId, userId, state) =>
    set((s) => {
      const server = { ...(s.voice[serverId] ?? {}) };
      if (state) server[userId] = state;
      else delete server[userId];
      return { voice: { ...s.voice, [serverId]: server } };
    }),
}));

/** Live presence: realtime updates win over the value delivered with the user object. */
export function usePresence(user: Pick<UserPublic, "id" | "presence"> | null | undefined): Presence {
  const live = useRealtime((s) => (user ? s.presence[user.id] : undefined));
  return live ?? user?.presence ?? "offline";
}
