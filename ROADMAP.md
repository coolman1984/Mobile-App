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

## M05 — audit mirror contract and staged client (2026-10-11, source verified in part)

Two repositories, one contract. The Worker side lives in Apps-Factory (`templates/telemetry-relay/src/audit.js`, table `owner_audit`, `POST /audit/append`, `GET /audit/recent`, admin token only). This repository adds the phone's client. **Nothing in the app calls it yet**: no screen, no worker job and no setting changed, so the app behaves exactly as after M04.

- `audit/AuditMirrorClient.kt` (pure Kotlin) appends a `ChainLink` as `{entry, previousHash, entryHash}` where `entry` is the stored canonical text, never rebuilt. It uses the owner token only, never the pull token, and never `/ack`. `rejectedHubPath` allows exactly `POST /audit/append` and `GET /audit/recent`.
- Outcomes are explicit: `Ok`, `Conflict(head)` (409), `NotAvailable` (404/405: an older Worker, baseline only), `AuthBlocked` (401/403), `Invalid` (400/413/507: retrying the same bytes cannot help), `Retry` (network, 429, 5xx: identical bytes are safe to resend), `BadResponse` (a 2xx it cannot trust) and `Rejected` (refused here before any request).
- An append is confirmed only when the hub echoes the same event id, sequence and hash, and 201/200 agree with `replay` false/true. A page from `/audit/recent` is re-hashed row by row, checked for order, predecessor links, the chain id, the head, the cursor and genesis coverage before it is returned. A tampered, reordered, truncated or inconsistent page is `BadResponse`.
- `AuditAnchor.compare` names how the phone's retained head relates to the hub's: in sync, hub behind, hub ahead, or mismatch. A mismatch is to be shown, never repaired silently.
- Cross-implementation proof: `templates/telemetry-relay/test/audit-phone-chain.json` was produced by `AuditCanonical.link` (a chain with quotes, a backslash, a tab, a newline, Arabic and a decomposed accent) and the Worker test accepts it and returns the same bytes. The shared golden entry in `app/src/test/resources/audit/golden-v1.json` is accepted by the Worker byte for byte.

Verification actually run:

- `cd templates/telemetry-relay && npm test` (Node 22.22): 66 tests passed, 17 of them new for the audit mirror (auth classes, canonical form, hash chain, gap/fork/other-device conflicts, concurrent writers, caps, append-only triggers, privacy, D1 query counts).
- The Android SDK cannot be downloaded in this environment, so `gradle :app:testDebugUnitTest :app:assembleDebug` was **NOT run** and neither was CI. Instead the Android-free sources (`HubLogic`, `HubClient`, `DemoState`, `domain/Clock`, `audit/{CanonicalJson,AuditChain,AuditText,AuditRedaction,AuditMirrorClient}`) were compiled with a standalone Kotlin 2.1.20 compiler and `AuditMirrorClientTest` (14 new tests) plus `HubLogicTest`, `DemoStateTest` and `AuditChainTest` ran green with JUnit 4.13.2 and kotlinx-serialization 1.8.1 (36 tests). That is evidence about the pure logic only; it is not the project's own build.

Still open, on purpose:

- No mirror job: nothing reads the local chain, calls the client, stores a mirror cursor or retries. Mirror progress must live in the outbox/cursor tables, never in audit rows, and a sync run is summarised, not mirrored entry by entry (see IMPLEMENTATION_PLAN section 6.2). That is a later milestone.
- The Worker change is **not deployed**. `schema.sql` must be re-run on D1 first, and the two append-only triggers in it have not been tried on live D1. Until then the app would see `NotAvailable`.
- `GET /owner/capabilities` (a negotiated capability check) is M13. The client currently infers "not available" from 404/405.
- A shared admin token is not a cryptographic identity: the mirror shows tampering against a retained head; it cannot prove who wrote an entry.
- Independent review, a PR and CI on the exact commit are pending; this session pushes the branch only.

## M06 — encrypted backup core (2026-10-11, pure logic verified, not wired)

Source only, and **not connected to anything the owner can touch**: no screen, no WorkManager job, no Room snapshot, no file picker. What exists is the part that must be right before those are built.

- `backup/BackupArchive.kt`: the archive `PPOB` | format | manifest length | manifest | nonce | AES-256-GCM(files). The manifest (canonical JSON: format and database versions, snapshot id, sizes and SHA-256 per file, audit chain heads, hub profile ids, key mode) is readable without the key and **authenticated** as GCM associated data, so a changed manifest or file fails to open. The same answer is given for a wrong key and a changed byte. Names are restricted, the number of files and the sizes are capped, and the build refuses to output anything that still contains the plain SQLite header.
- `backup/BackupRestore.kt`: `RestorePlanner.check` refuses a newer database version before it tries the key, then opens, verifies hashes and requires the database and the logical export. It returns the notices the owner must see before confirming: tokens must be entered again, every queued decision comes back as "needs review", a new audit chain starts after the restored one, signing stays OFF. `quarantine` turns every active outbox state into NEEDS_REVIEW and leaves finished ones alone.
- `backup/BackupCoordinator.kt`: takes a snapshot through `SnapshotSource.quiesced` (writers and sync paused, WAL checkpointed, database closed, reopened in `finally`), seals it and writes through `BackupSink` (temporary name, then atomic commit). A failure at any stage discards the temporary file and leaves the last good artifact untouched. The destination only ever receives sealed bytes.
- `backup/KeystoreBackupKey.kt`: the default device-bound, non-exportable key, with a round-trip probe and no plaintext fallback. It cannot recover a backup after phone loss, uninstall or on another phone. It follows `KeystoreOutboxSealer` and is the one Android file here: it is **UNVERIFIED**.

Verification actually run: `BackupTest` (16 tests: round trip, no plaintext, fresh nonce, wrong key vs tamper, authenticated manifest, truncation/format/magic, name and size limits, a leaking sealer is caught, restore notices, newer schema before key, missing files, spliced manifest, outbox quarantine, coordinator failure at every stage keeps the last good file and reopens) passed with the standalone Kotlin compiler and JUnit, in the same scratch project as M05. `gradle :app:testDebugUnitTest :app:assembleDebug` was **NOT run** (no Android SDK here), and neither was CI.

**NOT IMPLEMENTED** (each is its own piece of work): the Room-backed `SnapshotSource` and the logical JSON exporter; the SAF destination and persisted tree permission; the daily WorkManager job and "Back up now"; the restore screen, reauthentication and the atomic replacement of the live database; starting the new audit chain from the restored head; streaming (the archive is built in memory and capped at 64 MiB, larger snapshots fail with TOO_LARGE); portable recovery with an owner-held secret; the instrumented Keystore, migration and clean-phone restore drills. Cross-device recovery is therefore **not available**, and no screen may promise it.

## M07 — navigation model, bilingual strings and the new shell (2026-10-11, logic verified, shell UNVERIFIED)

The pure model is tested. The Compose shell that draws it is written but **was never compiled or run**: the Android SDK and Google's Maven repository are both unreachable here, so only the model, the resources and a stub-based type check of `NavUi.kt` could be run. Treat the shell as unreviewed until `gradle :app:testDebugUnitTest :app:assembleDebug` and a handset pass.

Model (`navigation/`, no Android types):
- `Destinations.kt`: one table of every destination from the plan's map (five roots, details, the More categories, settings pages, the licence-codes module), each with a path holding at most one opaque `{id}`, its root, an optional More category and an optional capability.
- `Route.kt`: `Route.parse` is the only way in from outside text. It refuses queries, fragments, `..`, `%`, control characters, ids over 64 characters and long hex strings (token or hash shaped), and reads `payment/new` as a literal.
- `NavState.kt`: five remembered root stacks; reselecting a root pops to it; the same route twice stacks once; Back closes sheet, then drawer, then detail, then goes Home, then asks to exit; `OneShot` hands an event over exactly once; `encode`/`decode` survive a rotation or recreation (paths only, overlays closed, unsafe or duplicate entries dropped, depth capped).
- `DeepLink.kt`: nothing opens while locked; the link is kept and decided again after unlock with the capabilities granted then. A screen that needs the hub explains when there is none; every licence-codes screen is "unavailable" while `REAL_ISSUANCE_ENABLED` is false.
- `NavLayout.kt`: bottom bar below 600dp, rail from 600dp; badge counts only, capped at 99+.
- Resources: `values/strings.xml` (Arabic) and `values-en/strings.xml` hold the same 60 keys; a test fails if a key is missing, empty or orphaned, English text contains Arabic letters, or a placeholder differs. The English app name is now "Pixel Plus Owner" on English-language phones.

Shell (`OwnerApp.kt`, `NavUi.kt`), changed in this step:
- The six-tab bar is replaced by the five roots (Home, Requests, Customers, Alerts, More) as a bottom bar, or a rail from 600dp. Badges show pending requests and unread alerts. The root state is saved across recreation (`rememberSaveable`) and the system Back goes through the reducer (`BackHandler`).
- Customers shows the existing fictional-companies screen (the real customer book is M11). Settings and the disabled Licence Codes explanation moved under More; More lists every category and marks entries that have no screen yet. Any destination without a screen, or without its capability, opens an explanation instead of a half screen.
- The request decision flow, the queue, the settings form and the hub refresh code are untouched.

Verification actually run: `NavigationTest` (18 tests) with the standalone Kotlin compiler; with M05/M06 and the existing `HubLogicTest`, `DemoStateTest`, `AuditChainTest` the scratch run was 70 tests, all green. `NavUi.kt` type-checked against hand-written Compose stubs (which proves my own code, not the real signatures). `OwnerApp.kt` was only checked for syntax, because its other dependencies (WorkManager, Room, Keystore) cannot be compiled here. Gradle and CI were **NOT run**.

**NOT IMPLEMENTED**: the categorised drawer (the More tab carries the categories instead; `drawerOpen` exists in the state but nothing opens it), the bottom sheet, deep-link intake in `MainActivity` (the manifest has no deep-link filter on purpose; there is no app lock yet, M09), a language setting (all existing screen text is still hard-coded Arabic, so an English phone sees English tab labels over Arabic screens), the screens behind most destinations, and the emulator navigation/RTL smoke test.

## M08 to M15 — cores, screens and the acceptance record (2026-10-11)

The branch now has its own CI (`.github/workflows/android.yml`: JDK 17, Android SDK 35, Gradle 8.11.1, `:app:testDebugUnitTest :app:assembleDebug`). Run 1 (`1e80d54`, M05 to M07) and run 2 (`ab3c753`, lock, appearance, activity, backup, home summary) finished green on GitHub Actions; run 3 and later are recorded at the end of this section. A green build is **not** a test on a handset: nothing below has been installed, opened on a phone or reviewed by a second person.

| # | Pure logic (JVM tests) | What the owner sees now | Not done |
|---|---|---|---|
| M08 | Eight palettes with an AA contrast test over every text pair, mode and dynamic-colour resolution, bounded font scale, safe fallbacks (`ui/theme`) | **More > Appearance**: mode, palette, Android 12+ phone colours, font size (committed when the slider is released). Applied app-wide by `OwnerTheme` | Bundled fonts with licences and hashes (only the system font), screenshot review at 320dp and large fonts |
| M09 | Monotonic-clock lock policy, count-only notices (`security`) | **Lock**: the app asks for the phone's own screen lock on start and after the chosen time; content is hidden (and `FLAG_SECURE`) when leaving. **More > App lock** sets on/off and the time | In-app biometric prompt: it needs `USE_BIOMETRIC`, a permission the repository rules (AGENTS.md) forbid without the owner's decision. Notification settings page. Instrumented lock tests |
| M10 | Request tabs that never call an approval "issued", KPIs counted once, Cairo "today" (`domain/Kpi.kt`) | **Home** summary cards; **Requests** filter (waiting / approved and waiting for the PC / approval expired) | The request-detail screen, swipe decisions, charts |
| M11 | Arabic search, Cairo follow-up times, safe links (`domain/Customers.kt`) | **Customers**: a private book (names and phones sealed with their own device key), search, call button (the dialler only), hide and restore, a notes panel per customer. **More > Follow-ups**: add with a Cairo date and time, mark done, hide | Reminders that reach the owner outside the app (WorkManager notifications), branches, links to hub installs, editing a customer, global search |
| M12 | Minor-unit money, Cairo-month totals, expiry buckets, trial drafts (`domain/Licences.kt`) | **More > Money**: post a payment for a customer (amount, currency, optional sealed reference), month totals per currency, recent entries, reverse an entry with an opposite one | The licence expiry calendar and its reminders, product screens, local trial drafts screen |
| M13 | Worker routes `GET /installs`, `/licence/history`, `/licence/events?beforeId`, `/owner/capabilities` (Apps-Factory, 76 tests); phone `OwnerReadClient` | **More > Licences** (history of every request state, paged, with a status filter) and **More > Installs** (ids only). An older hub shows "not supported yet" | Deploying the Worker; incident / telemetry screens |
| M14 | A code reader that matches the factory's Python reader on 135 recorded checks; a closed signing gate (`licensing`) | Nothing new on screen; the Licence Codes page still says issuing is off | Everything about signing |
| M15 | `AuditExport`, `AuditMirrorPlan` | **More > Activity**: the verified activity list (Arabic and English), a copy-the-export button, and a **manual** "send the log to the hub" button | Trash for notes and follow-ups on screen (hidden records are kept in the database; only hidden customers are listed), search across everything, the user guide, the emulator and migration suites, the visual matrix |

Also on screen: **More > Backup** writes a sealed archive to a file the owner picks (`VACUUM INTO`, Android 11 and up). There is **no restore** in the app, the archive opens only with this phone's key, and the screen says both. Room moved to version 4 (additive: `notes`, `follow_ups`, `payment_entries`; posted cash is refused any edit or delete by triggers). Names, phones, note text, titles, references and amounts never enter the audit chain (a test checks the amount).

Safety decisions taken without asking the owner, to be confirmed: no new Android permission and no new dependency were added; the lock uses the phone's screen lock instead of a biometric prompt for that reason; the audit mirror and the history screens only talk to the hub when the owner presses a button or opens the page, and only when a hub is saved.

Still not run anywhere: an emulator or handset, TalkBack, a Worker deploy, an independent review, a migration test from a real version 3 file (the test compares the migration's tables with the ones Room creates), and any drill of lost phone, restore or kill during backup.

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
