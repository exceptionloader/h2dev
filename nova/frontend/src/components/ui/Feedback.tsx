import { Loader2, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn("size-6 animate-spin text-muted", className)} aria-label="Lädt" />;
}

export function FullPageSpinner() {
  return (
    <div className="flex h-full items-center justify-center">
      <Spinner className="size-8" />
    </div>
  );
}

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ icon: Icon, title, children, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center rounded-2xl border border-dashed border-line px-6 py-10 text-center",
        className,
      )}
    >
      <div className="mb-4 rounded-2xl bg-card p-3">
        <Icon className="size-6 text-accent" aria-hidden />
      </div>
      <h3 className="font-semibold">{title}</h3>
      {children && <div className="mt-1 max-w-md text-sm text-muted">{children}</div>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

export function Badge({ children, color }: { children: ReactNode; color?: string | null }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-line bg-card px-2.5 py-0.5 text-xs font-medium">
      {color && <span className="size-2 rounded-full" style={{ backgroundColor: color }} />}
      {children}
    </span>
  );
}

export function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-2xl border border-line bg-surface p-5", className)}>
      {children}
    </section>
  );
}

export function PageHeader({
  title,
  subtitle,
  icon: Icon,
  actions,
}: {
  title: string;
  subtitle?: ReactNode;
  icon?: LucideIcon;
  actions?: ReactNode;
}) {
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line px-6">
      {Icon && <Icon className="size-5 text-muted" aria-hidden />}
      <h1 className="truncate font-semibold">{title}</h1>
      {subtitle && <div className="hidden truncate text-sm text-muted md:block">{subtitle}</div>}
      <div className="ml-auto flex items-center gap-2">{actions}</div>
    </header>
  );
}
