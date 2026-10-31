import { Navigate, Outlet, useLocation } from "react-router";
import { WifiOff } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { EmptyState, FullPageSpinner } from "@/components/ui/Feedback";
import { useMe } from "./api";

export function RequireAuth() {
  const { data: me, isPending, error, refetch } = useMe();
  const location = useLocation();
  if (isPending) return <FullPageSpinner />;
  if (error) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <EmptyState
          icon={WifiOff}
          title="NOVA ist gerade nicht erreichbar"
          action={<Button onClick={() => refetch()}>Erneut versuchen</Button>}
        >
          {error.message}
        </EmptyState>
      </div>
    );
  }
  if (!me) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/anmelden?weiter=${next}`} replace />;
  }
  return <Outlet />;
}

export function GuestOnly() {
  const { data: me, isPending } = useMe();
  if (isPending) return <FullPageSpinner />;
  if (me) return <Navigate to="/" replace />;
  return <Outlet />;
}
