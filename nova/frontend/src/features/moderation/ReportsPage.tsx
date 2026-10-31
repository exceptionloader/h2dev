import { Check, Flag, X } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Spinner } from "@/components/ui/Feedback";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useServerContext } from "@/features/servers/useServerContext";
import { useReports, useUpdateReport } from "@/features/social/api";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/format";
import { can } from "@/lib/permissions";
import { REASON_LABEL } from "./ReportModal";

const STATUS = [["open", "Offen"], ["resolved", "Erledigt"], ["dismissed", "Abgelehnt"]] as const;

export function ReportsPage() {
  const server = useServerContext();
  const allowed = can(server.my_permissions, "MANAGE_MESSAGES") || can(server.my_permissions, "KICK_MEMBERS");
  const [status, setStatus] = useState<"open" | "resolved" | "dismissed">("open");
  const { data, isPending } = useReports(server.id, status, allowed);
  const update = useUpdateReport(server.id);

  return (
    <>
      <PageHeader icon={Flag} title="Meldungen" subtitle="Moderations-Warteschlange" />
      <div className="flex-1 overflow-y-auto p-6">
        {!allowed ? (
          <EmptyState icon={Flag} title="Kein Zugriff">Nur Moderatoren sehen Meldungen.</EmptyState>
        ) : (
          <div className="mx-auto max-w-3xl space-y-4">
            <div className="flex gap-1" role="tablist">
              {STATUS.map(([key, label]) => (
                <button key={key} role="tab" aria-selected={status === key} onClick={() => setStatus(key)} className={cn("rounded-lg px-3 py-1.5 text-sm", status === key ? "bg-card text-fg" : "text-muted hover:text-fg")}>
                  {label}
                </button>
              ))}
            </div>
            {isPending && <Spinner />}
            {data?.length === 0 && <EmptyState icon={Flag} title="Nichts zu tun">Keine Meldungen in dieser Ansicht.</EmptyState>}
            {data?.map((r) => (
              <div key={r.id} className="rounded-2xl border border-line bg-surface p-4">
                <div className="flex items-start gap-3">
                  {r.target && <UserAvatar user={r.target} size="md" showPresence={false} />}
                  <div className="min-w-0 flex-1">
                    <div className="text-sm">
                      <span className="font-semibold">{r.target?.display_name ?? "Unbekannt"}</span>{" "}
                      <span className="text-muted">gemeldet wegen</span>{" "}
                      <span className="font-semibold text-warning">{REASON_LABEL[r.reason]}</span>
                    </div>
                    <div className="text-xs text-muted">von {r.reporter?.display_name ?? "gelöschtem Nutzer"} · {formatDateTime(r.created_at)}</div>
                    {r.message_excerpt && <blockquote className="mt-2 rounded-lg border-l-2 border-line-strong bg-base/50 px-3 py-2 text-sm">{r.message_excerpt}</blockquote>}
                    {r.details && <p className="mt-2 text-sm text-muted">„{r.details}“</p>}
                  </div>
                </div>
                {r.status === "open" && (
                  <div className="mt-3 flex gap-2">
                    <Button size="sm" onClick={() => update.mutate({ id: r.id, status: "resolved" })}><Check className="size-4" /> Erledigt</Button>
                    <Button size="sm" variant="ghost" onClick={() => update.mutate({ id: r.id, status: "dismissed" })}><X className="size-4" /> Ablehnen</Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
