# Pixel Plus Owner Android | staged delivery plan

Owner direction: run the software-company operational desk from one Android phone, while the trusted owner-PC is OFF. Build on what exists in Apps-Factory (Licence Studio, af-license 0.3.0, Control Center and telemetry relay), not a second licence format or a fake database.

## P0 — foundation (THIS PR)
- Native Android shell with Arabic RTL and five working navigation areas.
- Fictional 3-company, requests and alerts fixtures; filtering and local simulated triage.
- Locked real codes UI, no network/signing permissions or secret.
- Unit tests for company scoping and demo transitions.
- Scoped CI builds and uploads debug APK only.
- Documentation, security model, next tasks and explicit unimplemented statuses.

**P0 exit gates:** CI green on exact head; static build checks; demo APK installed on a phone/emulator and user flows verified. If installation was not performed, report P0 as "source delivered / field verification outstanding".

## P1 — ownership and real app lock (separate PR)
- Decide secure device enrollment and identity, PIN + biometric reauth, offline auto-lock, lost phone/lockout behavior, secure encrypted recovery workflow.
- Prove offline mobile signer with a NEW non-exported/Keystore-protected wrapping key, NOT the existing owner's desktop private key. The Ed25519 private signing material can require software implementation and must be carefully protected while resident in memory.
- Generate test-only mobile public/private key pair at runtime, sign the CURRENT 144-character af-license code format, verify it with the Store's actual current verifier. Tests: trial/monthly/perpetual, product mismatch, device mismatch, expiry, wrong public key, date tampering and old PC code compatibility.
- Produce migration/public-key registration instructions and safe rollback strategy; do not activate any real production key.
- Add instrumented Android security tests, emulator smoke test, backed-up release key and explicit internal distribution policy.

## P2a — phone hub, this branch (2026-10-10)

Done in source, not yet proven on a handset:

- Settings for the hub URL (https only), the owner token, and an optional telemetry pull token. Stored with `EncryptedSharedPreferences` and a Keystore `MasterKey`. Clear returns to the labelled demo.
- Pending requests from `GET /licence/pending`. One-tap approve or reject after a confirm dialog.
- Approve sends `POST /licence/decide` `{"id","action":"approve"}` only. The Worker records `licence_owner` and leaves the request pending. No code is accepted. Licence Studio on the PC still signs. Reject sends `action: refuse`.
- Alerts are read-only: `GET /licence/events`, and `GET /pull` when the pull token is set. The app never calls `/ack`.
- Local notifications via WorkManager every 15 minutes. No Firebase.
- `INTERNET` and `POST_NOTIFICATIONS` only, in this app's manifest. Cleartext is off.

Still open: deploy the Worker change before phone approval works against production; install the APK on a phone; MFA; server-side company scope; encrypted offline cache; payment proof. Do not treat this branch as P3 issuance.

## M02 — private notebook (this branch, source only)

Done in source. Not shown on a screen. Not proven on a handset.

- Room database version 1: workspace, hub address, customer, branch, product, and the link between a customer and a hub record.
- Write-ahead logging and foreign keys stay on. An orphan row is refused. A physical delete is refused.
- Hide and restore run as one transaction. Restore the parent before the child. A second active copy of the same identity is refused.
- The exported schema is committed. The build does not wipe the file when it cannot migrate.
- The fictional demo companies are not imported. Hub tokens stay in `EncryptedSharedPreferences`, not in this database. Release signing stays off.

Still open, on purpose:

- No screen reads or writes this notebook. The customer list on the phone is still the in-memory demo. Do not describe it as live.
- Customer names and phones are length-checked opaque bytes. Sealing those bytes with the Android Keystore is **NOT IMPLEMENTED**.
- Audit, undo, money, tickets and the sync outbox are later milestones.

## P2 — authenticated cloud request/alert read (remaining)
- Read existing Control Center API and relay, map data contracts and authorization gaps.
- Implement opt-in backend adaptor with one synthetic data tenant first: login/MFA, revocable device tokens, server-side scoped company access, read-only request inbox, alerts and device/company overview.
- Server, not UI, enforces tenant/user/role scope; no mass-export of customer data to phone.
- Add encrypted local cache only if truly needed. Last-sync timestamp, offline/failed states and no stale real approvals.
- Push notifications after real cloud deploy and cost/privacy approval; keep Telegram as supplemental.
- Prove two-company isolation and expired/revoked device denial in automated negative tests.

## P3 — end-to-end issuance (separate PRs)
- Real pending request details: company, installed product, short device ref, trial history, expiry, verified eligibility.
- For *paid* codes, verified human payment decision and immutable reference BEFORE signing. Owner re-auth for every paid/perpetual code.
- Enroll mobile public key with trusted products, then issue on-device; independently verify the signed output against shipped Store and another product.
- Idempotent request lifecycle: pending -> owner-reviewed -> locally-signed -> queued -> delivered -> activated, audited at each point. Offline issuance must not bypass policy and must reconcile when reconnected.
- Key rotation, lost-device revocation (future issuances only), recovery and compromised-key mitigation; tell owner existing offline perpetual codes cannot be remotely invalidated instantly.
- Test device A vs B, agent/bot trying to sign, double taps, replay, concurrent requests, clock rollbacks, offline retries, no payment proof, wrong tenant.

## P4 — owner command center rollout (separate PRs)
- Connect customers, companies, installs, licences, incidents, payments marked by owner, repair requests and reviewed agent-task queue to existing Control Center.
- Push alerts for licence expiry, failed backups and security, with minimal sensitive info and scoped deep links.
- Actual Android handset pilot with **test keys and synthetic Store**; stable notifications while app backgrounded; battery/network recovery; rotation and tablet layouts; accessibility and RTL.
- Real customer rollout ONLY after mobile signer key registration, owner-led security review, operational backup/recovery, cost cap and field acceptance.

## Exact next steps for Claude Code Cloud
1. Run current Android CI and fix any build/test defects before extending scope. Verify the `.github/workflows/pixel-plus-owner-android.yml` workflow actually fires.
2. Open a **P1-only PR** implementing the test-key signing compatibility proof and Android app lock with security tests. Keep the real issuance switch disabled.
3. Add minimal screenshot/emulator proof, review findings and reproducible commands. Do not make billing, hosted signers, backend deployment or new public keys a hidden side effect.
4. Avoid mass merging. Close each PR after all checks/review, then update ROADMAP checkboxes with actual evidence.
