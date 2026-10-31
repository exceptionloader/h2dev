import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type {
  AuditLogEntry,
  Channel,
  ChannelType,
  Member,
  Role,
  Server,
  ServerDetail,
  Visibility,
} from "@/lib/types";

export const serverKeys = {
  list: ["servers"] as const,
  detail: (id: string) => ["server", id] as const,
  channels: (id: string) => ["server", id, "channels"] as const,
  members: (id: string) => ["server", id, "members"] as const,
  roles: (id: string) => ["server", id, "roles"] as const,
  audit: (id: string) => ["server", id, "audit"] as const,
};

export const useServers = () =>
  useQuery({ queryKey: serverKeys.list, queryFn: () => api<Server[]>("/servers") });

export const useServer = (id: string) =>
  useQuery({ queryKey: serverKeys.detail(id), queryFn: () => api<ServerDetail>(`/servers/${id}`) });

export const useChannels = (id: string) =>
  useQuery({
    queryKey: serverKeys.channels(id),
    queryFn: () => api<Channel[]>(`/servers/${id}/channels`),
  });

export const useMembers = (id: string) =>
  useQuery({
    queryKey: serverKeys.members(id),
    queryFn: () => api<Member[]>(`/servers/${id}/members`),
  });

export const useRoles = (id: string) =>
  useQuery({ queryKey: serverKeys.roles(id), queryFn: () => api<Role[]>(`/servers/${id}/roles`) });

export const useAuditLog = (id: string, enabled: boolean) =>
  useQuery({
    queryKey: serverKeys.audit(id),
    queryFn: () => api<AuditLogEntry[]>(`/servers/${id}/audit-logs`),
    enabled,
  });

export function useCreateServer() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; description?: string; visibility: Visibility }) =>
      api<ServerDetail>("/servers", { method: "POST", body }),
    onSuccess: (server) => {
      qc.setQueryData(serverKeys.detail(server.id), server);
      qc.invalidateQueries({ queryKey: serverKeys.list });
    },
  });
}

export function useUpdateServer(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name?: string; description?: string | null; visibility?: Visibility }) =>
      api<ServerDetail>(`/servers/${id}`, { method: "PATCH", body }),
    onSuccess: (server) => {
      qc.setQueryData(serverKeys.detail(id), server);
      qc.invalidateQueries({ queryKey: serverKeys.list });
      qc.invalidateQueries({ queryKey: serverKeys.audit(id) });
    },
  });
}

export function useDeleteServer(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (confirm_name: string) =>
      api<void>(`/servers/${id}`, { method: "DELETE", body: { confirm_name } }),
    onSuccess: () => {
      qc.removeQueries({ queryKey: ["server", id] });
      qc.invalidateQueries({ queryKey: serverKeys.list });
    },
  });
}

export function useCreateChannel(serverId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      name: string;
      type: ChannelType;
      topic?: string;
      parent_id?: string | null;
      private: boolean;
    }) => api<Channel>(`/servers/${serverId}/channels`, { method: "POST", body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: serverKeys.channels(serverId) });
      qc.invalidateQueries({ queryKey: serverKeys.audit(serverId) });
    },
  });
}
