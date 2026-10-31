import { CheckCircle2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, TextField } from "@/components/ui/Field";
import { AuthLayout } from "./AuthLayout";
import { useConfirmPasswordReset, useRequestPasswordReset } from "./api";

const backToLogin = (
  <Link to="/anmelden" className="font-medium text-accent hover:underline">
    Zurück zur Anmeldung
  </Link>
);

export function PasswordResetRequestPage() {
  const [email, setEmail] = useState("");
  const mutation = useRequestPasswordReset();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate(email.trim());
  };

  return (
    <AuthLayout
      title="Passwort vergessen"
      subtitle="Wir senden dir einen Link zum Zurücksetzen."
      footer={backToLogin}
    >
      {mutation.isSuccess ? (
        <div className="flex gap-3 rounded-lg border border-success/30 bg-success/10 p-3 text-sm">
          <CheckCircle2 className="size-5 shrink-0 text-success" />
          <p>
            Falls ein Konto mit dieser Adresse existiert, ist ein Link unterwegs. Er ist 30 Minuten
            gültig.
          </p>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          <FormError message={mutation.error?.message} />
          <TextField
            label="E-Mail"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoFocus
          />
          <Button type="submit" className="w-full" loading={mutation.isPending} disabled={!email}>
            Link senden
          </Button>
        </form>
      )}
    </AuthLayout>
  );
}

export function PasswordResetConfirmPage() {
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);
  const mutation = useConfirmPasswordReset();
  const navigate = useNavigate();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (password.length < 10) return setLocalError("Das Passwort braucht mindestens 10 Zeichen.");
    if (password !== repeat) return setLocalError("Die Passwörter stimmen nicht überein.");
    setLocalError(null);
    mutation.mutate(
      { token, new_password: password },
      { onSuccess: () => navigate("/anmelden?zurueckgesetzt=1", { replace: true }) },
    );
  };

  return (
    <AuthLayout title="Neues Passwort" subtitle="Wähle ein neues, sicheres Passwort." footer={backToLogin}>
      {!token ? (
        <FormError message="Dieser Link ist unvollständig. Fordere einen neuen an." />
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          <FormError message={localError ?? mutation.error?.message} />
          <TextField
            label="Neues Passwort"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoFocus
          />
          <TextField
            label="Passwort wiederholen"
            type="password"
            autoComplete="new-password"
            value={repeat}
            onChange={(e) => setRepeat(e.target.value)}
          />
          <Button type="submit" className="w-full" loading={mutation.isPending}>
            Passwort speichern
          </Button>
          <p className="text-xs text-muted">Danach wirst du auf allen Geräten abgemeldet.</p>
        </form>
      )}
    </AuthLayout>
  );
}
