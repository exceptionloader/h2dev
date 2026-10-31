import type { UserPublic } from "@/lib/types";
import { usePresence } from "@/stores/realtime";
import { Avatar } from "./Avatar";

/** Avatar with live presence dot. */
export function UserAvatar({
  user,
  size = "sm",
  showPresence = true,
  className,
}: {
  user: UserPublic;
  size?: "sm" | "md" | "lg" | "nav";
  showPresence?: boolean;
  className?: string;
}) {
  const presence = usePresence(user);
  return (
    <Avatar
      id={user.id}
      name={user.display_name}
      src={user.avatar_url}
      size={size}
      status={showPresence && !user.is_bot ? presence : undefined}
      className={className}
    />
  );
}
