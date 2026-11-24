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
    phone: str = ""
    phone_verified: bool = False
    # Firebase custom claim {"admin": true}, set by scripts/set_admin_claim.py; gates /api/admin only.
    admin: bool = False


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
    email_ok = claims.get("email_verified") is True and bool(claims.get("email"))
    # Firebase only sets phone_number once the number has been confirmed by OTP.
    phone = claims.get("phone_number") or ""
    if not email_ok and not phone:
        raise HTTPException(403, "Verified email or phone required")
    settings = request.app.state.settings
    if not getattr(settings, "open_access", False) and claims["uid"] not in settings.test_account_uids:
        raise HTTPException(403, "Test account required")
    return Identity(claims["uid"], claims["email"] if email_ok else "", claims.get("name") or "", email_ok,
                    claims.get("auth_time", 0), phone, bool(phone), claims.get("admin") is True)


async def database(request: Request):
    async with request.app.state.sessions.begin() as session:
        yield session


async def account(who=Depends(identity), db=Depends(database)):
    query = insert(users).values(firebase_uid=who.uid, display_name=who.name[:256], email=who.email,
                                 email_verified=who.verified, phone_verified=who.phone_verified,
                                 phone_number=who.phone or None)
    query = query.on_conflict_do_update(index_elements=[users.c.firebase_uid],
        set_={"email": who.email, "email_verified": who.verified, "phone_verified": who.phone_verified,
              # Keep a number the user edited on screen 14; fill it from the verified phone otherwise.
              "phone_number": func.coalesce(users.c.phone_number, query.excluded.phone_number),
              "updated_at": func.now()})
    user = (await db.execute(query.returning(users))).mappings().one()
    if user.account_status != "ACTIVE":
        raise HTTPException(403, "Account unavailable")
    return user
