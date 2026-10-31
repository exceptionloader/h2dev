import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, upload } from "@/lib/api";
import type {
  AuditLogEntry,
  Ban,
  Bot,
  BotInstallation,
  Invite,
  InvitePreview,
  Override,
  VoiceState,
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

// --- Milestone 2+ ----------------------------------------------------------------------------


export const moreKeys = {
  invites: (id: string) => ["server", id, "invites"] as const,
  bans: (id: string) => ["server", id, "bans"] as const,
  bots: (id: string) => ["server", id, "bots"] as const,
  availableBots: (id: string) => ["server", id, "bots", "available"] as const,
  overrides: (id: string, channelId: string) => ["server", id, "overrides", channelId] as const,
  voice: (id: string) => ["server", id, "voice"] as const,
};

/** Generic mutation that refreshes the given server scopes afterwards. */
export function useServerMutation<TVars, TResult = void>(
  serverId: string,
  fn: (vars: TVars) => Promise<TResult>,
  scopes: ReadonlyArray<readonly unknown[]> = [["server", serverId]],
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      for (const key of scopes) qc.invalidateQueries({ queryKey: key });
      qc.invalidateQueries({ queryKey: serverKeys.list });
    },
  });
}

export const useInvites = (id: string) =>
  useQuery({ queryKey: moreKeys.invites(id), queryFn: () => api<Invite[]>(`/servers/${id}/invites`) });

export const useCreateInvite = (id: string) =>
  useServerMutation(
    id,
    (body: { max_uses?: number | null; expires_in_hours?: number | null }) =>
      api<Invite>(`/servers/${id}/invites`, { method: "POST", body }),
    [moreKeys.invites(id)],
  );

export const useDeleteInvite = (id: string) =>
  useServerMutation(id, (code: string) => api<void>(`/invites/${code}`, { method: "DELETE" }), [moreKeys.invites(id)]);

export const useInvitePreview = (code: string) =>
  useQuery({ queryKey: ["invite", code], queryFn: () => api<InvitePreview>(`/invites/${code}`), retry: false });

export function useAcceptInvite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => api<Server>(`/invites/${code}/accept`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: serverKeys.list }),
  });
}

export const useBans = (id: string, enabled: boolean) =>
  useQuery({ queryKey: moreKeys.bans(id), queryFn: () => api<Ban[]>(`/servers/${id}/bans`), enabled });

export const useMemberActions = (id: string) => ({
  kick: useServerMutation(id, (userId: string) => api<void>(`/servers/${id}/members/${userId}`, { method: "DELETE" })),
  ban: useServerMutation(id, (body: { user_id: string; reason?: string }) =>
    api<void>(`/servers/${id}/bans`, { method: "POST", body }),
  ),
  unban: useServerMutation(id, (userId: string) => api<void>(`/servers/${id}/bans/${userId}`, { method: "DELETE" })),
  setRole: useServerMutation(id, ({ userId, roleId, assign }: { userId: string; roleId: string; assign: boolean }) =>
    api<void>(`/servers/${id}/members/${userId}/roles/${roleId}`, { method: assign ? "PUT" : "DELETE" }),
  ),
  leave: useServerMutation(id, () => api<void>(`/servers/${id}/members/@me`, { method: "DELETE" })),
  transfer: useServerMutation(id, (body: { new_owner_id: string; password: string }) =>
    api<void>(`/servers/${id}/transfer-ownership`, { method: "POST", body }),
  ),
});

export const useRoleActions = (id: string) => ({
  create: useServerMutation(id, (body: { name: string; color?: string | null; permissions: string[] }) =>
    api<Role>(`/servers/${id}/roles`, { method: "POST", body }),
  ),
  update: useServerMutation(
    id,
    ({ roleId, ...body }: { roleId: string; name?: string; color?: string | null; permissions?: string[] }) =>
      api<Role>(`/servers/${id}/roles/${roleId}`, { method: "PATCH", body }),
  ),
  remove: useServerMutation(id, (roleId: string) => api<void>(`/servers/${id}/roles/${roleId}`, { method: "DELETE" })),
  move: useServerMutation(id, ({ roleId, direction }: { roleId: string; direction: "up" | "down" }) =>
    api<void>(`/servers/${id}/roles/${roleId}/move`, { method: "POST", body: { direction } }),
  ),
});

export const useChannelActions = (id: string) => ({
  update: useServerMutation(
    id,
    ({ channelId, ...body }: { channelId: string; name?: string; topic?: string | null }) =>
      api<Channel>(`/servers/${id}/channels/${channelId}`, { method: "PATCH", body }),
  ),
  remove: useServerMutation(id, (channelId: string) =>
    api<void>(`/servers/${id}/channels/${channelId}`, { method: "DELETE" }),
  ),
  setOverride: useServerMutation(
    id,
    ({ channelId, roleId, allow, deny }: { channelId: string; roleId: string; allow: string[]; deny: string[] }) =>
      api<void>(`/servers/${id}/channels/${channelId}/overrides/roles/${roleId}`, {
        method: "PUT",
        body: { allow, deny },
      }),
  ),
});

export const useOverrides = (id: string, channelId: string, enabled: boolean) =>
  useQuery({
    queryKey: moreKeys.overrides(id, channelId),
    queryFn: () => api<Override[]>(`/servers/${id}/channels/${channelId}/overrides`),
    enabled,
  });

export const useUploadIcon = (id: string) =>
  useServerMutation(id, (file: File) => upload<Server>(`/servers/${id}/icon`, file));

export const useVoiceStates = (id: string) =>
  useQuery({ queryKey: moreKeys.voice(id), queryFn: () => api<VoiceState[]>(`/servers/${id}/voice-states`) });

export const useInstalledBots = (id: string) =>
  useQuery({ queryKey: moreKeys.bots(id), queryFn: () => api<BotInstallation[]>(`/servers/${id}/bots`) });

export const useAvailableBots = (id: string, enabled: boolean) =>
  useQuery({
    queryKey: moreKeys.availableBots(id),
    queryFn: () => api<Array<Bot & { installed: boolean }>>(`/servers/${id}/bots/available`),
    enabled,
  });

export const useBotInstall = (id: string) => ({
  install: useServerMutation(id, (body: { bot_id: string; permissions: string[] }) =>
    api<void>(`/servers/${id}/bots`, { method: "POST", body }),
  ),
  uninstall: useServerMutation(id, (botId: string) => api<void>(`/servers/${id}/bots/${botId}`, { method: "DELETE" })),
});
