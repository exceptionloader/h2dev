import { Bot as BotIcon, Copy, ImagePlus, KeyRound, LogOut, RefreshCw, Settings, Trash2, UserRound } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router";
import { Avatar, STATUS_LABEL } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { PageHeader, Panel } from "@/components/ui/Feedback";
import { FormError, SelectField, TextField } from "@/components/ui/Field";
import { meKey, useChangePassword, useLogout, useMe, useUpdateProfile } from "@/features/auth/api";
import { useBotActions, useMyBots } from "@/features/social/api";
import { api, upload } from "@/lib/api";
import { toast } from "@/stores/toasts";
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
          <AvatarControls hasAvatar={!!me.avatar_url} />
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

function AvatarControls({ hasAvatar }: { hasAvatar: boolean }) {
  const qc = useQueryClient();
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<UserMe>) => {
    setBusy(true);
    try {
      qc.setQueryData(meKey, await fn());
    } catch (err) {
      toast({ title: err instanceof Error ? err.message : "Fehler", tone: "error" });
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="mt-2 flex gap-2">
      <input ref={ref} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void run(() => upload<UserMe>("/users/me/avatar", f)); e.target.value = ""; }} />
      <Button size="sm" variant="secondary" loading={busy} onClick={() => ref.current?.click()}><ImagePlus className="size-4" /> Profilbild</Button>
      {hasAvatar && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void run(() => api<UserMe>("/users/me/avatar", { method: "DELETE" }))}>Entfernen</Button>}
    </div>
  );
}

function BotDeveloper() {
  const { data: bots = [] } = useMyBots();
  const actions = useBotActions();
  const [name, setName] = useState("");
  const [isPublic, setIsPublic] = useState(false);
  const [token, setToken] = useState<{ name: string; token: string } | null>(null);

  return (
    <Panel>
      <h2 className="mb-1 flex items-center gap-2 font-semibold"><BotIcon className="size-4 text-accent" /> Bots (für Entwickler)</h2>
      <p className="mb-4 text-sm text-muted">
        Eigene Bots laufen als separates Programm und nutzen die NOVA-API mit ihrem Token (<code>Authorization: Bot …</code>). Sie haben auf einem Server nur die Rechte, die ihnen bei der Installation gegeben werden.
      </p>
      {token && (
        <div className="mb-4 rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm">
          <div className="mb-1 font-semibold">Token für {token.name} – wird nur jetzt angezeigt!</div>
          <div className="flex items-center gap-2">
            <code className="flex-1 truncate rounded bg-base px-2 py-1 text-xs">{token.token}</code>
            <button onClick={() => { void navigator.clipboard?.writeText(token.token); toast({ title: "Token kopiert", tone: "success" }); }} aria-label="Token kopieren"><Copy className="size-4" /></button>
          </div>
        </div>
      )}
      <ul className="mb-4 divide-y divide-line">
        {bots.map((b) => (
          <li key={b.id} className="flex items-center gap-3 py-2 text-sm">
            <span className="flex-1"><span className="font-medium">{b.name}</span> <span className="text-xs text-muted">@{b.user.username} · {b.is_public ? "öffentlich" : "privat"}</span></span>
            <button onClick={() => actions.update.mutate({ id: b.id, is_public: !b.is_public })} className="text-xs text-accent hover:underline">{b.is_public ? "Privat machen" : "Veröffentlichen"}</button>
            <button onClick={() => actions.regenerate.mutate(b.id, { onSuccess: (r) => setToken({ name: r.name, token: r.token }) })} className="text-muted hover:text-fg" aria-label="Neues Token" title="Neues Token"><RefreshCw className="size-4" /></button>
            <button onClick={() => { if (window.confirm(`Bot „${b.name}“ löschen?`)) actions.remove.mutate(b.id); }} className="text-muted hover:text-danger" aria-label="Bot löschen"><Trash2 className="size-4" /></button>
          </li>
        ))}
      </ul>
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); actions.create.mutate({ name: name.trim(), is_public: isPublic }, { onSuccess: (r) => { setToken({ name: r.name, token: r.token }); setName(""); } }); }}>
        <div className="flex-1"><TextField label="Neuer Bot" value={name} onChange={(e) => setName(e.target.value)} maxLength={32} placeholder="z. B. Stats Bot" /></div>
        <label className="flex h-10 items-center gap-2 text-sm"><input type="checkbox" className="accent-primary" checked={isPublic} onChange={(e) => setIsPublic(e.target.checked)} /> öffentlich</label>
        <Button type="submit" disabled={name.trim().length < 2} loading={actions.create.isPending}>Erstellen</Button>
      </form>
      <FormError message={actions.create.error?.message} />
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
          <BotDeveloper />
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
