import { Gamepad2, Plus, Zap } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Spinner } from "@/components/ui/Feedback";
import { useServers } from "@/features/servers/api";
import { useServerContext } from "@/features/servers/useServerContext";
import { useMySessions, useServerSessions } from "./api";
import { CreateSessionModal } from "./CreateSessionModal";
import { SessionCard } from "./SessionCard";

export function ActivitiesPage() {
  const { data: sessions, isPending } = useMySessions();
  const { data: servers = [] } = useServers();
  const [open, setOpen] = useState(false);
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader
        icon={Zap}
        title="Aktivitäten"
        subtitle="Offene Sessions auf all deinen Servern"
        actions={servers.length > 0 && <Button size="sm" onClick={() => setOpen(true)}><Plus className="size-4" /> Session starten</Button>}
      />
      <div className="flex-1 overflow-y-auto p-6">
        {isPending ? <Spinner /> : sessions && sessions.length > 0 ? (
          <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
            {sessions.map((s) => <SessionCard key={s.id} session={s} showServer />)}
          </div>
        ) : (
          <EmptyState icon={Gamepad2} title="Gerade läuft nichts" action={servers.length > 0 && <Button onClick={() => setOpen(true)}><Plus className="size-4" /> Erste Session starten</Button>}>
            {servers.length > 0 ? "Starte eine Session – alle auf dem Server bekommen sofort Bescheid." : "Tritt zuerst einem Server bei oder erstelle einen."}
          </EmptyState>
        )}
      </div>
      <CreateSessionModal open={open} onClose={() => setOpen(false)} />
    </div>
  );
}

export function ServerSessionsPage() {
  const server = useServerContext();
  const { data: sessions, isPending } = useServerSessions(server.id);
  const [open, setOpen] = useState(false);
  return (
    <>
      <PageHeader icon={Gamepad2} title="Sessions" actions={<Button size="sm" onClick={() => setOpen(true)}><Plus className="size-4" /> Session starten</Button>} />
      <div className="flex-1 overflow-y-auto p-6">
        {isPending ? <Spinner /> : sessions && sessions.length > 0 ? (
          <div className="grid gap-4 xl:grid-cols-2">{sessions.map((s) => <SessionCard key={s.id} session={s} />)}</div>
        ) : (
          <EmptyState icon={Gamepad2} title="Keine offenen Sessions" action={<Button onClick={() => setOpen(true)}>Session starten</Button>}>
            z. B. „Valorant Ranked – 2 Spieler gesucht“
          </EmptyState>
        )}
      </div>
      <CreateSessionModal open={open} onClose={() => setOpen(false)} serverId={server.id} />
    </>
  );
}
