# Pixel Plus Owner · Implementation plan
## Keep builds green; establish trustworthy data before expanding the HQ

> **PLAN ONLY · 2026-10-10 · No implementation or deployment authorization implied**  
> Baseline: `e131a04`, branch `feat/owner-app-live-hub-20261010`.  
> UI contract: [DESIGN_BRIEF.md](DESIGN_BRIEF.md). Existing security context: [SECURITY_AND_COMPATIBILITY.md](SECURITY_AND_COMPATIBILITY.md).

## 1. Scope, ordering and baseline

This pass changes exactly DESIGN_BRIEF.md and IMPLEMENTATION_PLAN.md. No application, Worker, Gradle, workflow, font or test files are changed; no tests, builds, commits or pushes are performed. Paths below are **future implementation targets**, not work done in this pass. The owner's explicit permission allows these documents to be written directly without another CLI agent.

Priority: **green build → Room/audit/recovery → navigation → live request journey → broader screens → polish → gated HQ parity/signing**. The existing demo remains isolated; it must never seed real Room tables or silently become live. Current source supports HTTPS configuration, pending requests, approval/refusal, licence events, optional read-only mailbox summaries and periodic notifications. Full CRM, durable cash/audit records and offline decisions are not implemented.

| Existing constraint | Plan |
|---|---|
| No local JDK; Actions builds | GitHub Actions performs all Android compilation/unit checks; no pretend local Gradle verification |
| Gradle 8.11.1 / JDK 17 / SDK 35 / minSdk 26 | Preserve this lane; do not adopt libraries requiring SDK 36 or a toolchain upgrade |
| Workflow `.github/workflows/pixel-plus-owner-android.yml` | Preserve `:app:testDebugUnitTest :app:assembleDebug` and artifact `pixel-plus-owner-debug-apk` |
| Existing `EncryptedSharedPreferences` | Preserve readable settings; isolate deprecated API behind SecureHubStore; migrate deliberately later |
| Existing shared hub tokens | Existing admin and pull auth only; no silent new identity/role system or claim of per-company authorization |
| No `/ack` from phone | Static transport allowlist and negative tests; also no `/licence/ack` or full-registry POST `/installs` |
| Earlier no-signing/software-wrapped-key guidance | Owner's later scope permits only separate non-exportable hardware Ed25519 identity, default OFF; no software fallback |
| PC / hosted operations | Phone completeness is staged; missing endpoints and customer-agent bridge remain explicit limitations |

Before implementation, restore access to Issue #47 and reconcile its requirements. GitHub API read failed in this planning session. Live Worker version, handset hardware and existing customer field acceptance are UNKNOWN. Do not fetch live customer data or use tokens merely to validate a design plan.

## 2. Dependency lock proposal

These are **exact conservative pins**, chosen for the inspected SDK-35/Kotlin-2.1 lane, not the newest available releases. Compatibility is a reasoned proposal until M01 resolves dependencies and produces a green APK; repository pins are not proof that CI ran. No dynamic `+` or snapshot versions. Centralize in the existing Gradle files initially; a version catalogue is optional and must not become a separate migration project.

| Component / coordinate | Pin | Compatibility decision |
|---|---|---|
| Gradle | `8.11.1` | Existing CI installation |
| JDK / Android build tools / compile+target / min | `17` / `35.0.0` / `35` / `26` | Keep baseline |
| `com.android.application` | `8.9.2` | AGP 8.9 supports API 35, Gradle 8.11.1, JDK 17 |
| Kotlin Android, Compose compiler plugin, serialization plugin | all `2.1.20` | All Kotlin plugins match exactly |
| `com.google.devtools.ksp` | `2.1.20-1.0.32` | Kotlin prefix exactly `2.1.20`; select KSP2 consistently |
| `androidx.compose:compose-bom` | `2025.04.01` | Existing BOM; UI/foundation/animation/testing modules inherit its versions |
| `androidx.compose.material3:material3` | BOM-managed `1.3.2` | Stable Material 3 components; no Expressive-only API dependency |
| `androidx.activity:activity-compose` | `1.10.1` | Existing baseline |
| `androidx.appcompat:appcompat` | `1.7.0` | AppCompatActivity + app locale APIs; supplies FragmentActivity for biometric |
| `androidx.lifecycle:lifecycle-{runtime-compose,viewmodel-compose,process}` | `2.8.7` | Lifecycle-aware collection, pure reducer + thin Android view models |
| `androidx.room:room-{runtime,ktx,compiler,testing}` | all `2.7.1` | Compiler via KSP; runtime/processor/testing stay aligned |
| `androidx.navigation:navigation-compose`, `navigation-testing` | `2.8.9` | Navigation 2 typed routes; avoid an unrelated Navigation 3 migration |
| `androidx.work:work-runtime-ktx`, `work-testing` | `2.10.5` | Existing runtime; SDK-35 lane |
| `androidx.biometric:biometric` | `1.1.0` | Stable biometric API; device-credential fallback by supported API path |
| `androidx.security:security-crypto` | `1.1.0` | Existing token storage only; deprecated, no expansion of usage |
| `androidx.datastore:datastore-preferences` | `1.1.4` | Per-device nonsecret appearance/preferences |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.8.1` | Existing pin, Kotlin 2.1 compatible |
| `org.jetbrains.kotlinx:kotlinx-coroutines-{android,test}` | `1.10.1` | Match Kotlin 2.1 lane; test dispatcher controls worker timing |
| `junit:junit` | `4.13.2` | Existing JVM tests |
| `org.robolectric:robolectric` | `4.14.1` | JVM Android/SQLite behavior where feasible; not hardware proof |
| `androidx.test:runner`, `androidx.test.ext:junit` | `1.6.2`, `1.2.1` | Instrumented migrations/navigation later, separate emulator job |
| `androidx.compose.ui:ui-test-junit4`, tooling/test manifest | same BOM | Only appropriate test/debug configurations |

Sources: [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [KSP release](https://github.com/google/ksp/releases/tag/2.1.20-1.0.32), [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Room](https://developer.android.com/jetpack/androidx/releases/room), [Navigation](https://developer.android.com/jetpack/androidx/releases/navigation), [WorkManager](https://developer.android.com/jetpack/androidx/releases/work), [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore), [security-crypto deprecation](https://developer.android.com/jetpack/androidx/releases/security). M01 inspects the resolved graph/AAR minCompileSdk and dependency conflicts; do not claim this researched table is an executed compatibility test.

Use the existing HTTPS transport; no Retrofit/DI framework/chart/crypto library is necessary for the first lane. Dependency-free manual composition root. Native AES-GCM, SHA-256 and Android Keystore; never implement Ed25519 math. Font assets and library notices are offline. No Firebase, Play services, paid SDKs or automatic customer messaging.

## 3. Architecture and navigation map

```text
Compose destinations → view model / pure Kotlin reducer → use cases
                                                     → repositories
Room Flow ← transaction coordinator ← audit / outbox ←┘
    ↑               ↑
Hub sync worker ← existing HTTPS transport → factory-hub
Backup worker → snapshot coordinator → encrypted SAF export
DataStore → appearance/locale preferences
Keystore → token protection / field encryption / backup encryption
         → gated hardware signer (separate key, later)
```

Keep existing root package `com.pixelplus.owner`. Add `data/db`, `data/repository`, `domain`, `audit`, `sync`, `backup`, `security`, `navigation`, `ui/components`, `ui/screens`, `ui/theme`. Preserve `DemoState.kt`, `HubLogic.kt`, `HubClient.kt` contracts/tests while extracting seams. Use injected Clock/ZoneId/UUID source/transport and pure value models; no Android imports in KPI, filtering, money, audit canonicalization, outbox transition and undo reducers.

Navigation Compose typed routes contain opaque IDs only. Never pass tokens, contact text, money references or codes in route arguments.

```text
welcome → settings/hub → lock → home
home ↔ requests ↔ customers ↔ alerts ↔ more        (five root stacks)
requests → request/{id} → decision-dialog
customers → customer/{id} → edit | note/{id} | task/{id} | install/{id}
more → licences → licence/{id} | expiry-calendar
more → money → payment/new | payment/{id}/correction
more → products → product/{id}
more → installs → install/{id} | registration/{id}
more → telemetry → usage | guides | releases
more → tickets → ticket/{id}
more → support → grant/{id} | repair/{id}
more → activity → audit/{id} | audit/export
more → sync → operation/{id}; more → trash
more → settings → hub | appearance | language | lock | notifications | backup
                → alert-rules | agent-access | help
more → codes → verify | public-key | recovery | issue/review/result (all gated)
```

Same route IDs for drawer/More/rail/bottom items. Save/restore root stacks with singleTop; Back first closes sheet/drawer, then detail, then returns normally. Deep links go through unlock and capability checks. A width change must not duplicate actions or navigation events.

## 4. Room schema contract

Room v1 is a new durable store; existing synthetic in-memory records are not migrated. Enable `JournalMode.WRITE_AHEAD_LOGGING`; Room/SQLite foreign keys ON on **every connection**, checked in integration tests. `exportSchema=true`, KSP `room.schemaLocation=<app>/schemas`; commit all exported schemas in future implementation. No `fallbackToDestructiveMigration`, destructive downgrade, silent re-create or destructive REPLACE cascades.

Common mutable-record fields **C**: `id TEXT UUID PK`, `workspaceId TEXT`, `createdAtUtcMs INTEGER`, `updatedAtUtcMs INTEGER`, `revision INTEGER`, `deletedAtUtcMs INTEGER NULL`. Local workspace is an organizational namespace, not a new server authorization scope. FK scope checks prevent linking entities from different hub profiles/workspaces. Store server timestamps separately from device timestamps. Use UTC integers, local dates only for calendar-domain values; all currency amounts use integer minor units + ISO currency, never Float/Double.

| Entity → table | Fields beyond C / source | Keys, indices and relationships |
|---|---|---|
| Workspace → `workspaces` | name, defaultCurrency=`EGP` | Root scope; no physical delete |
| HubProfile → `hub_profiles` | workspaceId, normalizedUrl, originEpoch, capabilitiesJson, lastTestUtcMs | Unique workspace+URL; **no tokens**; change origin invalidates queue dispatch |
| Customer → `customers` | encryptedDisplayName, encryptedPhone, normalizedSearchToken, localStatus | FK workspace RESTRICT; index `(workspaceId,deletedAtUtcMs,localStatus)` |
| Branch → `branches` | customerId, encryptedLabel | FK customer RESTRICT; index customerId; optional single default branch |
| Product → `products` | productCode, displayName, localTrialDays, remoteTrialDays?, policyVersion?, source | Unique `(workspaceId,productCode)`; CHECK trial days 1–60 |
| Install → `installs` | customerId?, branchId?, productId?, hubProfileId?, remoteInstallId?, deviceCode?, version?, health?, lastSeen?, provenance | FKs RESTRICT; unique hubProfile+remoteInstallId; index customer, product, lastSeen; **no install token/hash** |
| CustomerLink → `customer_links` | customerId, hubProfileId, remoteType, remoteId, linkedBy | Explicit relation, unique profile+type+remoteId; no shop-name heuristics |
| LicenceRequest → `licence_requests` | hubProfileId, remoteId, productCode, deviceCode, installId?, customerId?, kind, serverStatus, ownerDecision, ownerDecidedAt?, createdServerAt, fetchedAt, snapshotHash | Unique profile+remoteId; indices `(profile,status,createdServerAt,id)`, customerId; server status separate from queue state |
| LicenceMetadata → `licences` | customerId?, installId?, productId, requestId?, serial?, edition, issuedDate?, firstDate?, lastDate?, graceDays?, seats?, keyFingerprint?, evidenceType, verifiedAt? | FKs RESTRICT; index customerId, `(productId,lastDate,deletedAtUtcMs)`; no expiry inferred from request date; no code stored initially |
| Incident → `incidents` | hubProfileId?, remoteId?, productId?, fingerprint, severity, remoteStatus, firstSeen, lastSeen, count, version, sourceCoverage | Unique profile+remoteId when present; index `(workspaceId,remoteStatus,severity,lastSeen)` |
| IncidentInstall → `incident_installs` | incidentId, installId | Composite unique; both FKs RESTRICT; preserve history |
| AlertOverlay → `alert_overlays` | sourceType, sourceId, readAt?, localResolutionAt?, snoozeUntil? | Unique workspace+sourceType+sourceId; never overwrites remote incident status |
| HubEvent → `hub_events` | profileId, remoteEventId, requestRemoteId?, eventType, eventAt, safeDetail | Unique profile+remoteEventId; index eventAt; append-only source cache, no code/text dump |
| MailboxReceipt → `mailbox_receipts` | profileId, batchId, installRemoteId, receivedAt, byteCount, known, payloadDigest | Unique profile+batchId; cache safe summary, not token_hash/raw compressed payload |
| PaymentEntry → `payment_entries` | customerId, productId?, amountMinor, currency, receivedAt, encryptedReference, confirmedAt, reversalOfId?, correctionGroupId? | FK customer/product/self RESTRICT; unique reversalOfId when nonnull; index currency+receivedAt and customerId; immutable money fields after posting |
| Note → `notes` | customerId, encryptedBody | FK customer RESTRICT; index customerId+updatedAt |
| FollowUp → `follow_ups` | customerId?, licenceId?, encryptedTitle, dueAtUtcMs, dueZone=`Africa/Cairo`, completedAt?, reminderOffsetMin, notifiedAt? | FK customer/licence RESTRICT; index `(deletedAtUtcMs,completedAt,dueAtUtcMs)` |
| AuditEntry → `audit_entries` | See §5; append-only, **no C/update/delete fields** | PK eventId; UNIQUE `(chainId,sequence)`; indices time, screen, action, entityType+entityId, result |
| OutboxOperation → `outbox` | profileId, originEpoch, operationId, operationType, entityId, payloadCiphertext, payloadHash, idempotencyKey, expectedStateHash, dependsOn?, status, attempts, nextAttemptAt, leaseUntil?, lastErrorCode?, expiresAt?, confirmedAt | Unique profile+idempotencyKey; FK profile/self; index `(status,nextAttemptAt)`; audit mirror FIFO per chain |
| UndoRecord → `undo_records` | operationId, entityType/id, inverseCiphertext, expectedRevision, expiresAt, usedAt?, invalidatedAt? | Unique operationId; index expiry; FK audit event for original action |
| SyncCursor → `sync_cursors` | profileId, stream, cursor, lastSuccess, lastAttempt, coverageStart?, coverageEnd?, complete | Unique profile+stream; advance only atomically with cached rows |
| BackupRecord → `backup_records` | artifactId, documentUri?, formatVersion, dbVersion, keyAliasVersion, createdAt, digest, state, verifiedAt?, failureCode? | Index state+createdAt; never contains key bytes/passphrase |
| NotificationReceipt → `notification_receipts` | stableEventKey, scheduledAt, deliveredAt?, channel | Unique event key; no customer details in notification body |
| PreferenceChange → `preference_changes` | settingKey, desiredSafeValue, previousSafeValue, state | Reconcile audited Room intent with DataStore; nonsecret values only |

Planned parity extensions, introduced only with their endpoint/consumer: `tickets` (remote id, redacted payload, status, revision), `support_grants` (installId, scope, customer consent reference, issuedAt/expiresAt/revokedAt), `repair_requests` (grant FK, allowlisted action, state), `metric_buckets` (product/install/day/type/count), `alert_rules` and `alert_deliveries`, `product_policy_snapshots`, `mobile_public_keys` (alias, raw public bytes, fingerprint, securityLevel, status; **no private bytes**), `product_key_trust` (product/version/key FK, acceptance evidence), and append-only `issuance_records` (terms digest, serial, public key ID, payment entry FK, request ID, result; no code in audit). Each gets explicit profile-scoped unique remote IDs and query/FK indices. They are not empty tables added speculatively in M02.

### 4.1 Search, encryption and soft deletion

Room is not itself encrypted. Keep the file in app-private storage with Android backup disabled. Encrypt contact names/phones, notes, payment references, sensitive outbox payloads and canonical audit bodies using separate versioned Keystore AES-GCM keys with random per-write nonces; no custom cipher. Index only approved nonsensitive metadata. For name search, use a keyed normalized exact/prefix token index or decrypt a bounded page in memory; no plaintext FTS of private contacts/notes. First implementation uses bounded in-memory name search and indexed IDs/status/date; add keyed search only if measured volume requires it. Do not claim file-wide database encryption.

Soft-delete mutable local records only, in a transaction with audit and undo. Children remain recoverable; tombstoning a customer hides its active views and dependent tasks but never erases licences, money or audit. Trash restore previews dependencies, restores parent first and rejects conflicting active identities. Do not remove remote authoritative rows when absent from a partial refresh. Remote “delete” means local hide unless a dedicated authorized command exists. No permanent-delete UI/DAO; delivered outbox records remain terminal history. Storage growth is a known cost; warn/export, do not silently prune records.

Posted payment corrections use linked compensating entries, preserving the original amount. “Undo cash entry” creates a reversal, never deletes it. Trash/archive controls must not change historical money totals. Business-row soft deletion does not make retained personal records a telemetry payload.

### 4.2 Migrations and transactional rules

Every schema change increments Room version and exports a schema. Use explicit forward SQL migrations/backfills, no loss of old columns until reviewed conversion. Verify SQLite integrity, foreign_key_check, row counts, money totals and audit hashes before/after. Refuse unsupported newer backups/downgrades with a readable message. Backup before migration; retain previous validated artifact and working DB on failure.

JVM pure tests cover transformations and financial/audit invariants. Robolectric/SQLite tests exercise real SQL where possible. `MigrationTestHelper` tests on a CI emulator cover each adjacent migration plus oldest-supported→latest; pure fake-DAO tests are **not** proof of SQLite migration safety. Snapshot old schemas/fixtures, including soft-deleted parents and queued actions. Use `@Transaction`/`withTransaction` for domain write + audit append + outbox insertion + undo state; a crash must commit all or none.

## 5. Append-only audit and reversible actions

Every important action has an entry: state changes and confirmations/cancellations, important detail views, navigation choice to a sensitive screen, filter/sort application, appearance/language settings, unlock success/failure, exports/restores, sync attempts/results, conflicts and safe error codes. Log semantic actions once, not recompositions, every keystroke or search contents. Tokens, code strings, keys, contact details, note text, payment references, amounts and raw exception bodies never enter audit. Before/after contains allowlisted status/IDs/settings and `[redacted]` indicators for sensitive fields; financial evidence remains in the protected business table.

### 5.1 Exact entry shape and chaining

| Field | Rule |
|---|---|
| `schemaVersion`, `eventId`, `chainId`, `sequence` | UUID IDs; sequence begins at 1 and increments transactionally per device chain |
| `deviceInstanceId`, `actorType`, `workspaceId`, `hubProfileId?` | Pseudonymous IDs; no hardware identifier or user name |
| `occurredAtUtcMs`, `cairoLocalIso`, `cairoOffsetSeconds`, `zoneId` | Store UTC and actual `Africa/Cairo` conversion including DST; device time is not trusted server time |
| `screen`, `action`, `entityType`, `entityId?` | Enumerated action/screen; opaque IDs |
| `beforeSafe`, `afterSafe`, `result`, `reasonCode?` | Typed, allowlisted primitives; result intent/success/failure/cancelled/conflict |
| `explanationAr`, `explanationEn`, `explanationTemplateVersion` | Both sentences generated from safe templates; e.g. “تم تسجيل الموافقة في قائمة الانتظار.” / “Approval was added to the queue.” |
| `correlationId`, `originalEventId?`, `operationId?` | Link retries/undo without editing originals |
| `previousHash`, `entryHash`, `canonicalVersion` | Lowercase hex SHA-256; zero 32-byte previous hash for genesis |

Canonical bytes: schema-fixed field order as a JSON array, explicit nulls, UTF-8, NFC normalized strings, integers only, nested safe maps sorted lexicographically, standard JSON escaping, no whitespace, no locale-specific numbers. Define golden byte fixtures shared with Worker; never rely on arbitrary map serialization. `entryHash = SHA256(previousHash decoded as 32 bytes || canonicalEntryBytes)`, excluding hash fields and mutable transmission metadata. `chainId` and `sequence` are inside canonical bytes. Persist the canonical ciphertext plus necessary safe indices/hash in one writer transaction; hash decrypted canonical bytes during verification.

Audit DAO exposes **append/read/verify only**, no update/delete. SQLite triggers reject UPDATE/DELETE as defense in depth; transactions insert audit with ordinary domain changes. Mirror progress belongs in outbox/cursors, never audit rows. Safe audit templates allow local and remote entries to use the **same canonical bytes/hash**, so privacy filtering cannot break the chain. A separate chain is used on each fresh install/restore generation; preserved imported chains are immutable.

`verifyChain()` checks canonical hash, sequence gaps, predecessor hash and genesis/checkpoint. Tests mutate value, order, timestamp, delete middle entry, replay ID and reorder rows. Hash chains detect tampering relative to a retained head; they cannot prove authenticity against a fully compromised app rewriting the entire chain or truncating an unanchored tail. Retain last-seen heads in backup manifests and hub receipts; compare anchors and display mismatch. Shared admin token is not a cryptographic identity.

Activity exports JSON (canonical entries + chain metadata) and Markdown (both explanations, times, safe before/after, verification result). Preview before SAF save/share to an AI assistant; do not automatically upload/send. Audit the export intent and result without a self-referential hash requirement: export through a fixed sequence, append result afterwards. Verification/read/export do not recursively audit their own internal reads.

### 5.2 Undo

Show Snackbar Undo for approximately 10 seconds for notes/tasks/contact edits/local read state/soft-delete. Store inverse and original revision; compare current revision before undo, append compensating audit event and new revision atomically. After timeout, Activity offers eligible local reversal only if no intervening dependent change. Settings undo writes a new preference change; payments use reversal entries. Expired/conflicting undo explains why it cannot proceed.

No hub approve/refuse Undo once dispatched; confirmation says **لا يمكن التراجع بعد الإرسال / Cannot undo after sending**. A queued intent can be explicitly cancelled before a worker leases it; this is cancellation of unsent work, logged separately. Race with dispatch must reject cancellation; timeout after network send is “outcome unknown”, not safe to undo. A later explicit refusal allowed by existing server rules is a new decision, not an automatic inverse.

## 6. Offline sync and Worker contracts

### 6.1 Existing API facts

Live address suggested in setup: `https://factory-hub.mflma2040.workers.dev`; configurable HTTPS origin, no embedded secrets. Never forward Authorization on redirects or across origins. Validate response size/shape and forbid cleartext.

| Route | Auth | Existing semantics / phone use |
|---|---|---|
| GET `/licence/pending?limit=&after=<created_at>:<id>` | LICENCE_ADMIN_TOKEN | Up to 100 per page; `owner_decision` can be expired; exhaust pages for complete count |
| POST `/licence/decide` | Same admin | `{id,action:approve}` records owner decision, stays pending; nonempty code rejected. `{id,action:refuse}` closes request. `issue` reserved for signing workflow, not ordinary phone requests |
| POST `/licence/states` | Same admin | Up to 50 IDs; state reconciliation only, not complete history; approval expiry needs pending/history server projection |
| GET `/licence/events?limit=` | Same admin | Latest ≤200; currently no cursor; not a full business audit or permanent licence registry |
| GET `/pull?limit=` | RELAY_PULL_TOKEN | Oldest ≤100 opaque base64/gzip batches; includes sensitive token hashes which phone must discard; no cursor, no guarantee of full history |
| POST `/installs` | Pull token | Full registry replacement (`id`,`token_hash`); removes omitted installs. **Never use as phone CRUD** |
| POST `/ack` | Pull token | Deletes mailbox batches; phone never calls |
| `/licence/request`, `/licence/status`, `/licence/ack` | Shop/poll-token contract | No phone request creation or impersonation of shop |

`/pull` rows expire after at most 14 days and PC ingestion acknowledges/removes them; licence events retain 90 days and closed request retention is bounded. Neither source proves complete lifetime revenue, customer count, licence expiry or incident history. Existing source comments that call this undeployed may lag the live URL; deployment remains unverified here.

### 6.2 Outbox state machine

```text
CONFIRMED_LOCAL → QUEUED → LEASED → SENT → CONFIRMED_REMOTE
                       ↘ RETRY_WAIT ↗      ↘ CONFLICT / NEEDS_REVIEW
QUEUED → CANCELLED       any unauthorized → AUTH_BLOCKED
```

A confirmation writes intent audit + outbox atomically; UI shows queued count/age. Worker uses network constraint, unique work per profile, database lease and exponential backoff with jitter. No sensitive data in WorkManager input/output. Lookup encrypted tokens only during request execution. On profile/origin/token changes pause affected work, never send old queued decisions to a new hub. Token rotation resumes after connection check and state reconciliation.

Before each decision: fresh server state, approval lifetime and expected decision match; absence/stale/closed state blocks dispatch. Server remains authoritative during races. Store request+decision stable operation ID; retries reuse it. Existing approve/refuse semantics are replay-aware; do not send a new action to “compensate” for an ambiguous response. Reconcile via states/pending/history. 401/403 pause; 409 becomes human conflict; malformed 2xx fails closed; 429/5xx/network retry with bounded attempts and visible action; overdue owner intent requires confirmation again. Initial recommended intent TTL 24h, never extend the server's approval window.

Mirror entries in chain order, retry identical event bytes and ID. Sync results are audited locally, but uploading an audit entry does not generate another mirrored audit event for that upload alone: summarize actual sync runs to prevent an infinite mirror loop. Use no worker when there is no business/pending work. Notifications deduplicate stable event IDs; first snapshot does not notify. Periodic checks ≥15 minutes are approximate under Doze, not instant push. Local reminders use WorkManager/inexact scheduling; no exact-alarm permission or promise.

### 6.3 Minimal Worker additions (future PRs, no deployment)

Keep `adminOk` exact behavior: nonempty LICENCE_ADMIN_TOKEN and existing constant-time bearer comparison. Export/reuse it or move unchanged into a shared auth helper with regression tests. **Every new owner endpoint below uses this existing admin token.** Existing `/pull`, POST `/installs` and `/ack` keep pull-token auth. Wrong token class must always fail. No signing endpoint, private key, payment proof upload or new role model.

| Addition | Request / response and behavior | Storage / limits |
|---|---|---|
| POST `/audit/append` | `{entry:<canonical safe entry>,previousHash,entryHash}` → `{eventId,sequence,entryHash,receivedAt,replay}`; validates schema/hash and next sequence | One entry ≤16KiB; unique chain+sequence and eventId; identical replay 200, different duplicate/sequence gap 409; atomic conditional insertion |
| GET `/audit/recent?chainId=&beforeSequence=&limit=` | Safe entries plus head hash/sequence and next cursor | Limit 1–100 default 50, indexed pagination, no private business fields |
| GET `/installs?afterId=&limit=` | `{installs:[{id}],nextCursor,coverage}` initially; omit token_hash/gen | Existing table read only, limit 100. Other metadata is unavailable until validated projection exists |
| GET `/licence/history?status=&after=&limit=` | Safe metadata for all request states, effective owner decision/expiry, decided/delivered timestamps; no code/poll hash/ref/src | Index `(status,created_at,id)`; limit 100; backward-compatible additive route |
| GET `/licence/events?beforeId=&limit=` extension | Existing response retains `events`; add `nextCursor` | Existing latest-page behavior unchanged; index/PK ID |
| GET `/owner/capabilities` | Protocol version + supported optional routes, no secret/config dump | Admin-only constant metadata; old Worker 404 means baseline-only capability |

No D1 migration directory currently exists: `schema.sql` uses repeatable CREATE statements (including previous licence_owner addition). Follow that additive pattern for `owner_audit`; add a migration file only if the template later adopts a migrations convention. Never reset live D1.

Proposed `owner_audit`: `event_id TEXT PRIMARY KEY`, `chain_id TEXT NOT NULL`, `sequence INTEGER NOT NULL CHECK(sequence>0)`, `device_instance_id TEXT NOT NULL`, `occurred_at_utc_ms INTEGER NOT NULL`, `received_at INTEGER NOT NULL`, `screen TEXT`, `action TEXT`, `entity_type TEXT`, `entity_id TEXT NULL`, `result TEXT`, `canonical_json TEXT NOT NULL`, `previous_hash TEXT NOT NULL`, `entry_hash TEXT NOT NULL`; UNIQUE `(chain_id,sequence)`, INDEX `(received_at,event_id)` and `(entity_type,entity_id,received_at)`. Safe canonical JSON includes both explanations. Conditional INSERT requires expected prior hash/sequence; concurrent writers cannot fork the chain. No UPDATE/DELETE endpoint or retention cleanup for this table in the initial implementation; growth/capacity is reported. A future retention policy would need explicit anchors and export, not an accidental reuse of telemetry cleanup.

Add `src/audit.js`, `src/owner.js` as needed; route from `worker.js`; amend `schema.sql`; `test/audit.test.mjs` and `test/owner.test.mjs` use existing real SQLite D1 shim. **package.json currently enumerates three test files explicitly**: register both new suites or they will not run. Future command: `cd templates/telemetry-relay && npm test` under Node 22.13+; preserve licence/relay/Telegram suites.

Endpoint tests: missing/empty/wrong admin, pull token on admin routes and vice versa; wrong methods; bounded/chunked body; invalid JSON/unknown keys; PII/secret rejection; canonical hash mismatch; duplicate and conflicting replay; sequence gaps; concurrent insertion; stable cursor boundaries; no token hashes/codes in responses; cleanup does not delete owner_audit; old clients still pass; safe SQL parameters; request approve stays pending and never accepts code. These tests are planned, not run now.

### 6.4 Complete HQ parity needs a real data producer

Adding names to Worker routes does not make Control Center data available. Current D1 has no authoritative customer registry, heartbeat history, incident engine, grant/repair workflow or PC policy store. Minimal endpoints above do not solve this. Plan later bounded projections and command receipts behind the **same adminOk**; privacy and customer-consent rules remain prerequisites. Until then the corresponding screens explain “Not connected yet”, or show explicitly local metadata.

| Later endpoint family | Producer / consumer required | Operations and critical verification |
|---|---|---|
| `/hq/customers`, `/hq/installs`, `/hq/registrations` | Sanitized CC registry projection; explicit local↔remote links | View/register/deactivate/discard via versioned commands; no full-registry replacement; no contact names/phones uploaded without a separate approved scope |
| `/hq/health`, `/hq/incidents`, `/hq/metrics` | Validated af-telemetry taxonomy and CC aggregation | Heartbeats, incident resolution/regression, usage pseudonyms, guide funnel, releases; cursor/replay/coverage tests; schema allowlist unchanged |
| `/hq/tickets`, `/hq/grants`, `/hq/repairs` | CC/product agent bridge, existing customer-issued grants | Redacted ticket status; request/approve 4 allowed repairs only; server checks scope, expiry/revocation at execution, never arbitrary shell |
| `/hq/alert-settings`, `/hq/alert-deliveries` | CC 12-rule engine and channel executor | Versioned switches/thresholds, Cairo work-hours, fan-out results; no channel secrets to phone; external test-send requires explicit owner action |
| `/hq/products`, `/hq/trial-policy`, `/hq/agent-access` | Studio policy consumer with durable trial ledger | Policy versions, daily cap, product days 1–60, agent ≤14 days; off by default, never automated paid issue; no cloud PC signing key |
| `/hq/licences`, `/hq/audit` | Sanitized Studio/CC metadata publisher | Expiry/claims and origin-labelled history; distinguish signed JSON documents from short codes; never expose private key or retained code history by default |
| `/hq/commands`, `/hq/commands/{id}` | Durable command queue and trusted executor | `{operationId,resourceId,expectedRevision,action,safeArgs}` → pending/result; idempotent receipts, conflict 409, execution-time auth/consent checks |

Each family gets bounded pagination/body limits, admin-token negative tests, replay/concurrency tests and producer-to-phone fixture tests before it appears “live”. Registry/incident/policy tables get revision/tombstone fields and additive schema changes. First transition may use PC-published snapshots/commands; label **PC-dependent**. True PC-off HQ requires a reviewed hub-side implementation of the relevant business rules and direct customer-agent integration. That is separate work with measured D1 limits, privacy scope and deployment approval, not a “minimal endpoint” claim. No cloud deployment/spend is authorized by this document.

## 7. Backup, restore, app lock and local preferences

### Backup design

Daily unique WorkManager job (battery/storage constraints) and manual Backup now. Quiesce domain writers and sync, checkpoint WAL, close Room, copy a consistent DB file, create a versioned logical JSON export, then reopen in finally. Never copy an open main DB without WAL consistency. Snapshot manifest contains format/schema/app versions, creation UTC, file sizes/hashes, audit chain heads and source profile IDs, never tokens/private keys. Stream snapshots into an AES-GCM encrypted archive; use a random 96-bit nonce per encryption and authenticate manifest metadata. Keep staging app-private and clear failed temporary plaintext files; do not place plaintext on SAF destinations.

Default backup key is non-exportable Android Keystore AES-256. This fulfills device-bound scheduled encryption but **cannot recover after phone loss, uninstall/key loss or another phone**. UI says so before export. Manual SAF export can copy this encrypted artifact, but copying does not make its key portable.

Recommended separate portable recovery mode: generate a random 256-bit recovery secret, show it once for owner storage outside the phone, wrap a random archive data key using standard AES-GCM, and retain only a Keystore-encrypted copy of the recovery secret for scheduled future exports if the owner opts in. Version and authenticate both wrap envelopes; never include the recovery secret or signing private key in an archive. This is a documented additional recovery choice, requiring owner understanding and a clean-phone restore drill before claiming recoverability. Avoid home-grown passphrase KDFs. If this mode is deferred, mark cross-device recovery **NOT IMPLEMENTED**.

SAF uses persisted document-tree permission for optional automatic export; revocation/disk full/provider offline is visible and leaves the last good artifact intact. No broad storage permission. No automatic permanent deletion of owner records or backups; show storage pressure and require an explicit future retention policy.

Restore: select → reauthenticate → validate envelope/size/hash/key → staging DB/JSON → migration/integrity/FK/audit verification → show counts and warnings → backup current state → atomic replacement → reopen. Failure leaves original DB usable. Disable imported queued network actions until explicit reconciliation/reconfirmation; tokens must be re-entered and signing remains OFF. Preserve imported audit chains, start a new chain referencing their verified heads; never truncate the current audit silently (archive it first). Keystore fields need portable re-encryption through logical JSON for a new phone; a raw DB copy with device-bound ciphertext alone is not portable. JSON and DB representations must have matching snapshot IDs.

### Lock, language and settings

App lock: AndroidX BiometricPrompt with device-credential fallback for ordinary unlock; on API 26–29 use supported credential confirmation flow where combined authenticators are restricted. Add normal `USE_BIOMETRIC` permission (and merged legacy fingerprint permission if the library requires it); this is required by the owner's biometric feature and is an explicit evolution of the earlier app permission list. Keep INTERNET and optional POST_NOTIFICATIONS; no camera/contacts/location/call/Google services. Require fresh unlock after process start; timeout options immediate/1/5/15 minutes background, measured with monotonic time, robust to wall-clock change. Hide content before relock; don't expose data in recents. No homemade PIN database.

Signing authentication is stricter and does **not** accept device credential fallback; see §8. Background backup/notification jobs use separate keys and cannot invoke the signer. Notifications display generic counts; tap routes through lock. Android 13 notification denial leaves all workflows usable in-app.

Preferences in DataStore: locale, theme mode, preset/dynamic, Arabic font, English font, appFontScale, reduced motion, lock timeout, notification categories, backup schedule. Hub tokens remain encrypted in SecureHubStore, not DataStore/Room/route state. AppCompatDelegate.setApplicationLocales with AppCompatActivity and `supportsRtl=true` handles API26+ resource locale/direction; recreation preserves ID-based navigation, not secrets. All strings/plurals/content descriptions exist in values and values-en. Fonts/palette apply live; locale may recreate.

Room and DataStore do not share a transaction: record safe preference intent in Room/audit, apply DataStore, append success and mark intent applied; reconcile incomplete intents on restart without duplicating effects. Slider preview stays temporary; commit/audit one before/after on release, not every pixel. Token changes log only “credential replaced”, no before/after secret, hash or length.

## 8. Licence Codes: API support, exact compatibility and rollout

### 8.1 Verified platform distinction

[Android Signature reference](https://developer.android.com/reference/java/security/Signature) lists Ed25519 from **API 33**. That is the platform cryptographic algorithm, not a blanket promise of hardware-backed AndroidKeyStore on every upgraded phone. [AOSP KeyMint tests](https://android.googlesource.com/platform/hardware/interfaces/+/0c5071f85dd0e36b07e3c63ae8bb21ff358c9695/security/keymint/aidl/vts/functional/KeyMintAidlTestBase.cpp) identify Curve25519 with **KeyMint v2** and exclude StrongBox for this curve in that implementation. [Android Keystore documentation](https://developer.android.com/privacy-and-security/keystore) describes security-level inspection and lists StrongBox's supported algorithm subset. [AOSP provider change](https://android.googlesource.com/platform/frameworks/base/+/2c68aa6bc7d3) also shows that the direct ED25519 generator alias evolved separately; provider spelling cannot be inferred solely from Signature availability.

| API/device | Plan |
|---|---|
| 26–32 | Licence Codes signing unavailable; all normal HQ features work |
| 33+ with old/unsupported KeyMint or software-emulated curve | Unavailable; OS upgrade alone is insufficient |
| 33+ with hardware TEE Ed25519 and required per-use biometric enforcement | Candidate; must pass actual generation/KeyInfo/CryptoObject tests |
| StrongBox present | Presence alone does not provide Ed25519; current cited KeyMint test excludes it. Attempt StrongBox only if actual algorithm support is established; otherwise qualifying TEE is allowed, never software |

Minimum signing candidate is API33; **no Android API level alone guarantees this module**. Use AndroidKeyStore explicitly: test supported EC generator with `ECGenParameterSpec("ed25519")` or the direct Ed25519 alias supported by that OS provider, never switch to an unconstrained software provider when one fails. In capability implementation validate actual KeyInfo securityLevel (TEE/StrongBox), sign purpose, non-exportable key (`encoded == null`), strong-auth enforcement and matching public-key/signature behavior. Temporary aliases are cleaned up; no production signature probe while module OFF. Tests use a separate isolated instrumented harness with synthetic keys.

Set `setUserAuthenticationRequired(true)` and `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on the signing key, require BiometricPrompt CryptoObject for **each** signature, invalidate on enrollment changes as appropriate. No authentication cache, worker signing, batch signing, device-PIN fallback or encrypted-software-private-key fallback. If hardware policy cannot be confirmed, explain unavailable. Emulator/JVM tests cannot establish hardware security.

### 8.2 Byte-for-byte short-code contract

Source of truth: `packages/af-license/af_license/codes.py`, Studio `service.py`, and current product verifier. Do not reimplement cryptographic primitives; Kotlin only packs bytes and uses vetted platform signing.

| Offset / bytes | Value (big endian) |
|---|---|
| 0 / 1 | Format version `1` |
| 1 / 4 | First 4 bytes SHA-256(`AF-PRODUCT/1|` + UTF-8 product ID) |
| 5 / 1 | Edition trial=1, standard=2, pro=3, perpetual=4 |
| 6 / 2 | Issued day since 2024-01-01 |
| 8 / 2 | First day since epoch |
| 10 / 2 | Inclusive last day; perpetual=65535 |
| 12 / 1 | Grace days 0–60; perpetual=0 |
| 13 / 6 | First 6 bytes SHA-256(`AF-DEVICE-TAG/1|` + normalized ASCII 10-symbol device code); zero only where binding is allowed |
| 19 / 4 | Cryptographically random serial bytes |
| 23 / 1 | Seats 1–255 |
| 24 / 2 | First 2 bytes SHA-256(raw 32-byte public key) |

Layout is Python `>B4sBHHHB6s4sB2s`: **26-byte payload**. Sign ASCII `AF-LICENCE-CODE/1` immediately followed by payload, with pure Ed25519 (not Ed25519ph or signing a prehash). Append 64-byte signature = 90 bytes; Crockford alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`; 144 symbols in 24 groups of 6 separated by 23 hyphens (**167 display characters**). Raw public bytes, not X.509 SPKI wrapper, feed key tag/base64url export. Mirror Python normalization/aliases and invalid input behavior exactly.

Studio monthly maps to standard, normally 30 days + 3 grace; trial uses product 1–60 days (default 14), zero grace; permanent requires device, no expiry. Actual owner terms/policy remain authoritative; no invented pricing. Day bounds 0–65535, finite length 1–3660 days and inclusive end are tested; default issued day follows the PC implementation's calendar behavior, with Cairo date choice visible and fixed-clock tests.

Inspected Python tests generate ephemeral keys; no fixed PC-produced golden code was found in the inspected code tests. Before mobile signing implementation, generate a deterministic **public test vector** using the actual Python issuer in a temporary test environment with synthetic inputs, fixed date and serial; retain payload bytes/public key/code, never a production private key. JVM test verifies exact payload/Base32/result against it; JDK17's Ed25519 can verify the vector. Instrumentation signs with a new runtime Keystore test key; Python/Store verifier must accept the produced code. Cross-language tests include trial/standard/perpetual, wrong product/device/key, altered dates, malformed code, old PC code and public key-tag collision handling. Existing Store end-to-end tests must keep passing; no claim of exact signature match across different keys.

### 8.3 Default-OFF gates and exact later rollout

`realIssuanceEnabled=false` remains the effective default. Persist requested toggle separately from effective capability. Every sign entrypoint checks switch + supported hardware + registered product trust + terms/payment evidence + fresh per-use biometric + reconciled request. UI alone is not a gate. While OFF no signature call is possible. Until approved rollout, general APK builds retain the hard-disabled effective gate even if a preview preference is toggled/audited.

Future updates to SECURITY_AND_COMPATIBILITY.md and ROADMAP.md must record these steps (not edited now):

1. Replace old software-key fallback wording with hardware-only capability matrix; keep PC key and previous codes unchanged.
2. Prove code format with synthetic fixtures and actual Store verifier; exercise unavailable devices and biometric cancellation/invalidation.
3. On an eligible phone, owner generates a **new** non-exportable key; export only raw public key/fingerprint. No private-key export/backup/migration command exists.
4. Keep module OFF. Prepare product update that **adds** the mobile public key alongside PC key, with rollback and old-code regression tests; owner approves rollout explicitly.
5. Install/update synthetic product device, then controlled customer devices. Record product/version trust evidence per install; older devices say “PC key only”.
6. Owner enables approved capability only after trust/backup/field gates. Every paid/permanent issue needs explicit received-payment record/reference and fresh strong biometric; Telegram approval alone is insufficient. Existing PC paid gate is unchanged until reviewed parity.
7. Sign locally, verify locally, then optionally deliver using existing `/licence/decide action:issue` only in the gated issuance path after its review. Do not create a signing endpoint; do not put codes in audit. Retrying delivery uses the same signed code/serial, never re-signs silently. Persist any later delivery ciphertext in a separate protected issuance store, excluded from general exports.
8. Loss/recovery: revoke exposed hub tokens; disable future phone use; create new phone key; approved product update adds replacement public key/removes compromised key where policy allows; PC remains fallback. No recovery of old private key from a backup. Already-issued perpetual licences on disconnected old products cannot be instantly revoked.

A global permanent trial ledger and reliable entitlement history must survive beyond the Worker's bounded retention before PC-independent issuance is safe. Resolve PC/mobile concurrent issuance and one-trial-per-machine/device centrally with durable idempotent reservations **without** cloud signing. This gate is not met by M14's disabled capability proof.

## 9. Fifteen independently verifiable milestones

Each row is a small PR target with a runnable app at its end. Larger future parity work is intentionally outside the initial lane (§10). **Every milestone requires A:** Actions runs `gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:assembleDebug`, preserves existing DemoStateTest/HubLogicTest, uploads `pixel-plus-owner-debug-apk`. A previous green SHA is required before starting the next stage. UI remains on the existing route until its replacement passes. CI results must be reported with exact commit/run; no committing/pushing in this planning pass.

Path shorthand: **K** = `apps/pixel-plus-owner-mobile/app/src/main/java/com/pixelplus/owner/`; **T** = corresponding `src/test/java/com/pixelplus/owner/`; **R** = `app/src/main/res/`; **W** = `templates/telemetry-relay/`. `A` always means both unit tests and APK compilation, not a documentation check.

| # | Bounded change / future files | Acceptance criteria | Checks beyond A |
|---|---|---|---|
| M01 | Lock dependencies; root/app Gradle; `K/domain/Clock.kt`; CI only if needed | Pins resolve under existing SDK35; old demo/live flows unchanged; no new feature enabled | Dependency graph/AAR SDK audit; pure clock/Cairo boundary test; no secrets in generated artifact |
| M02 | Room base + customers/products/links; `K/data/db/{OwnerDatabase,Entities,Daos,Migrations}.kt`, `app/schemas/`; `T/DatabaseTest.kt` | WAL/FKs enabled, exported schema; transactional soft-delete/restore; no fake demo import | SQLite/Robolectric FK/orphan/unique constraints; killed transaction rollback; schema export deterministic |
| M03 | Audit + undo infrastructure; `K/audit/*`, `K/domain/Undo.kt`, audit/undo schema migration; `T/Audit*`, `T/Undo*` | All repository writes append safe bilingual chain entries; no audit update/delete DAO; undo revision guard | Golden canonical bytes, tamper/gap/reorder tests, concurrent writers, redaction, soft-delete/restore + undo + payment reversal model |
| M04 | Outbox/cursors + cache repositories; `K/sync/*`, adapt HubClient/HubBackground; schema migration | Offline decisions queued as intents; no optimistic remote success, no `/ack`; fresh reconciliation and profile binding | State machine, retry/lease recovery, double tap, ambiguous network response, stale approval, credential change, notification dedup |
| M05 | Minimal audit Worker contract; `W/src/audit.js`, shared admin helper, worker routing, schema, package test list; audit client adapter | Safe append/recent and replay/head checks; existing auth intact; staged client handles missing route | `cd W && npm test`; auth separation, unknown fields/PII, hash/order/race, quota bounds; Android fake-transport contract tests; no deploy |
| M06 | Device-bound backup/restore core; `K/backup/*`, backup schema/worker, settings entry; `T/Backup*` | Consistent encrypted JSON+DB archive, same-device restore; clear recovery limit; failures keep live DB | Snapshot/WAL consistency, corruption/wrong-key/newer-schema rejection, SAF failure, restore queue quarantine; schedule test; instrumented Keystore check remains separate |
| M07 | Native shell/navigation + bilingual resources; `K/navigation/*`, OwnerApp/MainActivity, `R/values*/strings.xml` | Five destinations + categorized drawer + More; rail at 600dp; complete paired strings, retained root state | Pure route/badge tests; locale resource parity; deep-link gate test; emulator navigation/RTL smoke where available |
| M08 | Theme/fonts/appearance; `K/ui/theme/*`, AppearanceScreen, DataStore adapter, `R/font/*`, `assets/licences/*` | Eight palettes/dark/light/system/dynamic fallback; separate font picks; live slider preserving system scale | Colour pair tests, asset real-TTF/license/source hashes, preference recovery/audit test; APK size report; 320dp and large-font screenshot review pending until rendered |
| M09 | Lock/settings/notifications; `K/security/AppLock*`, settings screens, MainActivity/manifest | API26 credential fallback, timeout; tokens protected; permission-denied notifications still usable | Pure timeout/clock tests, masked audits; instrumented biometric cancel/credential/relock test; worker cannot access signer |
| M10 | Complete live request journey + Home; `K/ui/screens/{Home,Requests,RequestDetail}`, KPI reducers | Pending/approved/rejected mapped honestly; confirmations; daily summary; Canvas chart only when valid data | KPI uniqueness/unknown coverage, request tab logic, old/server conflict, never treat approve as issue; swipe/button equivalent test |
| M11 | Customers/notes/tasks/contacts; database migration + `K/ui/screens/customer/*`, FollowUps | Durable customer details, link devices/requests, local notes/reminders; global search first slice | Filters/sorts/Arabic normalization, tombstone dependent restore, task dates/DST, intent URI validation, no automatic WhatsApp; JVM reminder scheduling |
| M12 | Money/products/licences/calendar; migrations + corresponding screens/reducers | Minor-unit cash + immutable corrections; product trial draft explicitly local; known expiry buckets and reminders | Cairo month totals/currency isolation/reversals; trial bounds, leap day/grace/permanent/unknown expiry; calendar accessibility semantics; no paid issuance implied |
| M13 | Safe registry/history read routes + source-aware alerts/telemetry; `W/src/owner.js`, licence pagination, schema/package tests; install/alert screens | GET installs omits hashes; request history paginated; mailbox partial; local alert overlays never masquerade as remote resolution | `npm test` all suites; two token classes; >100 pagination/tied timestamps; duplicate batches/vanished mailbox; Android mapping/search/filters, old Worker 404 fallback |
| M14 | Disabled Licence Codes module + codec/capability abstraction; `K/licensing/*`, screens, `T/CodeFormatTest.kt`; security/roadmap update | Default OFF; unavailable explanation; exact payload verifier/vector; public-only enrollment design; no production sign path activated | JVM PC vector verification, OFF gate checked from every entrypoint; isolated hardware harness tests API33+/TEE/auth if device available, otherwise explicitly blocked; no private fixture key committed |
| M15 | Activity/search/Trash/sync/backup UI completion and acceptance record; `K/ui/screens/system/*`; ROADMAP/SECURITY_AND_COMPATIBILITY future edits | Owner can find/review/restore/export actions; all unavailable parity screens explain limits; guide explains recovery; every unfinished item recorded | A + Worker `npm test` if touched; migration emulator suite; accessibility/offline/kill/restore drills, visual matrix; record unrun handset gates honestly |

Instrumented tests are an additional CI emulator job, not replacements for required A. `MigrationTestHelper`/Keystore/TalkBack cases may require runtime hardware; keep build green while recording those gates as pending rather than claiming pass. No benchmark/test suite that merely duplicates trivial implementation; target money, authority, replay, recovery and accessibility failure modes.

## 10. Follow-on work for complete HQ parity

The 15-stage lane delivers a durable operational HQ foundation, not all Control Center server capabilities or real mobile issuance. Preserve green builds by executing each following track as separate small vertical slices after its gate, each with A and relevant Worker/PC/product tests:

| Track | Small slice sequence | Definition of done / gate |
|---|---|---|
| Portable recovery | Recovery-secret envelope → SAF export → clean-phone JSON import/re-encryption | Owner can restore all local business records/audit without old Keystore; actual clean-phone drill; signing keys still unrecoverable |
| Registry & health | Sanitized projection producer → list/detail → versioned registration/deactivate commands | Match CC registration/revocation behavior with PC-off executor plan; no token-hash export; install isolation tests |
| Incidents & analytics | Validated durable event projection → incident lifecycle → usage/guides/releases | CC fingerprint/regression semantics, no raw PII, cursor/retention/coverage proofs; active incident resolution is server-confirmed |
| Tickets & support | Redacted ticket list/status → customer grant read → allowlisted repair request/approval | Product executor validates current grant at execution; revoked/expired scope never runs; no arbitrary commands |
| Alert rules & delivery | Read 12-rule settings → validated edits → reviewed test-send/results | Cairo work-hours/6h dedup/fan-out invariants; no customer outreach or unexpected paid channel |
| Studio parity | Product policy sync → durable trial ledger/reservations → agent policy management → licence metadata | Trial uniqueness/race tests and paid owner evidence; PC/shared policies clearly versioned; no autonomous paid issuance |
| Mobile signing rollout | Hardware enrollment → product trust update → trial synthetic proof → paid evidence gate → delivery recovery | Exact old/new verifier compatibility, each signature re-authenticated, owner gates in §8.3; PC fallback retained |

Files: add bounded `W/src/hq_<domain>.js` + tables/tests, CC projection adapters alongside `apps/control-center/control_center/{app,telemetry,alerts}.py`, Studio adapters alongside `apps/licence-studio/licence_studio/{service,relay}.py`, matching app repositories/screens and future ROADMAP entries. Reuse versioned factory contracts where shared; do not copy a stale CC implementation into Worker or edit vendored product verifiers directly. A product public-key update follows the vendor/release workflow in that product repository.

## 11. Risks and honest release gates

| Risk | Control / remaining limit |
|---|---|
| Ed25519 platform support confused with hardware support | API33 is only a candidate; KeyMint2/KeyInfo and real handset proof; StrongBox cannot be promised |
| Shared admin token compromised | Existing model retained, encrypted storage, strict HTTPS/origin, manual rotation; no claim of device revocation or tenant isolation beyond current server |
| PC shutdown before code issue | Approval explicitly awaiting PC until gated signer and authoritative policy parity; no promise that approval delivers a code |
| Mailbox is partial/transient | Last-sync and coverage labels; no invented aggregate records or absence-as-deletion |
| Audit expands data collection | Safe enums/IDs/settings only; no names, phones, amounts, references, notes, secrets or code values mirrored |
| Audit unbounded / outbox unbounded | Monitor size, backlog and D1 capacity; no silent destruction; compression/export/retention require separate reviewed policy |
| Soft-delete leaks through search | Every active query scope filters tombstones; audit/money history intentionally preserved; Trash isolated |
| Device-bound backup gives false reassurance | Explicit same-device-only label until portable recovery track passes; raw DB ciphertext cannot migrate keys |
| Security-crypto deprecated | Retain for compatibility temporarily; later migrate with tested read-old/write-new and no loss of tokens |
| 16 fonts increase APK / inconsistent Arabic | Variable or two weights, glyph tests, per-family licenses; drop failed options with documented reason; font budget measured in M08 |
| Locale or large text breaks workflows | Paired strings, full RTL/LTR change, ≥48dp targets, no fixed row text heights, 200% accessibility checks |
| Notifications late or denied | WorkManager approximate; in-app due list is authoritative; no paid push promise |
| Future support bridge bypasses consent | Backend execution-time grant validation; owner cannot grant customer consent to himself |
| CI green mistaken for production | Separate review, real-device install, kill/recovery drill, old Store acceptance and owner approval; no deploy/merge here |

**Evidence for later reports:** exact commit/PR and Actions run, APK artifact, tests actually executed, omitted checks with reason, screenshot paths actually viewed, endpoint deployment status, clean-phone restore result, Store verification result and field acceptance. Current deliverable is the two plans only; all implementation milestones and field evidence remain planned.
