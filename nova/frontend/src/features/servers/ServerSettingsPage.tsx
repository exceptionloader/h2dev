import { Settings, ShieldCheck, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { Badge, PageHeader, Panel } from "@/components/ui/Feedback";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/ui/Field";
import { can, PERMISSION_LABELS, type PermissionName } from "@/lib/permissions";
import type { Visibility } from "@/lib/types";
import { useDeleteServer, useRoles, useUpdateServer } from "./api";
import { useServerContext } from "./useServerContext";

function GeneralSettings() {
  const server = useServerContext();
  const [name, setName] = useState(server.name);
  const [description, setDescription] = useState(server.description ?? "");
  const [visibility, setVisibility] = useState<Visibility>(server.visibility);
  const mutation = useUpdateServer(server.id);
  const editable = can(server.my_permissions, "MANAGE_SERVER");

  const dirty =
    name.trim() !== server.name ||
    (description.trim() || null) !== server.description ||
    visibility !== server.visibility;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate({ name: name.trim(), description: description.trim() || null, visibility });
  };

  return (
    <Panel>
      <h2 className="mb-4 font-semibold">Allgemein</h2>
      <form onSubmit={submit} className="space-y-4">
        <FormError message={mutation.error?.message} />
        <TextField label="Servername" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} disabled={!editable} required />
        <TextAreaField
          label="Beschreibung"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={500}
          disabled={!editable}
        />
        <SelectField
          label="Sichtbarkeit"
          value={visibility}
          onChange={(e) => setVisibility(e.target.value as Visibility)}
          disabled={!editable}
        >
          <option value="private">Privat – nur mit Einladung</option>
          <option value="public">Öffentlich</option>
        </SelectField>
        {editable ? (
          <div className="flex items-center gap-3">
            <Button type="submit" loading={mutation.isPending} disabled={!dirty || !name.trim()}>
              Änderungen speichern
            </Button>
            {mutation.isSuccess && !dirty && <span className="text-sm text-success">Gespeichert.</span>}
          </div>
        ) : (
          <p className="text-sm text-muted">Du brauchst die Berechtigung „Server verwalten“, um das zu ändern.</p>
        )}
      </form>
    </Panel>
  );
}

function RolesOverview() {
  const server = useServerContext();
  const { data: roles = [] } = useRoles(server.id);
  return (
    <Panel>
      <h2 className="mb-1 flex items-center gap-2 font-semibold">
        <ShieldCheck className="size-4 text-accent" /> Rollen & Berechtigungen
      </h2>
      <p className="mb-4 text-sm text-muted">
        Höhere Rollen stehen oben. Eigene Rollen erstellen und zuweisen folgt in Meilenstein 2.
      </p>
      <ul className="space-y-3">
        {roles.map((role) => (
          <li key={role.id} className="rounded-xl border border-line bg-base/40 p-3">
            <div className="mb-2 flex items-center gap-2">
              <Badge color={role.color}>{role.name}</Badge>
              {role.is_default && <span className="text-xs text-muted">Standardrolle aller Mitglieder</span>}
            </div>
            <div className="flex flex-wrap gap-1.5">
              {role.permissions.map((p) => (
                <span key={p} className="rounded-md bg-card px-2 py-0.5 text-xs text-muted">
                  {PERMISSION_LABELS[p as PermissionName] ?? p}
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

function DangerZone() {
  const server = useServerContext();
  const [confirm, setConfirm] = useState("");
  const mutation = useDeleteServer(server.id);
  const navigate = useNavigate();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate(confirm, { onSuccess: () => navigate("/", { replace: true }) });
  };

  return (
    <Panel className="border-danger/30">
      <h2 className="mb-1 flex items-center gap-2 font-semibold text-danger">
        <Trash2 className="size-4" /> Server löschen
      </h2>
      <p className="mb-4 text-sm text-muted">
        Löscht den Server mit allen Kanälen, Rollen und Mitgliedschaften. Das kann nicht rückgängig
        gemacht werden.
      </p>
      <form onSubmit={submit} className="space-y-3">
        <FormError message={mutation.error?.message} />
        <TextField
          label={`Gib „${server.name}“ zur Bestätigung ein`}
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <Button type="submit" variant="danger" loading={mutation.isPending} disabled={confirm !== server.name}>
          Endgültig löschen
        </Button>
      </form>
    </Panel>
  );
}

export function ServerSettingsPage() {
  const server = useServerContext();
  return (
    <>
      <PageHeader icon={Settings} title="Einstellungen & Rollen" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-2xl space-y-6">
          {/* key: reset the form when the server changes */}
          <GeneralSettings key={server.id + server.name} />
          <RolesOverview />
          {server.is_owner && <DangerZone />}
        </div>
      </div>
    </>
  );
}
