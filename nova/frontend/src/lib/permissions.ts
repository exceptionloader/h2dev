/** UI-only permission helpers. The backend enforces every permission independently. */
export type PermissionName =
  | "VIEW_CHANNEL"
  | "SEND_MESSAGES"
  | "ADD_REACTIONS"
  | "ATTACH_FILES"
  | "CONNECT_VOICE"
  | "SPEAK"
  | "CREATE_INVITES"
  | "MANAGE_MESSAGES"
  | "KICK_MEMBERS"
  | "BAN_MEMBERS"
  | "MOVE_MEMBERS"
  | "MANAGE_CHANNELS"
  | "MANAGE_ROLES"
  | "MANAGE_EVENTS"
  | "MANAGE_BOTS"
  | "VIEW_AUDIT_LOG"
  | "MANAGE_SERVER"
  | "ADMINISTRATOR";

export const PERMISSION_LABELS: Record<PermissionName, string> = {
  VIEW_CHANNEL: "Kanäle ansehen",
  SEND_MESSAGES: "Nachrichten senden",
  ADD_REACTIONS: "Reaktionen hinzufügen",
  ATTACH_FILES: "Dateien anhängen",
  CONNECT_VOICE: "Sprachkanälen beitreten",
  SPEAK: "Sprechen",
  CREATE_INVITES: "Einladungen erstellen",
  MANAGE_MESSAGES: "Nachrichten verwalten",
  KICK_MEMBERS: "Mitglieder entfernen",
  BAN_MEMBERS: "Mitglieder bannen",
  MOVE_MEMBERS: "Mitglieder verschieben",
  MANAGE_CHANNELS: "Kanäle verwalten",
  MANAGE_ROLES: "Rollen verwalten",
  MANAGE_EVENTS: "Events verwalten",
  MANAGE_BOTS: "Bots verwalten",
  VIEW_AUDIT_LOG: "Audit-Log ansehen",
  MANAGE_SERVER: "Server verwalten",
  ADMINISTRATOR: "Administrator (alle Rechte)",
};

export function can(permissions: readonly string[] | undefined, perm: PermissionName): boolean {
  if (!permissions) return false;
  return permissions.includes("ADMINISTRATOR") || permissions.includes(perm);
}
