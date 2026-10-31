import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Bot, DMChannel, DirectMessage, DiscoverServer, Friends, Report, ReportReason, Server } from "@/lib/types";

export const socialKeys = {
  friends: ["friends"] as const,
  dms: ["dms"] as const,
  dmMessages: (id: string) => ["dms", id, "messages"] as const,
  discover: (q: string) => ["discover", q] as const,
  reports: (serverId: string, status: string) => ["server", serverId, "reports", status] as const,
  myBots: ["bots", "mine"] as const,
};

export const useFriends = () =>
  useQuery({ queryKey: socialKeys.friends, queryFn: () => api<Friends>("/friends") });

export function useFriendActions() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: socialKeys.friends });
  return {
    request: useMutation({
      mutationFn: (username: string) => api<void>("/friends/requests", { method: "POST", body: { username } }),
      onSuccess: refresh,
    }),
    accept: useMutation({
      mutationFn: (id: string) => api<void>(`/friends/requests/${id}/accept`, { method: "POST" }),
      onSuccess: refresh,
    }),
    decline: useMutation({
      mutationFn: (id: string) => api<void>(`/friends/requests/${id}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (userId: string) => api<void>(`/friends/${userId}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
    block: useMutation({
      mutationFn: (userId: string) => api<void>(`/blocks/${userId}`, { method: "PUT" }),
      onSuccess: refresh,
    }),
    unblock: useMutation({
      mutationFn: (userId: string) => api<void>(`/blocks/${userId}`, { method: "DELETE" }),
      onSuccess: refresh,
    }),
  };
}

export const useDMs = () => useQuery({ queryKey: socialKeys.dms, queryFn: () => api<DMChannel[]>("/dms") });

export function useOpenDM() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => api<DMChannel>("/dms", { method: "POST", body: { user_id: userId } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: socialKeys.dms }),
  });
}

export const useDMMessages = (dmId: string) =>
  useInfiniteQuery({
    queryKey: socialKeys.dmMessages(dmId),
    queryFn: ({ pageParam }) =>
      api<DirectMessage[]>(`/dms/${dmId}/messages${pageParam ? `?before=${pageParam}` : ""}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.length >= 50 ? last[0]?.id : undefined),
    staleTime: Infinity,
  });

export function useSendDM(dmId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (content: string) =>
      api<DirectMessage>(`/dms/${dmId}/messages`, { method: "POST", body: { content } }),
    onSuccess: (msg) => {
      addDM(qc, msg);
      qc.invalidateQueries({ queryKey: socialKeys.dms });
    },
  });
}

type DMPages = { pages: DirectMessage[][]; pageParams: unknown[] };

export function addDM(qc: ReturnType<typeof useQueryClient>, msg: DirectMessage) {
  qc.setQueryData<DMPages>(socialKeys.dmMessages(msg.dm_id), (data) => {
    if (!data || data.pages.some((p) => p.some((m) => m.id === msg.id))) return data;
    const pages = [...data.pages];
    pages[0] = [...(pages[0] ?? []), msg];
    return { ...data, pages };
  });
}

export function removeDM(qc: ReturnType<typeof useQueryClient>, dmId: string, id: string) {
  qc.setQueryData<DMPages>(socialKeys.dmMessages(dmId), (data) =>
    data ? { ...data, pages: data.pages.map((p) => p.filter((m) => m.id !== id)) } : data,
  );
}

export function useDeleteDM() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (msg: DirectMessage) => api<void>(`/dm-messages/${msg.id}`, { method: "DELETE" }),
    onSuccess: (_, msg) => removeDM(qc, msg.dm_id, msg.id),
  });
}

export const useDiscover = (q: string) =>
  useQuery({
    queryKey: socialKeys.discover(q),
    queryFn: () => api<DiscoverServer[]>(`/discover/servers${q ? `?q=${encodeURIComponent(q)}` : ""}`),
  });

export function useJoinPublic() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (serverId: string) => api<Server>(`/servers/${serverId}/join`, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["servers"] });
      qc.invalidateQueries({ queryKey: ["discover"] });
    },
  });
}

export const useCreateReport = () =>
  useMutation({
    mutationFn: (body: {
      reason: ReportReason;
      details?: string;
      message_id?: string;
      target_user_id?: string;
      server_id?: string;
    }) => api<{ id: string }>("/reports", { method: "POST", body }),
  });

export const useReports = (serverId: string, status: string, enabled: boolean) =>
  useQuery({
    queryKey: socialKeys.reports(serverId, status),
    queryFn: () => api<Report[]>(`/servers/${serverId}/reports${status ? `?status=${status}` : ""}`),
    enabled,
  });

export function useUpdateReport(serverId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: Report["status"] }) =>
      api<Report>(`/reports/${id}`, { method: "PATCH", body: { status } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["server", serverId, "reports"] }),
  });
}

export const useMyBots = () => useQuery({ queryKey: socialKeys.myBots, queryFn: () => api<Bot[]>("/bots") });

export function useBotActions() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: socialKeys.myBots });
  return {
    create: useMutation({
      mutationFn: (body: { name: string; description?: string; is_public: boolean }) =>
        api<Bot & { token: string }>("/bots", { method: "POST", body }),
      onSuccess: refresh,
    }),
    regenerate: useMutation({
      mutationFn: (id: string) => api<Bot & { token: string }>(`/bots/${id}/token`, { method: "POST" }),
    }),
    update: useMutation({
      mutationFn: ({ id, ...body }: { id: string; is_public?: boolean }) =>
        api<Bot>(`/bots/${id}`, { method: "PATCH", body }),
      onSuccess: refresh,
    }),
    remove: useMutation({ mutationFn: (id: string) => api<void>(`/bots/${id}`, { method: "DELETE" }), onSuccess: refresh }),
  };
}
