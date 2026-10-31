import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { GamingSession, NoteKind, SessionNote, Space } from "@/lib/types";

export const activityKeys = {
  spaces: (serverId: string) => ["server", serverId, "spaces"] as const,
  serverSessions: (serverId: string) => ["server", serverId, "sessions"] as const,
  mySessions: ["sessions"] as const,
  session: (id: string) => ["session", id] as const,
  notes: (id: string) => ["session", id, "notes"] as const,
};

export const useSpaces = (serverId: string) =>
  useQuery({ queryKey: activityKeys.spaces(serverId), queryFn: () => api<Space[]>(`/servers/${serverId}/spaces`) });

export function useSpaceActions(serverId: string) {
  const qc = useQueryClient();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: activityKeys.spaces(serverId) });
    qc.invalidateQueries({ queryKey: ["server", serverId, "channels"] });
  };
  return {
    create: useMutation({
      mutationFn: (body: { name: string; description?: string }) =>
        api<Space>(`/servers/${serverId}/spaces`, { method: "POST", body }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (spaceId: string) => api<void>(`/servers/${serverId}/spaces/${spaceId}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}

export const useMySessions = () =>
  useQuery({ queryKey: activityKeys.mySessions, queryFn: () => api<GamingSession[]>("/sessions") });

export const useServerSessions = (serverId: string) =>
  useQuery({
    queryKey: activityKeys.serverSessions(serverId),
    queryFn: () => api<GamingSession[]>(`/servers/${serverId}/sessions`),
  });

export const useSession = (id: string) =>
  useQuery({ queryKey: activityKeys.session(id), queryFn: () => api<GamingSession>(`/sessions/${id}`) });

function useSessionRefresh() {
  const qc = useQueryClient();
  return (session: GamingSession) => {
    qc.setQueryData(activityKeys.session(session.id), session);
    qc.invalidateQueries({ queryKey: activityKeys.mySessions });
    qc.invalidateQueries({ queryKey: activityKeys.serverSessions(session.server_id) });
  };
}

export function useCreateSession() {
  const refresh = useSessionRefresh();
  return useMutation({
    mutationFn: ({
      serverId,
      ...body
    }: {
      serverId: string;
      game: string;
      title: string;
      description?: string;
      max_participants: number;
      start_time?: string;
      voice_channel_id?: string | null;
    }) => api<GamingSession>(`/servers/${serverId}/sessions`, { method: "POST", body }),
    onSuccess: refresh,
  });
}

export function useSessionAction() {
  const refresh = useSessionRefresh();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: "join" | "leave" | "close" }) =>
      api<GamingSession>(`/sessions/${id}/${action}`, { method: "POST" }),
    onSuccess: refresh,
  });
}

export const useInviteToSession = (id: string) =>
  useMutation({
    mutationFn: (userId: string) => api<void>(`/sessions/${id}/invite`, { method: "POST", body: { user_id: userId } }),
  });

export const useNotes = (sessionId: string) =>
  useQuery({ queryKey: activityKeys.notes(sessionId), queryFn: () => api<SessionNote[]>(`/sessions/${sessionId}/notes`) });

export function useNoteActions(sessionId: string) {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: activityKeys.notes(sessionId) });
  return {
    add: useMutation({
      mutationFn: (body: { kind: NoteKind; content: string; url?: string; due_at?: string }) =>
        api<SessionNote>(`/sessions/${sessionId}/notes`, { method: "POST", body }),
      onSuccess: refresh,
    }),
    toggle: useMutation({
      mutationFn: ({ id, done }: { id: string; done: boolean }) =>
        api<SessionNote>(`/notes/${id}`, { method: "PATCH", body: { done } }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<void>(`/notes/${id}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}
