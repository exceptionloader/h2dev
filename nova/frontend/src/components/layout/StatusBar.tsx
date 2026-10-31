import { Headphones, WifiOff } from "lucide-react";
import { Link } from "react-router";
import { Avatar, STATUS_LABEL } from "@/components/ui/Avatar";
import { useMe } from "@/features/auth/api";

/**
 * Bottom bar: the current user and the voice connection.
 * Voice (LiveKit) arrives in milestone 3; until then it truthfully shows "not connected"
 * and offers no controls that would not work.
 */
export function StatusBar() {
  const { data: me } = useMe();
  if (!me) return null;
  return (
    <footer className="flex h-14 shrink-0 items-center gap-4 border-t border-line bg-surface px-4">
      <Link to="/einstellungen" className="flex min-w-0 items-center gap-3 rounded-lg px-2 py-1 hover:bg-card">
        <Avatar id={me.id} name={me.display_name} src={me.avatar_url} status={me.status} size="sm" />
        <div className="min-w-0 leading-tight">
          <div className="truncate text-sm font-semibold">{me.display_name}</div>
          <div className="truncate text-xs text-muted">{me.custom_status || STATUS_LABEL[me.status]}</div>
        </div>
      </Link>
      <div className="ml-auto flex items-center gap-3 text-sm text-muted" aria-live="polite">
        <Headphones className="size-4" aria-hidden />
        <span className="hidden sm:inline">Sprachkanal</span>
        <span className="inline-flex items-center gap-1.5 rounded-full border border-line px-2.5 py-0.5 text-xs">
          <WifiOff className="size-3.5" aria-hidden /> Nicht verbunden
        </span>
      </div>
    </footer>
  );
}
