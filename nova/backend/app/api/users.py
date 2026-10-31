from fastapi import APIRouter, status

from app.api.deps import DB, Auth
from app.schemas.user import ChangePasswordRequest, UpdateProfileRequest, UserMe
from app.services import auth_service, user_service

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserMe)
async def me(auth: Auth):
    return auth.user


@router.patch("/me", response_model=UserMe)
async def update_me(data: UpdateProfileRequest, auth: Auth, db: DB):
    user = await user_service.update_profile(db, auth.user, data)
    await db.commit()
    return user


@router.post("/me/password", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(data: ChangePasswordRequest, auth: Auth, db: DB):
    await auth_service.change_password(
        db, auth.user, data.current_password, data.new_password, keep_session_id=auth.session.id
    )
    await db.commit()
