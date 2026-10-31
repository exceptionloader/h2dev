import { Crown } from "lucide-react";
import { useMemo } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Spinner } from "@/components/ui/Feedback";
import type { Member, Role } from "@/lib/types";
import { useMembers, useRoles } from "./api";

interface Group {
  key: string;
  label: string;
  color: string | null;
  members: Member[];
}

export function groupMembers(members: Member[], roles: Role[]): Group[] {
  const ranked = roles.filter((r) => !r.is_default).sort((a, b) => b.position - a.position);
  const groups: Group[] = [
    { key: "owner", label: "Owner", color: "#F59E0B", members: [] },
    ...ranked.map((r) => ({ key: r.id, label: r.name, color: r.color, members: [] as Member[] })),
    { key: "default", label: roles.find((r) => r.is_default)?.name ?? "Mitglieder", color: null, members: [] },
  ];
  const byKey = new Map(groups.map((g) => [g.key, g]));
  for (const m of members) {
    const top = m.is_owner ? "owner" : (ranked.find((r) => m.role_ids.includes(r.id))?.id ?? "default");
    byKey.get(top)!.members.push(m);
  }
  return groups.filter((g) => g.members.length > 0);
}

export function MemberSidebar({ serverId }: { serverId: string }) {
  const members = useMembers(serverId);
  const roles = useRoles(serverId);
  const groups = useMemo(
    () => (members.data && roles.data ? groupMembers(members.data, roles.data) : []),
    [members.data, roles.data],
  );

  return (
    <aside aria-label="Mitglieder" className="hidden w-64 shrink-0 overflow-y-auto border-l border-line bg-surface p-3 lg:block">
      {(members.isPending || roles.isPending) && <Spinner className="mx-auto mt-4 size-5" />}
      {groups.map((group) => (
        <section key={group.key} className="mb-4">
          <h3 className="mb-1 flex items-center gap-1.5 px-2 text-xs font-semibold tracking-wide text-muted uppercase">
            {group.color && <span className="size-2 rounded-full" style={{ backgroundColor: group.color }} />}
            {group.label} — {group.members.length}
          </h3>
          <ul>
            {group.members.map((m) => (
              <li key={m.id} className="flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-card">
                <Avatar id={m.user.id} name={m.user.display_name} src={m.user.avatar_url} size="sm" />
                <div className="min-w-0 leading-tight">
                  <div className="flex items-center gap-1 truncate text-sm font-medium" style={{ color: group.color ?? undefined }}>
                    {m.nickname || m.user.display_name}
                    {m.is_owner && <Crown className="size-3.5 shrink-0 text-warning" aria-label="Owner" />}
                  </div>
                  <div className="truncate text-xs text-muted">{m.user.custom_status || `@${m.user.username}`}</div>
                </div>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </aside>
  );
}
