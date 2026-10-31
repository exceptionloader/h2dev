import { Compass, House, MessageSquare, Plus, Settings, Users, Zap, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Link, NavLink } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { useMe } from "@/features/auth/api";
import { useServers } from "@/features/servers/api";
import { cn } from "@/lib/cn";
import { useUi } from "@/stores/ui";
import { NovaLogo } from "./NovaLogo";

function Tooltip({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="group relative flex justify-center">
      {children}
      <span
        role="tooltip"
        className="pointer-events-none absolute top-1/2 left-full z-40 ml-3 -translate-y-1/2 rounded-md border border-line bg-card px-2.5 py-1 text-xs font-medium whitespace-nowrap opacity-0 shadow-lg transition-opacity group-hover:opacity-100"
      >
        {label}
      </span>
    </div>
  );
}

const railItem =
  "relative flex size-11 items-center justify-center rounded-2xl text-muted transition-all hover:rounded-xl hover:bg-card hover:text-fg";
const railActive = "rounded-xl bg-primary/15 text-primary hover:bg-primary/20 hover:text-primary";

function RailLink({ to, label, icon: Icon, end }: { to: string; label: string; icon: LucideIcon; end?: boolean }) {
  return (
    <Tooltip label={label}>
      <NavLink to={to} end={end} aria-label={label} className={({ isActive }) => cn(railItem, isActive && railActive)}>
        <Icon className="size-5" />
      </NavLink>
    </Tooltip>
  );
}

const PRIMARY_NAV = [
  { to: "/", label: "Home", icon: House, end: true },
  { to: "/entdecken", label: "Entdecken", icon: Compass },
  { to: "/aktivitaeten", label: "Aktivitäten", icon: Zap },
  { to: "/freunde", label: "Freunde", icon: Users },
  { to: "/nachrichten", label: "Nachrichten", icon: MessageSquare },
];

export function MainNav() {
  const { data: me } = useMe();
  const { data: servers } = useServers();
  const openCreate = useUi((s) => s.setCreateServerOpen);

  return (
    <nav aria-label="Hauptnavigation" className="flex w-[72px] shrink-0 flex-col items-center gap-2 border-r border-line bg-base py-3">
      <Link to="/" aria-label="NOVA Home" className="mb-1">
        <NovaLogo className="size-11" />
      </Link>
      {PRIMARY_NAV.map((item) => (
        <RailLink key={item.to} {...item} />
      ))}

      <div className="my-1 h-px w-8 bg-line" />

      <div className="flex min-h-0 w-full flex-1 flex-col items-center gap-2 overflow-y-auto" aria-label="Deine Server">
        {servers?.map((server) => (
          <Tooltip key={server.id} label={server.name}>
            <NavLink
              to={`/server/${server.id}`}
              aria-label={server.name}
              className={({ isActive }) =>
                cn("relative rounded-2xl transition-all hover:rounded-xl", isActive && "rounded-xl ring-2 ring-primary")
              }
            >
              <Avatar id={server.id} name={server.name} src={server.icon_url} square size="nav" />
            </NavLink>
          </Tooltip>
        ))}
        <Tooltip label="Server erstellen">
          <button
            onClick={() => openCreate(true)}
            aria-label="Server erstellen"
            className={cn(railItem, "border border-dashed border-line-strong text-success hover:border-success hover:bg-success/10 hover:text-success")}
          >
            <Plus className="size-5" />
          </button>
        </Tooltip>
      </div>

      <RailLink to="/einstellungen" label="Einstellungen" icon={Settings} />
      {me && (
        <Tooltip label={me.display_name}>
          <Link to="/einstellungen" aria-label="Profil">
            <Avatar id={me.id} name={me.display_name} src={me.avatar_url} status={me.status} />
          </Link>
        </Tooltip>
      )}
    </nav>
  );
}
