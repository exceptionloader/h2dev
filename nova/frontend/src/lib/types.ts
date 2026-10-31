export type UserStatus = "online" | "idle" | "dnd" | "invisible";

export type Presence = "online" | "idle" | "dnd" | "offline";

export interface UserPublic {
  id: string;
  username: string;
  display_name: string;
  avatar_url: string | null;
  status: UserStatus;
  custom_status: string | null;
  is_bot: boolean;
  presence: Presence;
}

export interface UserMe extends UserPublic {
  email: string;
  created_at: string;
}

export type Visibility = "private" | "public";

export interface Server {
  id: string;
  name: string;
  description: string | null;
  icon_url: string | null;
  banner_url: string | null;
  owner_id: string;
  visibility: Visibility;
  created_at: string;
}

export interface ServerDetail extends Server {
  member_count: number;
  my_permissions: string[];
  is_owner: boolean;
}

export type ChannelType = "category" | "text" | "voice" | "announcement";

export interface Channel {
  id: string;
  server_id: string;
  parent_id: string | null;
  type: ChannelType;
  name: string;
  topic: string | null;
  position: number;
  is_private: boolean;
  my_permissions: string[];
}

export interface Role {
  id: string;
  name: string;
  color: string | null;
  icon: string | null;
  position: number;
  permissions: string[];
  is_default: boolean;
  managed: boolean;
}

export interface Member {
  id: string;
  user: UserPublic;
  nickname: string | null;
  joined_at: string;
  role_ids: string[];
  is_owner: boolean;
}

export interface AuditLogEntry {
  id: string;
  action: string;
  actor: UserPublic | null;
  target_type: string | null;
  target_id: string | null;
  details: Record<string, unknown>;
  created_at: string;
}

export interface Attachment {
  id: string;
  filename: string;
  content_type: string;
  size: number;
  is_image: boolean;
  url: string;
}

export interface Reaction {
  emoji: string;
  user_ids: string[];
}

export interface Message {
  id: string;
  channel_id: string;
  server_id: string;
  author: UserPublic | null;
  content: string;
  created_at: string;
  edited_at: string | null;
  pinned: boolean;
  reply_to: { id: string; author: UserPublic | null; content: string } | null;
  reactions: Reaction[];
  attachments: Attachment[];
}

export interface Invite {
  code: string;
  server_id: string;
  creator: UserPublic | null;
  max_uses: number | null;
  uses: number;
  expires_at: string | null;
  created_at: string;
}

export interface InvitePreview {
  code: string;
  server: Server;
  member_count: number;
  already_member: boolean;
  expires_at: string | null;
}

export interface Ban {
  user: UserPublic;
  reason: string | null;
  created_at: string;
}

export interface Override {
  role_id: string | null;
  member_id: string | null;
  allow: string[];
  deny: string[];
}

export interface VoiceState {
  user_id: string;
  server_id: string;
  channel_id: string;
  muted: boolean;
  deafened: boolean;
  joined_at: string;
}

export interface Zone {
  id: string;
  channel_id: string;
  name: string;
  kind: "lobby" | "gaming" | "chill" | "afk" | "strategy" | "custom";
  x: number;
  y: number;
  width: number;
  height: number;
  color: string | null;
  can_connect: boolean;
}

export interface Space {
  id: string;
  server_id: string;
  name: string;
  description: string | null;
  zones: Zone[];
  created_at: string;
}

export interface GamingSession {
  id: string;
  server_id: string;
  server_name: string;
  creator: UserPublic | null;
  game: string;
  title: string;
  description: string | null;
  max_participants: number;
  participants: UserPublic[];
  start_time: string;
  status: "open" | "closed";
  is_full: boolean;
  voice_channel_id: string | null;
  voice_channel_name: string | null;
  created_at: string;
}

export type NoteKind = "note" | "link" | "task" | "followup";

export interface SessionNote {
  id: string;
  session_id: string;
  author: UserPublic | null;
  kind: NoteKind;
  content: string;
  url: string | null;
  done: boolean;
  due_at: string | null;
  created_at: string;
}

export interface FriendRequest {
  id: string;
  user: UserPublic;
  created_at: string;
}

export interface Friends {
  friends: UserPublic[];
  incoming: FriendRequest[];
  outgoing: FriendRequest[];
  blocked: UserPublic[];
}

export interface DirectMessage {
  id: string;
  dm_id: string;
  author: UserPublic;
  content: string;
  created_at: string;
  edited_at: string | null;
}

export interface DMChannel {
  id: string;
  user: UserPublic;
  last_message: DirectMessage | null;
  last_message_at: string;
}

export type ReportReason = "spam" | "harassment" | "hate" | "cheating" | "nsfw" | "other";

export interface Report {
  id: string;
  reporter: UserPublic | null;
  target: UserPublic | null;
  server_id: string | null;
  message_id: string | null;
  message_excerpt: string | null;
  reason: ReportReason;
  details: string | null;
  status: "open" | "resolved" | "dismissed";
  created_at: string;
  handled_at: string | null;
}

export interface Bot {
  id: string;
  name: string;
  description: string | null;
  is_public: boolean;
  is_first_party: boolean;
  user: UserPublic;
  created_at: string;
}

export interface BotInstallation {
  bot: Bot;
  permissions: string[];
  installed_at: string;
}

export interface DiscoverServer extends Server {
  member_count: number;
  is_member: boolean;
}
