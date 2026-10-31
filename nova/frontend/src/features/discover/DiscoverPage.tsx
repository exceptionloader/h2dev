import { Compass, Search, Users } from "lucide-react";
import { useDeferredValue, useState } from "react";
import { useNavigate } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, PageHeader, Spinner } from "@/components/ui/Feedback";
import { useDiscover, useJoinPublic } from "@/features/social/api";
import { toast } from "@/stores/toasts";

export function DiscoverPage() {
  const [q, setQ] = useState("");
  const query = useDeferredValue(q.trim());
  const { data, isPending } = useDiscover(query);
  const join = useJoinPublic();
  const navigate = useNavigate();

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={Compass} title="Entdecken" subtitle="Öffentliche Communities" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-4xl space-y-6">
          <label className="flex items-center gap-2 rounded-xl border border-line bg-surface px-3 focus-within:border-primary">
            <Search className="size-4 text-muted" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Server suchen …" maxLength={64} className="h-11 flex-1 bg-transparent focus:outline-none" aria-label="Server suchen" />
          </label>
          {isPending ? <Spinner /> : data && data.length > 0 ? (
            <div className="grid gap-4 sm:grid-cols-2">
              {data.map((s) => (
                <div key={s.id} className="flex flex-col rounded-2xl border border-line bg-surface p-4">
                  <div className="flex items-center gap-3">
                    <Avatar id={s.id} name={s.name} src={s.icon_url} square />
                    <div className="min-w-0">
                      <div className="truncate font-semibold">{s.name}</div>
                      <div className="flex items-center gap-1 text-xs text-muted"><Users className="size-3" /> {s.member_count} Mitglieder</div>
                    </div>
                  </div>
                  <p className="mt-2 line-clamp-2 flex-1 text-sm text-muted">{s.description || "Keine Beschreibung"}</p>
                  <div className="mt-3">
                    {s.is_member ? (
                      <Button size="sm" variant="secondary" onClick={() => navigate(`/server/${s.id}`)}>Öffnen</Button>
                    ) : (
                      <Button size="sm" loading={join.isPending && join.variables === s.id} onClick={() => join.mutate(s.id, { onSuccess: () => navigate(`/server/${s.id}`), onError: (e) => toast({ title: e.message, tone: "error" }) })}>
                        Beitreten
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState icon={Compass} title="Keine öffentlichen Server gefunden">Server werden hier gelistet, wenn ihre Sichtbarkeit auf „Öffentlich“ steht.</EmptyState>
          )}
        </div>
      </div>
    </div>
  );
}
