from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from typing import Generator
from uuid import uuid4

import jwt
from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from pwdlib import PasswordHash
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

APP_VERSION = "0.1.0"
JWT_SECRET = os.getenv("JWT_SECRET")
if not JWT_SECRET:
    # Safe for local development only. Render must set a strong JWT_SECRET env var.
    JWT_SECRET = "local-development-only-change-me"

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
