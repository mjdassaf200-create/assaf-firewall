# ASSAF Steel Security — Pre-release Test Plan

This is a test plan only. It does not mean the feature is implemented or that an APK is ready.

## Build and environment gates
- [ ] Backend unit tests pass on a disposable/staging PostgreSQL database.
- [ ] Android debug APK builds from the security branch.
- [ ] Android package/version and backend API version are recorded together.
- [ ] Staging service uses separate database and secrets; production database is untouched.
- [ ] Owner recovery secret is generated once, displayed once, and stored offline by the owner.
- [ ] No real user balances, personal data, or production owner credentials are used in test accounts.

## Device-binding scenarios
- [ ] Fresh install A can register one test account.
- [ ] Same install A can sign in again after app process restart.
- [ ] Same install A remains signed in after device restart.
- [ ] Install B cannot sign into account bound to A.
- [ ] Install A cannot register or sign into a second account.
- [ ] Changing Wi-Fi/mobile network or using VPN does not change the enrolled key identity.
- [ ] Uninstall/reinstall behavior is explicitly tested and documented; it must not silently bypass binding.
- [ ] Invalid, expired, replayed, or wrong-key challenge proofs are rejected.

## Sessions
- [ ] Access token expires as designed.
- [ ] Valid refresh rotates the refresh token and invalidates the previous token.
- [ ] Reuse of a rotated/revoked refresh token is rejected and triggers session revocation policy.
- [ ] Refresh token is not stored in plaintext preferences/logs.
- [ ] Logout revokes the server-side session.

## Owner security and recovery
- [ ] Owner password alone cannot authorize a different device.
- [ ] Owner remains signed in on the enrolled device across app/device restart.
- [ ] Correct one-time recovery secret on replacement device revokes old owner sessions and device.
- [ ] Recovery secret cannot be reused.
- [ ] Wrong recovery secret does not change owner binding.
- [ ] Recovery rotates the secret and records an audit event.
- [ ] No password-only or hidden bypass exists.

## Existing account migration
- [ ] Existing account on its currently linked device can upgrade to a Keystore key with proof of possession.
- [ ] Existing account cannot be claimed by a new device during migration.
- [ ] Lost-old-device support recovery is separately authenticated, audited, and rate-limited.
- [ ] Rollback procedure is tested without deleting user/account/wallet data.

## Release acceptance
- [ ] Backend tests and Android build are green.
- [ ] Staging tests above are completed and evidence recorded.
- [ ] API compatibility between APK and backend is verified.
- [ ] Production rollout is approved only after staging acceptance.
- [ ] Never advertise absolute protection: rooted/compromised devices and OS-level attacks remain outside guarantees.
