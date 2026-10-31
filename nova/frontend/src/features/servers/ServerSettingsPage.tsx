import { Crown, ImagePlus, Settings, Trash2 } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { PageHeader, Panel } from "@/components/ui/Feedback";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/ui/Field";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import type { Visibility } from "@/lib/types";
import { toast } from "@/stores/toasts";
import { useDeleteServer, useMemberActions, useMembers, useUpdateServer, useUploadIcon } from "./api";
import { BotsTab, InvitesTab } from "./settings/InvitesBotsTabs";
import { MembersTab } from "./settings/MembersTab";
import { RolesTab } from "./settings/RolesTab";
import { useServerContext } from "./useServerContext";

type Tab = "general" | "roles" | "members" | "invites" | "bots" | "owner";

function GeneralSettings() {
  const server = useServerContext();
  const [name, setName] = useState(server.name);
  const [description, setDescription] = useState(server.description ?? "");
  const [visibility, setVisibility] = useState<Visibility>(server.visibility);
  const mutation = useUpdateServer(server.id);
  const icon = useUploadIcon(server.id);
  const fileRef = useRef<HTMLInputElement>(null);
  const editable = can(server.my_permissions, "MANAGE_SERVER");
  const dirty = name.trim() !== server.name || (description.trim() || null) !== server.description || visibility !== server.visibility;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate({ name: name.trim(), description: description.trim() || null, visibility });
  };

  return (
    <Panel>
      <div className="mb-5 flex items-center gap-4">
        <Avatar id={server.id} name={server.name} src={server.icon_url} square size="lg" />
        {editable && (
          <>
            <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) icon.mutate(f, { onError: (err) => toast({ title: err.message, tone: "error" }) }); }} />
            <Button variant="secondary" size="sm" onClick={() => fileRef.current?.click()} loading={icon.isPending}>
              <ImagePlus className="size-4" /> Server-Icon ändern
            </Button>
            <span className="text-xs text-muted">PNG, JPEG, GIF oder WebP, max. 2 MB</span>
          </>
        )}
      </div>
      <form onSubmit={submit} className="space-y-4">
        <FormError message={mutation.error?.message} />
        <TextField label="Servername" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} disabled={!editable} required />
        <TextAreaField label="Beschreibung" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} disabled={!editable} />
        <SelectField label="Sichtbarkeit" value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)} disabled={!editable} hint="Öffentliche Server erscheinen unter „Entdecken“ und können ohne Einladung betreten werden.">
          <option value="private">Privat – nur mit Einladung</option>
          <option value="public">Öffentlich</option>
        </SelectField>
        {editable ? (
          <div className="flex items-center gap-3">
            <Button type="submit" loading={mutation.isPending} disabled={!dirty || !name.trim()}>Änderungen speichern</Button>
            {mutation.isSuccess && !dirty && <span className="text-sm text-success">Gespeichert.</span>}
          </div>
        ) : (
          <p className="text-sm text-muted">Du brauchst die Berechtigung „Server verwalten“, um das zu ändern.</p>
        )}
      </form>
    </Panel>
  );
}

function OwnerTab() {
  const server = useServerContext();
  const { data: members = [] } = useMembers(server.id);
  const { transfer } = useMemberActions(server.id);
  const del = useDeleteServer(server.id);
  const navigate = useNavigate();
  const [target, setTarget] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const candidates = members.filter((m) => !m.is_owner && !m.user.is_bot);

  return (
    <div className="space-y-6">
      <Panel>
        <h2 className="mb-1 flex items-center gap-2 font-semibold"><Crown className="size-4 text-warning" /> Eigentümerschaft übertragen</h2>
        <p className="mb-4 text-sm text-muted">Die neue Person erhält alle Rechte. Du verlierst den Owner-Status sofort. Zur Sicherheit brauchen wir dein Passwort.</p>
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            const name = candidates.find((m) => m.user.id === target)?.user.display_name;
            if (!window.confirm(`Server wirklich an ${name} übertragen?`)) return;
            transfer.mutate({ new_owner_id: target, password }, { onSuccess: () => { toast({ title: "Eigentümerschaft übertragen", tone: "success" }); setPassword(""); } });
          }}
        >
          <FormError message={transfer.error?.message} />
          <SelectField label="Neue:r Owner" value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">Mitglied auswählen …</option>
            {candidates.map((m) => <option key={m.user.id} value={m.user.id}>{m.user.display_name} (@{m.user.username})</option>)}
          </SelectField>
          <TextField label="Dein Passwort" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <Button type="submit" variant="secondary" disabled={!target || !password} loading={transfer.isPending}>Übertragen</Button>
        </form>
      </Panel>
      <Panel className="border-danger/30">
        <h2 className="mb-1 flex items-center gap-2 font-semibold text-danger"><Trash2 className="size-4" /> Server löschen</h2>
        <p className="mb-4 text-sm text-muted">Löscht den Server mit allen Kanälen, Nachrichten, Rollen und Mitgliedschaften. Das kann nicht rückgängig gemacht werden.</p>
        <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); del.mutate(confirm, { onSuccess: () => navigate("/", { replace: true }) }); }}>
          <FormError message={del.error?.message} />
          <TextField label={`Gib „${server.name}“ zur Bestätigung ein`} value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          <Button type="submit" variant="danger" loading={del.isPending} disabled={confirm !== server.name}>Endgültig löschen</Button>
        </form>
      </Panel>
    </div>
  );
}

export function ServerSettingsPage() {
  const server = useServerContext();
  const p = server.my_permissions;
  const [tab, setTab] = useState<Tab>("general");
  const tabs: Array<[Tab, string, boolean]> = [
    ["general", "Allgemein", true],
    ["roles", "Rollen", true],
    ["members", "Mitglieder & Bans", true],
    ["invites", "Einladungen", can(p, "CREATE_INVITES")],
    ["bots", "Bots", true],
    ["owner", "Owner", server.is_owner],
  ];
  return (
    <>
      <PageHeader icon={Settings} title="Einstellungen & Rollen" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-4xl">
          <div className="mb-6 flex flex-wrap gap-1 border-b border-line" role="tablist">
            {tabs.filter(([, , show]) => show).map(([key, label]) => (
              <button key={key} role="tab" aria-selected={tab === key} onClick={() => setTab(key)} className={cn("border-b-2 px-3 py-2 text-sm", tab === key ? "border-primary text-fg" : "border-transparent text-muted hover:text-fg")}>
                {label}
              </button>
            ))}
          </div>
          {tab === "general" && <GeneralSettings key={server.id + server.name} />}
          {tab === "roles" && <RolesTab server={server} />}
          {tab === "members" && <MembersTab server={server} />}
          {tab === "invites" && <InvitesTab server={server} />}
          {tab === "bots" && <BotsTab server={server} />}
          {tab === "owner" && server.is_owner && <OwnerTab />}
        </div>
      </div>
    </>
  );
}
