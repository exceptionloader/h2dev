import { CornerUpLeft, FileDown, Flag, Pencil, Pin, PinOff, SmilePlus, Trash2 } from "lucide-react";
import { memo, useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/format";
import type { Message } from "@/lib/types";
import { RichText } from "./RichText";

export const QUICK_EMOJIS = ["👍", "🔥", "😂", "🎮", "❤️", "GG"];

function formatSize(bytes: number) {
  return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

const time = new Intl.DateTimeFormat("de-DE", { hour: "2-digit", minute: "2-digit" });

interface Props {
  message: Message;
  grouped: boolean;
  meId: string;
  canReact: boolean;
  canManage: boolean;
  canSend: boolean;
  onReply: (m: Message) => void;
  onEdit: (m: Message, content: string) => void;
  onDelete: (m: Message) => void;
  onReact: (m: Message, emoji: string, add: boolean) => void;
  onPin: (m: Message, pinned: boolean) => void;
  onReport: (m: Message) => void;
}

function MessageItemInner({ message: m, grouped, meId, canReact, canManage, canSend, ...on }: Props) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(m.content);
  const [picker, setPicker] = useState(false);
  const mine = m.author?.id === meId;
  const author = m.author;

  const saveEdit = () => {
    const content = draft.trim();
    if (content && content !== m.content) on.onEdit(m, content);
    setEditing(false);
  };

  return (
    <article
      id={`msg-${m.id}`}
      className={cn("group relative flex gap-3 rounded-lg px-4 hover:bg-card/50", grouped ? "py-0.5" : "mt-3 pt-1.5 pb-0.5")}
    >
      <div className="w-10 shrink-0">
        {!grouped && author && <Avatar id={author.id} name={author.display_name} src={author.avatar_url} size="md" />}
        {grouped && (
          <time className="invisible block pt-1 text-right text-[10px] text-muted group-hover:visible" dateTime={m.created_at}>
            {time.format(new Date(m.created_at))}
          </time>
        )}
      </div>
      <div className="min-w-0 flex-1">
        {m.reply_to && (
          <button
            onClick={() => document.getElementById(`msg-${m.reply_to!.id}`)?.scrollIntoView({ behavior: "smooth", block: "center" })}
            className="mb-0.5 flex max-w-full items-center gap-1.5 truncate text-xs text-muted hover:text-fg"
          >
            <CornerUpLeft className="size-3 shrink-0" />
            <span className="font-semibold">{m.reply_to.author?.display_name ?? "Unbekannt"}</span>
            <span className="truncate">{m.reply_to.content || "Anhang"}</span>
          </button>
        )}
        {!grouped && (
          <div className="flex items-baseline gap-2">
            <span className="font-semibold">{author?.display_name ?? "Gelöschter Nutzer"}</span>
            {author?.is_bot && <span className="rounded bg-primary/20 px-1.5 text-[10px] font-bold text-primary">BOT</span>}
            <time className="text-xs text-muted" dateTime={m.created_at}>
              {formatDateTime(m.created_at)}
            </time>
            {m.pinned && <Pin className="size-3 text-warning" aria-label="Angeheftet" />}
          </div>
        )}
        {editing ? (
          <div className="mt-1">
            <textarea
              value={draft}
              autoFocus
              maxLength={2000}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  saveEdit();
                }
                if (e.key === "Escape") setEditing(false);
              }}
              className="w-full resize-none rounded-lg border border-line bg-base p-2 text-sm focus:border-primary focus:outline-none"
              rows={2}
            />
            <p className="text-xs text-muted">Enter zum Speichern · Esc zum Abbrechen</p>
          </div>
        ) : (
          m.content && (
            <p className="text-[15px] leading-relaxed break-words whitespace-pre-wrap text-fg/90">
              <RichText text={m.content} />
              {m.edited_at && <span className="ml-1 text-[10px] text-muted">(bearbeitet)</span>}
            </p>
          )
        )}
        {m.attachments.length > 0 && (
          <div className="mt-1.5 flex flex-wrap gap-2">
            {m.attachments.map((a) =>
              a.is_image ? (
                <a key={a.id} href={a.url} target="_blank" rel="noopener noreferrer">
                  <img src={a.url} alt={a.filename} loading="lazy" className="max-h-72 max-w-sm rounded-lg border border-line object-contain" />
                </a>
              ) : (
                <a
                  key={a.id}
                  href={a.url}
                  download={a.filename}
                  className="flex items-center gap-2 rounded-lg border border-line bg-card px-3 py-2 text-sm hover:border-line-strong"
                >
                  <FileDown className="size-4 text-accent" />
                  <span className="max-w-60 truncate">{a.filename}</span>
                  <span className="text-xs text-muted">{formatSize(a.size)}</span>
                </a>
              ),
            )}
          </div>
        )}
        {m.reactions.length > 0 && (
          <div className="mt-1 flex flex-wrap gap-1">
            {m.reactions.map((r) => {
              const mineR = r.user_ids.includes(meId);
              return (
                <button
                  key={r.emoji}
                  disabled={!canReact}
                  onClick={() => on.onReact(m, r.emoji, !mineR)}
                  className={cn(
                    "flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs",
                    mineR ? "border-primary/60 bg-primary/15" : "border-line bg-card hover:border-line-strong",
                  )}
                  aria-pressed={mineR}
                >
                  <span>{r.emoji}</span>
                  <span className="text-muted">{r.user_ids.length}</span>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {!editing && (
        <div className="absolute -top-3 right-4 hidden items-center rounded-lg border border-line bg-surface shadow-lg group-hover:flex group-focus-within:flex">
          {canReact && (
            <div className="relative">
              <button className="p-1.5 text-muted hover:text-fg" onClick={() => setPicker((v) => !v)} aria-label="Reaktion hinzufügen" title="Reagieren">
                <SmilePlus className="size-4" />
              </button>
              {picker && (
                <div className="absolute top-8 right-0 z-10 flex gap-1 rounded-lg border border-line bg-surface p-1 shadow-xl">
                  {QUICK_EMOJIS.map((e) => (
                    <button
                      key={e}
                      className="rounded px-1.5 py-1 text-sm hover:bg-card"
                      onClick={() => {
                        on.onReact(m, e, true);
                        setPicker(false);
                      }}
                    >
                      {e}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
          {canSend && (
            <button className="p-1.5 text-muted hover:text-fg" onClick={() => on.onReply(m)} aria-label="Antworten" title="Antworten">
              <CornerUpLeft className="size-4" />
            </button>
          )}
          {mine && canSend && (
            <button className="p-1.5 text-muted hover:text-fg" onClick={() => { setDraft(m.content); setEditing(true); }} aria-label="Bearbeiten" title="Bearbeiten">
              <Pencil className="size-4" />
            </button>
          )}
          {canManage && (
            <button className="p-1.5 text-muted hover:text-fg" onClick={() => on.onPin(m, !m.pinned)} aria-label={m.pinned ? "Lösen" : "Anheften"} title={m.pinned ? "Lösen" : "Anheften"}>
              {m.pinned ? <PinOff className="size-4" /> : <Pin className="size-4" />}
            </button>
          )}
          {!mine && author && (
            <button className="p-1.5 text-muted hover:text-warning" onClick={() => on.onReport(m)} aria-label="Melden" title="Melden">
              <Flag className="size-4" />
            </button>
          )}
          {(mine || canManage) && (
            <button className="p-1.5 text-muted hover:text-danger" onClick={() => on.onDelete(m)} aria-label="Löschen" title="Löschen">
              <Trash2 className="size-4" />
            </button>
          )}
        </div>
      )}
    </article>
  );
}

export const MessageItem = memo(MessageItemInner);
