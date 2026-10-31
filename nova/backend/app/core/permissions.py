"""Permission model: bit flags, effective-permission calculation and hierarchy rules.

Everything here is pure (no database access) so it can be unit-tested exhaustively.

Rules
-----
* The server owner (``servers.owner_id``) always has every permission. Ownership is
  not a role, so no role change can ever remove it.
* Every member implicitly has the server's default role ("Mitglied").
* ``ADMINISTRATOR`` grants every permission and bypasses channel overrides.
* Channel overrides are applied in this order: default role, then all other roles of
  the member (denies first, then allows), then member-specific overrides.
* Role hierarchy: a member may only manage roles strictly *below* their highest role
  and may only grant permissions they hold themselves.
"""

from collections.abc import Iterable
from dataclasses import dataclass
from enum import IntFlag
from uuid import UUID


class Permission(IntFlag):
    VIEW_CHANNEL = 1 << 0
    SEND_MESSAGES = 1 << 1
    ADD_REACTIONS = 1 << 2
    ATTACH_FILES = 1 << 3
    CONNECT_VOICE = 1 << 4
    SPEAK = 1 << 5
    CREATE_INVITES = 1 << 6
    MANAGE_MESSAGES = 1 << 7
    KICK_MEMBERS = 1 << 8
    BAN_MEMBERS = 1 << 9
    MOVE_MEMBERS = 1 << 10
    MANAGE_CHANNELS = 1 << 11
    MANAGE_ROLES = 1 << 12
    MANAGE_EVENTS = 1 << 13
    MANAGE_BOTS = 1 << 14
    VIEW_AUDIT_LOG = 1 << 15
    MANAGE_SERVER = 1 << 16
    ADMINISTRATOR = 1 << 30


ALL_PERMISSIONS = Permission(0)
for _p in Permission:
    ALL_PERMISSIONS |= _p
NO_PERMISSIONS = Permission(0)

DEFAULT_MEMBER_PERMISSIONS = (
    Permission.VIEW_CHANNEL
    | Permission.SEND_MESSAGES
    | Permission.ADD_REACTIONS
    | Permission.ATTACH_FILES
    | Permission.CONNECT_VOICE
    | Permission.SPEAK
    | Permission.CREATE_INVITES
)
DEFAULT_MODERATOR_PERMISSIONS = (
    DEFAULT_MEMBER_PERMISSIONS
    | Permission.MANAGE_MESSAGES
    | Permission.KICK_MEMBERS
    | Permission.BAN_MEMBERS
    | Permission.MOVE_MEMBERS
    | Permission.MANAGE_EVENTS
    | Permission.VIEW_AUDIT_LOG
)
DEFAULT_ADMIN_PERMISSIONS = ALL_PERMISSIONS & ~Permission.ADMINISTRATOR

# Permissions that only make sense on channel overrides.
CHANNEL_SCOPED = (
    Permission.VIEW_CHANNEL
    | Permission.SEND_MESSAGES
    | Permission.ADD_REACTIONS
    | Permission.ATTACH_FILES
    | Permission.CONNECT_VOICE
    | Permission.SPEAK
    | Permission.MANAGE_MESSAGES
    | Permission.MOVE_MEMBERS
    | Permission.MANAGE_CHANNELS
)

OWNER_POSITION = 1 << 31  # above any real role position


def sanitize(value: int) -> Permission:
    """Drop unknown bits coming from user input or the database."""
    return Permission(value & int(ALL_PERMISSIONS))


def permission_names(value: int) -> list[str]:
    perms = sanitize(value)
    return [p.name for p in Permission if p in perms and p.name]


@dataclass(frozen=True, slots=True)
class RoleInfo:
    id: UUID
    position: int
    permissions: int
    is_default: bool = False


@dataclass(frozen=True, slots=True)
class OverrideInfo:
    allow: int
    deny: int
    role_id: UUID | None = None
    member_id: UUID | None = None


@dataclass(frozen=True, slots=True)
class MemberContext:
    """Everything needed to compute a member's permissions in a server."""

    member_id: UUID
    is_owner: bool
    default_role: RoleInfo
    roles: tuple[RoleInfo, ...]  # assigned roles, excluding the default role

    @property
    def top_position(self) -> int:
        if self.is_owner:
            return OWNER_POSITION
        return max([self.default_role.position, *(r.position for r in self.roles)])


def base_permissions(ctx: MemberContext) -> Permission:
    if ctx.is_owner:
        return ALL_PERMISSIONS
    perms = sanitize(ctx.default_role.permissions)
    for role in ctx.roles:
        perms |= sanitize(role.permissions)
    if Permission.ADMINISTRATOR in perms:
        return ALL_PERMISSIONS
    return perms


def channel_permissions(ctx: MemberContext, overrides: Iterable[OverrideInfo]) -> Permission:
    base = base_permissions(ctx)
    if Permission.ADMINISTRATOR in base:
        return ALL_PERMISSIONS

    overrides = list(overrides)
    perms = int(base)

    for ov in overrides:
        if ov.role_id == ctx.default_role.id:
            perms = (perms & ~ov.deny) | ov.allow

    role_ids = {r.id for r in ctx.roles}
    allow = deny = 0
    for ov in overrides:
        if ov.role_id is not None and ov.role_id in role_ids:
            allow |= ov.allow
            deny |= ov.deny
    perms = (perms & ~deny) | allow

    for ov in overrides:
        if ov.member_id == ctx.member_id:
            perms = (perms & ~ov.deny) | ov.allow

    result = sanitize(perms)
    # Without VIEW_CHANNEL a member cannot do anything in the channel.
    if Permission.VIEW_CHANNEL not in result:
        return NO_PERMISSIONS
    return result


def has(perms: int, required: Permission) -> bool:
    return (int(perms) & int(required)) == int(required)


# --- Hierarchy rules ------------------------------------------------------------------


def can_manage_role(actor: MemberContext, role: RoleInfo) -> bool:
    """Actor may edit/delete/assign ``role``."""
    if actor.is_owner:
        return True
    if not has(base_permissions(actor), Permission.MANAGE_ROLES):
        return False
    return role.position < actor.top_position


def can_grant_permissions(actor: MemberContext, requested: int) -> bool:
    """Actor may set a role's permissions to ``requested`` (no privilege escalation)."""
    if actor.is_owner:
        return True
    actor_perms = base_permissions(actor)
    return (int(sanitize(requested)) & ~int(actor_perms)) == 0


def can_moderate_member(actor: MemberContext, target: MemberContext) -> bool:
    """Actor outranks target (required for kick/ban/role changes on the target)."""
    if target.is_owner or actor.member_id == target.member_id:
        return False
    if actor.is_owner:
        return True
    return actor.top_position > target.top_position
