"""Unit tests for the pure permission engine."""

import uuid

from app.core.permissions import (
    ALL_PERMISSIONS,
    DEFAULT_ADMIN_PERMISSIONS,
    DEFAULT_MEMBER_PERMISSIONS,
    DEFAULT_MODERATOR_PERMISSIONS,
    NO_PERMISSIONS,
    MemberContext,
    OverrideInfo,
    Permission,
    RoleInfo,
    base_permissions,
    can_grant_permissions,
    can_manage_role,
    can_moderate_member,
    channel_permissions,
    sanitize,
)

DEFAULT = RoleInfo(uuid.uuid4(), 0, int(DEFAULT_MEMBER_PERMISSIONS), is_default=True)
MOD = RoleInfo(uuid.uuid4(), 1, int(DEFAULT_MODERATOR_PERMISSIONS))
ADMIN = RoleInfo(uuid.uuid4(), 2, int(DEFAULT_ADMIN_PERMISSIONS))
ROLE_MGR = RoleInfo(uuid.uuid4(), 3, int(Permission.MANAGE_ROLES | Permission.KICK_MEMBERS))
SUPER = RoleInfo(uuid.uuid4(), 5, int(Permission.ADMINISTRATOR))


def member(*roles: RoleInfo, owner: bool = False) -> MemberContext:
    return MemberContext(uuid.uuid4(), owner, DEFAULT, tuple(roles))


def test_owner_has_everything_even_without_roles():
    assert base_permissions(member(owner=True)) == ALL_PERMISSIONS


def test_owner_cannot_be_locked_out_of_channels():
    owner = member(owner=True)
    deny_all = [
        OverrideInfo(allow=0, deny=int(ALL_PERMISSIONS), role_id=DEFAULT.id),
        OverrideInfo(allow=0, deny=int(ALL_PERMISSIONS), member_id=owner.member_id),
    ]
    assert channel_permissions(owner, deny_all) == ALL_PERMISSIONS


def test_default_role_applies_to_everyone():
    assert base_permissions(member()) == DEFAULT_MEMBER_PERMISSIONS


def test_roles_are_combined():
    perms = base_permissions(member(MOD))
    assert Permission.KICK_MEMBERS in perms and Permission.SEND_MESSAGES in perms
    assert Permission.MANAGE_ROLES not in perms


def test_administrator_flag_grants_all():
    assert base_permissions(member(SUPER)) == ALL_PERMISSIONS


def test_unknown_bits_are_dropped():
    assert sanitize(1 << 50) == NO_PERMISSIONS


def test_private_channel_hidden_for_members_visible_for_allowed_role():
    overrides = [
        OverrideInfo(allow=0, deny=int(Permission.VIEW_CHANNEL), role_id=DEFAULT.id),
        OverrideInfo(allow=int(Permission.VIEW_CHANNEL), deny=0, role_id=MOD.id),
    ]
    assert channel_permissions(member(), overrides) == NO_PERMISSIONS
    assert Permission.VIEW_CHANNEL in channel_permissions(member(MOD), overrides)


def test_member_override_beats_role_override():
    m = member(MOD)
    overrides = [
        OverrideInfo(allow=int(Permission.SEND_MESSAGES), deny=0, role_id=MOD.id),
        OverrideInfo(allow=0, deny=int(Permission.SEND_MESSAGES), member_id=m.member_id),
    ]
    assert Permission.SEND_MESSAGES not in channel_permissions(m, overrides)


def test_role_allow_beats_role_deny_between_roles():
    # One role denies, another allows: allow wins (union semantics).
    other = RoleInfo(uuid.uuid4(), 1, 0)
    overrides = [
        OverrideInfo(allow=0, deny=int(Permission.SEND_MESSAGES), role_id=other.id),
        OverrideInfo(allow=int(Permission.SEND_MESSAGES), deny=0, role_id=MOD.id),
    ]
    assert Permission.SEND_MESSAGES in channel_permissions(member(MOD, other), overrides)


def test_announcement_override_blocks_posting_only():
    overrides = [OverrideInfo(allow=0, deny=int(Permission.SEND_MESSAGES), role_id=DEFAULT.id)]
    perms = channel_permissions(member(), overrides)
    assert Permission.VIEW_CHANNEL in perms and Permission.SEND_MESSAGES not in perms


def test_role_management_requires_permission_and_higher_position():
    assert not can_manage_role(member(MOD), DEFAULT)  # no MANAGE_ROLES
    mgr = member(ROLE_MGR)
    assert can_manage_role(mgr, ADMIN)  # position 2 < 3
    assert not can_manage_role(mgr, ROLE_MGR)  # same position
    assert not can_manage_role(mgr, SUPER)  # above
    assert can_manage_role(member(owner=True), SUPER)


def test_cannot_grant_permissions_you_do_not_have():
    mgr = member(ROLE_MGR)
    assert can_grant_permissions(mgr, int(Permission.KICK_MEMBERS | Permission.SEND_MESSAGES))
    assert not can_grant_permissions(mgr, int(Permission.BAN_MEMBERS))
    assert not can_grant_permissions(mgr, int(Permission.ADMINISTRATOR))
    # The default admin role does not hold ADMINISTRATOR and cannot hand it out.
    assert not can_grant_permissions(member(ADMIN), int(Permission.ADMINISTRATOR))


def test_moderation_hierarchy():
    owner, admin, mod, plain = member(owner=True), member(ADMIN), member(MOD), member()
    assert can_moderate_member(admin, mod)
    assert can_moderate_member(mod, plain)
    assert not can_moderate_member(mod, admin)
    assert not can_moderate_member(mod, member(MOD))  # equal rank
    assert not can_moderate_member(admin, owner)  # nobody outranks the owner
    assert not can_moderate_member(owner, owner)
    assert can_moderate_member(owner, admin)
