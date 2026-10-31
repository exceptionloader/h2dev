import { KeyRound, LogOut, Settings, UserRound } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Avatar, STATUS_LABEL } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { PageHeader, Panel } from "@/components/ui/Feedback";
import { FormError, SelectField, TextField } from "@/components/ui/Field";
import { useChangePassword, useLogout, useMe, useUpdateProfile } from "@/features/auth/api";
import { formatDate } from "@/lib/format";
import type { UserMe, UserStatus } from "@/lib/types";

function ProfileForm({ me }: { me: UserMe }) {
  const [displayName, setDisplayName] = useState(me.display_name);
  const [status, setStatus] = useState<UserStatus>(me.status);
  const [customStatus, setCustomStatus] = useState(me.custom_status ?? "");
  const mutation = useUpdateProfile();

  const dirty =
    displayName.trim() !== me.display_name ||
    status !== me.status ||
    (customStatus.trim() || null) !== me.custom_status;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate({ display_name: displayName.trim(), status, custom_status: customStatus.trim() });
  };

  return (
    <Panel>
      <h2 className="mb-4 flex items-center gap-2 font-semibold">
        <UserRound className="size-4 text-accent" /> Profil
      </h2>
      <div className="mb-5 flex items-center gap-4">
        <Avatar id={me.id} name={displayName || me.username} src={me.avatar_url} size="lg" status={status} />
        <div>
          <div className="text-lg font-semibold">{displayName || me.username}</div>
          <div className="text-sm text-muted">
            @{me.username} · dabei seit {formatDate(me.created_at)}
          </div>
          <div className="mt-1 text-xs text-muted">Profilbilder kommen mit Datei-Uploads in Meilenstein 2.</div>
        </div>
      </div>
      <form onSubmit={submit} className="space-y-4">
        <FormError message={mutation.error?.message} />
        <TextField label="Anzeigename" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={32} required />
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value as UserStatus)}>
          {(Object.keys(STATUS_LABEL) as UserStatus[]).map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </SelectField>
        <TextField
          label="Eigener Status"
          value={customStatus}
          onChange={(e) => setCustomStatus(e.target.value)}
          maxLength={128}
          placeholder="z. B. Ranked grinden 🎮"
        />
        <div className="flex items-center gap-3">
          <Button type="submit" loading={mutation.isPending} disabled={!dirty || !displayName.trim()}>
            Speichern
          </Button>
          {mutation.isSuccess && !dirty && <span className="text-sm text-success">Gespeichert.</span>}
        </div>
      </form>
    </Panel>
  );
}

function PasswordForm() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);
  const mutation = useChangePassword();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (next.length < 10) return setLocalError("Das neue Passwort braucht mindestens 10 Zeichen.");
    setLocalError(null);
    mutation.mutate(
      { current_password: current, new_password: next },
      {
        onSuccess: () => {
          setCurrent("");
          setNext("");
        },
      },
    );
  };

  return (
    <Panel>
      <h2 className="mb-4 flex items-center gap-2 font-semibold">
        <KeyRound className="size-4 text-accent" /> Passwort ändern
      </h2>
      <form onSubmit={submit} className="space-y-4">
        <FormError message={localError ?? mutation.error?.message} />
        <TextField label="Aktuelles Passwort" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <TextField label="Neues Passwort" type="password" autoComplete="new-password" hint="Mindestens 10 Zeichen. Andere Geräte werden abgemeldet." value={next} onChange={(e) => setNext(e.target.value)} />
        <div className="flex items-center gap-3">
          <Button type="submit" loading={mutation.isPending} disabled={!current || !next}>
            Passwort ändern
          </Button>
          {mutation.isSuccess && <span className="text-sm text-success">Passwort geändert.</span>}
        </div>
      </form>
    </Panel>
  );
}

export function SettingsPage() {
  const { data: me } = useMe();
  const logout = useLogout();
  const navigate = useNavigate();
  if (!me) return null;

  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={Settings} title="Einstellungen" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-2xl space-y-6">
          <ProfileForm key={me.id} me={me} />
          <PasswordForm />
          <Panel>
            <h2 className="mb-1 font-semibold">Konto</h2>
            <p className="mb-4 text-sm text-muted">Angemeldet als {me.email}</p>
            <Button
              variant="secondary"
              loading={logout.isPending}
              onClick={() => logout.mutate(undefined, { onSettled: () => navigate("/anmelden", { replace: true }) })}
            >
              <LogOut className="size-4" /> Abmelden
            </Button>
          </Panel>
        </div>
      </div>
    </div>
  );
}
