import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData, type QueryClient } from "@tanstack/react-query";
import { api, upload } from "@/lib/api";
import type { Attachment, Message } from "@/lib/types";

export const PAGE = 50;
export const messagesKey = (channelId: string) => ["channel", channelId, "messages"] as const;
export const pinsKey = (channelId: string) => ["channel", channelId, "pins"] as const;

type Pages = InfiniteData<Message[], string | undefined>;

export function useMessages(channelId: string) {
  return useInfiniteQuery({
    queryKey: messagesKey(channelId),
    queryFn: ({ pageParam }) =>
      api<Message[]>(`/channels/${channelId}/messages${pageParam ? `?before=${pageParam}` : ""}`),
    initialPageParam: undefined as string | undefined,
    // pages[0] = newest page; further pages are older.
    getNextPageParam: (lastPage) => (lastPage.length >= PAGE ? lastPage[0]?.id : undefined),
    staleTime: Infinity, // kept fresh by the gateway
  });
}

/** Insert or replace a message in the cached pages (used by mutations and the gateway). */
export function upsertMessage(qc: QueryClient, msg: Message) {
  qc.setQueryData<Pages>(messagesKey(msg.channel_id), (data) => {
    if (!data) return data;
    let found = false;
    const pages = data.pages.map((page) =>
      page.map((m) => {
        if (m.id !== msg.id) return m;
        found = true;
        return msg;
      }),
    );
    if (!found && pages.length > 0) pages[0] = [...pages[0]!, msg];
    return { ...data, pages };
  });
  if (msg.pinned !== undefined) qc.invalidateQueries({ queryKey: pinsKey(msg.channel_id) });
}

export function removeMessage(qc: QueryClient, channelId: string, messageId: string) {
  qc.setQueryData<Pages>(messagesKey(channelId), (data) =>
    data
      ? {
          ...data,
          pages: data.pages.map((page) =>
            page
              .filter((m) => m.id !== messageId)
              .map((m) => (m.reply_to?.id === messageId ? { ...m, reply_to: null } : m)),
          ),
        }
      : data,
  );
}

export const usePins = (channelId: string, enabled: boolean) =>
  useQuery({
    queryKey: pinsKey(channelId),
    queryFn: () => api<Message[]>(`/channels/${channelId}/pins`),
    enabled,
  });

export function useSendMessage(channelId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { content: string; reply_to_id?: string | null; attachment_ids?: string[] }) =>
      api<Message>(`/channels/${channelId}/messages`, { method: "POST", body }),
    onSuccess: (msg) => upsertMessage(qc, msg),
  });
}

export function useMessageActions() {
  const qc = useQueryClient();
  const onMsg = (msg: Message) => upsertMessage(qc, msg);
  return {
    edit: useMutation({
      mutationFn: ({ id, content }: { id: string; content: string }) =>
        api<Message>(`/messages/${id}`, { method: "PATCH", body: { content } }),
      onSuccess: onMsg,
    }),
    remove: useMutation({
      mutationFn: (msg: Message) => api<void>(`/messages/${msg.id}`, { method: "DELETE" }),
      onSuccess: (_, msg) => removeMessage(qc, msg.channel_id, msg.id),
    }),
    react: useMutation({
      mutationFn: ({ id, emoji, add }: { id: string; emoji: string; add: boolean }) =>
        api<Message>(`/messages/${id}/reactions/${encodeURIComponent(emoji)}`, { method: add ? "PUT" : "DELETE" }),
      onSuccess: onMsg,
    }),
    pin: useMutation({
      mutationFn: ({ id, pinned }: { id: string; pinned: boolean }) =>
        api<Message>(`/messages/${id}/pin`, { method: pinned ? "PUT" : "DELETE" }),
      onSuccess: onMsg,
    }),
  };
}

export const uploadAttachment = (channelId: string, file: File) =>
  upload<Attachment>(`/channels/${channelId}/attachments`, file);
