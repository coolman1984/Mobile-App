# Coding instructions: Pixel Plus Owner Android

Before editing, read the parent factory's `AGENTS.md`, `RULES.md`, `PARTS.md` and Issue #47. Then read README / ROADMAP / SECURITY_AND_COMPATIBILITY here. Do not treat product plans as already implemented.

- Native Android in Kotlin + Compose; Arabic-first RTL, accessible thumb-friendly screens. Demo model is intentionally pure Kotlin for testing.
- Never introduce a code-signing endpoint in a cloud service. Existing encrypted owner-PC Ed25519 key stays on the PC. A NEW mobile signing identity requires hardware-backed wrapping, re-auth, trusted public-key rollout and revocation/recovery design.
- No `INTERNET` permission until a reviewed authenticated client, an explicit owner-approved backend deployment and negative isolation tests exist.
- Keep sample company data fictional, application ID distinct, and `realIssuanceEnabled` false until *all* crypto, authorization and trust-rollout acceptance checks pass.
- Any paid monthly/permanent issuance must require documented owner payment confirmation/ref and a fresh local biometric/PIN gate. Telegram button approval is not payment proof.
- All currently shipped products and previous PC codes must remain verifiable; old Store devices cannot suddenly trust a new mobile key without an approved update.
- The demo state is in memory, not persistence. Never accidentally label simulated approval/delivery/alerts as real.
- For every change: code + automated tests + docs, `gradle :app:testDebugUnitTest :app:assembleDebug`, independent review, PR into main only after required checks pass.
- Never auto-merge or deploy based on missing green tests or old review commits.
