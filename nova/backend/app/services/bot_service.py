"""Bot applications, tokens and server installations."""

import re
import secrets
import uuid

from sqlalchemy import func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import permissions as perms
from app.core.errors import AppError, Conflict, Forbidden, NotFound
from app.core.permissions import Permission
from app.core.security import hash_token
from app.models import BotApplication, BotInstallation, MemberRole, ServerRole, User
from app.realtime.hub import public_user
from app.schemas.social import BotOut
from app.services import audit_service, member_service
from app.services.server_service import ServerAccess

MAX_BOTS_PER_USER = 5
FIRST_PARTY_NAME = "NOVA Bot"


def new_token() -> str:
    return "nb_" + secrets.token_urlsafe(32)


async def _unique_username(db: AsyncSession, name: str) -> str:
    base = re.sub(r"[^a-z0-9_]", "", name.lower().replace(" ", "_"))[:20] or "bot"
    for _ in range(20):
        candidate = f"{base}_bot{secrets.randbelow(10000):04d}"
        if not await db.scalar(select(User.id).where(User.username == candidate)):
            return candidate
    raise AppError("Could not create bot user", code="bot_username")


async def _create_bot_user(db: AsyncSession, name: str, username: str | None = None) -> User:
    user = User(
        username=username or await _unique_username(db, name),
        email=f"{uuid.uuid4()}@bots.nova.invalid",
        display_name=name,
        password_hash="!",  # noqa: S106 - unusable: bots never log in with a password
        is_bot=True,
    )
    db.add(user)
    await db.flush()
    return user


def bot_out(app: BotApplication, user: User) -> BotOut:
    return BotOut(
        id=app.id,
        name=app.name,
        description=app.description,
        is_public=app.is_public,
        is_first_party=app.is_first_party,
        user=public_user(user),
        created_at=app.created_at,
    )


async def create_bot(db: AsyncSession, owner: User, data) -> tuple[BotApplication, User, str]:  # noqa: ANN001
    count = await db.scalar(select(func.count()).where(BotApplication.owner_id == owner.id))
    if (count or 0) >= MAX_BOTS_PER_USER:
        raise AppError("Bot limit reached", code="bot_limit_reached")
    user = await _create_bot_user(db, data.name)
    token = new_token()
    app = BotApplication(
        owner_id=owner.id,
        bot_user_id=user.id,
        name=data.name,
        description=data.description,
        token_hash=hash_token(token),
        is_public=data.is_public,
    )
    db.add(app)
    await db.flush()
    return app, user, token


async def _own_bot(db: AsyncSession, owner: User, bot_id: uuid.UUID) -> tuple[BotApplication, User]:
    app = await db.get(BotApplication, bot_id)
    if app is None or app.owner_id != owner.id:
        raise NotFound("Bot not found", code="bot_not_found")
    user = await db.get(User, app.bot_user_id)
    assert user is not None
    return app, user


async def list_my_bots(db: AsyncSession, owner: User) -> list[tuple[BotApplication, User]]:
    rows = await db.execute(
        select(BotApplication, User)
        .join(User, User.id == BotApplication.bot_user_id)
        .where(BotApplication.owner_id == owner.id)
        .order_by(BotApplication.created_at)
    )
    return [(a, u) for a, u in rows]


async def update_bot(db: AsyncSession, owner: User, bot_id: uuid.UUID, values: dict):
    app, user = await _own_bot(db, owner, bot_id)
    if values.get("name"):
        app.name = user.display_name = values["name"]
    if "description" in values:
        app.description = values["description"] or None
    if values.get("is_public") is not None:
        app.is_public = values["is_public"]
    await db.flush()
    return app, user


async def regenerate_token(
    db: AsyncSession, owner: User, bot_id: uuid.UUID
) -> tuple[BotApplication, User, str]:
    app, user = await _own_bot(db, owner, bot_id)
    token = new_token()
    app.token_hash = hash_token(token)
    await db.flush()
    return app, user, token


async def delete_bot(db: AsyncSession, owner: User, bot_id: uuid.UUID) -> User:
    app, user = await _own_bot(db, owner, bot_id)
    # Deleting the bot user cascades to the application, memberships and installations.
    await db.delete(user)
    await db.flush()
    return user


async def available_bots(db: AsyncSession, access: ServerAccess, actor: User):
    installed = set(
        await db.scalars(
            select(BotInstallation.bot_id).where(BotInstallation.server_id == access.server.id)
        )
    )
    rows = await db.execute(
        select(BotApplication, User)
        .join(User, User.id == BotApplication.bot_user_id)
        .where(or_(BotApplication.is_public.is_(True), BotApplication.owner_id == actor.id))
        .order_by(BotApplication.is_first_party.desc(), BotApplication.name)
    )
    return [(a, u, a.id in installed) for a, u in rows]


async def installations(db: AsyncSession, access: ServerAccess):
    rows = await db.execute(
        select(BotInstallation, BotApplication, User)
        .join(BotApplication, BotApplication.id == BotInstallation.bot_id)
        .join(User, User.id == BotApplication.bot_user_id)
        .where(BotInstallation.server_id == access.server.id)
    )
    return list(rows)


async def install(
    db: AsyncSession, access: ServerAccess, actor: User, bot_id: uuid.UUID, names: list[str]
) -> BotInstallation:
    access.require(Permission.MANAGE_BOTS)
    app = await db.get(BotApplication, bot_id)
    if app is None or not (app.is_public or app.owner_id == actor.id):
        raise NotFound("Bot not found", code="bot_not_found")
    bits = member_service.to_bits(names)
    if bits & Permission.ADMINISTRATOR:
        raise AppError("Bots cannot be administrators", code="escalation")
    # A bot can never receive more power than the person installing it.
    if not perms.can_grant_permissions(access.ctx, bits):
        raise Forbidden("You cannot grant permissions you do not have", code="escalation")
    if await db.scalar(
        select(BotInstallation.id).where(
            BotInstallation.bot_id == app.id, BotInstallation.server_id == access.server.id
        )
    ):
        raise Conflict("Bot already installed", code="bot_already_installed")
    bot_user = await db.get(User, app.bot_user_id)
    assert bot_user is not None
    member = await member_service.add_member(db, access.server, bot_user, via="bot_install")
    await db.execute(
        update(ServerRole)
        .where(ServerRole.server_id == access.server.id, ServerRole.is_default.is_(False))
        .values(position=ServerRole.position + 1)
    )
    role = ServerRole(
        server_id=access.server.id,
        name=f"{app.name}"[:28] + " ⚙",
        position=1,
        permissions=bits,
        managed=True,
        color="#94A3B8",
    )
    db.add(role)
    await db.flush()
    db.add(MemberRole(member_id=member.id, role_id=role.id))
    installation = BotInstallation(
        bot_id=app.id,
        server_id=access.server.id,
        role_id=role.id,
        installed_by=actor.id,
        permissions=bits,
    )
    db.add(installation)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="bot.install",
        target_type="bot",
        target_id=app.id,
        details={"name": app.name, "permissions": perms.permission_names(bits)},
    )
    await db.flush()
    return installation


async def uninstall(
    db: AsyncSession, access: ServerAccess, actor: User, bot_id: uuid.UUID
) -> uuid.UUID:
    access.require(Permission.MANAGE_BOTS)
    row = (
        await db.execute(
            select(BotInstallation, BotApplication)
            .join(BotApplication, BotApplication.id == BotInstallation.bot_id)
            .where(BotInstallation.bot_id == bot_id, BotInstallation.server_id == access.server.id)
        )
    ).first()
    if row is None:
        raise NotFound("Bot not installed", code="bot_not_found")
    installation, app = row
    await member_service.remove_member(db, access.server.id, app.bot_user_id)
    audit_service.record(
        db,
        server_id=access.server.id,
        actor_id=actor.id,
        action="bot.uninstall",
        target_type="bot",
        target_id=app.id,
        details={"name": app.name},
    )
    await db.flush()
    return app.bot_user_id


async def ensure_first_party_bot(db: AsyncSession, token: str) -> BotApplication:
    """Create or update the first-party bot so that ``token`` authenticates it."""
    app = await db.scalar(select(BotApplication).where(BotApplication.is_first_party.is_(True)))
    if app is None:
        existing = await db.scalar(select(User.id).where(User.username == "novabot"))
        user = await _create_bot_user(db, FIRST_PARTY_NAME, None if existing else "novabot")
        app = BotApplication(
            owner_id=None,
            bot_user_id=user.id,
            name=FIRST_PARTY_NAME,
            description="Würfeln, Münzwurf, Teams auslosen und offene Sessions anzeigen.",
            token_hash=hash_token(token),
            is_public=True,
            is_first_party=True,
        )
        db.add(app)
    else:
        app.token_hash = hash_token(token)
    await db.flush()
    return app
