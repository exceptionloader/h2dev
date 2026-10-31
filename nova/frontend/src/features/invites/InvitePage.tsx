import { Ticket, Users } from "lucide-react";
import { useNavigate, useParams } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { EmptyState, FullPageSpinner } from "@/components/ui/Feedback";
import { FormError } from "@/components/ui/Field";
import { useAcceptInvite, useInvitePreview } from "@/features/servers/api";

export function InvitePage() {
  const { code = "" } = useParams();
  const { data, isPending, error } = useInvitePreview(code);
  const accept = useAcceptInvite();
  const navigate = useNavigate();

  if (isPending) return <FullPageSpinner />;
  return (
    <div className="flex flex-1 items-center justify-center p-6">
      {error || !data ? (
        <EmptyState icon={Ticket} title="Einladung ungültig">Dieser Link ist abgelaufen, aufgebraucht oder existiert nicht.</EmptyState>
      ) : (
        <div className="w-full max-w-sm rounded-2xl border border-line bg-surface p-7 text-center shadow-2xl">
          <p className="text-sm text-muted">Du wurdest eingeladen zu</p>
          <Avatar id={data.server.id} name={data.server.name} src={data.server.icon_url} square size="lg" className="mx-auto my-4 w-fit" />
          <h1 className="text-xl font-bold">{data.server.name}</h1>
          {data.server.description && <p className="mt-1 text-sm text-muted">{data.server.description}</p>}
          <p className="mt-2 flex items-center justify-center gap-1 text-xs text-muted"><Users className="size-3" /> {data.member_count} Mitglieder</p>
          <div className="mt-6 space-y-3">
            <FormError message={accept.error?.message} />
            {data.already_member ? (
              <Button className="w-full" onClick={() => navigate(`/server/${data.server.id}`)}>Zum Server</Button>
            ) : (
              <Button className="w-full" loading={accept.isPending} onClick={() => accept.mutate(code, { onSuccess: (s) => navigate(`/server/${s.id}`, { replace: true }) })}>
                Einladung annehmen
              </Button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
