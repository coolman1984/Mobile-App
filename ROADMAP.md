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
- Money, tickets and the sync outbox are later milestones. The local audit chain and undo guard are M03.

## M03 — local audit and undo (this branch, source only)

Done in source. Not shown on a screen. Not proven on a handset.

- Room version 2 adds an append-only audit chain and undo rows. A version 1 notebook is migrated in place. The file is not wiped.
- Each successful notebook write adds one Arabic explanation and one English explanation in the same transaction. A duplicate that the notebook refuses is recorded when the workspace already exists. Names, phones, tokens, notes and amounts are kept out of the chain.
- The hash is SHA-256 of the previous hash plus fixed canonical bytes. A shared golden file locks those bytes. Tests cover a changed byte, a missing middle row, a reordered list, a repeated id, and eight writers at once.
- Undo is offered for about 10 seconds. After that, the activity path can still undo a local change when nothing else has touched that record. A revision mismatch, a conflicting restore, or a later child change refuses the undo and says why. Work that has already been sent uses the fixed sentence: لا يمكن التراجع بعد الإرسال / Cannot undo after sending.
- Audit rows have no update or delete method. Database triggers refuse those statements.
- A cash reversal is a calculation: a new negative posting that points at the original, which stays unchanged. A hidden customer does not change the total. There is no payment table.

Still open, on purpose:

- No activity screen and no save-to-file flow on the phone. A JSON and bilingual Markdown formatter exists and is covered by a unit test. The screen is a later milestone.
- The chain is not sent to the hub. Worker audit endpoints are a later milestone. Worker authentication was not changed.
- Sealing the audit body with the Android Keystore is in the app and is used only after a round-trip check. These unit tests seal with an in-memory AES key. A handset proof was not run.
- Migration was checked by opening a synthetic version 1 file in the unit test. The emulator migration helper was not run.
- Customer names and phones are still opaque bytes. Sealing those bytes with the Android Keystore is **NOT IMPLEMENTED**.
- Do not describe the in-memory reversal as stored money.

## M04 — durable decision queue and hub cache (2026-10-11, source verified)

Continues the partial working-tree implementation after M03 (`ba22843`). Only M04 is implemented. Issue #47 was read through the GitHub API; its later signing direction remains gated by the newer implementation plan. No Worker changes, deployment, push, pricing change or real customer data access.

- Room version 3 migrates version 1 through 2 in place, preserving the notebook and audit. Adds outbox, cursors, request/event caches, safe mailbox receipts and notification receipts. Tokens stay in encrypted preferences; no code or signing identity is stored.
- The existing confirmation dialog saves an encrypted decision intent and bilingual audit entry together. It shows queued status, count and oldest age, plus blocked count. Double taps and a competing approve/refuse tap keep one active operation. The displayed remote decision is never changed optimistically.
- Network-constrained unique work is scoped to the hub profile. Database leases, compare-and-set state writes, bounded exponential retry with jitter, 24-hour intent expiry and recovery after restart protect dispatch. State changes append bilingual audit records in the same transaction. Work input/output contains no tokens or payload.
- Every POST is preceded by fresh state and paginated pending reads. `/states` returns raw approval, so the pending projection is also required to detect expired approvals. A successful POST is confirmed only by a subsequent fresh server read. A lost response or 5xx is reconciled before retrying the exact stored action/body/operation; no opposite action is sent as compensation. Malformed success, closed/absent requests and stale intents stop for review.
- Origin changes invalidate old queued intents. Token rotation and clearing fence affected queues before changing encrypted settings. Settings commits and dispatch share a process lock; saves run off the UI thread. A successful connection and reconciliation are required before resuming a credential-paused queue; each operation is checked freshly again before dispatch.
- Pending reads exhaust stable pages (up to 100 pages of 100; larger or repeated-page results fail closed). Rows and cursors commit together. Offline/network/server failures can show a labelled saved request snapshot and last successful connection; authorization denial remains visible. Cache records omit shop/name/reference/version text, licence codes, tokens and raw compressed mailbox bodies. Event free text is discarded. Events and mailbox coverage remain explicitly partial; disappearance never deletes local history.
- Notification baselines and stable keys are stored per hub. First snapshots are silent, including an empty first snapshot. Observed keys suppress repeated attempts across restarts; `deliveredAt` is set only after Android accepts the notification. Counts only, Arabic and English. No `/ack`, `/licence/ack`, `/installs` writes, Firebase, paid library or signing path.

Verification in this workspace:

- `source /workspace/android-tools/env.sh && (cd apps/pixel-plus-owner-mobile && gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug)` — 94 unit tests and debug build passed; dependency/SDK and debug APK secret/permission/signing audits included.
- `(cd templates/telemetry-relay && PATH=/workspace/node22/bin:$PATH npm test)` — 52 tests passed, although no Worker files changed.
- Android tests cover state machine, concurrent/double/opposite taps, lost and ambiguous responses, 5xx after commit, malformed 2xx, effective approval expiry, closed/absent requests, auth/credential/origin changes, lease recovery, bounded retry, encryption failure/tampering, cache/cursor rollback, migrations from 1 and 2, durable profile-scoped notification dedup and safe WorkManager input.

Remaining M04 limits / safe subset:

- The existing Worker accepts no operation receipt or conditional expected-state hash. Stable operation/idempotency IDs are local; server replay behavior is request/action based. A race between the fresh read and POST is still decided by existing Worker transitions. Full conditional receipts need a separately authorized later contract; auth was not weakened to create them.
- The phone conservatively rejects a new approval older than the Worker's default 72 hours; a longer configured server window is not discoverable. Effective expiry is read from the server, and a shorter server window still wins. Device clock cannot be treated as trusted server time.
- Only cached requests are connected to an offline screen. Event/mailbox repositories exist; their full offline screen projections, queue item review/cancel/reconfirm UI and reactive/background completion updates remain later M10/M15 work. Use refresh to update the queue summary. Global app language/navigation are M07; M04 feedback has paired Arabic/English paragraphs with RTL/LTR direction and Arabic first.
- Outbox encryption fails closed when its separate Keystore key cannot round-trip; no plaintext fallback. Hardware Keystore proof, real handset install/offline/kill/notification drills, screenshots, TalkBack/large-font AR/EN review and emulator migration harness are **UNVERIFIED** here. Notifications use at-most-once attempts: permission denial or a crash between receipt and posting can suppress a notice; the in-app inbox remains authoritative.
- Local outbox/cache history is retained without a destructive purge; no portable backup is claimed (M06/follow-on recovery). Remote audit upload remains M05. Signing and release signing remain OFF. Independent review, a PR and CI on the exact commit are pending; this session makes a local commit only, as requested.

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
