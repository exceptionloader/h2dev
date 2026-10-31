export type UserStatus = "online" | "idle" | "dnd" | "invisible";

export interface UserPublic {
  id: string;
  username: string;
  display_name: string;
  avatar_url: string | null;
  status: UserStatus;
  custom_status: string | null;
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
