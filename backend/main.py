from __future__ import annotations

import json
import re
import os
from datetime import datetime, timedelta, timezone
from typing import Generator
from uuid import uuid4

import jwt
from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from pwdlib import PasswordHash
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

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


Base.metadata.create_all(bind=engine)

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


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=10, max_length=128)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class UserResponse(BaseModel):
    id: str
    email: EmailStr
    created_at: datetime
    model_config = ConfigDict(from_attributes=True)


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


def create_token(user: User) -> TokenResponse:
    expires = datetime.now(timezone.utc) + timedelta(hours=12)
    token = jwt.encode({"sub": user.id, "email": user.email, "exp": expires}, JWT_SECRET, algorithm="HS256")
    return TokenResponse(access_token=token, expires_in=12 * 60 * 60)


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


@app.post("/v1/auth/register", response_model=TokenResponse, status_code=201, tags=["auth"])
def register(body: RegisterRequest, db: Session = Depends(get_db)):
    email = str(body.email).lower()
    if db.scalar(select(User).where(User.email == email)):
        raise HTTPException(status_code=409, detail="An account with this email already exists")
    user = User(email=email, password_hash=password_hash.hash(body.password))
    db.add(user)
    db.flush()
    db.add(Subscription(user_id=user.id, plan="free", status="active", provider="none"))
    db.commit()
    db.refresh(user)
    return create_token(user)


@app.post("/v1/auth/login", response_model=TokenResponse, tags=["auth"])
def login(body: LoginRequest, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    if user is None or not password_hash.verify(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Email or password is incorrect")
    return create_token(user)


@app.get("/v1/me", response_model=UserResponse, tags=["auth"])
def me(user: User = Depends(current_user)):
    return user


@app.get("/v1/settings", response_model=list[SettingResponse], tags=["settings"])
def list_settings(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(AppSetting).where(AppSetting.user_id == user.id).order_by(AppSetting.package_name)))


@app.put("/v1/settings", response_model=SettingResponse, tags=["settings"])
def save_setting(body: SettingInput, user: User = Depends(current_user), db: Session = Depends(get_db)):
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
def list_schedules(user: User = Depends(current_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(Schedule).where(Schedule.user_id == user.id).order_by(Schedule.created_at.desc()))
    return [{**{k: getattr(row, k) for k in ("id", "package_name", "start_time", "end_time", "enabled", "timezone_name", "created_at")}, "days": row.days.split(",")} for row in rows]


@app.post("/v1/schedules", response_model=ScheduleResponse, status_code=201, tags=["schedules"])
def create_schedule(body: ScheduleInput, user: User = Depends(current_user), db: Session = Depends(get_db)):
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
def delete_schedule(schedule_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    row = db.scalar(select(Schedule).where(Schedule.id == schedule_id, Schedule.user_id == user.id))
    if row is None:
        raise HTTPException(status_code=404, detail="Schedule not found")
    db.delete(row)
    db.commit()
    return None


@app.get("/v1/subscription", response_model=SubscriptionResponse, tags=["subscription"])
def subscription(user: User = Depends(current_user), db: Session = Depends(get_db)):
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
    ip_address: Mapped[str] = mapped_column(String(64), default="unknown")


class DeviceBan(Base):
    __tablename__ = "device_bans"
    install_id: Mapped[str] = mapped_column(String(80), primary_key=True)
    reason: Mapped[str] = mapped_column(String(500), default="")
    banned_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    active: Mapped[bool] = mapped_column(Boolean, default=True)

class Appeal(Base):
    __tablename__ = "ban_appeals"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    install_id: Mapped[str] = mapped_column(String(80), index=True)
    message: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="pending", index=True)
    ip_address: Mapped[str] = mapped_column(String(64), default="unknown")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

class ContactMessage(Base):
    __tablename__ = "contact_messages"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    name: Mapped[str] = mapped_column(String(80), nullable=False)
    message: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)


Base.metadata.create_all(bind=engine)

# Lightweight additive migration for databases created by earlier ASSAF versions.
from sqlalchemy import inspect, text
with engine.begin() as connection:
    existing_columns = {column["name"] for column in inspect(engine).get_columns("install_records")}
    if "ip_address" not in existing_columns:
        connection.execute(text("ALTER TABLE install_records ADD COLUMN ip_address VARCHAR(64) NOT NULL DEFAULT 'unknown'"))

class ContactMessageInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    message: str = Field(min_length=1, max_length=2000)


class OwnerLoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=256)

class TelemetryPing(BaseModel):
    install_id: str = Field(min_length=12, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    app_version: str = Field(default="unknown", max_length=40)

def owner_token(email: str) -> TokenResponse:
    expires = datetime.now(timezone.utc) + timedelta(hours=4)
    token = jwt.encode({"sub": "owner:" + email, "email": email, "role": "owner", "exp": expires}, JWT_SECRET, algorithm="HS256")
    return TokenResponse(access_token=token, expires_in=4 * 60 * 60)

def current_owner(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)) -> dict:
    if credentials is None:
        raise HTTPException(status_code=401, detail="Owner login required")
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=["HS256"])
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired owner session")
    if payload.get("role") != "owner" or not str(payload.get("sub", "")).startswith("owner:"):
        raise HTTPException(status_code=403, detail="Owner access only")
    return payload

@app.post("/v1/admin/login", tags=["owner"])
def owner_login(body: OwnerLoginRequest):
    if not OWNER_ADMIN_EMAIL or not OWNER_ADMIN_PASSWORD:
        raise HTTPException(status_code=503, detail="Owner login is not configured yet")
    supplied_email = str(body.email).strip().lower()
    if not secrets.compare_digest(supplied_email, OWNER_ADMIN_EMAIL) or not secrets.compare_digest(body.password, OWNER_ADMIN_PASSWORD):
        raise HTTPException(status_code=401, detail="Email or password is incorrect")
    return owner_token(supplied_email)

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
    latest = db.query(InstallRecord).order_by(InstallRecord.last_seen.desc()).limit(100).all()
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
            {"install_id": row.install_id, "app_version": row.app_version, "last_seen": row.last_seen.isoformat(), "ip_address": row.ip_address, "banned": bool((db.get(DeviceBan, row.install_id) and db.get(DeviceBan, row.install_id).active))}
            for row in latest
        ],
        "notes": {
            "active_devices_5m": "Installations that pinged the API within the last five minutes; requires the app telemetry client.",
            "reported_installs": "Unique installation IDs reported by the app, not APK downloads from a store.",
        },
    }


class AppealInput(BaseModel):
    install_id: str = Field(min_length=12, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    message: str = Field(min_length=10, max_length=2000)

class BanActionInput(BaseModel):
    reason: str = Field(default="", max_length=500)

def request_ip(request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    value = forwarded or (request.client.host if request.client else "unknown")
    return value[:64]

@app.middleware("http")
async def enforce_device_bans(request, call_next):
    path = request.url.path
    allowed = (path in {"/", "/health", "/v1/access/status", "/v1/appeals", "/v1/telemetry/ping"}
               or path.startswith("/v1/admin/"))
    install_id = request.headers.get("x-assaf-install-id", "").strip()
    if path.startswith("/v1/") and not allowed and install_id:
        with SessionLocal() as db:
            ban = db.get(DeviceBan, install_id)
            if ban and ban.active:
                from starlette.responses import JSONResponse
                return JSONResponse(status_code=403, content={"detail": "ASSAF_ACCESS_RESTRICTED", "message": "Your access is restricted. You may submit an appeal."})
    return await call_next(request)

@app.get("/v1/access/status", tags=["access"])
def access_status(request: Request):
    install_id = request.headers.get("x-assaf-install-id", "").strip()
    if not install_id:
        raise HTTPException(status_code=400, detail="Installation ID is required")
    with SessionLocal() as db:
        now = datetime.now(timezone.utc)
        record = db.get(InstallRecord, install_id)
        if record is None:
            record = InstallRecord(install_id=install_id, app_version="unknown", first_seen=now, last_seen=now, ip_address=request_ip(request))
            db.add(record)
        else:
            record.last_seen = now
            record.ip_address = request_ip(request)
        ban = db.get(DeviceBan, install_id)
        latest = db.scalar(select(Appeal).where(Appeal.install_id == install_id).order_by(Appeal.created_at.desc()))
        db.commit()
        return {"blocked": bool(ban and ban.active),
                "ban_reason": ban.reason if ban and ban.active else "",
                "appeal_status": latest.status if latest else None}

@app.post("/v1/appeals", status_code=201, tags=["appeals"])
def submit_appeal(body: AppealInput, request: Request, db: Session = Depends(get_db)):
    header_id = request.headers.get("x-assaf-install-id", "").strip()
    if not header_id or header_id != body.install_id:
        raise HTTPException(status_code=400, detail="Installation ID mismatch")
    ban = db.get(DeviceBan, body.install_id)
    if not ban or not ban.active:
        raise HTTPException(status_code=409, detail="This installation is not currently blocked")
    now = datetime.now(timezone.utc)
    recent = db.scalar(select(Appeal).where(Appeal.install_id == body.install_id, Appeal.created_at >= now - timedelta(hours=24)).order_by(Appeal.created_at.desc()))
    if recent:
        raise HTTPException(status_code=429, detail="An appeal was already submitted in the last 24 hours")
    row = Appeal(install_id=body.install_id, message=body.message.strip(), ip_address=request_ip(request))
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "status": row.status, "created_at": row.created_at.isoformat()}

@app.get("/v1/admin/appeals", tags=["owner"])
def owner_appeals(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    rows = db.query(Appeal).order_by(Appeal.created_at.desc()).limit(300).all()
    return {"appeals": [{"id": x.id, "install_id": x.install_id, "message": x.message, "status": x.status, "ip_address": x.ip_address, "created_at": x.created_at.isoformat()} for x in rows]}

@app.post("/v1/admin/devices/{install_id}/ban", tags=["owner"])
def ban_device(install_id: str, body: BanActionInput, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    if not re.fullmatch(r"[A-Za-z0-9_-]{12,80}", install_id):
        raise HTTPException(status_code=422, detail="Invalid installation ID")
    row = db.get(DeviceBan, install_id)
    if row is None:
        row = DeviceBan(install_id=install_id, reason=body.reason.strip(), active=True)
        db.add(row)
    else:
        row.reason = body.reason.strip()
        row.active = True
        row.banned_at = datetime.now(timezone.utc)
    db.commit()
    return {"install_id": install_id, "active": True}

@app.post("/v1/admin/devices/{install_id}/unban", tags=["owner"])
def unban_device(install_id: str, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    row = db.get(DeviceBan, install_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Device ban not found")
    row.active = False
    db.commit()
    return {"install_id": install_id, "active": False}

@app.post("/v1/admin/appeals/{appeal_id}/review", tags=["owner"])
def review_appeal(appeal_id: str, body: BanActionInput, decision: str, _owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    if decision not in {"accept", "reject"}:
        raise HTTPException(status_code=422, detail="Decision must be accept or reject")
    row = db.get(Appeal, appeal_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Appeal not found")
    if row.status != "pending":
        raise HTTPException(status_code=409, detail="Appeal has already been reviewed")
    ban = db.get(DeviceBan, row.install_id)
    if decision == "accept":
        if ban:
            ban.active = False
        row.status = "accepted"
    else:
        row.status = "rejected"
    row.reviewed_at = datetime.now(timezone.utc)
    db.commit()
    return {"id": row.id, "status": row.status, "device_unblocked": decision == "accept"}

@app.post("/v1/contact/messages", status_code=201, tags=["contact"])
def create_contact_message(body: ContactMessageInput, request, db: Session = Depends(get_db)):
    name = body.name.strip()
    message = body.message.strip()
    if not name:
        raise HTTPException(status_code=422, detail="Please enter your name")
    if not message:
        raise HTTPException(status_code=422, detail="Please write a message")
    row = ContactMessage(name=name, message=message)
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "name": row.name, "message": row.message, "created_at": row.created_at}


@app.get("/v1/admin/contact-messages", tags=["owner"])
def owner_contact_messages(_owner: dict = Depends(current_owner), db: Session = Depends(get_db)):
    rows = db.query(ContactMessage).order_by(ContactMessage.created_at.desc()).limit(200).all()
    return {
        "messages": [
            {"id": row.id, "name": row.name, "message": row.message, "created_at": row.created_at.isoformat()}
            for row in rows
        ]
    }


@app.post("/v1/telemetry/ping", status_code=204, tags=["telemetry"])
def telemetry_ping(body: TelemetryPing, request: Request, db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    row = db.get(InstallRecord, body.install_id)
    if row is None:
        row = InstallRecord(install_id=body.install_id, app_version=body.app_version, first_seen=now, last_seen=now, ip_address=request_ip(request))
        db.add(row)
    else:
        row.app_version = body.app_version
        row.last_seen = now
        row.ip_address = request_ip(request)
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
def analyze_link(body: LinkCheckRequest):
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
    # Heuristics are transparent indicators, not proof of maliciousness.
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
    if not flags:
        flags.append({"level": "info", "code": "no_simple_flags", "detail": "No basic URL-format warning was detected. This is not a clean bill of health."})
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
            "status": "preliminary_only",
            "flags": flags,
            "threat_intelligence": "not_checked",
            "tls_certificate": "not_checked",
            "redirect_chain": "not_checked",
            "page_content": "not_fetched",
            "confidence": "limited",
            "important": "ASSAF did not open the submitted site or query malware/phishing reputation databases. No warning does not mean safe; HTTPS alone does not mean trustworthy.",
        },
        "data_sources": ["Local URL parser", "System DNS resolver", "Public RDAP endpoint (when available)"],
    }
