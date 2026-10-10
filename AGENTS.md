# Coding instructions: Pixel Plus Owner Android

Before editing, read the parent factory's `AGENTS.md`, `RULES.md`, `PARTS.md` and Issue #47. Then read README / ROADMAP / SECURITY_AND_COMPATIBILITY here. Do not treat product plans as already implemented.

- Native Android in Kotlin + Compose; Arabic-first RTL, accessible thumb-friendly screens. Demo model is intentionally pure Kotlin for testing.
- Never introduce a code-signing endpoint in a cloud service. Existing encrypted owner-PC Ed25519 key stays on the PC. A NEW mobile signing identity requires hardware-backed wrapping, re-auth, trusted public-key rollout and revocation/recovery design.
- `INTERNET` is allowed only for the https hub client (owner token and optional pull token in `EncryptedSharedPreferences`). No cleartext. No `/ack`. No Firebase. Do not add other permissions beyond `POST_NOTIFICATIONS` for local alerts.
- Keep sample company data fictional when the hub is not configured, application ID distinct, and `realIssuanceEnabled` false. The phone must not sign or store a licence code. Approval only records the owner's decision.
- Any paid monthly/permanent issuance must require documented owner payment confirmation/ref and a fresh local biometric/PIN gate. Telegram button approval is not payment proof.
- All currently shipped products and previous PC codes must remain verifiable; old Store devices cannot suddenly trust a new mobile key without an approved update.
- The demo state is in memory, not persistence. Never accidentally label simulated approval/delivery/alerts as real.
- For every change: code + automated tests + docs, `gradle :app:testDebugUnitTest :app:assembleDebug`, independent review, PR into main only after required checks pass.
- Never auto-merge or deploy based on missing green tests or old review commits.
