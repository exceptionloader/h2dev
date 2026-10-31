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
    case "channel.update":
      return "hat einen Kanal bearbeitet";
    case "channel.delete":
      return `hat den Kanal „${String(d.name)}“ gelöscht`;
    case "channel.override":
      return `hat Kanalrechte für „${String(d.role)}“ geändert`;
    case "member.join":
      return "ist dem Server beigetreten";
    case "member.leave":
      return "hat den Server verlassen";
    case "member.kick":
      return "hat ein Mitglied entfernt";
    case "member.ban":
      return `hat ein Mitglied gebannt${d.reason ? ` („${String(d.reason)}“)` : ""}`;
    case "member.unban":
      return "hat einen Bann aufgehoben";
    case "member.role_add":
      return `hat die Rolle „${String(d.role)}“ vergeben`;
    case "member.role_remove":
      return `hat die Rolle „${String(d.role)}“ entzogen`;
    case "role.create":
      return `hat die Rolle „${String(d.name)}“ erstellt`;
    case "role.update":
      return "hat eine Rolle bearbeitet";
    case "role.delete":
      return `hat die Rolle „${String(d.name)}“ gelöscht`;
    case "role.move":
      return `hat die Rolle „${String(d.name)}“ verschoben`;
    case "invite.create":
      return "hat eine Einladung erstellt";
    case "invite.delete":
      return "hat eine Einladung gelöscht";
    case "ownership.transfer":
      return "hat die Eigentümerschaft übertragen";
    case "message.delete":
      return `hat eine Nachricht in #${String(d.channel)} gelöscht`;
    case "message.pin":
      return `hat eine Nachricht in #${String(d.channel)} angeheftet`;
    case "message.unpin":
      return `hat eine Nachricht in #${String(d.channel)} gelöst`;
    case "space.create":
      return `hat den Gaming Space „${String(d.name)}“ erstellt`;
    case "space.update":
      return "hat einen Gaming Space bearbeitet";
    case "space.delete":
      return `hat den Gaming Space „${String(d.name)}“ gelöscht`;
    case "session.close":
      return `hat die Session „${String(d.title)}“ beendet`;
    case "bot.install":
      return `hat den Bot „${String(d.name)}“ installiert`;
    case "bot.uninstall":
      return `hat den Bot „${String(d.name)}“ entfernt`;
    case "report.resolved":
      return "hat eine Meldung erledigt";
    case "report.dismissed":
      return "hat eine Meldung abgelehnt";
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
