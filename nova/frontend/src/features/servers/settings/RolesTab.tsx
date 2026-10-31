import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Badge, Panel } from "@/components/ui/Feedback";
import { FormError, TextField } from "@/components/ui/Field";
import { cn } from "@/lib/cn";
import { PERMISSION_LABELS, can, type PermissionName } from "@/lib/permissions";
import type { Role, ServerDetail } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useRoleActions, useRoles } from "../api";

const ALL_PERMS = Object.keys(PERMISSION_LABELS) as PermissionName[];

export function RolesTab({ server }: { server: ServerDetail }) {
  const { data: roles = [] } = useRoles(server.id);
  const actions = useRoleActions(server.id);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const editable = can(server.my_permissions, "MANAGE_ROLES");
  const selected = roles.find((r) => r.id === selectedId) ?? null;
  const fail = (e: Error) => toast({ title: e.message, tone: "error" });

  return (
    <div className="grid gap-6 md:grid-cols-[240px_1fr]">
      <Panel className="p-3">
        <ul className="space-y-1">
          <li className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted">
            <span className="size-2 rounded-full bg-warning" /> Owner <span className="ml-auto text-xs">immer oben</span>
          </li>
          {roles.map((r, i) => (
            <li key={r.id} className={cn("group flex items-center gap-1 rounded-lg px-2 py-1.5 text-sm", selectedId === r.id ? "bg-card" : "hover:bg-card/60")}>
              <button className="flex min-w-0 flex-1 items-center gap-2 text-left" onClick={() => setSelectedId(r.id)}>
                <span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: r.color ?? "#94A3B8" }} />
                <span className="truncate">{r.name}</span>
              </button>
              {editable && !r.is_default && (
                <span className="flex opacity-0 group-hover:opacity-100">
                  <button disabled={i === 0} onClick={() => actions.move.mutate({ roleId: r.id, direction: "up" }, { onError: fail })} aria-label={`${r.name} nach oben`} className="p-0.5 text-muted hover:text-fg disabled:opacity-30"><ArrowUp className="size-3.5" /></button>
                  <button disabled={roles[i + 1]?.is_default} onClick={() => actions.move.mutate({ roleId: r.id, direction: "down" }, { onError: fail })} aria-label={`${r.name} nach unten`} className="p-0.5 text-muted hover:text-fg disabled:opacity-30"><ArrowDown className="size-3.5" /></button>
                </span>
              )}
            </li>
          ))}
        </ul>
        {editable && (
          <form
            className="mt-3 flex gap-1 border-t border-line pt-3"
            onSubmit={(e) => {
              e.preventDefault();
              actions.create.mutate({ name: newName.trim(), permissions: [] }, { onSuccess: (r) => { setNewName(""); setSelectedId(r.id); }, onError: fail });
            }}
          >
            <input value={newName} onChange={(e) => setNewName(e.target.value)} maxLength={32} placeholder="Neue Rolle" aria-label="Name der neuen Rolle" className="h-8 min-w-0 flex-1 rounded-md border border-line bg-base px-2 text-sm focus:border-primary focus:outline-none" />
            <Button type="submit" size="sm" disabled={!newName.trim()} aria-label="Rolle erstellen"><Plus className="size-4" /></Button>
          </form>
        )}
      </Panel>
      {selected ? <RoleEditor key={selected.id + selected.permissions.join()} server={server} role={selected} editable={editable} onDeleted={() => setSelectedId(null)} /> : (
        <Panel><p className="text-sm text-muted">Wähle eine Rolle aus. Höhere Rollen stehen oben; du kannst nur Rollen unter deiner höchsten Rolle bearbeiten und nur Rechte vergeben, die du selbst hast.</p></Panel>
      )}
    </div>
  );
}

function RoleEditor({ server, role, editable, onDeleted }: { server: ServerDetail; role: Role; editable: boolean; onDeleted: () => void }) {
  const actions = useRoleActions(server.id);
  const [name, setName] = useState(role.name);
  const [color, setColor] = useState(role.color ?? "#94A3B8");
  const [perms, setPerms] = useState<string[]>(role.permissions);
  const managed = role.managed;
  const locked = !editable || managed;
  const mine = server.my_permissions;
  const dirty = name !== role.name || color !== (role.color ?? "#94A3B8") || perms.join() !== role.permissions.join();

  const save = () =>
    actions.update.mutate({
      roleId: role.id,
      ...(role.is_default ? {} : { name: name.trim(), color }),
      permissions: ALL_PERMS.filter((p) => perms.includes(p)),
    });

  return (
    <Panel>
      <div className="mb-4 flex items-center gap-2">
        <Badge color={role.color}>{role.name}</Badge>
        {role.is_default && <span className="text-xs text-muted">Standardrolle – gilt für alle Mitglieder</span>}
        {managed && <span className="text-xs text-muted">Bot-Rolle (über die Bot-Installation verwaltet)</span>}
      </div>
      <FormError message={actions.update.error?.message ?? actions.remove.error?.message} />
      {!role.is_default && (
        <div className="mb-4 grid grid-cols-[1fr_auto] gap-3">
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} maxLength={32} disabled={locked} />
          <div className="space-y-1.5">
            <span className="block text-xs font-semibold tracking-wide text-muted uppercase">Farbe</span>
            <input type="color" value={color} onChange={(e) => setColor(e.target.value.toUpperCase())} disabled={locked} aria-label="Rollenfarbe" className="h-10 w-14 cursor-pointer rounded-lg border border-line bg-base" />
          </div>
        </div>
      )}
      <h3 className="mb-2 text-sm font-semibold">Berechtigungen</h3>
      <div className="grid gap-1 sm:grid-cols-2">
        {ALL_PERMS.map((p) => {
          const holds = can(mine, p);
          return (
            <label key={p} className={cn("flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm", locked || !holds ? "opacity-60" : "cursor-pointer hover:bg-card")}>
              <input
                type="checkbox"
                className="size-4 accent-primary"
                checked={perms.includes(p)}
                disabled={locked || !holds}
                onChange={(e) => setPerms((cur) => (e.target.checked ? [...cur, p] : cur.filter((x) => x !== p)))}
              />
              {PERMISSION_LABELS[p]}
            </label>
          );
        })}
      </div>
      {!locked && (
        <div className="mt-4 flex gap-2">
          <Button onClick={save} disabled={!dirty || !name.trim()} loading={actions.update.isPending}>Speichern</Button>
          {!role.is_default && (
            <Button variant="ghost" onClick={() => { if (window.confirm(`Rolle „${role.name}“ löschen?`)) actions.remove.mutate(role.id, { onSuccess: onDeleted }); }}>
              <Trash2 className="size-4" /> Löschen
            </Button>
          )}
        </div>
      )}
    </Panel>
  );
}
