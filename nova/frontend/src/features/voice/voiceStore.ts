/**
 * Voice connection (LiveKit). The backend issues a short-lived token after checking
 * CONNECT_VOICE/SPEAK; this store owns the Room and exposes a small, UI-friendly state.
 */
import type { RemoteTrack, Room } from "livekit-client";
import { create } from "zustand";
import { api, ApiError } from "@/lib/api";

export type VoiceStatus = "idle" | "connecting" | "connected" | "reconnecting" | "error";

interface VoiceTarget {
  channelId: string;
  channelName: string;
  serverId: string;
  serverName: string;
}

interface VoiceStore {
  status: VoiceStatus;
  target: VoiceTarget | null;
  error: string | null;
  muted: boolean;
  deafened: boolean;
  canSpeak: boolean;
  speaking: string[]; // user ids
  join: (target: VoiceTarget) => Promise<void>;
  leave: () => Promise<void>;
  toggleMute: () => Promise<void>;
  toggleDeafen: () => Promise<void>;
}

let room: Room | null = null;
const audioElements = new Set<HTMLMediaElement>();

async function reportState(channelId: string | null, muted = false, deafened = false) {
  try {
    await api("/voice/state", { method: "PUT", body: { channel_id: channelId, muted, deafened } });
  } catch {
    /* display only — ignore */
  }
}

export const useVoice = create<VoiceStore>((set, get) => ({
  status: "idle",
  target: null,
  error: null,
  muted: false,
  deafened: false,
  canSpeak: false,
  speaking: [],

  join: async (target) => {
    if (get().target?.channelId === target.channelId && get().status === "connected") return;
    await get().leave();
    set({ status: "connecting", target, error: null, speaking: [] });
    try {
      const { url, token, can_speak } = await api<{ url: string; token: string; can_speak: boolean }>(
        `/voice/channels/${target.channelId}/token`,
        { method: "POST" },
      );
      // Loaded on demand: keeps the ~200 KB LiveKit SDK out of the initial bundle.
      const { ConnectionState, Room, RoomEvent, Track } = await import("livekit-client");
      const r = new Room({ adaptiveStream: true, dynacast: true });
      room = r;
      r.on(RoomEvent.TrackSubscribed, (track: RemoteTrack) => {
        if (track.kind !== Track.Kind.Audio) return;
        const el = track.attach();
        el.muted = get().deafened;
        audioElements.add(el);
        document.body.appendChild(el);
      });
      r.on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
        for (const el of track.detach()) {
          audioElements.delete(el);
          el.remove();
        }
      });
      r.on(RoomEvent.ActiveSpeakersChanged, (speakers) => set({ speaking: speakers.map((p) => p.identity) }));
      r.on(RoomEvent.ConnectionStateChanged, (state) => {
        if (room !== r) return;
        if (state === ConnectionState.Reconnecting || state === ConnectionState.SignalReconnecting) {
          set({ status: "reconnecting" });
        } else if (state === ConnectionState.Connected) {
          set({ status: "connected" });
        }
      });
      r.on(RoomEvent.Disconnected, () => {
        if (room !== r) return; // intentional leave/switch
        room = null;
        set({ status: "error", error: "Verbindung zum Sprachkanal verloren." });
        void reportState(null);
      });

      await r.connect(url, token);
      await r.startAudio().catch(() => undefined);
      let muted = !can_speak;
      if (can_speak) {
        try {
          await r.localParticipant.setMicrophoneEnabled(true);
        } catch {
          muted = true;
          set({ error: "Kein Mikrofonzugriff – du hörst nur zu." });
        }
      }
      set({ status: "connected", canSpeak: can_speak, muted, deafened: false });
      await reportState(target.channelId, muted, false);
    } catch (err) {
      room?.disconnect();
      room = null;
      const message =
        err instanceof ApiError ? err.message : "Verbindung zum Sprachserver fehlgeschlagen. Ist LiveKit erreichbar?";
      set({ status: "error", error: message });
    }
  },

  leave: async () => {
    const r = room;
    const wasConnected = get().target !== null;
    room = null;
    for (const el of audioElements) el.remove();
    audioElements.clear();
    set({ status: "idle", target: null, speaking: [], muted: false, deafened: false });
    if (r) await r.disconnect();
    if (wasConnected) await reportState(null);
  },

  toggleMute: async () => {
    const { muted, deafened, canSpeak, target } = get();
    if (!room || !canSpeak) return;
    const next = !muted;
    await room.localParticipant.setMicrophoneEnabled(!next).catch(() => undefined);
    set({ muted: next, deafened: next ? deafened : false });
    if (!next && deafened) for (const el of audioElements) el.muted = false;
    if (target) await reportState(target.channelId, next, next ? deafened : false);
  },

  toggleDeafen: async () => {
    const { deafened, target, canSpeak } = get();
    if (!room) return;
    const next = !deafened;
    for (const el of audioElements) el.muted = next;
    // Deafening also mutes the microphone; undeafening restores it.
    if (canSpeak) await room.localParticipant.setMicrophoneEnabled(!next).catch(() => undefined);
    set({ deafened: next, muted: next || !canSpeak });
    if (target) await reportState(target.channelId, next || !canSpeak, next);
  },
}));
