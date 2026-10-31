import { cn } from "@/lib/cn";
import { colorFor, initials } from "@/lib/format";
import type { Presence, UserStatus } from "@/lib/types";

const STATUS_COLOR: Record<UserStatus | Presence, string> = {
  online: "bg-success",
  idle: "bg-warning",
  dnd: "bg-danger",
  invisible: "bg-muted",
  offline: "bg-slate-600",
};

export const PRESENCE_LABEL: Record<Presence, string> = {
  online: "Online",
  idle: "Abwesend",
  dnd: "Nicht stören",
  offline: "Offline",
};

export const STATUS_LABEL: Record<UserStatus, string> = {
  online: "Online",
  idle: "Abwesend",
  dnd: "Nicht stören",
  invisible: "Unsichtbar",
};

const SIZES = { sm: "size-8 text-xs", md: "size-10 text-sm", nav: "size-11 text-sm", lg: "size-16 text-xl" };

interface Props {
  id: string;
  name: string;
  src?: string | null;
  size?: keyof typeof SIZES;
  status?: UserStatus | Presence;
  square?: boolean;
  className?: string;
}


export function Avatar({ id, name, src, size = "md", status, square, className }: Props) {
  return (
    <div className={cn("relative shrink-0", className)}>
      {src ? (
        <img
          src={src}
          alt=""
          loading="lazy"
          className={cn(SIZES[size], square ? "rounded-xl" : "rounded-full", "object-cover")}
        />
      ) : (
        <div
          aria-hidden
          className={cn(
            SIZES[size],
            square ? "rounded-xl" : "rounded-full",
            "flex items-center justify-center font-semibold text-white",
          )}
          style={{ backgroundColor: colorFor(id) }}
        >
          {initials(name)}
        </div>
      )}
      {status && (
        <span
          title={status === "offline" ? "Offline" : STATUS_LABEL[status]}
          className={cn(
            "absolute -right-0.5 -bottom-0.5 size-3.5 rounded-full border-[3px] border-surface",
            STATUS_COLOR[status],
          )}
        />
      )}
    </div>
  );
}
