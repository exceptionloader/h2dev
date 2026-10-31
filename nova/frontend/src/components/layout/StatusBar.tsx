import { Link } from "react-router";
import { Avatar, STATUS_LABEL } from "@/components/ui/Avatar";
import { useMe } from "@/features/auth/api";
import { VoiceBar } from "@/features/voice/VoiceBar";

/** Bottom bar: the current user and the voice connection. */
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
      <VoiceBar />
    </footer>
  );
}
