import { ServerOff } from "lucide-react";
import { Link, Outlet, useParams } from "react-router";
import { buttonStyles } from "@/components/ui/Button";
import { EmptyState, FullPageSpinner } from "@/components/ui/Feedback";
import { useUi } from "@/stores/ui";
import { useServer } from "./api";
import { MemberSidebar } from "./MemberSidebar";
import { ServerSidebar } from "./ServerSidebar";

export function ServerLayout() {
  const { serverId = "" } = useParams();
  const { data: server, isPending, error } = useServer(serverId);
  const memberListOpen = useUi((s) => s.memberListOpen);

  if (isPending) return <FullPageSpinner />;
  if (error || !server) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <EmptyState
          icon={ServerOff}
          title="Server nicht gefunden"
          action={
            <Link to="/" className={buttonStyles("secondary")}>
              Zur Startseite
            </Link>
          }
        >
          Der Server existiert nicht oder du bist kein Mitglied.
        </EmptyState>
      </div>
    );
  }

  return (
    <>
      <ServerSidebar server={server} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Outlet context={server} />
      </div>
      {memberListOpen && <MemberSidebar serverId={server.id} />}
    </>
  );
}
