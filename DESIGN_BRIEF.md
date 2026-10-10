# Pixel Plus Owner · قيادة الشركة
## Design brief — a complete company HQ, built in safe stages

> **Planning only · 10 October 2026 · Arabic first · Android 26+**  
> Branch: `feat/owner-app-live-hub-20261010`; inspected baseline: `e131a04`.  
> Companion: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). No screen described as planned below is claimed to be shipped.

## 1. The owner and the promise

One non-technical owner in Egypt needs to understand what needs attention, make a clear decision, and find its history from his phone. The first complete journey is **open a real request → understand the customer/device → confirm approval or refusal → see the actual hub result and audit entry**. Approval is not issuance, payment, delivery, or activation.

The long-term destination replaces Licence Studio and Control Center operational workflows. Today the phone remains an approval desk; PC signing remains available throughout migration. The later owner requirement permits a separate, default-OFF mobile signing identity, only on qualifying hardware. It supersedes earlier software-key fallback ideas; it does not authorize importing the PC key or activating production signing.

**Success:** the owner finds pending work in two taps, always knows whether data is live/cached/local, can recover local records, and never mistakes a queued action for a completed action. Full PC independence remains conditional on backend parity, device capability and approved public-key rollout.

### Evidence and current limits

Read: factory README/RULES/PARTS; unified execution roadmap; design-factory standards; app AGENTS/README/ROADMAP/SECURITY_AND_COMPATIBILITY, Gradle and CI; `HubClient.kt`, Worker `src/{worker,licence,common}.js` and `schema.sql`; Licence Studio README/service and af-license `codes.py`/tests; Control Center README/API and registry/telemetry references. Issue #47 could not be retrieved because GitHub API access failed. No handset, emulator or competitor app was run; this is source/document research, not a screenshot review. Current production Worker deployment/version is **UNKNOWN**; do not assume branch approval support is deployed.

## 2. Research: useful 2026 patterns, applied deliberately

Research checked 2026-10-10 against primary product documentation. These are strong references, not a claim of an independently ranked “best app” or firsthand visual testing.

| Reference | Documented capability / pattern | Application here |
|---|---|---|
| [Stripe mobile Dashboard](https://support.stripe.com/questions/stripe-iphone-and-android-mobile-apps-mobile-dashboard-app-for-standard-direct-users) | Metrics, customers and payment management on mobile | A compact money summary with drill-down and explicit status. Our amounts are manually recorded cash, never Stripe balances. |
| [Shopify mobile admin](https://help.shopify.com/en/manual/shopify-admin/shopify-app/using-the-shopify-app) | Store work and notifications from a phone | Daily operational overview plus deep customer/product pages; move routine work off the PC progressively. |
| [Linear mobile](https://linear.app/docs/get-the-app) and [Inbox](https://linear.app/docs/inbox) | Actionable issue notifications | Requests and incidents as focused queues: status, urgency, clear next action, history. |
| [Revolut Business analytics](https://www.revolut.com/business/analytics/) | Spend overview and reporting | Legible totals, period selection and transaction details; no suggestion that this app moves money. |
| [Notion mobile workspace](https://www.notion.com/en-us/help/workspaces-on-mobile) | Persistent navigation and workspace search | Consistent way home, global search, notes and follow-ups attached to their customer. |
| [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) and [adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation) | Expressive hierarchy and adaptive navigation | Larger important actions, controlled shape/colour, bottom bar→rail adaptation. Preserve the owner-required drawer even where newer patterns favour expanded rails. |

### Three directions considered

| Direction | Strength | Tradeoff | Decision |
|---|---|---|---|
| Calm editorial | Clear Arabic, generous hierarchy, warm neutral surface | Fewer rows at once | **Selected:** best for decisions by one non-technical owner |
| Dense operations desk | Many records visible | Too much detail on a small phone | Borrow compact filters and tablet split panes |
| Expressive premium | Distinctive shape and movement | Can distract from irreversible actions | Borrow selective emphasis, never celebratory approval effects |

Single foundation: **existing native Jetpack Compose Material 3**. Factory web foundations/CSS are not Android dependencies. Carry over global geometry, semantics and quality gates; adapt browser-only checks to Compose/TalkBack/instrumentation. Creative profile: `design-factory/creative-recipes/windows-micro-motion.json`, essential tier, translated to native transitions. No web animation runtime, 3D, video or decorative bitmap assets.

## 3. Visual system

### 3.1 Eight presets, light + dark, plus dynamic colour

Default **Nile Ink** with System mode. Preset and mode are independent; Material You is a separate palette choice on API 31+, falling back to the last preset on API 26–30. All colours live in theme tokens, never screen literals. Hex values below are proposed source tokens, not already-tested contrast results.

| Preset / Arabic label | Light primary | Light canvas / surface | Dark primary | Dark canvas / surface |
|---|---|---|---|---|
| Nile Ink / حبر النيل | `#244E78` | `#F6F8FC` / `#FFFFFF` | `#A8CCF4` | `#111820` / `#1B2530` |
| Emerald / زمرد | `#176348` | `#F4F8F5` / `#FFFFFF` | `#8DD8B5` | `#101B16` / `#1B2922` |
| Copper / نحاسي | `#8A4A28` | `#FCF7F2` / `#FFFFFF` | `#F2B58F` | `#211811` / `#30241B` |
| Indigo / نيلي | `#5145A1` | `#F8F7FD` / `#FFFFFF` | `#C6BDFF` | `#191627` / `#262239` |
| Teal / فيروزي | `#00666B` | `#F2F9F9` / `#FFFFFF` | `#80D6D9` | `#0F1C1E` / `#1C2A2D` |
| Plum / برقوقي | `#7B4169` | `#FCF6FA` / `#FFFFFF` | `#EAB1D6` | `#21151F` / `#31222E` |
| Slate / أردوازي | `#455B6B` | `#F6F8FA` / `#FFFFFF` | `#B6CBDB` | `#131B21` / `#202B33` |
| Olive / زيتوني | `#586327` | `#F8F9F2` / `#FFFFFF` | `#C6D28E` | `#191D10` / `#282E1C` |

Common light text `#17212B`, secondary `#46535F`, outline `#697785`; dark text `#EDF2F7`, secondary `#BBC6D0`, outline `#8A98A6`. Light on-primary white; dark on-primary `#10202A`. Generate complete Material `ColorScheme` roles from each seed with a pinned offline Material Theme Builder export, then explicitly check every used foreground/background pair; adjust proposed tokens where needed. Containers, on-container, inverse, surface variants, disabled, focus and scrim are required roles, not implicit defaults.

Status pairs (foreground/background): success light `#14633E/#E3F5EA`, dark `#9EE2B8/#173B28`; warning light `#754B00/#FFF0CD`, dark `#FFD684/#3E2D0B`; error light `#A32121/#FFE9E7`, dark `#FFB4AB/#4B1917`; info uses primary/container. Status always also has icon + text. Target contrast 4.5:1 body, 3:1 large text/non-text controls. Dynamic colours get the same checks; no money dashboard filled with unrelated card colours.

### 3.2 Typography and offline font catalogue

Default Arabic **IBM Plex Sans Arabic**, English **Inter**. Bundle fonts locally; no downloadable-font provider, CDN or Play services. The 16-family target below is a planned catalogue, not a claim that assets have been downloaded. Rubik is a requested choice whose Arabic coverage must be checked: if missing, label its Arabic picker entry “Latin/numerals; Arabic uses IBM Plex” or drop it with documentation; never claim unsupported Arabic glyphs are supplied by Rubik.

| Language | Families (Google Fonts directory under `ofl/`) | Packaging |
|---|---|---|
| Arabic | Cairo (`cairo`), Tajawal (`tajawal`), IBM Plex Sans Arabic (`ibmplexsansarabic`), Noto Kufi Arabic (`notokufiarabic`) | Variable TTF if offered; otherwise Regular + Bold |
| Arabic | Almarai (`almarai`), Readex Pro (`readexpro`), Rubik (`rubik`), Changa (`changa`) | Same; verify Arabic shaping, numerals and fallback per family |
| English | Inter (`inter`), Roboto (`roboto`), Poppins (`poppins`), Manrope (`manrope`) | Same; no italic set required |
| English | IBM Plex Sans (`ibmplexsans`), DM Sans (`dmsans`), Nunito (`nunito`), Outfit (`outfit`) | Same |

Source: [official Google Fonts repository](https://github.com/google/fonts), including [IBM Plex Sans Arabic](https://github.com/google/fonts/tree/main/ofl/ibmplexsansarabic) and [Tajawal Regular](https://github.com/google/fonts/blob/main/ofl/tajawal/Tajawal-Regular.ttf). During implementation pin a repository commit, resolve actual TTF filenames, fetch **only** `https://raw.githubusercontent.com/google/fonts/<commit>/ofl/<family>/<file>` (or verified `apache/` path), and fetch that directory's OFL/license text. Validate HTTP success, `file` reporting TrueType, font metadata/glyph coverage and SHA-256; reject HTML/error bodies. Store lowercase_underscore filenames under `app/src/main/res/font/`, license texts plus source/hash manifest under `app/src/main/assets/licences/`. Do not rename font internal family names. Missing asset/license = drop that option and record it in ROADMAP in its later milestone. No asset downloads in this planning pass.

| Role | Default size / line height | Weight |
|---|---|---|
| Page title / hero total | 28sp / 40sp | Bold |
| Section title | 20sp / 30sp | Bold |
| Body / field value | 16sp / 24sp | Regular |
| Row metadata / helper | 14sp / 22sp | Regular |
| Button / navigation | 14sp / 22sp | Bold |

Appearance offers independent Arabic/English font pickers, preview sentence + numbers, and size slider **0.85–1.40** (default 1.0, reset). Apply app scale once to typography size AND line height while retaining system accessibility scaling; never multiply both typography and density. Example: system 1.3 × app 1.2 gives approximately 1.56 at linear scales; respect Android's nonlinear accessibility scaling. Use scrollable layouts and no fixed text heights. Arabic uses no letter spacing; codes, URLs and digit identifiers are LTR-isolated. Use 0–9 digits, `ج.م.`/EGP labels, explicit Africa/Cairo dates and timezone in detail/export views.

### 3.3 Geometry, motion and components

| Token | Specification |
|---|---|
| Spacing | 4, 8, 12, 16, 24, 32, 48, 64dp; phone gutter 16, tablet 24 |
| Shapes | Fields/buttons 12dp; cards 16dp; sheets 24dp top corners; chips pill-shaped |
| Touch | Minimum 48×48dp, icons 20dp inline / 24dp navigation; visible text on destructive actions |
| Layout | One primary action per screen; 16dp card padding; lazy lists, stable IDs; max reading column 840dp |
| Motion | 120ms feedback, 180ms crossfade/AnimatedContent, 200ms animateContentSize; no delayed confirmation |
| Reduced motion | Respect system animator duration; in-app reduce-motion option; instant content swap, no pulsing skeleton |
| Layers | Canvas → surface/row → content → focus/status → sheet/dialog; no background blur dependency |
| Performance | No chart library; Canvas charts have equivalent text summaries and accessible data lists |

Shared components: OwnerScaffold, HubStatusPill, SourceLabel, KpiCard, DailySummary, SearchField, FilterSheet, StatusChip, EntityRow, TimelineRow, MoneyAmount, ConfirmDecisionDialog, UndoSnackbar, EmptyState, ErrorState, OfflineBanner, LoadingPlaceholder, CalendarMonth and SectionHeader. Icons use one bundled Material vector family with license notices; directional arrows mirror, clocks/logos/code text do not.

Every live list supports refresh, last-success timestamp, cached/offline state and retry. Do not replace usable cached content with a full spinner. Saving disables duplicate taps and preserves forms. Denied/token-invalid state links to Hub settings without revealing secrets. No-data state differs from unavailable/unknown, and from a list filtered to zero results. Demo is visibly marked **بيانات تجريبية / Demo data**, isolated from real/local records.

## 4. Information architecture

```text
Pixel Plus Owner
+-- Overview / نظرة عامة
|   +-- Home · Daily summary · Global search
+-- Customers & Licences / العملاء والتراخيص
|   +-- Customers → Detail → Edit / Notes / Tasks / Devices
|   +-- Licences → Detail / Expiry calendar
|   +-- Licence Codes → Verify / Public key / Recovery / Issue (gated)
+-- Operations / التشغيل
|   +-- Requests → Detail → Confirm decision
|   +-- Alerts & Incidents → Detail / Timeline
|   +-- Installs → Detail / Pending registrations
|   +-- Follow-ups → Task editor
|   +-- Tickets & Feedback → Detail
|   +-- Support grants & Repairs → Detail (future, consent-bound)
|   +-- Telemetry → Usage / Guide funnel / Release health
+-- Money / الأموال
|   +-- Revenue & Payments → Record / Detail / Correction
+-- Products / المنتجات
|   +-- Product list → Product detail / Trial settings
+-- System / النظام
    +-- Activity log → Event detail / Verify chain / Export
    +-- Sync centre → Queue / Conflict detail
    +-- Trash → Restore
    +-- Settings → Hub / Appearance / Language / Lock / Notifications
    |            → Backup & Restore / Alert rules / Agent access (future)
    +-- Help & About → Licences / Privacy / Capability status
```

**Exactly five bottom destinations:** Home, Requests, Customers, Alerts, More. This resolves the earlier “5 tabs + More” wording using the owner's later explicit five-item list. More is a page with all remaining categories, not a sixth tab. Drawer contains all categories/subpages and remains accessible from the top start-edge menu. Child screens use Back; root menu is accessible after returning or via scaffold action. Settings never becomes a hidden gesture.

| Available window width | Navigation | Content |
|---|---|---|
| <600dp, including narrow landscape | Five-item bottom bar + modal start-edge drawer | Single pane; detail pushed on stack |
| ≥600dp | Five-item NavigationRail + the same modal drawer; no bottom bar | Single pane until room permits |
| ≥840dp | Rail + drawer; selected list/detail split | ~360dp list + flexible detail; fold hinge respected |

Use available window width, not device model/orientation alone. In Arabic the start edge is right; in English left. Preserve selection, list scroll, filters and detail IDs on resize and rotation. Drawer/More/tab destinations share one navigation graph. Request badge counts **needs owner review**, not approved-awaiting-PC; alerts count unread open items. Counts cap visually at `99+`, TalkBack reads full count and freshness. More shows a single attention dot with an accessible summary, not the sum of unrelated numbers.

Global search covers customers, requests, licences and incidents from the local indexed cache; group results by category, show source/freshness, debounce, and paginate. Never imply whole-company coverage from a partial mailbox cache. Arabic search normalizes diacritics/tatweel and common alef variants without changing stored names. No query text in audit or telemetry. Filters/sorts persist per destination locally; badges count unfiltered data. All strings and accessibility labels exist in `values/` Arabic and `values-en/` English. Locale switch changes resources, layout direction, pluralization and dialogs; system BiometricPrompt follows platform localization limitations.

## 5. Screen inventory and ASCII wireframes

These are logical wireframes, not screenshots. English mnemonics keep ASCII geometry readable; actual UI defaults to formal Arabic. `H` = hub-derived, `L` = local-only, `P` = planned endpoint, `G` = capability/rollout gate. Every `L` screen/section displays **محلي على الجهاز فقط / Only on this device**. Shared shell `MENU | title | SEARCH`, source/freshness strip and the five destinations apply to all roots; detail screens use Back. Each numbered frame is a destination unless marked sheet/dialog.

### 5.1 Start and everyday work

```text
01 Welcome / setup [L]              02 App lock [L]
+------------------------------+   +------------------------------+
| Pixel Plus Owner             |   | Pixel Plus Owner             |
| Arabic [v]   English         |   | Locked · data hidden         |
| [Connect hub] [Try demo]      |   | [Unlock with biometric]      |
| What stays on this phone     |   | [Use device credential]      |
+------------------------------+   +------------------------------+

03 Home [H+L]                      04 Global search [H cache+L]
+------------------------------+   +------------------------------+
| MENU Home             SEARCH |   | BACK Search                  |
| Hub · updated 10:24          |   | [Search all records........] |
| Today: 3 requests need you   |   | All Customers Requests ...   |
| [Review next request]        |   | Customers / source badge     |
| Active customers | Trials    |   |  Customer → detail           |
| Pending | Cash EGP | Alerts  |   | Requests / source badge      |
| Monthly cash: Canvas chart   |   |  Request → detail            |
| Due today · Expiring soon    |   | Offline: cached results only |
| HOME REQ CUSTOMERS ALERT MORE|   +------------------------------+
+------------------------------+

05 Customers [L+H links]            06 Customer detail [L+H]
+------------------------------+   +------------------------------+
| MENU Customers        SEARCH |   | BACK Customer         EDIT   |
| [Search] [Filter] [Sort]      |   | Local contact · hub IDs      |
| Active / Trial / All         |   | [Dial] [Open WhatsApp]       |
| Name · devices · status    > |   | Overview Devices Licences    |
| Name · next follow-up      > |   | Notes Tasks History          |
| [+ Add customer]             |   | Product / request links      |
+------------------------------+   | [+ Note] [+ Follow-up]       |
                                   +------------------------------+
07 Customer editor [L]             08 Note editor [L]
+------------------------------+   +------------------------------+
| BACK New / edit customer     |   | BACK Note                    |
| Name [.....................] |   | Customer [selected]          |
| Phone [....................] |   | [Note text................]  |
| Company / branch [.........] |   | Local only · never mirrored  |
| [Save] [Move to Trash]        |   | [Save] [Move to Trash]        |
+------------------------------+   +------------------------------+

09 Requests [H]                    10 Request detail [H]
+------------------------------+   +------------------------------+
| MENU Requests         SEARCH |   | BACK Request #short-id       |
| Pending Approved Rejected    |   | Product · kind · device      |
| [Filter] [Newest/Oldest]      |   | Owner decision ≠ licence     |
| Shop · trial · waiting     > |   | Payment: confirmed on PC?    |
| Shop · monthly · queued    > |   | Timeline · freshness         |
| Swipe reveals action buttons|   | [Approve] [Reject]           |
+------------------------------+   +------------------------------+

11 Decision dialog [H intent]      12 Licences [L+P]
+------------------------------+   +------------------------------+
| Confirm approve / reject?    |   | MENU Licences         SEARCH |
| Product / device / request   |   | Active Expiring Expired      |
| Approval does not issue code|   | [Filter product] [Calendar]  |
| Cannot undo after sending.   |   | Company · known expiry     > |
| Offline: queued, not sent.   |   | Unknown dates shown unknown  |
| [Cancel] [Confirm]           |   | [+ Record metadata]          |
+------------------------------+   +------------------------------+

13 Licence detail/editor [L+P]     14 Expiry calendar [L+P]
+------------------------------+   +------------------------------+
| BACK Licence                 |   | BACK Expiry calendar         |
| Product · device · edition   |   | [Previous] October [Next]    |
| Start / end / grace / source |   | Sa Su Mo Tu We Th Fr         |
| Permanent / unknown explicit |   |  .  .  .  .  1  2  3         |
| [Renewal reminder] [Edit L]   |   |  4  5  6  7  8  9 10         |
| History · no code in audit   |   | Selected day: licences list  |
+------------------------------+   +------------------------------+

15 Alerts & incidents [H+L+P]      16 Incident detail [H+L+P]
+------------------------------+   +------------------------------+
| MENU Alerts           SEARCH |   | BACK Incident / alert        |
| Open Resolved · Severity     |   | Severity + plain explanation |
| ! Product · fingerprint    > |   | Affected installs / versions |
| ! Backup warning · cached  > |   | First / last seen · timeline |
| Unread badge · refresh       |   | [Mark read locally]          |
+------------------------------+   | [Resolve P] [Add local note] |
                                   +------------------------------+
17 Follow-ups [L]                  18 Follow-up editor [L]
+------------------------------+   +------------------------------+
| MENU Follow-ups              |   | BACK Follow-up               |
| Today Upcoming Completed     |   | Customer [v]  Title [......] |
| [ ] Contact customer · 14:00 |   | Due date/time · Cairo        |
| [ ] Renew licence · tomorrow |   | Reminder [on]                |
| [+ Follow-up]                |   | [Save] [Complete] [Trash]     |
+------------------------------+   +------------------------------+
```

Home KPI definitions: active customers = distinct non-deleted linked customers with known currently active licence metadata; trials = known current trial licences, not trial requests; pending = unexpired requests needing review; revenue = non-reversed local cash entries in selected Cairo month; incidents = open validated incidents in available coverage. Missing inputs render `— / غير متاح`, never zero. A small label explains coverage and last sync. Daily summary combines due follow-ups, requests and known expiries, with no invented forecast.

### 5.2 Money, products and operations depth

```text
19 Revenue & payments [L]          20 Record cash [L]
+------------------------------+   +------------------------------+
| MENU Recorded cash           |   | BACK Record received cash    |
| Local only · October [v]     |   | Customer / product [v]       |
| Total EGP · Canvas bars      |   | Amount [....] EGP            |
| [Filter customer] [Sort]     |   | Received date · reference    |
| Date · customer · amount   > |   | [ ] I confirm cash received  |
| [+ Record received cash]     |   | [Save local record]          |
+------------------------------+   +------------------------------+

21 Payment detail/correction [L]   22 Products [L+P]
+------------------------------+   +------------------------------+
| BACK Recorded payment        |   | MENU Products                |
| Amount · date · reference    |   | Al-Store · trial 14 days    > |
| Manual confirmation history  |   | Source: local draft / PC     |
| [Record reversal]            |   | [Search] [+ Local product]   |
| [Correct with linked entry]  |   | Changes do not update PC yet |
+------------------------------+   +------------------------------+

23 Product detail/editor [L+P]     24 Installs [P]
+------------------------------+   +------------------------------+
| BACK Al-Store                |   | MENU Installs                |
| ID / name / compatibility    |   | Registered Pending           |
| Trial days [14] (1–60)       |   | Product / status / filters   |
| Local setting ≠ Studio policy|   | Device ID · last seen      > |
| [Save draft] [Publish P]      |   | Unavailable if API missing   |
+------------------------------+   +------------------------------+

25 Install detail [P]              26 Pending install review [P]
+------------------------------+   +------------------------------+
| BACK Install                 |   | BACK Registration request    |
| Product/version · customer   |   | Opaque ID · product/version  |
| Health · backup · sync age   |   | Customer [v] · tier [v]      |
| Consent · grants · history   |   | Preview registration effect  |
| [Deactivate P]               |   | [Approve P] [Discard P]      |
+------------------------------+   +------------------------------+

27 Telemetry overview [H+P]        28 Usage / guides / releases [P]
+------------------------------+   +------------------------------+
| MENU Telemetry               |   | BACK Analytics               |
| Mailbox snapshot · partial   |   | Usage Guides Releases        |
| Batch counts · timestamps    |   | Product / period / install   |
| No acknowledgement available |   | Canvas chart + text summary  |
| [Usage] [Guides] [Releases]   |   | Counts / versions / failures |
+------------------------------+   +------------------------------+

29 Tickets & feedback [P]          30 Ticket detail [P]
+------------------------------+   +------------------------------+
| MENU Tickets                 |   | BACK Ticket                  |
| Open Waiting Closed Ideas    |   | Category · redacted report   |
| Product · category · age   > |   | Untrusted text · timeline    |
| [Filter] [Sort]              |   | [Change status P]            |
+------------------------------+   | No automatic customer reply  |
                                   +------------------------------+
31 Grants & repairs [P]            32 Grant / repair detail [P]
+------------------------------+   +------------------------------+
| MENU Support                 |   | BACK Support request         |
| Grants Repairs               |   | Customer consent / expiry    |
| Scope · expiry · status    > |   | Exact allowed action / scope |
| Owner approval still needed  |   | [Approve P] [Reject P]        |
+------------------------------+   | Disabled if grant invalid    |
                                   +------------------------------+
```

Contact shortcuts use `ACTION_DIAL` with `tel:` and a user-opened `https://wa.me/` link after validation. No CALL_PHONE/contacts permission and no automated WhatsApp message. A tap opens another app; it does not record “customer contacted” as fact. Local cash is neither a payment processor nor evidence already accepted by Licence Studio.

### 5.3 System and appearance

```text
33 More / drawer contents          34 Activity log [L; mirror P]
+------------------------------+   +------------------------------+
| More / full category tree    |   | MENU Activity log     SEARCH |
| Customers & Licences       > |   | Date Type Entity Result      |
| Operations / Money         > |   | 10:24 Decision queued        |
| Products / System          > |   | 10:23 Appearance changed     |
| Sync status · Backup status  |   | [Verify chain] [Export]      |
+------------------------------+   +------------------------------+

35 Audit detail / export sheet     36 Sync centre / queue detail
+------------------------------+   +------------------------------+
| BACK Event                   |   | BACK Synchronization         |
| Arabic sentence / English    |   | Last success / next attempt  |
| UTC + Cairo · result         |   | Queued 2 · needs review 1    |
| Safe before → after          |   | Request · attempts · reason  |
| [Undo local action if valid] |   | [Retry] [Review conflict]    |
| JSON / Markdown · preview    |   | Cancel only before dispatch  |
+------------------------------+   +------------------------------+

37 Trash [L]                      38 Settings [L]
+------------------------------+   +------------------------------+
| BACK Trash                   |   | MENU Settings                |
| Customers Notes Tasks ...    |   | Hub / Appearance / Language  |
| Item · moved date          > |   | Lock / Notifications         |
| [Restore] · never erase      |   | Backup / Alert rules P       |
| Parent dependencies shown    |   | Agent access P / Help        |
+------------------------------+   +------------------------------+

39 Hub connection [L secrets]      40 Appearance [L]
+------------------------------+   +------------------------------+
| BACK Hub                     |   | BACK Appearance              |
| HTTPS URL [................] |   | Light Dark System            |
| Admin token [**************] |   | 8 preset swatches + Dynamic  |
| Pull token [***************] |   | Arabic font [v] English [v]  |
| [Test] [Save] [Disconnect]    |   | Small ----O--- Extra large   |
| Token status; never echo     |   | Live Arabic + English preview|
+------------------------------+   | Reduce motion [ ] [Reset]    |
                                   +------------------------------+
41 Language [L]                    42 App lock [L]
+------------------------------+   +------------------------------+
| BACK Language                |   | BACK App lock                |
| (*) العربية  ( ) English     |   | Enabled [on]                 |
| Preview direction & wording  |   | Background timeout [1 min v] |
| [Apply] · retain current page|   | Biometric / credential check |
+------------------------------+   | Signing: strong biometric only|
                                   +------------------------------+
43 Notifications [L]               44 Backup & restore [L]
+------------------------------+   +------------------------------+
| BACK Notifications           |   | BACK Backup & restore        |
| Requests [on] Alerts [on]    |   | Last backup / device-bound   |
| Follow-ups [on] Expiry [on]  |   | Daily schedule · destination |
| Android permission status    |   | [Export] [Restore]           |
| Approximate timing explained |   | Recovery limits / dry-run    |
+------------------------------+   | Progress → validated summary |
                                   +------------------------------+
45 Alert rules & delivery [P]      46 Agent access & trial policy [P]
+------------------------------+   +------------------------------+
| BACK Alert rules             |   | BACK Agent access            |
| Rule · enabled · threshold   |   | Agents · policy OFF          |
| Friday off / Saturday [ ]    |   | Trial limits · daily cap     |
| Delivery status per channel  |   | Create/revoke requires gate  |
| [Save P] [Test channels P]    |   | No phone-held PC secrets     |
+------------------------------+   +------------------------------+

47 Help / About / licences         48 Shared filters (sheet)
+------------------------------+   +------------------------------+
| BACK Help                    |   | Filter records               |
| First-day guide / error help |   | Status [v] Product [v]       |
| Local vs hub / privacy       |   | Date range / severity        |
| Version / capability status  |   | Sort [Newest v]              |
| Fonts & library licences     |   | [Clear] [Show results]       |
+------------------------------+   +------------------------------+
```

### 5.4 Licence Codes: honest, gated capability

```text
49 Licence Codes [G]               50 Verify code [G local]
+------------------------------+   +------------------------------+
| MENU Licence Codes           |   | BACK Verify                  |
| Not active yet [OFF]         |   | Product / device [..........]|
| Device capability / reason   |   | [Paste code]                 |
| Store trusts PC key today    |   | Result / dates / trusted key |
| [Public key] [Recovery]      |   | No code in log or cloud      |
| [Issue] disabled until gates |   +------------------------------+
+------------------------------+

51 Public key / enrollment [G]     52 Signing recovery [G]
+------------------------------+   +------------------------------+
| BACK Mobile public key       |   | BACK Lost phone / key        |
| Hardware status / fingerprint|   | Private key cannot be backed |
| [Generate after re-auth]     |   | up or transferred.           |
| [Copy/export PUBLIC key]    |   | New phone → new public key   |
| Products trusting it: none   |   | Product update; PC fallback   |
+------------------------------+   +------------------------------+

53 Issue/review/result [G, OFF]     54 Shared unavailable / errors
+------------------------------+   +------------------------------+
| BACK Review licence terms    |   | Feature unavailable          |
| Product/device · kind/dates   |   | Why: API/key/permission      |
| Paid: cash evidence/ref      |   | Your existing data is safe   |
| Confirm terms → biometric    |   | [Retry / Settings / Back]    |
| Sign → verify → show result  |   | No fake success or numbers   |
| No background / batch signing|   +------------------------------+
+------------------------------+
```

A feature unavailable on this device remains explainable, not a dead button. Module toggle label: **غير مفعّل بعد / Not active yet**, OFF initially. Toggle changes are audited; turning it on expresses intent, not permission to bypass capability, payment, reauthentication or product-trust gates. While OFF **no signature call** can execute, including tests/probes in production, workers and deep links.

## 6. PC → app parity and data ownership

Legend: **Done/source** = exists in inspected phone source, not field-verified. **Planned** = no current phone parity. **Gated** = missing backend, trust rollout or consent boundary. Hub endpoints are Worker paths, never confused with Control Center `/api/*` routes.

| PC feature | App destination | Source / endpoint | Phone status |
|---|---|---|---|
| Studio shop request inbox | Requests | `/licence/pending` admin token | Done/source; richer tabs planned |
| Owner approval/refusal | Request detail | `/licence/decide` approve/refuse | Done/source; deployment unknown |
| Request reconciliation/history | Requests / Activity | `/licence/states`, `/licence/events`; proposed `/licence/history` | Events done/source; full history planned |
| Trial/monthly/permanent code issue | Licence Codes | Local hardware signer; future existing `issue` transport | Gated OFF; PC signs today |
| Codes search/copy/renew and verifier | Licence Codes / Licences | Local verified metadata/import; no existing code-history API | Planned; code disclosure gated |
| Product catalogue / 1–60-day trial | Products | Local draft; future policy endpoint + Studio consumer | Planned; no effect on PC policy yet |
| PC key creation/unlock/public export | Public key / Recovery | New phone Keystore identity; PC key never imported | Gated; no equivalent PC-key unlock |
| Automatic trials / agent tokens / MCP | Agent access | Future policy/agent bridge; PC executor today | Gated; no automatic mobile signing |
| Customer registry | Customers | Local CRM + explicit IDs; future sanitized registry bridge | Planned; not inferred from shop label |
| Registered/pending installs; deactivation | Installs | Proposed GET `/installs`, future registry commands | Planned; current POST is PC full replacement |
| Heartbeats, backup/sync health | Install detail | Partial `/pull`; future validated health projection | Planned; raw mailbox not complete health |
| Incidents / regression / resolution | Alerts & incidents | Partial local derivation; future `/hq/incidents` | Live licence events only done/source |
| Usage by pseudonym / guide funnel / releases | Telemetry tabs | Future sanitized `/hq/metrics` | Gated backend projection |
| Tickets / problem reports / ideas | Tickets & Feedback | Future redacted `/hq/tickets` | Gated privacy and bridge |
| Customer support grants / allowlisted repairs | Support | Future `/hq/grants`, `/hq/repairs` bridge | Gated; consent cannot be created by owner alone |
| Licence claims / signed JSON document desk | Licence detail | Future CC bridge; separate af-license document format | Gated; not the 144-symbol code format |
| 12 alert rules / channels / delivery reports | Alert rules | Future `/hq/alert-settings`, `/hq/alert-deliveries` | Gated; local notifications planned |
| PC audit history | Activity log | Phone local chain + proposed `/audit/*`; future imported PC projection | Planned; distinguish separate origins |
| Relay ingestion/ack and registry synchronization | Telemetry | Phone GET `/pull`; PC alone POST `/ack` and full POST `/installs` | Read summary done/source; never phone ack |
| Cash, customer notes, follow-ups, Trash | Money / Customer / System | Room, local notifications | Planned phone-native additions |
| Backups, settings, theme and lock | Settings | Room/DataStore/Keystore/SAF | Hub settings done/source; rest planned |

### Source labels and integrity

- **Hub / مباشر من المركز:** confirmed response from the configured HTTPS hub, with timestamp. Token scope remains existing shared admin/pull scopes; local company filters are not server authorization.
- **Cached / نسخة محفوظة:** last known hub response. Loss of a mailbox row after PC acknowledgement does not mean an incident was resolved or a customer deleted.
- **Local / محلي على الجهاز فقط:** contacts, notes, follow-ups, cash, reminders, local read/resolve overlays, appearance and manually entered licence/product metadata. Never silently upload these through telemetry or audit.
- **Unavailable / غير متاح:** no trusted data source. No placeholder numeric KPI, invented customer-device join, or false delivery/activation.
- Linking a local customer to a hub request/install is explicit and audited. Shop display text, device code and install UUID are different identifiers; preserve all namespaces. No new personal information is sent to the Worker by this plan's minimal endpoints.

## 7. Acceptance and outstanding evidence

Target checks: 320/390dp phones, 600/840dp boundaries and 768/1440dp wide windows; AR/EN × light/dark × eight presets and dynamic fallback; largest app/system fonts; keyboard/TalkBack; gestures with button alternatives; offline refresh; token denial; queued conflict; restore; reduced motion. Native checks replace axe/browser gates for this app. Chart summaries and calendar cells must be accessible. Visual baseline and after screenshots remain **UNVERIFIED** until actual APK rendering is reviewed.

Dependencies remain conservative for SDK 35; the design borrows Expressive principles without requiring experimental 2026 APIs. The implementation plan defines green-build milestones, schema and endpoint contracts, exact dependency pins, Keystore limitations, backup recovery and later updates required to ROADMAP/SECURITY_AND_COMPATIBILITY. Only these two planning documents are changed in this pass.
