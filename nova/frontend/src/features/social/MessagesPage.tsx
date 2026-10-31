import { MessageSquare, Send, Trash2 } from "lucide-react";
import { useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { NavLink, useParams } from "react-router";
import { EmptyState, PageHeader, Spinner } from "@/components/ui/Feedback";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useMe } from "@/features/auth/api";
import { RichText } from "@/features/chat/RichText";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/stores/toasts";
import { useDMMessages, useDMs, useDeleteDM, useSendDM } from "./api";

export function MessagesPage() {
  const { dmId } = useParams();
  const { data: dms = [], isPending } = useDMs();
  const current = dms.find((d) => d.id === dmId);

  return (
    <div className="flex min-w-0 flex-1">
      <aside aria-label="Unterhaltungen" className="flex w-72 shrink-0 flex-col border-r border-line bg-surface">
        <div className="flex h-14 items-center border-b border-line px-4 font-semibold">Direktnachrichten</div>
        <div className="flex-1 overflow-y-auto p-2">
          {isPending && <Spinner className="mx-auto size-5" />}
          {!isPending && dms.length === 0 && <p className="p-2 text-sm text-muted">Noch keine Unterhaltungen. Schreib einem Freund über die Freundesliste.</p>}
          {dms.map((dm) => (
            <NavLink key={dm.id} to={`/nachrichten/${dm.id}`} className={({ isActive }) => cn("flex items-center gap-3 rounded-lg px-2 py-2 hover:bg-card", isActive && "bg-card")}>
              <UserAvatar user={dm.user} />
              <div className="min-w-0">
                <div className="truncate text-sm font-medium">{dm.user.display_name}</div>
                <div className="truncate text-xs text-muted">{dm.last_message?.content ?? "Noch keine Nachrichten"}</div>
              </div>
            </NavLink>
          ))}
        </div>
      </aside>
      {current ? (
        <Conversation key={current.id} dmId={current.id} name={current.user.display_name} />
      ) : (
        <div className="flex flex-1 flex-col">
          <PageHeader icon={MessageSquare} title="Nachrichten" />
          <div className="flex flex-1 items-center justify-center p-6">
            <EmptyState icon={MessageSquare} title="Wähle eine Unterhaltung">Direktnachrichten sind zwischen Freunden möglich.</EmptyState>
          </div>
        </div>
      )}
    </div>
  );
}

function Conversation({ dmId, name }: { dmId: string; name: string }) {
  const { data: me } = useMe();
  const query = useDMMessages(dmId);
  const send = useSendDM(dmId);
  const del = useDeleteDM();
  const [text, setText] = useState("");
  const scroller = useRef<HTMLDivElement>(null);
  const messages = useMemo(() => (query.data ? [...query.data.pages].reverse().flat() : []), [query.data]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 200) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (!text.trim()) return;
    send.mutate(text.trim(), {
      onSuccess: () => {
        setText("");
        requestAnimationFrame(() => scroller.current && (scroller.current.scrollTop = scroller.current.scrollHeight));
      },
      onError: (err) => toast({ title: err.message, tone: "error" }),
    });
  };

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={MessageSquare} title={name} />
      <div ref={scroller} className="flex-1 overflow-y-auto px-4 py-4" role="log" aria-label={`Unterhaltung mit ${name}`}>
        {query.hasNextPage && (
          <button onClick={() => void query.fetchNextPage()} className="mx-auto mb-3 block text-xs text-accent hover:underline">Ältere laden</button>
        )}
        {query.isPending && <Spinner className="mx-auto" />}
        {messages.map((m) => (
          <div key={m.id} className="group flex gap-3 rounded-lg px-2 py-1.5 hover:bg-card/50">
            <UserAvatar user={m.author} showPresence={false} />
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline gap-2">
                <span className="text-sm font-semibold">{m.author.display_name}</span>
                <time className="text-xs text-muted">{formatDateTime(m.created_at)}</time>
              </div>
              <p className="text-[15px] break-words whitespace-pre-wrap"><RichText text={m.content} /></p>
            </div>
            {m.author.id === me?.id && (
              <button onClick={() => del.mutate(m)} className="text-muted opacity-0 group-hover:opacity-100 hover:text-danger" aria-label="Nachricht löschen">
                <Trash2 className="size-4" />
              </button>
            )}
          </div>
        ))}
      </div>
      <form onSubmit={submit} className="flex items-end gap-2 border-t border-line p-4">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); } }}
          rows={1}
          maxLength={2000}
          placeholder={`Nachricht an ${name}`}
          aria-label="Nachricht schreiben"
          className="max-h-40 min-h-10 flex-1 resize-none rounded-xl border border-line bg-card px-3 py-2 focus:border-primary focus:outline-none"
        />
        <button type="submit" disabled={!text.trim() || send.isPending} className="rounded-xl bg-primary p-2.5 text-white hover:bg-primary-hover disabled:opacity-40" aria-label="Senden">
          <Send className="size-4" />
        </button>
      </form>
    </div>
  );
}
