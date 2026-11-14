import base64
import hashlib
import os
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

os.environ.setdefault("JWT_SECRET", "test-only-secret-that-is-long-enough-for-assaf")
os.environ.setdefault("DATABASE_URL", "sqlite:////tmp/assaf-steel-security-tests.db")

from backend.main import DeviceChallenge, SessionLocal, verify_device_proof


def _key_material():
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_key, base64.b64encode(public_key).decode("ascii")


def _add_challenge(db, challenge, email, device_id, purpose="user", expires=None):
    device_hash = hashlib.sha256(device_id.encode("utf-8")).hexdigest()
    db.add(DeviceChallenge(
        challenge=challenge,
        email=email,
        device_id_hash=device_hash,
        purpose=purpose,
        expires_at=expires or datetime.now(timezone.utc) + timedelta(minutes=2),
    ))
    db.commit()


def test_valid_signature_is_accepted_and_challenge_is_single_use():
    db = SessionLocal()
    try:
        challenge = "single-use-challenge-value-000000000001"
        email = "member@example.com"
        device_id = "device-identifier-for-test-0001"
        private_key, public_key = _key_material()
        _add_challenge(db, challenge, email, device_id)
        signature = base64.b64encode(
            private_key.sign(challenge.encode(), ec.ECDSA(hashes.SHA256()))
        ).decode("ascii")

        verified_key = verify_device_proof(
            db, email, device_id, challenge, signature, public_key
        )
        assert verified_key == public_key
        assert db.get(DeviceChallenge, challenge) is None

        with pytest.raises(HTTPException) as exc:
            verify_device_proof(db, email, device_id, challenge, signature, public_key)
        assert exc.value.status_code == 401
    finally:
        db.close()


def test_challenge_cannot_be_replayed_from_another_device():
    db = SessionLocal()
    try:
        challenge = "device-binding-challenge-value-0000002"
        email = "member2@example.com"
        private_key, public_key = _key_material()
        _add_challenge(db, challenge, email, "original-device-identifier-0002")
        signature = base64.b64encode(
            private_key.sign(challenge.encode(), ec.ECDSA(hashes.SHA256()))
        ).decode("ascii")

        with pytest.raises(HTTPException) as exc:
            verify_device_proof(
                db, email, "different-device-identifier-0002",
                challenge, signature, public_key
            )
        assert exc.value.status_code == 401
    finally:
        db.close()


def test_registered_account_public_key_cannot_be_replaced():
    db = SessionLocal()
    try:
        challenge = "fixed-key-challenge-value-000000000003"
        email = "member3@example.com"
        device_id = "device-identifier-for-test-0003"
        private_key, public_key = _key_material()
        _, different_public_key = _key_material()
        _add_challenge(db, challenge, email, device_id)
        signature = base64.b64encode(
            private_key.sign(challenge.encode(), ec.ECDSA(hashes.SHA256()))
        ).decode("ascii")

        with pytest.raises(HTTPException) as exc:
            verify_device_proof(
                db, email, device_id, challenge, signature,
                different_public_key, expected_public_key=public_key
            )
        assert exc.value.status_code == 403
    finally:
        db.close()


def test_expired_challenge_is_rejected():
    db = SessionLocal()
    try:
        challenge = "expired-challenge-value-000000000004"
        email = "member4@example.com"
        device_id = "device-identifier-for-test-0004"
        private_key, public_key = _key_material()
        _add_challenge(
            db, challenge, email, device_id,
            expires=datetime.now(timezone.utc) - timedelta(seconds=5)
        )
        signature = base64.b64encode(
            private_key.sign(challenge.encode(), ec.ECDSA(hashes.SHA256()))
        ).decode("ascii")

        with pytest.raises(HTTPException) as exc:
            verify_device_proof(db, email, device_id, challenge, signature, public_key)
        assert exc.value.status_code == 401
        assert db.get(DeviceChallenge, challenge) is None
    finally:
        db.close()
