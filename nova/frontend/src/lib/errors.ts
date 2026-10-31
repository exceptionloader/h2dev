/** German user-facing messages for backend error codes. */
const MESSAGES: Record<string, string> = {
  not_authenticated: "Bitte melde dich an.",
  session_expired: "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.",
  invalid_credentials: "Benutzername/E-Mail oder Passwort ist falsch.",
  account_disabled: "Dieses Konto ist deaktiviert.",
  username_taken: "Dieser Benutzername ist bereits vergeben.",
  email_taken: "Diese E-Mail-Adresse ist bereits registriert.",
  invalid_current_password: "Das aktuelle Passwort ist falsch.",
  invalid_reset_token: "Der Link ist ungültig oder abgelaufen. Fordere einen neuen an.",
  rate_limited: "Zu viele Versuche. Bitte warte kurz und versuche es erneut.",
  validation_error: "Bitte überprüfe deine Eingaben.",
  forbidden: "Dafür fehlt dir die Berechtigung.",
  missing_permission: "Dafür fehlt dir die Berechtigung.",
  owner_only: "Nur der Server-Owner kann das tun.",
  server_not_found: "Server nicht gefunden.",
  not_found: "Nicht gefunden.",
  server_limit_reached: "Du hast die maximale Anzahl eigener Server erreicht.",
  channel_limit_reached: "Dieser Server hat die maximale Anzahl an Kanälen erreicht.",
  invalid_parent: "Die gewählte Kategorie ist ungültig.",
  confirm_name_mismatch: "Der eingegebene Name stimmt nicht mit dem Servernamen überein.",
  bad_origin: "Anfrage von unbekannter Herkunft blockiert.",
  network_error: "Keine Verbindung zum Server. Läuft das Backend?",
  internal_error: "Ein unerwarteter Fehler ist aufgetreten.",
};

export function errorMessage(code: string | undefined): string {
  return (code && MESSAGES[code]) || MESSAGES.internal_error!;
}
