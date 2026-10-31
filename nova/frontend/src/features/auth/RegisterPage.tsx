import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, TextField } from "@/components/ui/Field";
import { ApiError } from "@/lib/api";
import { AuthLayout } from "./AuthLayout";
import { useRegister } from "./api";

const USERNAME_RE = /^[a-z0-9_.]{3,32}$/;

export function RegisterPage() {
  const [form, setForm] = useState({ username: "", email: "", display_name: "", password: "" });
  const [clientError, setClientError] = useState<{ field: string; msg: string } | null>(null);
  const mutation = useRegister();
  const navigate = useNavigate();

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const username = form.username.trim().toLowerCase();
    if (!USERNAME_RE.test(username) || username.includes("..")) {
      return setClientError({ field: "username", msg: "3–32 Zeichen: a–z, 0–9, _ oder ." });
    }
    if (form.password.length < 10) {
      return setClientError({ field: "password", msg: "Mindestens 10 Zeichen." });
    }
    setClientError(null);
    mutation.mutate(
      {
        username,
        email: form.email.trim(),
        password: form.password,
        display_name: form.display_name.trim() || undefined,
      },
      { onSuccess: () => navigate("/", { replace: true }) },
    );
  };

  const serverField = mutation.error instanceof ApiError ? mutation.error.field : undefined;
  const fieldError = (name: string) =>
    clientError?.field === name
      ? clientError.msg
      : serverField === name
        ? "Ungültige Eingabe."
        : undefined;

  return (
    <AuthLayout
      title="Konto erstellen"
      subtitle="Ein Konto für deine Server, Freunde und Sessions."
      footer={
        <>
          Bereits registriert?{" "}
          <Link to="/anmelden" className="font-medium text-accent hover:underline">
            Anmelden
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        <FormError message={mutation.error && !serverField ? mutation.error.message : null} />
        <TextField
          label="Benutzername"
          hint="Eindeutig, klein geschrieben – z. B. jakob_dev"
          autoComplete="username"
          value={form.username}
          onChange={set("username")}
          error={fieldError("username")}
          required
          autoFocus
        />
        <TextField
          label="Anzeigename"
          hint="Optional – so sehen dich andere."
          value={form.display_name}
          onChange={set("display_name")}
          maxLength={32}
        />
        <TextField
          label="E-Mail"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={set("email")}
          error={fieldError("email")}
          required
        />
        <TextField
          label="Passwort"
          type="password"
          autoComplete="new-password"
          hint="Mindestens 10 Zeichen."
          value={form.password}
          onChange={set("password")}
          error={fieldError("password")}
          required
        />
        <Button
          type="submit"
          className="w-full"
          loading={mutation.isPending}
          disabled={!form.username || !form.email || !form.password}
        >
          Registrieren
        </Button>
      </form>
    </AuthLayout>
  );
}
