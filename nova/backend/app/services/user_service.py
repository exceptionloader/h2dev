from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User
from app.schemas.user import UpdateProfileRequest


async def update_profile(db: AsyncSession, user: User, data: UpdateProfileRequest) -> User:
    values = data.model_dump(exclude_unset=True)
    if values.get("display_name"):
        user.display_name = values["display_name"]
    if values.get("status"):
        user.status = values["status"]
    if "custom_status" in values:
        user.custom_status = values["custom_status"] or None
    await db.flush()
    return user
