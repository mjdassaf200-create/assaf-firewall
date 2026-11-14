# ASSAF Steel Device Security

Status: approved specification; implementation is staged on `security/steel-device-binding`.

## Security invariants

1. One ASSAF account is bound to one app installation/device credential.
2. One device credential can be bound to only one user account.
3. Owner access is independently bound to one owner device credential; sharing the owner password does not authorize a second device.
4. VPN/IP changes do not alter the device credential.
5. Access sessions persist through secure refresh-token rotation; access tokens remain short-lived.
6. Owner recovery requires a high-entropy, one-time recovery secret. Recovery revokes all prior owner sessions and the previous device binding, binds the new device, rotates the recovery secret, and writes an audit event.
7. Never store private device keys or plaintext recovery secrets on the server.

## Required implementation

### Android
- Generate an EC signing key pair in Android Keystore per installation (hardware-backed where supported).
- Keep the private key non-exportable. Send only the public key during enrollment.
- Sign a fresh server challenge for login and session refresh; reject replayed/expired challenges.
- Encrypt refresh-token storage using a Keystore-backed key.
- Apply the same device proof to owner login.
- Provide a dedicated owner recovery screen that accepts the one-time recovery secret and enrolls the replacement device.

### Backend / PostgreSQL
- Add device enrollment, short-lived challenge, refresh-session, owner-device, owner-recovery-hash, and security-audit records.
- Enforce unique device-key fingerprints and one active device per user/owner transactionally.
- Verify ECDSA signatures against stored public keys; never trust a client-supplied device ID as proof.
- Rotate refresh tokens on every use, revoke the previous token, and bind each session to its enrolled key.
- Implement owner recovery as an atomic operation: verify and consume the recovery hash, revoke old owner sessions, replace owner public key, rotate recovery hash, and audit the event.
- Preserve current user account data and existing Android-ID bindings during a controlled migration. Do not silently let an arbitrary new device claim an existing account.

## Migration and rollout
- Keep the existing login behavior available only during a controlled migration window.
- Require proof from the currently linked device before upgrading an existing account to a Keystore key.
- Provide a separately audited support/admin migration path for users who lost their old device; do not weaken owner recovery.
- Deploy backend changes before shipping the Android client that calls the new endpoints.
- Do not enable strict enforcement until Android and backend compatibility tests pass.

## Required tests before release
- Same device: sign-in, app restart, device restart, refresh-token rotation.
- Different device: reject login and registration when account/device is already bound.
- One device attempting a second account: reject.
- VPN/IP change: still same enrolled key; no bypass.
- Replayed, expired, malformed, and wrong-key challenge: reject.
- Revoked/rotated refresh token: reject.
- Owner password on another device: reject.
- Owner recovery: one-time code works once, rotates, revokes old sessions/device; replay fails.
- Lost recovery secret: no password-only bypass; use documented out-of-band operator recovery.
- Existing account migration and rollback checks.
- Android build, backend tests, staging deployment, and production health checks.

## Release gates
- No claim of "steel security" or production readiness until the cryptographic proof, recovery, session rotation, migration, and test gates above are implemented and verified.
- ANDROID_ID alone is not sufficient device authentication. It may be retained only as a diagnostic/migration hint, never as the security proof.
