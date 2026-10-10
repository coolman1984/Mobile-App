# Security & licensing architecture: do NOT skip

This document is the **threat and compatibility contract**, not an assertion the secure signer already exists.

## Actual existing baseline
- The existing `apps/licence-studio` signs 144-character Ed25519 codes on a trusted owner PC, with an encrypted private key, after owner unlock. The Store validates codes offline using trusted public keys.
- `apps/control-center` has an API, requests, customer/install registry and alert model; its README explicitly marks hosted production deployment as NOT DONE.
- Telegram owner buttons can record trial decisions, but a trusted PC is needed to sign existing codes. Do not put the current desktop private key in this new mobile app, an APK, repo, CI, hosted relay or push notification.
- The new Android foundation **cannot** issue real codes, reach customers or notify the owner; it holds synthetic data in memory and has no INTERNET permission.

## Trust model
- Generate a **new mobile signing identity** after secure hardware-based enrollment and owner reauthentication. Android Keystore is used for protecting/wrapping material where supported; do not assume Ed25519 hardware signing is universally offered. Provide honest secure fallback and test compromises.
- Each existing client installation trusts only its configured public keys. Before using a mobile-signed code, distribute/register its trusted new public key to Store and other relevant apps in an approved software update. Do not replace or accidentally revoke the desktop public key; preserve old codes.
- Signing locally offline is feasible for registered product/device claims. It is NOT automatically safe to approve new cloud requests offline: trial uniqueness and paid payment evidence need server-authoritative reconciliation.
- Phone loss/revocation stops future *authorized* issuance after reconciliation, but issued perpetual offline codes cannot be invalidated instantly at disconnected customers. Document this limitation.
- A device-bound signed licence is not unbreakable anti-piracy. Design auditable misuse controls without destroying customer's read/export/backup rights.

## Mobile app hard boundaries
- Local owner PIN/biometric with lock after idle; fresh authorisation on sensitive action; no Android backup of signing state; no debug logs of tokens/keys/code values.
- All real API calls HTTPS with scoped revocable tokens; secret material never hard-coded. A production server must enforce tenant/role authorization on every request.
- Anti-replay: request ID + idempotency key, server-side state transitions, conditional writes, duplicate delivery safe. Never mark "activated" from just a Telegram button.
- Offline local audit append-only with sequence/tamper evidence, encrypted at rest and reconciled; do not fake remote audit when offline.
- Separate test/staging from real production; synthetic company and device fixtures only in APK published by this foundation.
- Licensing financial terms are an OWNER decision; no invented prices, payments, customer communications or new hosting costs.

## Negative tests to add before live integration
1. Switch companies then attempt to issue/read for a company outside the authenticated owner scope.
2. Try to sign after device revoke, app-lock timeout, wrong PIN, emulator instrumentation and copied key file.
3. Try monthly/perpetual without explicit verified payment approval/reference, or via agent/Telegram only.
4. Forged product/device/expiry; old PC code under new trust list; newly signed code on old client missing new trusted key.
5. Device disconnected, clock rollback, double tap/replay, queued code delivered twice, approval cancelled during signing.
6. Real backend 401/403/5xx, network loss, expired push tokens, concurrent sessions and app resume after kill.
7. Physical phone loss/recovery drill and operator documented revocation steps.

## Decision gates
- Signing key registration/rotation to shipped customers: needs the owner's explicit approval and rollback evidence.
- Cloud deployment, push provider, hosting spend and collection of real personal/company data: need scope, region, cost and privacy approvals.
- Real paid issuance: needs payment confirmation with reference, owner authentication and audit.
- Launch: do not claim ready just because Gradle/JUnit succeed. Demonstrate a real device + real Store verification flow first.
