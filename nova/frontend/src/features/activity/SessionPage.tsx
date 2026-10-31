import { CalendarClock, CheckSquare, Gamepad2, Link2, PhoneCall, Square, StickyNote, Trash2, UserPlus, XCircle } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { Button } from "@/components/ui/Button";
import { Badge, EmptyState, FullPageSpinner, PageHeader, Panel } from "@/components/ui/Feedback";
import { FormError, SelectField, TextField } from "@/components/ui/Field";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useMe } from "@/features/auth/api";
import { useServer } from "@/features/servers/api";
import { useFriends } from "@/features/social/api";
import { useVoice } from "@/features/voice/voiceStore";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/format";
import { can } from "@/lib/permissions";
import type { NoteKind } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useInviteToSession, useNoteActions, useNotes, useSession, useSessionAction } from "./api";
import { startLabel } from "./SessionCard";

const KIND_ICON = { note: StickyNote, link: Link2, task: CheckSquare, followup: CalendarClock };
const KIND_LABEL: Record<NoteKind, string> = { note: "Notiz", link: "Link", task: "Aufgabe", followup: "Folgetermin" };

export function SessionPage() {
  const { sessionId = "" } = useParams();
  const { data: s, isPending, error } = useSession(sessionId);
  const { data: me } = useMe();
  const { data: server } = useServer(s?.server_id ?? "");
  const action = useSessionAction();
  const voice = useVoice();

  if (isPending) return <FullPageSpinner />;
  if (error || !s) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <EmptyState icon={Gamepad2} title="Session nicht gefunden">Sie existiert nicht oder du bist nicht auf dem Server.</EmptyState>
      </div>
    );
  }
  const joined = s.participants.some((p) => p.id === me?.id);
  const canClose = s.creator?.id === me?.id || can(server?.my_permissions, "MANAGE_EVENTS");
  const run = (a: "join" | "leave" | "close") =>
    action.mutate({ id: s.id, action: a }, { onError: (e) => toast({ title: e.message, tone: "error" }) });

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={Gamepad2} title={s.title} subtitle={`${s.game} · ${s.server_name}`} />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto grid max-w-5xl gap-6 lg:grid-cols-[1fr_320px]">
          <div className="space-y-6">
            <Panel>
              <div className="flex flex-wrap items-center gap-2">
                <Badge>{s.status === "open" ? (s.is_full ? "Voll" : `${s.max_participants - s.participants.length} Plätze frei`) : "Beendet"}</Badge>
                <Badge>Start: {startLabel(s.start_time)}</Badge>
                <Link to={`/server/${s.server_id}`}><Badge>{s.server_name}</Badge></Link>
              </div>
              {s.description && <p className="mt-3 text-muted">{s.description}</p>}
              <div className="mt-4 flex flex-wrap gap-2">
                {s.status === "open" && (joined ? (
                  <Button variant="secondary" onClick={() => run("leave")}>Verlassen</Button>
                ) : (
                  <Button onClick={() => run("join")} disabled={s.is_full}>Beitreten</Button>
                ))}
                {s.voice_channel_id && server && (
                  <Button
                    variant="secondary"
                    onClick={() => void voice.join({ channelId: s.voice_channel_id!, channelName: s.voice_channel_name ?? "Voice", serverId: s.server_id, serverName: s.server_name })}
                    disabled={voice.target?.channelId === s.voice_channel_id && voice.status === "connected"}
                  >
                    <PhoneCall className="size-4" /> {s.voice_channel_name} beitreten
                  </Button>
                )}
                {s.status === "open" && canClose && (
                  <Button variant="ghost" onClick={() => { if (window.confirm("Session beenden?")) run("close"); }}>
                    <XCircle className="size-4" /> Beenden
                  </Button>
                )}
              </div>
            </Panel>
            <NotesPanel sessionId={s.id} canWrite={joined || canClose} />
          </div>
          <div className="space-y-6">
            <Panel>
              <h3 className="mb-3 font-semibold">Dabei ({s.participants.length}/{s.max_participants})</h3>
              <ul className="space-y-2">
                {s.participants.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 text-sm">
                    <UserAvatar user={p} /> {p.display_name}
                    {p.id === s.creator?.id && <span className="text-xs text-muted">· Host</span>}
                  </li>
                ))}
              </ul>
            </Panel>
            {s.status === "open" && <InviteFriend sessionId={s.id} participantIds={s.participants.map((p) => p.id)} />}
          </div>
        </div>
      </div>
    </div>
  );
}

function InviteFriend({ sessionId, participantIds }: { sessionId: string; participantIds: string[] }) {
  const { data: friends } = useFriends();
  const invite = useInviteToSession(sessionId);
  const [userId, setUserId] = useState("");
  const options = (friends?.friends ?? []).filter((f) => !participantIds.includes(f.id));
  return (
    <Panel>
      <h3 className="mb-3 flex items-center gap-2 font-semibold"><UserPlus className="size-4 text-accent" /> Freund einladen</h3>
      {options.length === 0 ? (
        <p className="text-sm text-muted">Keine Freunde zum Einladen. <Link to="/freunde" className="text-accent hover:underline">Freunde finden</Link></p>
      ) : (
        <div className="space-y-2">
          <FormError message={invite.error?.message} />
          <SelectField label="Freund" value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">Auswählen …</option>
            {options.map((f) => <option key={f.id} value={f.id}>{f.display_name}</option>)}
          </SelectField>
          <Button
            size="sm"
            disabled={!userId}
            loading={invite.isPending}
            onClick={() => invite.mutate(userId, { onSuccess: () => { toast({ title: "Einladung per Direktnachricht gesendet", tone: "success" }); setUserId(""); } })}
          >
            Einladen
          </Button>
          <p className="text-xs text-muted">Ist die Person noch nicht auf dem Server, wird automatisch eine Einladung mitgeschickt.</p>
        </div>
      )}
    </Panel>
  );
}

function NotesPanel({ sessionId, canWrite }: { sessionId: string; canWrite: boolean }) {
  const { data: notes = [] } = useNotes(sessionId);
  const { data: me } = useMe();
  const actions = useNoteActions(sessionId);
  const [kind, setKind] = useState<NoteKind>("note");
  const [content, setContent] = useState("");
  const [url, setUrl] = useState("");
  const [due, setDue] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    actions.add.mutate(
      {
        kind,
        content: content.trim(),
        url: kind === "link" ? url.trim() : undefined,
        due_at: kind === "followup" && due ? new Date(due).toISOString() : undefined,
      },
      { onSuccess: () => { setContent(""); setUrl(""); setDue(""); } },
    );
  };

  return (
    <Panel>
      <h3 className="mb-1 font-semibold">Session-Notizen</h3>
      <p className="mb-4 text-xs text-muted">Notizen, Links, Aufgaben und Folgetermine – für alle auf dem Server sichtbar.</p>
      <ul className="mb-4 space-y-2">
        {notes.length === 0 && <li className="text-sm text-muted">Noch keine Notizen.</li>}
        {notes.map((n) => {
          const Icon = n.kind === "task" ? (n.done ? CheckSquare : Square) : KIND_ICON[n.kind];
          return (
            <li key={n.id} className="group flex items-start gap-3 rounded-lg border border-line bg-base/40 px-3 py-2">
              <button
                disabled={n.kind !== "task" || !canWrite}
                onClick={() => actions.toggle.mutate({ id: n.id, done: !n.done })}
                className={cn("mt-0.5", n.kind === "task" && canWrite ? "text-accent" : "text-muted")}
                aria-label={n.kind === "task" ? (n.done ? "Als offen markieren" : "Als erledigt markieren") : KIND_LABEL[n.kind]}
              >
                <Icon className="size-4" />
              </button>
              <div className="min-w-0 flex-1 text-sm">
                <div className={cn(n.done && "text-muted line-through")}>{n.content}</div>
                {n.url && <a href={n.url} target="_blank" rel="noopener noreferrer nofollow" className="block truncate text-xs text-accent hover:underline">{n.url}</a>}
                {n.due_at && <div className="text-xs text-warning">📅 {formatDateTime(n.due_at)}</div>}
                <div className="text-[11px] text-muted">{n.author?.display_name} · {formatDateTime(n.created_at)}</div>
              </div>
              {n.author?.id === me?.id && (
                <button onClick={() => actions.remove.mutate(n.id)} className="text-muted opacity-0 group-hover:opacity-100 hover:text-danger" aria-label="Notiz löschen">
                  <Trash2 className="size-4" />
                </button>
              )}
            </li>
          );
        })}
      </ul>
      {canWrite ? (
        <form onSubmit={submit} className="space-y-2">
          <FormError message={actions.add.error?.message} />
          <div className="grid grid-cols-[140px_1fr] gap-2">
            <SelectField label="Art" value={kind} onChange={(e) => setKind(e.target.value as NoteKind)}>
              {(Object.keys(KIND_LABEL) as NoteKind[]).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
            </SelectField>
            <TextField label="Inhalt" value={content} onChange={(e) => setContent(e.target.value)} maxLength={1000} placeholder={kind === "task" ? "z. B. Aim-Training" : "z. B. Map-Pool: Ascent, Bind"} />
          </div>
          {kind === "link" && <TextField label="URL" type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://" />}
          {kind === "followup" && <TextField label="Wann" type="datetime-local" value={due} onChange={(e) => setDue(e.target.value)} />}
          <Button type="submit" size="sm" loading={actions.add.isPending} disabled={!content.trim() || (kind === "link" && !url.trim())}>Hinzufügen</Button>
        </form>
      ) : (
        <p className="text-sm text-muted">Tritt der Session bei, um Notizen hinzuzufügen.</p>
      )}
    </Panel>
  );
}
