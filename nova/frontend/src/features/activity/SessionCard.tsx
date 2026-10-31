import { Clock, Gamepad2, Volume2 } from "lucide-react";
import { Link } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { useMe } from "@/features/auth/api";
import { cn } from "@/lib/cn";
import type { GamingSession } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useSessionAction } from "./api";

const timeFmt = new Intl.DateTimeFormat("de-DE", { weekday: "short", hour: "2-digit", minute: "2-digit" });

export function startLabel(iso: string): string {
  const diff = new Date(iso).getTime() - Date.now();
  if (diff < 60_000) return "jetzt";
  if (diff < 3_600_000) return `in ${Math.round(diff / 60_000)} Min.`;
  return timeFmt.format(new Date(iso));
}

export function SessionCard({ session: s, showServer }: { session: GamingSession; showServer?: boolean }) {
  const { data: me } = useMe();
  const action = useSessionAction();
  const joined = s.participants.some((p) => p.id === me?.id);
  const free = s.max_participants - s.participants.length;
  const run = (a: "join" | "leave") =>
    action.mutate({ id: s.id, action: a }, { onError: (e) => toast({ title: e.message, tone: "error" }) });

  return (
    <div className={cn("flex flex-col rounded-2xl border bg-surface p-4 transition-colors", joined ? "border-primary/50" : "border-line hover:border-line-strong")}>
      <div className="flex items-start gap-3">
        <div className="rounded-xl bg-primary/15 p-2.5">
          <Gamepad2 className="size-5 text-primary" aria-hidden />
        </div>
        <div className="min-w-0 flex-1">
          <Link to={`/sessions/${s.id}`} className="block truncate font-semibold hover:underline">{s.title}</Link>
          <div className="truncate text-sm text-muted">
            {s.game}
            {showServer && <> · {s.server_name}</>}
          </div>
        </div>
        <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold", s.status === "closed" ? "bg-card text-muted" : free > 0 ? "bg-success/15 text-success" : "bg-warning/15 text-warning")}>
          {s.status === "closed" ? "Beendet" : free > 0 ? `${free} frei` : "Voll"}
        </span>
      </div>
      {s.description && <p className="mt-2 line-clamp-2 text-sm text-muted">{s.description}</p>}
      <div className="mt-3 flex items-center gap-3 text-xs text-muted">
        <span className="flex items-center gap-1"><Clock className="size-3.5" /> {startLabel(s.start_time)}</span>
        {s.voice_channel_name && <span className="flex items-center gap-1"><Volume2 className="size-3.5" /> {s.voice_channel_name}</span>}
      </div>
      <div className="mt-3 flex items-center gap-2">
        <div className="flex -space-x-2" aria-label={`${s.participants.length} von ${s.max_participants} Plätzen belegt`}>
          {s.participants.slice(0, 6).map((p) => (
            <Avatar key={p.id} id={p.id} name={p.display_name} src={p.avatar_url} size="sm" className="rounded-full ring-2 ring-surface" />
          ))}
        </div>
        <span className="text-xs text-muted">{s.participants.length}/{s.max_participants}</span>
        <div className="ml-auto">
          {s.status === "open" &&
            (joined ? (
              <Button size="sm" variant="ghost" onClick={() => run("leave")} loading={action.isPending}>Verlassen</Button>
            ) : (
              <Button size="sm" onClick={() => run("join")} disabled={free <= 0} loading={action.isPending}>Beitreten</Button>
            ))}
        </div>
      </div>
    </div>
  );
}
