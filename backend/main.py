from __future__ import annotations

import hashlib
import json
import os
import base64
import secrets
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from datetime import datetime, timedelta, timezone
from typing import Generator
from uuid import uuid4

import jwt
from fastapi import Depends, FastAPI, HTTPException, Query, status
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from pwdlib import PasswordHash
from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, create_engine, select, inspect, update
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker
from sqlalchemy.exc import IntegrityError

APP_VERSION = "0.1.0"
JWT_SECRET = os.getenv("JWT_SECRET")
if not JWT_SECRET or len(JWT_SECRET) < 32:
    raise RuntimeError("Set JWT_SECRET to a random secret of at least 32 characters before starting ASSAF API.")

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./assaf_local.db")
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql+psycopg://", 1)
elif DATABASE_URL.startswith("postgresql://"):
    DATABASE_URL = DATABASE_URL.replace("postgresql://", "postgresql+psycopg://", 1)

engine_options = {"pool_pre_ping": True}
if DATABASE_URL.startswith("sqlite"):
    engine_options["connect_args"] = {"check_same_thread": False}
engine = create_engine(DATABASE_URL, **engine_options)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
password_hash = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(512))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    is_banned: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    ban_reason: Mapped[str | None] = mapped_column(String(500), nullable=True)
    full_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    whatsapp_number: Mapped[str | None] = mapped_column(String(16), nullable=True, index=True)
    device_id_hash: Mapped[str | None] = mapped_column(String(64), unique=True, nullable=True, index=True)
    device_public_key: Mapped[str | None] = mapped_column(Text, nullable=True)


class SupportMessage(Base):
    __tablename__ = "support_messages"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    category: Mapped[str] = mapped_column(String(20), default="contact")
    message: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(20), default="new", index=True)
    admin_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)
    guest_token_hash: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)




class SupportReply(Base):
    __tablename__ = "support_replies"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    message_id: Mapped[str] = mapped_column(ForeignKey("support_messages.id", ondelete="CASCADE"), index=True)
    sender: Mapped[str] = mapped_column(String(10))  # user or owner
    message: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)


class AppSetting(Base):
    __tablename__ = "app_settings"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    package_name: Mapped[str] = mapped_column(String(255), primary_key=True)
    blocked: Mapped[bool] = mapped_column(Boolean, default=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class Schedule(Base):
    __tablename__ = "schedules"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    package_name: Mapped[str] = mapped_column(String(255))
    start_time: Mapped[str] = mapped_column(String(5))
    end_time: Mapped[str] = mapped_column(String(5))
    days: Mapped[str] = mapped_column(String(32), default="mon,tue,wed,thu,fri,sat,sun")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    timezone_name: Mapped[str] = mapped_column(String(64), default="UTC")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Subscription(Base):
    __tablename__ = "subscriptions"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    plan: Mapped[str] = mapped_column(String(32), default="free")
    status: Mapped[str] = mapped_column(String(32), default="inactive")
    provider: Mapped[str] = mapped_column(String(32), default="none")
    provider_reference: Mapped[str | None] = mapped_column(String(255), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class DeviceChallenge(Base):
    __tablename__ = "device_challenges"
    challenge: Mapped[str] = mapped_column(String(128), primary_key=True)
    email: Mapped[str] = mapped_column(String(320), index=True)
    device_id_hash: Mapped[str] = mapped_column(String(64), index=True)
    purpose: Mapped[str] = mapped_column(String(20), default="user")
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


class RefreshSession(Base):
    __tablename__ = "refresh_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    device_id_hash: Mapped[str] = mapped_column(String(64), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class OwnerDevice(Base):
    __tablename__ = "owner_device"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, default=1)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    device_id_hash: Mapped[str] = mapped_column(String(64), unique=True)
    public_key: Mapped[str] = mapped_column(Text)
    recovery_hash: Mapped[str] = mapped_column(String(64))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class OwnerRefreshSession(Base):
    __tablename__ = "owner_refresh_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    email: Mapped[str] = mapped_column(String(320), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    device_id_hash: Mapped[str] = mapped_column(String(64), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class SecurityAudit(Base):
    __tablename__ = "security_audit"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    actor: Mapped[str] = mapped_column(String(320), index=True)
    action: Mapped[str] = mapped_column(String(64), index=True)
    device_id_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)


Base.metadata.create_all(bind=engine)

# create_all does not alter existing tables; add new columns and uniqueness safely for existing installs.
_user_columns = {column["name"] for column in inspect(engine).get_columns("users")}
with engine.begin() as _connection:
    if "is_banned" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN is_banned BOOLEAN NOT NULL DEFAULT FALSE")
    if "ban_reason" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN ban_reason VARCHAR(500)")
    if "full_name" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN full_name VARCHAR(120)")
    if "whatsapp_number" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN whatsapp_number VARCHAR(16)")
    if "device_id_hash" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN device_id_hash VARCHAR(64)")
    if "device_public_key" not in _user_columns:
        _connection.exec_driver_sql("ALTER TABLE users ADD COLUMN device_public_key TEXT")
    _connection.exec_driver_sql("CREATE UNIQUE INDEX IF NOT EXISTS ix_users_device_id_hash ON users (device_id_hash)")

_support_columns = {column["name"] for column in inspect(engine).get_columns("support_messages")}
if "guest_token_hash" not in _support_columns:
    with engine.begin() as _connection:
        _connection.exec_driver_sql("ALTER TABLE support_messages ADD COLUMN guest_token_hash VARCHAR(64)")

app = FastAPI(
    title="ASSAF API",
    description="Backend API for ASSAF app settings, schedules, accounts, and subscription status.",
    version=APP_VERSION,
)

allowed_origins = [x.strip() for x in os.getenv("CORS_ORIGINS", "*").split(",") if x.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=False if "*" in allowed_origins else True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)


class DeviceChallengeRequest(BaseModel):
    email: EmailStr
    device_id: str = Field(min_length=16, max_length=128)
    purpose: str = Field(default="user", pattern=r"^(user|owner)$")


class RegisterRequest(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)
    email: EmailStr
    password: str = Field(min_length=10, max_length=128)
    whatsapp_number: str = Field(pattern=r"^\+[1-9]\d{7,14}$")
    device_id: str = Field(min_length=16, max_length=128)
    device_public_key: str = Field(min_length=80, max_length=2048)
    challenge: str = Field(min_length=32, max_length=128)
    signature: str = Field(min_length=40, max_length=512)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)
    device_id: str = Field(min_length=16, max_length=128)
    device_public_key: str = Field(min_length=80, max_length=2048)
    challenge: str = Field(min_length=32, max_length=128)
    signature: str = Field(min_length=40, max_length=512)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int
    refresh_token: str | None = None


class RefreshRequest(BaseModel):
    refresh_token: str = Field(min_length=32, max_length=256)
    device_id: str = Field(min_length=16, max_length=128)


class UserResponse(BaseModel):
    id: str
    email: EmailStr
    full_name: str | None = None
    whatsapp_number: str | None = None
    created_at: datetime
    is_banned: bool = False
    ban_reason: str | None = None
    model_config = ConfigDict(from_attributes=True)


class SupportMessageInput(BaseModel):
    message: str = Field(min_length=5, max_length=4000)
    guest_token: str | None = Field(default=None, min_length=32, max_length=128)


class BanRequest(BaseModel):
    banned: bool
    reason: str | None = Field(default=None, max_length=500)


class SupportReplyInput(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    guest_token: str | None = Field(default=None, min_length=32, max_length=128)


class SupportDecision(BaseModel):
    status: str = Field(pattern=r"^(accepted|rejected|answered)$")
    admin_note: str | None = Field(default=None, max_length=2000)


class SettingInput(BaseModel):
    package_name: str = Field(min_length=3, max_length=255, pattern=r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")
    blocked: bool


class SettingResponse(SettingInput):
    updated_at: datetime
    model_config = ConfigDict(from_attributes=True)


class ScheduleInput(BaseModel):
    package_name: str = Field(min_length=3, max_length=255, pattern=r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")
    start_time: str = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    end_time: str = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    days: list[str] = Field(default_factory=lambda: ["mon", "tue", "wed", "thu", "fri", "sat", "sun"])
    enabled: bool = True
    timezone_name: str = Field(default="UTC", min_length=1, max_length=64)


class ScheduleResponse(BaseModel):
    id: str
    package_name: str
    start_time: str
    end_time: str
    days: list[str]
    enabled: bool
    timezone_name: str
    created_at: datetime


class SubscriptionResponse(BaseModel):
    plan: str
    status: str
    provider: str
    updated_at: datetime


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def verify_device_proof(db: Session, email: str, device_id: str, challenge: str, signature: str, supplied_public_key: str, purpose: str = "user", expected_public_key: str | None = None) -> str:
    device_hash = hashlib.sha256(device_id.encode("utf-8")).hexdigest()
    row = db.get(DeviceChallenge, challenge)
    now = datetime.now(timezone.utc)
    if row is None or row.email != email.lower() or row.device_id_hash != device_hash or row.purpose != purpose:
        raise HTTPException(status_code=401, detail="Device security challenge is invalid")
    expiry = row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    if expiry <= now:
        db.delete(row)
        db.commit()
        raise HTTPException(status_code=401, detail="Device security challenge expired")
    if expected_public_key and not secrets.compare_digest(expected_public_key, supplied_public_key):
        raise HTTPException(status_code=403, detail="This account is linked to a different device key")
    try:
        der = base64.b64decode(supplied_public_key, validate=True)
        public_key = serialization.load_der_public_key(der)
        if not isinstance(public_key, ec.EllipticCurvePublicKey) or public_key.curve.name != "secp256r1":
            raise ValueError("Unsupported key")
        public_key.verify(base64.b64decode(signature, validate=True), challenge.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    except Exception:
        raise HTTPException(status_code=401, detail="Device proof could not be verified")
    db.delete(row)
    db.commit()
    return supplied_public_key


def create_token(user: User, db: Session) -> TokenResponse:
    expires = datetime.now(timezone.utc) + timedelta(hours=12)
    token = jwt.encode({"sub": user.id, "email": user.email, "device_id_hash": user.device_id_hash, "exp": expires}, JWT_SECRET, algorithm="HS256")
    refresh = secrets.token_urlsafe(48)
    db.add(RefreshSession(
        user_id=user.id,
        token_hash=hashlib.sha256(refresh.encode("utf-8")).hexdigest(),
        device_id_hash=user.device_id_hash or "",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    ))
    db.commit()
    return TokenResponse(access_token=token, expires_in=12 * 60 * 60, refresh_token=refresh)


def current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: Session = Depends(get_db),
) -> User:
    if credentials is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=["HS256"])
        user_id = payload.get("sub")
    except jwt.PyJWTError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired token")
    user = db.get(User, user_id) if user_id else None
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    if not user.device_id_hash or payload.get("device_id_hash") != user.device_id_hash:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="This session is not registered to this device. Sign in again on your registered phone.")
    return user


def active_user(user: User = Depends(current_user)) -> User:
    if user.is_banned:
        raise HTTPException(status_code=403, detail={"code": "ACCOUNT_BANNED", "message": "This account is banned. Contact support or submit an appeal."})
    return user


def optional_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(get_db)) -> User | None:
    if credentials is None:
        return None
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=["HS256"])
        user_id = payload.get("sub")
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.get(User, user_id) if user_id else None
    if user is None:
        raise HTTPException(status_code=401, detail="User not found")
    if not user.device_id_hash or payload.get("device_id_hash") != user.device_id_hash:
        raise HTTPException(status_code=401, detail="This session is not registered to this device.")
    return user


@app.get("/", tags=["system"])
def root():
    return {"service": "ASSAF API", "version": APP_VERSION, "status": "ok", "docs": "/docs"}


@app.head("/", include_in_schema=False, tags=["system"])
def root_head():
    # Platform HEAD probes should receive 200 instead of the default 405.
    return None


@app.get("/health", tags=["system"])
def health():
    return {"status": "ok", "service": "assaf-api", "version": APP_VERSION}


@app.post("/v1/auth/challenge", tags=["auth"])
def issue_device_challenge(body: DeviceChallengeRequest, db: Session = Depends(get_db)):
    email = str(body.email).lower()
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    challenge = secrets.token_urlsafe(48)
    db.add(DeviceChallenge(challenge=challenge, email=email, device_id_hash=device_hash, purpose=body.purpose, expires_at=datetime.now(timezone.utc) + timedelta(minutes=3)))
    db.query(DeviceChallenge).filter(DeviceChallenge.expires_at < datetime.now(timezone.utc)).delete(synchronize_session=False)
    db.commit()
    return {"challenge": challenge, "expires_in": 180}


@app.post("/v1/auth/register", response_model=TokenResponse, status_code=201, tags=["auth"])
def register(body: RegisterRequest, db: Session = Depends(get_db)):
    email = str(body.email).lower()
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    public_key = verify_device_proof(db, email, body.device_id, body.challenge, body.signature, body.device_public_key)
    if db.scalar(select(User).where(User.email == email)):
        raise HTTPException(status_code=409, detail="An account with this email already exists")
    if db.scalar(select(User).where(User.device_id_hash == device_hash)):
        raise HTTPException(status_code=409, detail="This phone already has an ASSAF account. Sign in to your existing account.")
    user = User(email=email, full_name=body.full_name.strip(), whatsapp_number=body.whatsapp_number, device_id_hash=device_hash, device_public_key=public_key, password_hash=password_hash.hash(body.password))
    db.add(user)
    db.add(SecurityAudit(actor=email, action="user_device_enrolled", device_id_hash=device_hash))
    db.flush()
    db.add(Subscription(user_id=user.id, plan="free", status="active", provider="none"))
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="This phone is already linked to an ASSAF account.")
    db.refresh(user)
    return create_token(user, db)


@app.post("/v1/auth/login", response_model=TokenResponse, tags=["auth"])
def login(body: LoginRequest, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    if user is None or not password_hash.verify(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Email or password is incorrect")
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    if user.device_id_hash and user.device_id_hash != device_hash:
        raise HTTPException(status_code=403, detail="This account is linked to another phone. Contact ASSAF owner to request a device change.")
    public_key = verify_device_proof(db, str(body.email).lower(), body.device_id, body.challenge, body.signature, body.device_public_key, expected_public_key=user.device_public_key)
    if not user.device_id_hash:
        user.device_id_hash = device_hash
    if not user.device_public_key:
        user.device_public_key = public_key
        db.add(SecurityAudit(actor=user.email, action="legacy_user_device_key_enrolled", device_id_hash=device_hash))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
            raise HTTPException(status_code=403, detail="This phone is already linked to another ASSAF account.")
    return create_token(user, db)


@app.post("/v1/auth/refresh", response_model=TokenResponse, tags=["auth"])
def refresh_user_session(body: RefreshRequest, db: Session = Depends(get_db)):
    token_hash = hashlib.sha256(body.refresh_token.encode("utf-8")).hexdigest()
    row = db.scalar(select(RefreshSession).where(RefreshSession.token_hash == token_hash))
    if row is None or row.revoked:
        raise HTTPException(status_code=401, detail="Refresh session is invalid or already used")
    now = datetime.now(timezone.utc)
    expiry = row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    if expiry <= now:
        row.revoked = True
        db.commit()
        raise HTTPException(status_code=401, detail="Refresh session expired; sign in again")
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    if not secrets.compare_digest(device_hash, row.device_id_hash):
        raise HTTPException(status_code=403, detail="Refresh session belongs to another device")
    user = db.get(User, row.user_id)
    if user is None or user.is_banned or user.device_id_hash != device_hash:
        row.revoked = True
        db.commit()
        raise HTTPException(status_code=403, detail="Account is unavailable or device binding changed")
    claim = db.execute(
        update(RefreshSession)
        .where(RefreshSession.id == row.id, RefreshSession.revoked.is_(False))
        .values(revoked=True)
    )
    if claim.rowcount != 1:
        db.rollback()
        raise HTTPException(status_code=401, detail="Refresh session was already rotated")
    db.commit()
    return create_token(user, db)


@app.get("/v1/me", response_model=UserResponse, tags=["auth"])
def me(user: User = Depends(current_user)):
    return user


@app.post("/v1/support/messages", status_code=201, tags=["support"])
def create_support_message(body: SupportMessageInput, user: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    if user is None and not body.guest_token:
        raise HTTPException(status_code=400, detail="Guest conversation token is required")
    guest_hash = hashlib.sha256(body.guest_token.encode("utf-8")).hexdigest() if user is None and body.guest_token else None
    row = SupportMessage(user_id=user.id if user else None, guest_token_hash=guest_hash, category="contact", message=body.message.strip())
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "status": row.status, "created_at": row.created_at.isoformat()}


@app.post("/v1/appeals", status_code=201, tags=["support"])
def submit_appeal(body: SupportMessageInput, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if not user.is_banned:
        raise HTTPException(status_code=409, detail="Appeals are only available for banned accounts")
    pending = db.scalar(select(SupportMessage).where(SupportMessage.user_id == user.id, SupportMessage.category == "appeal", SupportMessage.status == "pending"))
    if pending:
        raise HTTPException(status_code=409, detail="You already have a pending appeal")
    row = SupportMessage(user_id=user.id, category="appeal", message=body.message.strip(), status="pending")
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "status": row.status, "created_at": row.created_at.isoformat()}


@app.get("/v1/settings", response_model=list[SettingResponse], tags=["settings"])
def list_settings(user: User = Depends(active_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(AppSetting).where(AppSetting.user_id == user.id).order_by(AppSetting.package_name)))


@app.put("/v1/settings", response_model=SettingResponse, tags=["settings"])
def save_setting(body: SettingInput, user: User = Depends(active_user), db: Session = Depends(get_db)):
    setting = db.get(AppSetting, (user.id, body.package_name))
    if setting is None:
        setting = AppSetting(user_id=user.id, package_name=body.package_name, blocked=body.blocked)
        db.add(setting)
    else:
        setting.blocked = body.blocked
        setting.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(setting)
    return setting


@app.get("/v1/schedules", response_model=list[ScheduleResponse], tags=["schedules"])
def list_schedules(user: User = Depends(active_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(Schedule).where(Schedule.user_id == user.id).order_by(Schedule.created_at.desc()))
    return [{**{k: getattr(row, k) for k in ("id", "package_name", "start_time", "end_time", "enabled", "timezone_name", "created_at")}, "days": row.days.split(",")} for row in rows]


@app.post("/v1/schedules", response_model=ScheduleResponse, status_code=201, tags=["schedules"])
def create_schedule(body: ScheduleInput, user: User = Depends(active_user), db: Session = Depends(get_db)):
    valid_days = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"}
    days = sorted(set(body.days), key=lambda d: ["mon", "tue", "wed", "thu", "fri", "sat", "sun"].index(d)) if all(d in valid_days for d in body.days) else None
    if days is None or not days:
        raise HTTPException(status_code=422, detail="days must contain one or more values: mon, tue, wed, thu, fri, sat, sun")
    row = Schedule(user_id=user.id, package_name=body.package_name, start_time=body.start_time, end_time=body.end_time, days=",".join(days), enabled=body.enabled, timezone_name=body.timezone_name)
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "package_name": row.package_name, "start_time": row.start_time, "end_time": row.end_time, "days": row.days.split(","), "enabled": row.enabled, "timezone_name": row.timezone_name, "created_at": row.created_at}


@app.delete("/v1/schedules/{schedule_id}", status_code=204, tags=["schedules"])
def delete_schedule(schedule_id: str, user: User = Depends(active_user), db: Session = Depends(get_db)):
    row = db.scalar(select(Schedule).where(Schedule.id == schedule_id, Schedule.user_id == user.id))
    if row is None:
        raise HTTPException(status_code=404, detail="Schedule not found")
    db.delete(row)
    db.commit()
    return None


@app.get("/v1/subscription", response_model=SubscriptionResponse, tags=["subscription"])
def subscription(user: User = Depends(active_user), db: Session = Depends(get_db)):
    row = db.get(Subscription, user.id)
    if row is None:
        row = Subscription(user_id=user.id, plan="free", status="active", provider="none")
        db.add(row)
        db.commit()
        db.refresh(row)
    return row


# Owner-only analytics and privacy-conscious app telemetry.
# Configure OWNER_ADMIN_EMAIL and OWNER_ADMIN_PASSWORD as Render secrets.
import secrets
from sqlalchemy import func

OWNER_ADMIN_EMAIL = os.getenv("OWNER_ADMIN_EMAIL", "").strip().lower()
OWNER_ADMIN_PASSWORD = os.getenv("OWNER_ADMIN_PASSWORD", "")

class InstallRecord(Base):
    __tablename__ = "install_records"
    install_id: Mapped[str] = mapped_column(String(80), primary_key=True)
    app_version: Mapped[str] = mapped_column(String(40), default="unknown")
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)

Base.metadata.create_all(bind=engine)

class OwnerLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=256)
    device_id: str = Field(min_length=16, max_length=128)
    device_public_key: str = Field(min_length=80, max_length=2048)
    challenge: str = Field(min_length=32, max_length=128)
    signature: str = Field(min_length=40, max_length=512)


class OwnerRecoveryRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=256)
    recovery_code: str = Field(min_length=24, max_length=128)
    device_id: str = Field(min_length=16, max_length=128)
    device_public_key: str = Field(min_length=80, max_length=2048)
    challenge: str = Field(min_length=32, max_length=128)
    signature: str = Field(min_length=40, max_length=512)

class TelemetryPing(BaseModel):
    install_id: str = Field(min_length=12, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    app_version: str = Field(default="unknown", max_length=40)

def owner_token(email: str, device_hash: str, db: Session) -> TokenResponse:
    expires = datetime.now(timezone.utc) + timedelta(hours=4)
    token = jwt.encode({"sub": "owner:" + email, "email": email, "role": "owner", "device_id_hash": device_hash, "exp": expires}, JWT_SECRET, algorithm="HS256")
    refresh = secrets.token_urlsafe(48)
    db.add(OwnerRefreshSession(
        email=email,
        token_hash=hashlib.sha256(refresh.encode("utf-8")).hexdigest(),
        device_id_hash=device_hash,
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    ))
    db.commit()
    return TokenResponse(access_token=token, expires_in=4 * 60 * 60, refresh_token=refresh)

def current_owner(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(get_db)) -> dict:
    if credentials is None:
        raise HTTPException(status_code=401, detail="Owner login required")
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=["HS256"])
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired owner session")
    if payload.get("role") != "owner" or not str(payload.get("sub", "")).startswith("owner:"):
        raise HTTPException(status_code=403, detail="Owner access only")
    device = db.scalar(select(OwnerDevice).where(OwnerDevice.email == payload.get("email")))
    if device is None or payload.get("device_id_hash") != device.device_id_hash:
        raise HTTPException(status_code=401, detail="Owner session is not bound to the registered owner device")
    return payload

@app.post("/v1/admin/refresh", tags=["owner"])
def refresh_owner_session(body: RefreshRequest, db: Session = Depends(get_db)):
    token_hash = hashlib.sha256(body.refresh_token.encode("utf-8")).hexdigest()
    row = db.scalar(select(OwnerRefreshSession).where(OwnerRefreshSession.token_hash == token_hash))
    if row is None or row.revoked:
        raise HTTPException(status_code=401, detail="Owner refresh session is invalid or already used")
    now = datetime.now(timezone.utc)
    expiry = row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    if expiry <= now:
        row.revoked = True
        db.commit()
        raise HTTPException(status_code=401, detail="Owner refresh session expired; sign in again")
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    owner_device = db.scalar(select(OwnerDevice).where(OwnerDevice.email == row.email))
    if owner_device is None or owner_device.device_id_hash != device_hash or row.device_id_hash != device_hash:
        row.revoked = True
        db.commit()
        raise HTTPException(status_code=403, detail="Owner refresh session belongs to a different device")
    claim = db.execute(
        update(OwnerRefreshSession)
        .where(OwnerRefreshSession.id == row.id, OwnerRefreshSession.revoked.is_(False))
        .values(revoked=True)
    )
    if claim.rowcount != 1:
        db.rollback()
        raise HTTPException(status_code=401, detail="Owner refresh session was already rotated")
    db.commit()
    return owner_token(row.email, device_hash, db)


@app.get("/v1/admin/users", tags=["owner"])
def owner_users(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    rows = db.scalars(select(User).order_by(User.created_at.desc()).limit(500))
    return [{"id": row.id, "email": row.email, "full_name": row.full_name, "whatsapp_number": row.whatsapp_number, "whatsapp_url": ("https://wa.me/" + row.whatsapp_number.lstrip("+")) if row.whatsapp_number else None, "created_at": row.created_at.isoformat(), "is_banned": row.is_banned, "ban_reason": row.ban_reason} for row in rows]


@app.patch("/v1/admin/users/{user_id}/ban", tags=["owner"])
def owner_set_ban(user_id: str, body: BanRequest, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    user.is_banned = body.banned
    user.ban_reason = ((body.reason or "").strip()[:500] or None) if body.banned else None
    db.commit()
    return {"id": user.id, "email": user.email, "is_banned": user.is_banned, "ban_reason": user.ban_reason}


@app.get("/v1/admin/support", tags=["owner"])
def owner_support(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    rows = db.scalars(select(SupportMessage).order_by(SupportMessage.created_at.desc()).limit(300))
    result = []
    for row in rows:
        user = db.get(User, row.user_id) if row.user_id else None
        result.append({"id": row.id, "user_id": row.user_id, "email": user.email if user else None, "category": row.category, "message": row.message, "status": row.status, "admin_note": row.admin_note, "created_at": row.created_at.isoformat()})
    return result




@app.get("/v1/support/messages", tags=["support"])
def user_support_messages(user: User | None = Depends(optional_user), guest_token: str | None = Query(default=None, min_length=32, max_length=128), db: Session = Depends(get_db)):
    if user is not None:
        message_filter = SupportMessage.user_id == user.id
    elif guest_token:
        message_filter = SupportMessage.guest_token_hash == hashlib.sha256(guest_token.encode("utf-8")).hexdigest()
    else:
        raise HTTPException(status_code=401, detail="Guest conversation token is required")
    rows = db.scalars(select(SupportMessage).where(message_filter).order_by(SupportMessage.created_at.desc()).limit(100))
    result = []
    for row in rows:
        replies = db.scalars(select(SupportReply).where(SupportReply.message_id == row.id).order_by(SupportReply.created_at.asc()))
        result.append({"id": row.id, "category": row.category, "message": row.message, "status": row.status, "created_at": row.created_at.isoformat(), "replies": [{"id": x.id, "sender": x.sender, "message": x.message, "created_at": x.created_at.isoformat()} for x in replies]})
    return result


@app.post("/v1/support/{message_id}/replies", status_code=201, tags=["support"])
def user_support_reply(message_id: str, body: SupportReplyInput, user: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    row = db.get(SupportMessage, message_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    if user is not None:
        authorized = row.user_id == user.id
    else:
        supplied_hash = hashlib.sha256(body.guest_token.encode("utf-8")).hexdigest() if body.guest_token else None
        authorized = row.user_id is None and supplied_hash is not None and row.guest_token_hash == supplied_hash
    if not authorized:
        raise HTTPException(status_code=404, detail="Conversation not found")
    reply = SupportReply(message_id=row.id, sender="user", message=body.message.strip())
    db.add(reply)
    row.status = "new"
    db.commit()
    db.refresh(reply)
    return {"id": reply.id, "sender": reply.sender, "message": reply.message, "created_at": reply.created_at.isoformat()}


@app.get("/v1/admin/support/{message_id}/replies", tags=["owner"])
def owner_support_replies(message_id: str, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    row = db.get(SupportMessage, message_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    replies = db.scalars(select(SupportReply).where(SupportReply.message_id == message_id).order_by(SupportReply.created_at.asc()))
    return [{"id": x.id, "sender": x.sender, "message": x.message, "created_at": x.created_at.isoformat()} for x in replies]


@app.post("/v1/admin/support/{message_id}/replies", status_code=201, tags=["owner"])
def owner_support_reply(message_id: str, body: SupportReplyInput, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    row = db.get(SupportMessage, message_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    reply = SupportReply(message_id=row.id, sender="owner", message=body.message.strip())
    db.add(reply)
    row.status = "answered"
    row.admin_note = None
    db.commit()
    db.refresh(reply)
    return {"id": reply.id, "sender": reply.sender, "message": reply.message, "created_at": reply.created_at.isoformat()}


@app.patch("/v1/admin/support/{message_id}", tags=["owner"])
def owner_decide_support(message_id: str, body: SupportDecision, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    row = db.get(SupportMessage, message_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Support message not found")
    row.status = body.status
    row.admin_note = (body.admin_note or "").strip() or None
    if row.category == "appeal" and body.status == "accepted" and row.user_id:
        user = db.get(User, row.user_id)
        if user:
            user.is_banned = False
            user.ban_reason = None
    db.commit()
    return {"id": row.id, "status": row.status, "account_unbanned": row.category == "appeal" and body.status == "accepted"}


@app.post("/v1/admin/login", tags=["owner"])
def owner_login(body: OwnerLoginRequest, db: Session = Depends(get_db)):
    if not OWNER_ADMIN_EMAIL or not OWNER_ADMIN_PASSWORD:
        raise HTTPException(status_code=503, detail="Owner login is not configured yet")
    supplied_email = str(body.email).strip().lower()
    if not secrets.compare_digest(supplied_email, OWNER_ADMIN_EMAIL) or not secrets.compare_digest(body.password, OWNER_ADMIN_PASSWORD):
        raise HTTPException(status_code=401, detail="Email or password is incorrect")
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    existing = db.scalar(select(OwnerDevice).where(OwnerDevice.email == supplied_email))
    expected_key = existing.public_key if existing else None
    public_key = verify_device_proof(db, supplied_email, body.device_id, body.challenge, body.signature, body.device_public_key, purpose="owner", expected_public_key=expected_key)
    if existing and existing.device_id_hash != device_hash:
        raise HTTPException(status_code=403, detail="Owner account is already linked to another phone. Use the one-time recovery procedure.")
    if existing is None:
        recovery_code = secrets.token_urlsafe(32)
        db.add(OwnerDevice(email=supplied_email, device_id_hash=device_hash, public_key=public_key, recovery_hash=hashlib.sha256(recovery_code.encode("utf-8")).hexdigest()))
        db.add(SecurityAudit(actor=supplied_email, action="owner_device_enrolled", device_id_hash=device_hash))
        db.commit()
        token = owner_token(supplied_email, device_hash, db)
        return {"access_token": token.access_token, "token_type": token.token_type, "expires_in": token.expires_in, "recovery_code": recovery_code, "recovery_code_notice": "Save this code offline. It is shown only once."}
    return owner_token(supplied_email, device_hash, db)


@app.post("/v1/admin/recover-device", tags=["owner"])
def owner_recover_device(body: OwnerRecoveryRequest, db: Session = Depends(get_db)):
    supplied_email = str(body.email).strip().lower()
    if not OWNER_ADMIN_EMAIL or not OWNER_ADMIN_PASSWORD or not secrets.compare_digest(supplied_email, OWNER_ADMIN_EMAIL) or not secrets.compare_digest(body.password, OWNER_ADMIN_PASSWORD):
        raise HTTPException(status_code=401, detail="Owner credentials are incorrect")
    existing = db.scalar(select(OwnerDevice).where(OwnerDevice.email == supplied_email))
    if existing is None:
        raise HTTPException(status_code=404, detail="Owner device is not enrolled yet")
    recovery_hash = hashlib.sha256(body.recovery_code.encode("utf-8")).hexdigest()
    if not secrets.compare_digest(recovery_hash, existing.recovery_hash):
        raise HTTPException(status_code=403, detail="Recovery code is invalid or already used")
    device_hash = hashlib.sha256(body.device_id.encode("utf-8")).hexdigest()
    public_key = verify_device_proof(db, supplied_email, body.device_id, body.challenge, body.signature, body.device_public_key, purpose="owner")
    new_recovery_code = secrets.token_urlsafe(32)
    existing.device_id_hash = device_hash
    existing.public_key = public_key
    existing.recovery_hash = hashlib.sha256(new_recovery_code.encode("utf-8")).hexdigest()
    existing.updated_at = datetime.now(timezone.utc)
    db.add(SecurityAudit(actor=supplied_email, action="owner_device_recovered", device_id_hash=device_hash))
    db.commit()
    token = owner_token(supplied_email, device_hash, db)
    return {"access_token": token.access_token, "token_type": token.token_type, "expires_in": token.expires_in, "recovery_code": new_recovery_code, "recovery_code_notice": "New recovery code. Previous code has been invalidated."}

@app.get("/v1/admin/overview", tags=["owner"])
def owner_overview(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    active_since = now - timedelta(minutes=5)
    day_since = now - timedelta(hours=24)
    total_users = db.query(User).count()
    total_installs = db.query(InstallRecord).count()
    active_installs = db.query(InstallRecord).filter(InstallRecord.last_seen >= active_since).count()
    seen_24h = db.query(InstallRecord).filter(InstallRecord.last_seen >= day_since).count()
    new_users_7d = db.query(User).filter(User.created_at >= now - timedelta(days=7)).count()
    latest = db.query(InstallRecord).order_by(InstallRecord.last_seen.desc()).limit(8).all()
    return {
        "generated_at": now.isoformat(),
        "metrics": {
            "registered_users": total_users,
            "reported_installs": total_installs,
            "active_devices_5m": active_installs,
            "devices_seen_24h": seen_24h,
            "new_accounts_7d": new_users_7d,
        },
        "latest_activity": [
            {"app_version": row.app_version, "last_seen": row.last_seen.isoformat()}
            for row in latest
        ],
        "notes": {
            "active_devices_5m": "Installations that pinged the API within the last five minutes; requires the app telemetry client.",
            "reported_installs": "Unique installation IDs reported by the app, not APK downloads from a store.",
        },
    }

@app.post("/v1/telemetry/ping", status_code=204, tags=["telemetry"])
def telemetry_ping(body: TelemetryPing, db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    row = db.get(InstallRecord, body.install_id)
    if row is None:
        row = InstallRecord(install_id=body.install_id, app_version=body.app_version, first_seen=now, last_seen=now)
        db.add(row)
    else:
        row.app_version = body.app_version
        row.last_seen = now
    db.commit()
    return None


# Detailed link intelligence. This endpoint deliberately does not fetch the submitted site,
# preventing user-controlled URLs from becoming an SSRF/proxy primitive.
import ipaddress
import socket
import urllib.error
import urllib.parse
import urllib.request

class LinkCheckRequest(BaseModel):
    url: str = Field(min_length=4, max_length=2048)


def _google_safe_browsing_lookup(url: str) -> dict:
    """
    Query Google Safe Browsing only when an API key is configured.
    The submitted URL is sent to Google when this integration is enabled.
    """
    api_key = os.getenv("GOOGLE_SAFE_BROWSING_API_KEY", "").strip()
    if not api_key:
        return {
            "status": "not_configured",
            "source": "Google Safe Browsing",
            "reason": "No API key configured; only local indicators were checked.",
        }
    endpoint = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" + urllib.parse.quote(api_key, safe="")
    payload = {
        "client": {"clientId": "assaf-firewall", "clientVersion": APP_VERSION},
        "threatInfo": {
            "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"],
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [{"url": url}],
        },
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "ASSAF-LinkCheck/1.0"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            result = json.loads(response.read(100000).decode("utf-8"))
        matches = result.get("matches") or []
        if matches:
            threat_types = sorted({str(match.get("threatType", "UNKNOWN")) for match in matches})
            return {"status": "malicious", "source": "Google Safe Browsing", "threat_types": threat_types}
        return {"status": "clean", "source": "Google Safe Browsing", "reason": "No match returned for this URL at lookup time."}
    except (OSError, ValueError, urllib.error.URLError, TimeoutError) as exc:
        return {"status": "unavailable", "source": "Google Safe Browsing", "reason": f"Lookup failed: {type(exc).__name__}."}


def _public_dns_records(hostname: str) -> list[str]:
    records: set[str] = set()
    try:
        for item in socket.getaddrinfo(hostname, None, type=socket.SOCK_STREAM):
            address = item[4][0].split("%", 1)[0]
            try:
                parsed = ipaddress.ip_address(address)
                if parsed.is_global:
                    records.add(address)
            except ValueError:
                continue
    except (OSError, socket.gaierror):
        return []
    return sorted(records)


def _rdap_domain(domain: str) -> dict:
    # The host is fixed to rdap.org; domain is URL-escaped as a path component.
    request = urllib.request.Request(
        "https://rdap.org/domain/" + urllib.parse.quote(domain, safe=".-"),
        headers={"User-Agent": "ASSAF-LinkCheck/1.0", "Accept": "application/rdap+json, application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=4) as response:
            if response.status != 200:
                return {"status": "unavailable", "source": "RDAP"}
            data = json.loads(response.read(350000).decode("utf-8", "replace"))
        events = []
        for event in data.get("events", []):
            action = str(event.get("eventAction", ""))
            date = str(event.get("eventDate", ""))
            if action and date:
                events.append({"event": action, "date": date})
        registrar = None
        for entity in data.get("entities", []):
            if "registrar" in entity.get("roles", []):
                registrar = entity.get("vcardArray", [None, []])[1]
                break
        return {
            "status": "found",
            "domain_name": data.get("ldhName") or data.get("unicodeName") or domain,
            "handle": data.get("handle"),
            "registrar_vcard": registrar,
            "events": events,
            "nameservers": [x.get("ldhName") for x in data.get("nameservers", []) if x.get("ldhName")],
            "statuses": data.get("status", []),
            "source": "RDAP (public registration data)",
            "disclaimer": "Registry data may be privacy-redacted or incomplete; registration date is not the site build date.",
        }
    except (OSError, ValueError, urllib.error.URLError, TimeoutError):
        return {"status": "unavailable", "source": "RDAP", "reason": "No public registration record returned or lookup timed out."}


@app.post("/v1/link-check/analyze", tags=["link-check"])
def analyze_link(body: LinkCheckRequest, user: User | None = Depends(optional_user)):
    if user is not None and user.is_banned:
        raise HTTPException(status_code=403, detail={"code": "ACCOUNT_BANNED", "message": "This account is banned. Contact support or submit an appeal."})
    raw = body.url.strip()
    if any(ord(ch) < 32 for ch in raw):
        raise HTTPException(status_code=422, detail="URL contains invalid control characters")
    candidate = raw if "://" in raw else "https://" + raw
    try:
        parsed = urllib.parse.urlsplit(candidate)
        hostname = (parsed.hostname or "").rstrip(".").lower()
        scheme = parsed.scheme.lower()
        port = parsed.port
    except ValueError:
        raise HTTPException(status_code=422, detail="Invalid URL format")
    if scheme not in {"http", "https"} or not hostname or parsed.username or parsed.password:
        raise HTTPException(status_code=422, detail="Only public http/https URLs without embedded credentials are supported")
    try:
        host_ip = ipaddress.ip_address(hostname)
        if not host_ip.is_global:
            raise HTTPException(status_code=422, detail="Private, local, and reserved IP addresses cannot be checked")
        domain = hostname
        dns_records = [hostname]
        host_is_ip = True
    except ValueError:
        if "." not in hostname or hostname in {"localhost", "localhost.localdomain"}:
            raise HTTPException(status_code=422, detail="Enter a public internet domain name")
        try:
            domain = hostname.encode("idna").decode("ascii")
        except UnicodeError:
            raise HTTPException(status_code=422, detail="Invalid international domain name")
        dns_records = _public_dns_records(domain)
        host_is_ip = False
    labels = domain.split(".")
    registrable_hint = ".".join(labels[-2:]) if len(labels) >= 2 else domain
    # Transparent heuristics: these are signals, not proof of maliciousness.
    flags = []
    if scheme == "http":
        flags.append({"level": "warning", "code": "no_https", "detail": "The submitted URL uses HTTP, so transport encryption is not indicated."})
    if "@" in parsed.netloc:
        flags.append({"level": "warning", "code": "at_symbol", "detail": "An @ character appears in the authority portion; verify the actual hostname carefully."})
    if hostname.startswith("xn--") or ".xn--" in hostname:
        flags.append({"level": "review", "code": "idn_punycode", "detail": "The hostname contains an internationalized/punycode label; inspect for lookalike characters."})
    if len(hostname) > 55:
        flags.append({"level": "review", "code": "long_hostname", "detail": "The hostname is unusually long; this alone does not indicate malicious activity."})
    if not dns_records:
        flags.append({"level": "warning", "code": "dns_unresolved", "detail": "No public DNS address was returned by this server's resolver."})

    # Dynamic-DNS providers are used for legitimate and malicious purposes.
    dynamic_dns_suffixes = (".duckdns.org", ".ddns.net", ".no-ip.org", ".hopto.org", ".zapto.org", ".dynu.net", ".servehttp.com")
    if not host_is_ip and any(hostname.endswith(suffix) for suffix in dynamic_dns_suffixes):
        flags.append({"level": "review", "code": "dynamic_dns", "detail": "This hostname uses a dynamic-DNS provider. This is not proof of danger, but the destination may be controlled by an individual and deserves extra scrutiny."})

    query_params = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)[:80]
    suspicious_param_names = {"cam", "camera", "webcam", "capture"}
    if any(key.casefold() in suspicious_param_names for key, _ in query_params):
        flags.append({"level": "review", "code": "camera_related_parameter", "detail": "A URL parameter uses a camera-related name. Parameter names alone do not prove camera access or malicious behavior."})

    threat_intelligence = _google_safe_browsing_lookup(
        urllib.parse.urlunsplit((scheme, parsed.netloc, parsed.path or "/", parsed.query, ""))
    )
    if threat_intelligence.get("status") == "malicious":
        flags.append({"level": "warning", "code": "known_threat", "detail": "Google Safe Browsing reported this URL as a known threat."})

    if not flags:
        flags.append({"level": "info", "code": "no_simple_flags", "detail": "No basic URL-format warning was detected. This is not a clean bill of health."})

    # The score is deliberately capped because this endpoint does not fetch page content,
    # validate the TLS certificate, or follow redirects. No basic flags is not a clean bill of health.
    if threat_intelligence.get("status") == "malicious":
        score = 0
    else:
        score = 85 if threat_intelligence.get("status") == "clean" else 70
        deductions = {"no_https": 20, "at_symbol": 20, "idn_punycode": 15, "long_hostname": 10,
                      "dns_unresolved": 20, "dynamic_dns": 10, "camera_related_parameter": 15}
        score -= sum(deductions.get(flag["code"], 0) for flag in flags)
        score = max(5, min(85, score))
    query_params = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)[:80]
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "submitted_url": raw,
        "normalized_url": urllib.parse.urlunsplit((scheme, parsed.netloc, parsed.path or "/", parsed.query, "")),
        "scheme": scheme,
        "hostname": hostname,
        "port": port or (443 if scheme == "https" else 80),
        "path": parsed.path or "/",
        "query_parameters": [{"name": k, "value": v} for k, v in query_params],
        "fragment_present": bool(parsed.fragment),
        "domain": {
            "hostname": hostname,
            "registrable_domain_estimate": registrable_hint,
            "is_ip_address": host_is_ip,
            "public_dns_addresses": dns_records,
            "dns_status": "resolved_public_address" if dns_records else "no_public_address_returned",
            "note": "DNS answers are point-in-time observations and can vary by network/location.",
        },
        "registration": _rdap_domain(domain) if not host_is_ip else {"status": "not_applicable", "reason": "The submitted host is an IP address."},
        "security_review": {
            "status": "threat_match" if threat_intelligence.get("status") == "malicious" else "preliminary_only",
            "score": score,
            "flags": flags,
            "threat_intelligence": threat_intelligence,
            "tls_certificate": "not_checked",
            "redirect_chain": "not_checked",
            "page_content": "not_fetched",
            "confidence": "known_threat_match" if threat_intelligence.get("status") == "malicious" else ("limited_with_reputation_lookup" if threat_intelligence.get("status") == "clean" else "limited"),
            "important": "A clean reputation lookup does not guarantee safety. ASSAF does not fetch page content, validate TLS, or follow redirects. A dynamic-DNS or camera-related parameter is a caution signal, not proof of maliciousness.",
        },
        "data_sources": ["Local URL parser", "System DNS resolver", "Public RDAP endpoint (when available)"] + (["Google Safe Browsing"] if threat_intelligence.get("status") in {"clean", "malicious"} else []),
    }


# Wallet and immutable earnings ledger.
# All money values are stored as integer US cents; never use floating-point arithmetic.
class WalletAccount(Base):
    __tablename__ = "wallet_accounts"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    balance_cents: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="USD", nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class WalletEntry(Base):
    __tablename__ = "wallet_entries"
    __table_args__ = (UniqueConstraint("external_ref", name="uq_wallet_entry_external_ref"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    amount_cents: Mapped[int] = mapped_column(BigInteger, nullable=False)
    entry_type: Mapped[str] = mapped_column(String(24), nullable=False)
    description: Mapped[str] = mapped_column(String(300), nullable=False)
    source: Mapped[str] = mapped_column(String(40), default="system", nullable=False)
    external_ref: Mapped[str | None] = mapped_column(String(160), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)


class PayoutRequest(Base):
    __tablename__ = "payout_requests"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    amount_cents: Mapped[int] = mapped_column(BigInteger, nullable=False)
    method: Mapped[str] = mapped_column(String(40), nullable=False)
    destination: Mapped[str] = mapped_column(String(300), nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="pending", nullable=False, index=True)
    idempotency_key: Mapped[str] = mapped_column(String(80), unique=True, nullable=False)
    admin_note: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class PayoutInput(BaseModel):
    amount_cents: int = Field(gt=0, le=100000000)
    method: str = Field(min_length=2, max_length=40)
    destination: str = Field(min_length=4, max_length=300)
    idempotency_key: str = Field(min_length=16, max_length=80)


class OwnerEarningInput(BaseModel):
    amount_cents: int = Field(gt=0, le=100000000)
    description: str = Field(min_length=3, max_length=300)
    source: str = Field(default="manual", min_length=2, max_length=40)
    external_ref: str = Field(min_length=8, max_length=160)


class OwnerPayoutDecision(BaseModel):
    status: str = Field(pattern=r"^(paid|rejected)$")
    admin_note: str | None = Field(default=None, max_length=500)


# create_all creates only the new wallet tables; existing account and app tables are preserved.
Base.metadata.create_all(bind=engine)


def _wallet_for_update(db: Session, user_id: str) -> WalletAccount:
    wallet = db.scalar(select(WalletAccount).where(WalletAccount.user_id == user_id).with_for_update())
    if wallet is None:
        wallet = WalletAccount(user_id=user_id, balance_cents=0, currency="USD")
        db.add(wallet)
        db.flush()
    return wallet


def _wallet_entry_json(row: WalletEntry) -> dict:
    return {
        "id": row.id,
        "amount_cents": row.amount_cents,
        "amount": f"{row.amount_cents / 100:.2f}",
        "currency": "USD",
        "type": row.entry_type,
        "description": row.description,
        "source": row.source,
        "created_at": row.created_at.isoformat(),
    }


@app.get("/v1/wallet", tags=["wallet"])
def get_wallet(user: User = Depends(active_user), db: Session = Depends(get_db)):
    wallet = db.get(WalletAccount, user.id)
    if wallet is None:
        wallet = WalletAccount(user_id=user.id, balance_cents=0, currency="USD")
        db.add(wallet)
        db.commit()
        db.refresh(wallet)
    return {
        "balance_cents": wallet.balance_cents,
        "balance": f"{wallet.balance_cents / 100:.2f}",
        "currency": wallet.currency,
        "updated_at": wallet.updated_at.isoformat() if wallet.updated_at else None,
    }


@app.get("/v1/wallet/transactions", tags=["wallet"])
def wallet_transactions(limit: int = Query(default=50, ge=1, le=100), user: User = Depends(active_user), db: Session = Depends(get_db)):
    rows = db.scalars(
        select(WalletEntry).where(WalletEntry.user_id == user.id)
        .order_by(WalletEntry.created_at.desc()).limit(limit)
    )
    return [_wallet_entry_json(row) for row in rows]


@app.post("/v1/wallet/payouts", status_code=201, tags=["wallet"])
def request_payout(body: PayoutInput, user: User = Depends(active_user), db: Session = Depends(get_db)):
    existing = db.scalar(select(PayoutRequest).where(PayoutRequest.idempotency_key == body.idempotency_key))
    if existing is not None:
        if existing.user_id != user.id:
            raise HTTPException(status_code=409, detail="Idempotency key already used")
        return {"id": existing.id, "amount_cents": existing.amount_cents, "status": existing.status, "created_at": existing.created_at.isoformat()}
    wallet = _wallet_for_update(db, user.id)
    if wallet.balance_cents < body.amount_cents:
        raise HTTPException(status_code=409, detail="Insufficient available balance")
    payout = PayoutRequest(
        user_id=user.id, amount_cents=body.amount_cents,
        method=body.method.strip(), destination=body.destination.strip(),
        status="pending", idempotency_key=body.idempotency_key,
    )
    wallet.balance_cents -= body.amount_cents
    db.add(payout)
    db.flush()
    db.add(WalletEntry(
        user_id=user.id, amount_cents=-body.amount_cents, entry_type="payout_hold",
        description="Payout request reserved", source="payout",
        external_ref="payout:" + payout.id,
    ))
    db.commit()
    db.refresh(payout)
    return {"id": payout.id, "amount_cents": payout.amount_cents, "status": payout.status, "created_at": payout.created_at.isoformat()}


@app.get("/v1/admin/users/{user_id}", tags=["owner"])
def owner_user_detail(user_id: str, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    wallet = db.get(WalletAccount, user_id)
    entries = db.scalars(select(WalletEntry).where(WalletEntry.user_id == user_id).order_by(WalletEntry.created_at.desc()).limit(200))
    payouts = db.scalars(select(PayoutRequest).where(PayoutRequest.user_id == user_id).order_by(PayoutRequest.created_at.desc()).limit(100))
    totals = db.execute(
        select(
            func.coalesce(func.sum(WalletEntry.amount_cents).filter(WalletEntry.amount_cents > 0), 0),
            func.coalesce(func.sum(WalletEntry.amount_cents).filter(WalletEntry.amount_cents < 0), 0),
        ).where(WalletEntry.user_id == user_id)
    ).one()
    return {
        "user": {"id": user.id, "full_name": user.full_name, "email": user.email,
                 "whatsapp_number": user.whatsapp_number,
                 "whatsapp_url": ("https://wa.me/" + user.whatsapp_number.lstrip("+")) if user.whatsapp_number else None,
                 "created_at": user.created_at.isoformat(), "is_banned": user.is_banned,
                 "ban_reason": user.ban_reason, "device_linked": bool(user.device_id_hash)},
        "wallet": {"balance_cents": wallet.balance_cents if wallet else 0,
                   "balance": f"{(wallet.balance_cents if wallet else 0) / 100:.2f}",
                   "currency": wallet.currency if wallet else "USD",
                   "total_credited_cents": int(totals[0] or 0),
                   "total_debited_cents": int(abs(totals[1] or 0))},
        "transactions": [_wallet_entry_json(x) for x in entries],
        "payouts": [{"id": x.id, "amount_cents": x.amount_cents, "amount": f"{x.amount_cents / 100:.2f}",
                     "method": x.method, "destination": x.destination, "status": x.status,
                     "admin_note": x.admin_note, "created_at": x.created_at.isoformat(),
                     "reviewed_at": x.reviewed_at.isoformat() if x.reviewed_at else None} for x in payouts],
    }


@app.get("/v1/admin/wallets", tags=["owner"])
def owner_wallets(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    rows = db.execute(
        select(User.id, User.email, WalletAccount.balance_cents)
        .outerjoin(WalletAccount, WalletAccount.user_id == User.id)
        .order_by(User.created_at.desc()).limit(500)
    ).all()
    return [
        {"user_id": user_id, "email": email, "balance_cents": balance or 0,
         "balance": f"{(balance or 0) / 100:.2f}", "currency": "USD"}
        for user_id, email, balance in rows
    ]


@app.post("/v1/admin/wallets/{user_id}/earnings", status_code=201, tags=["owner"])
def owner_credit_earning(user_id: str, body: OwnerEarningInput, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    prior = db.scalar(select(WalletEntry).where(WalletEntry.external_ref == body.external_ref))
    if prior is not None:
        if prior.user_id != user_id:
            raise HTTPException(status_code=409, detail="External reference already used")
        return {"entry": _wallet_entry_json(prior), "duplicate": True}
    wallet = _wallet_for_update(db, user_id)
    wallet.balance_cents += body.amount_cents
    entry = WalletEntry(
        user_id=user_id, amount_cents=body.amount_cents, entry_type="earning",
        description=body.description.strip(), source=body.source.strip(),
        external_ref=body.external_ref.strip(),
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return {"entry": _wallet_entry_json(entry), "duplicate": False}


@app.get("/v1/admin/payouts", tags=["owner"])
def owner_list_payouts(payout_status: str | None = Query(default=None, pattern=r"^(pending|paid|rejected)$"), _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    query = select(PayoutRequest).order_by(PayoutRequest.created_at.desc()).limit(300)
    if payout_status:
        query = query.where(PayoutRequest.status == payout_status)
    rows = db.scalars(query)
    return [
        {"id": row.id, "user_id": row.user_id, "amount_cents": row.amount_cents,
         "method": row.method, "destination": row.destination, "status": row.status,
         "created_at": row.created_at.isoformat(), "admin_note": row.admin_note}
        for row in rows
    ]


@app.patch("/v1/admin/payouts/{payout_id}", tags=["owner"])
def owner_decide_payout(payout_id: str, body: OwnerPayoutDecision, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    payout = db.scalar(select(PayoutRequest).where(PayoutRequest.id == payout_id).with_for_update())
    if payout is None:
        raise HTTPException(status_code=404, detail="Payout request not found")
    if payout.status != "pending":
        raise HTTPException(status_code=409, detail="Payout request has already been reviewed")
    payout.status = body.status
    payout.admin_note = (body.admin_note or "").strip() or None
    payout.reviewed_at = datetime.now(timezone.utc)
    if body.status == "rejected":
        wallet = _wallet_for_update(db, payout.user_id)
        wallet.balance_cents += payout.amount_cents
        db.add(WalletEntry(
            user_id=payout.user_id, amount_cents=payout.amount_cents,
            entry_type="payout_refund", description="Rejected payout refunded",
            source="payout", external_ref="payout_refund:" + payout.id,
        ))
    db.commit()
    return {"id": payout.id, "status": payout.status, "reviewed_at": payout.reviewed_at.isoformat()}
