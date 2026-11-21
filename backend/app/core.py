from dataclasses import dataclass
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from firebase_admin import auth
from firebase_admin.exceptions import FirebaseError
from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert
from starlette.concurrency import run_in_threadpool
from app.database.v1 import users

bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class Identity:
    uid: str
    email: str
    name: str
    verified: bool
    auth_time: int = 0


async def identity(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
    if credentials is None:
        raise HTTPException(401, "Bearer token required", headers={"WWW-Authenticate": "Bearer"})
    try:
        claims = await run_in_threadpool(auth.verify_id_token, credentials.credentials,
                                         app=request.app.state.firebase, check_revoked=True)
    except (ValueError, auth.InvalidIdTokenError, auth.RevokedIdTokenError, auth.UserDisabledError, auth.UserNotFoundError):
        raise HTTPException(401, "Invalid session", headers={"WWW-Authenticate": "Bearer"}) from None
    except FirebaseError:
        raise HTTPException(503, "Identity service unavailable") from None
    if claims.get("email_verified") is not True or not claims.get("email"):
        raise HTTPException(403, "Verified email required")
    if claims["uid"] not in request.app.state.settings.test_account_uids:
        raise HTTPException(403, "Test account required")
    return Identity(claims["uid"], claims["email"], claims.get("name") or "", True, claims.get("auth_time", 0))


async def database(request: Request):
    async with request.app.state.sessions.begin() as session:
        yield session


async def account(who=Depends(identity), db=Depends(database)):
    query = insert(users).values(firebase_uid=who.uid, display_name=who.name[:256], email=who.email,
                                 email_verified=who.verified)
    query = query.on_conflict_do_update(index_elements=[users.c.firebase_uid],
        set_={"email": who.email, "email_verified": who.verified, "updated_at": func.now()})
    user = (await db.execute(query.returning(users))).mappings().one()
    if user.account_status != "ACTIVE":
        raise HTTPException(403, "Account unavailable")
    return user
