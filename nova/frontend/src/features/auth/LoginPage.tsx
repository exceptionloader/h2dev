import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, TextField } from "@/components/ui/Field";
import { AuthLayout } from "./AuthLayout";
import { useLogin } from "./api";

/** Only allow relative in-app redirects (prevents open redirects). */
export function safeRedirect(target: string | null): string {
  return target && target.startsWith("/") && !target.startsWith("//") ? target : "/";
}

export function LoginPage() {
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const mutation = useLogin();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate(
      { login, password },
      { onSuccess: () => navigate(safeRedirect(params.get("weiter")), { replace: true }) },
    );
  };

  return (
    <AuthLayout
      title="Willkommen zurück"
      subtitle="Melde dich an, um zu deinen Leuten zurückzukehren."
      footer={
        <>
          Noch kein Konto?{" "}
          <Link to="/registrieren" className="font-medium text-accent hover:underline">
            Jetzt registrieren
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        {params.get("zurueckgesetzt") && !mutation.error && (
          <div role="status" className="rounded-lg border border-success/30 bg-success/10 px-3 py-2 text-sm text-success">
            Passwort geändert. Du kannst dich jetzt anmelden.
          </div>
        )}
        <FormError message={mutation.error?.message} />
        <TextField
          label="Benutzername oder E-Mail"
          autoComplete="username"
          value={login}
          onChange={(e) => setLogin(e.target.value)}
          required
          autoFocus
        />
        <TextField
          label="Passwort"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <div className="text-right">
          <Link to="/passwort-vergessen" className="text-xs text-muted hover:text-accent">
            Passwort vergessen?
          </Link>
        </div>
        <Button type="submit" className="w-full" loading={mutation.isPending} disabled={!login || !password}>
          Anmelden
        </Button>
      </form>
    </AuthLayout>
  );
}
