import { Loader2, Paperclip, Pin, Send, X } from "lucide-react";
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Spinner } from "@/components/ui/Feedback";
import { useMe } from "@/features/auth/api";
import { ReportModal, type ReportTarget } from "@/features/moderation/ReportModal";
import { ApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Attachment, Channel, Message } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { uploadAttachment, useMessageActions, useMessages, usePins, useSendMessage } from "./api";
import { MessageItem } from "./MessageItem";

const GROUP_WINDOW_MS = 5 * 60 * 1000;

export function Chat({ channel, showPins }: { channel: Channel; showPins: boolean }) {
  const { data: me } = useMe();
  const query = useMessages(channel.id);
  const actions = useMessageActions();
  const [replyTo, setReplyTo] = useState<Message | null>(null);
  const [report, setReport] = useState<ReportTarget | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const prevHeight = useRef(0);

  const perms = channel.my_permissions;
  const canSend = can(perms, "SEND_MESSAGES");
  const canReact = can(perms, "ADD_REACTIONS");
  const canManage = can(perms, "MANAGE_MESSAGES");

  const messages = useMemo(
    () => (query.data ? [...query.data.pages].reverse().flat() : []),
    [query.data],
  );

  // Keep the view pinned to the newest message unless the user scrolled up;
  // keep the position stable when older pages are prepended.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    if (stickToBottom.current) el.scrollTop = el.scrollHeight;
    else if (prevHeight.current && el.scrollHeight > prevHeight.current && el.scrollTop < 50) {
      el.scrollTop += el.scrollHeight - prevHeight.current;
    }
    prevHeight.current = el.scrollHeight;
  }, [messages]);

  useEffect(() => {
    stickToBottom.current = true;
    setReplyTo(null);
  }, [channel.id]);

  // Images and other late content change the height after render: stay at the bottom
  // if the reader was there.
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = scroller.current;
    const inner = content.current;
    if (!el || !inner || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      if (stickToBottom.current) el.scrollTop = el.scrollHeight;
      prevHeight.current = el.scrollHeight;
    });
    observer.observe(inner);
    return () => observer.disconnect();
  }, []);

  const onScroll = () => {
    const el = scroller.current!;
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (el.scrollTop < 120 && query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
  };

  const fail = (err: unknown) => toast({ title: err instanceof Error ? err.message : "Fehler", tone: "error" });

  return (
    <div className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col">
        <div ref={scroller} onScroll={onScroll} className="flex-1 overflow-y-auto pb-4" aria-label="Nachrichten" role="log">
          <div ref={content}>
          {query.isFetchingNextPage && <Spinner className="mx-auto my-3 size-5" />}
          {!query.hasNextPage && !query.isPending && (
            <div className="px-4 pt-8 pb-4">
              <h2 className="text-2xl font-bold">Willkommen in #{channel.name}</h2>
              <p className="text-muted">{channel.topic || "Das ist der Anfang dieses Kanals."}</p>
            </div>
          )}
          {query.isPending && <Spinner className="mx-auto mt-10" />}
          {messages.map((m, i) => {
            const prev = messages[i - 1];
            const grouped =
              !!prev && !m.reply_to && prev.author?.id === m.author?.id &&
              new Date(m.created_at).getTime() - new Date(prev.created_at).getTime() < GROUP_WINDOW_MS;
            return (
              <MessageItem
                key={m.id}
                message={m}
                grouped={grouped}
                meId={me?.id ?? ""}
                canReact={canReact}
                canManage={canManage}
                canSend={canSend}
                onReply={setReplyTo}
                onEdit={(msg, content) => actions.edit.mutate({ id: msg.id, content }, { onError: fail })}
                onDelete={(msg) => {
                  if (window.confirm("Nachricht wirklich löschen?")) actions.remove.mutate(msg, { onError: fail });
                }}
                onReact={(msg, emoji, add) => actions.react.mutate({ id: msg.id, emoji, add }, { onError: fail })}
                onPin={(msg, pinned) => actions.pin.mutate({ id: msg.id, pinned }, { onError: fail })}
                onReport={(msg) =>
                  setReport({ label: `Nachricht von ${msg.author?.display_name}`, message_id: msg.id })
                }
              />
            );
          })}
          </div>
        </div>
        <Composer channel={channel} canSend={canSend} replyTo={replyTo} onClearReply={() => setReplyTo(null)} onSent={() => (stickToBottom.current = true)} />
      </div>
      {showPins && <PinnedPanel channelId={channel.id} />}
      <ReportModal target={report} onClose={() => setReport(null)} />
    </div>
  );
}

function PinnedPanel({ channelId }: { channelId: string }) {
  const { data, isPending } = usePins(channelId, true);
  return (
    <aside aria-label="Angeheftete Nachrichten" className="w-72 shrink-0 overflow-y-auto border-l border-line bg-surface p-3">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-semibold">
        <Pin className="size-4 text-warning" /> Angeheftet
      </h3>
      {isPending && <Spinner className="size-5" />}
      {data?.length === 0 && <p className="text-sm text-muted">Noch nichts angeheftet.</p>}
      <ul className="space-y-2">
        {data?.map((m) => (
          <li key={m.id}>
            <button
              onClick={() => document.getElementById(`msg-${m.id}`)?.scrollIntoView({ behavior: "smooth", block: "center" })}
              className="w-full rounded-lg border border-line bg-card p-2 text-left text-sm hover:border-line-strong"
            >
              <div className="text-xs font-semibold">{m.author?.display_name}</div>
              <div className="line-clamp-3 text-muted">{m.content || "Anhang"}</div>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}

function Composer({
  channel,
  canSend,
  replyTo,
  onClearReply,
  onSent,
}: {
  channel: Channel;
  canSend: boolean;
  replyTo: Message | null;
  onClearReply: () => void;
  onSent: () => void;
}) {
  const [text, setText] = useState("");
  const [files, setFiles] = useState<Attachment[]>([]);
  const [uploading, setUploading] = useState(false);
  const send = useSendMessage(channel.id);
  const fileInput = useRef<HTMLInputElement>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const canAttach = can(channel.my_permissions, "ATTACH_FILES");

  useEffect(() => {
    if (replyTo) area.current?.focus();
  }, [replyTo]);

  if (!canSend) {
    return (
      <div className="m-4 rounded-xl border border-line bg-card px-4 py-3 text-sm text-muted">
        Du hast in diesem Kanal keine Berechtigung zu schreiben.
      </div>
    );
  }

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    const content = text.trim();
    if ((!content && files.length === 0) || send.isPending) return;
    send.mutate(
      { content, reply_to_id: replyTo?.id ?? null, attachment_ids: files.map((f) => f.id) },
      {
        onSuccess: () => {
          setText("");
          setFiles([]);
          onClearReply();
          onSent();
        },
        onError: (err) => toast({ title: err.message, tone: "error" }),
      },
    );
  };

  const onFiles = async (list: FileList | null) => {
    if (!list) return;
    setUploading(true);
    try {
      for (const file of Array.from(list).slice(0, 5 - files.length)) {
        const att = await uploadAttachment(channel.id, file);
        setFiles((f) => [...f, att]);
      }
    } catch (err) {
      toast({ title: err instanceof ApiError ? err.message : "Upload fehlgeschlagen", tone: "error" });
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  return (
    <form onSubmit={submit} className="px-4 pb-4">
      {replyTo && (
        <div className="flex items-center gap-2 rounded-t-xl border border-b-0 border-line bg-card/60 px-3 py-1.5 text-xs text-muted">
          Antwort an <span className="font-semibold text-fg">{replyTo.author?.display_name}</span>
          <span className="truncate">{replyTo.content}</span>
          <button type="button" onClick={onClearReply} className="ml-auto hover:text-fg" aria-label="Antwort abbrechen">
            <X className="size-3.5" />
          </button>
        </div>
      )}
      {files.length > 0 && (
        <div className="flex flex-wrap gap-2 border border-b-0 border-line bg-card/60 px-3 py-2">
          {files.map((f) => (
            <span key={f.id} className="flex items-center gap-1 rounded-md bg-base px-2 py-1 text-xs">
              {f.filename}
              <button type="button" onClick={() => setFiles((x) => x.filter((y) => y.id !== f.id))} aria-label={`${f.filename} entfernen`}>
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className={cn("flex items-end gap-2 border border-line bg-card px-2 py-2", replyTo || files.length ? "rounded-b-xl" : "rounded-xl")}>
        {canAttach && (
          <>
            <input ref={fileInput} type="file" multiple hidden onChange={(e) => void onFiles(e.target.files)} />
            <button
              type="button"
              onClick={() => fileInput.current?.click()}
              disabled={uploading || files.length >= 5}
              className="rounded-lg p-2 text-muted hover:bg-base hover:text-fg disabled:opacity-40"
              aria-label="Datei anhängen"
              title="Datei anhängen (max. 10 MB)"
            >
              {uploading ? <Loader2 className="size-5 animate-spin" /> : <Paperclip className="size-5" />}
            </button>
          </>
        )}
        <textarea
          ref={area}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
            if (e.key === "Escape") onClearReply();
          }}
          rows={1}
          maxLength={2000}
          placeholder={`Nachricht an #${channel.name}`}
          aria-label="Nachricht schreiben"
          className="max-h-40 min-h-9 flex-1 resize-none bg-transparent px-1 py-1.5 text-[15px] placeholder:text-muted/60 focus:outline-none"
        />
        <button
          type="submit"
          disabled={send.isPending || (!text.trim() && files.length === 0)}
          className="rounded-lg bg-primary p-2 text-white hover:bg-primary-hover disabled:opacity-40"
          aria-label="Senden"
        >
          <Send className="size-4" />
        </button>
      </div>
    </form>
  );
}
