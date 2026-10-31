import { ScrollText } from "lucide-react";
import { Avatar } from "@/components/ui/Avatar";
import { EmptyState, FullPageSpinner, PageHeader } from "@/components/ui/Feedback";
import { formatDateTime } from "@/lib/format";
import { can } from "@/lib/permissions";
import type { AuditLogEntry } from "@/lib/types";
import { useAuditLog } from "./api";
import { CHANNEL_TYPE_LABEL } from "./CreateChannelModal";
import { useServerContext } from "./useServerContext";

const FIELD_LABEL: Record<string, string> = {
  name: "Name",
  description: "Beschreibung",
  visibility: "Sichtbarkeit",
};

export function describeAudit(entry: AuditLogEntry): string {
  const d = entry.details;
  switch (entry.action) {
    case "server.create":
      return `hat den Server „${String(d.name)}“ erstellt`;
    case "server.update":
      return `hat ${Object.keys(d).map((k) => FIELD_LABEL[k] ?? k).join(", ")} geändert`;
    case "channel.create": {
      const type = CHANNEL_TYPE_LABEL[d.type as keyof typeof CHANNEL_TYPE_LABEL] ?? "Kanal";
      return `hat ${d.private ? "privaten " : ""}${type} „${String(d.name)}“ erstellt`;
    }
    default:
      return entry.action;
  }
}

export function AuditLogPage() {
  const server = useServerContext();
  const allowed = can(server.my_permissions, "VIEW_AUDIT_LOG");
  const { data, isPending } = useAuditLog(server.id, allowed);

  return (
    <>
      <PageHeader icon={ScrollText} title="Audit-Log" subtitle="Die letzten 50 Verwaltungsaktionen" />
      <div className="flex-1 overflow-y-auto p-6">
        {!allowed ? (
          <EmptyState icon={ScrollText} title="Kein Zugriff">
            Du brauchst die Berechtigung „Audit-Log ansehen“.
          </EmptyState>
        ) : isPending ? (
          <FullPageSpinner />
        ) : (
          <ol className="mx-auto max-w-2xl space-y-2">
            {data?.map((entry) => (
              <li key={entry.id} className="flex items-center gap-3 rounded-xl border border-line bg-surface p-3">
                {entry.actor ? (
                  <Avatar id={entry.actor.id} name={entry.actor.display_name} size="sm" />
                ) : (
                  <div className="size-8 rounded-full bg-card" />
                )}
                <div className="min-w-0 flex-1 text-sm">
                  <span className="font-semibold">{entry.actor?.display_name ?? "Gelöschter Nutzer"}</span>{" "}
                  <span className="text-muted">{describeAudit(entry)}</span>
                </div>
                <time className="shrink-0 text-xs text-muted" dateTime={entry.created_at}>
                  {formatDateTime(entry.created_at)}
                </time>
              </li>
            ))}
          </ol>
        )}
      </div>
    </>
  );
}
