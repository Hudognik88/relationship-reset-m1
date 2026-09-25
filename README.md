# Relationship Reset — M1 CIS

Existing static GitHub Pages project, adapted for the Russian-speaking paid-pilot hypothesis on 25 September 2026. The international $149 / 30-day hypothesis is deferred in the existing sales package; the prior code remains in Git history.

## Current public experience

- Russian landing → 10-step free questionnaire → conservative local orientation → optional local intake review/copy.
- Planned paid pilot: 7 days from first delivery, first personalized answer + two outcome-based revisions. First three independent buyers: RUB 1,490; next seven: RUB 2,490. No subscription or unlimited chat.
- AI drafts and human review apply to the planned **paid** service. The free page uses deterministic JavaScript rules, not an LLM or human review.
- First paid answer: within 24 hours after both verified payment and receipt of intake. Follow-ups requested during the seven days: response within 24 hours.
- **No payment links, checkout, order collection, analytics, AI API, or backend are enabled.** Opening `success.html` does not verify payment or submit an order.

## Safety and data behavior

Structured safety/age/contact-boundary questions take precedence over the relationship goal. A conservative RU/EN free-text guard also stops obvious risk descriptions even when safety is marked No. It is not an exhaustive classifier; it can miss risks or match negated/historical statements. Do not market the free result as a safety assessment or full natural-language personalization.

No-contact requests and requests for space never produce a message script. Failed contact attempts lead to private reflection. The only conversation wording is conditional on an already ongoing, voluntary calm conversation, clear boundaries and no reported failed attempt. Time alone never cancels a boundary.

Completed STANDARD cases are stored in `sessionStorage` under `rr_case` and `rr_diag`, using `schemaVersion: m1-cis-v1` and a matching opaque `cis_` case ID. Safety/minor paths clear prior stored intake and hide the paid offer. Restart and intake deletion clear both keys. If storage is unavailable, the free result still works but review/copy is unavailable. No questionnaire text goes into URLs or network requests. Hosting can keep technical access logs.

`success.html` validates the schema, case ID, enumerated answers, date and safety mode before displaying intake. It rejects legacy or malformed cases and never treats URL payment parameters as evidence.

## Seller decision — 25 September 2026

The user confirmed that his wife agrees to become the actual seller of the pilot, take responsibility for delivery/support/refunds, and publish the seller name and INN supplied in the Prodamus form. Seller: София Мурмановна Липартелиани; INN 760605353023. Her NPD status is user-reported, not independently verified. This decision supersedes the earlier founder-only seller plan; she is not merely a nominal payment recipient. Relationship Reset remains the product brand.

## Before accepting money

Seller identity is published. Confirmation of her current NPD status, provider approval for this service and buyer geography, actual support/intake channel, final terms/refunds/privacy/retention, and a verified payment-to-delivery test remain open. Do not add a payment URL before these are resolved. Confirm a manual payment verification process (status, amount/currency, customer, case and duplicate check) for this bounded pilot. Do not substitute a redirect for payment verification.

Pilot measurements and price changes are manual. This site does not count purchases or switch to RUB 2,490 automatically. Update all public price text before offering the fourth paid order. No paid advertising is authorized by this change.

## Sources of truth

- [Sales package — active M1-CIS strategy](https://docs.google.com/document/d/1o32d45w3e9G6vfzOHz50ate5QAiqvJ-JyTurZ6aUnAc/edit)
- [Tracker — M1 CIS](https://docs.google.com/spreadsheets/d/1Y3pAa27q3Lr5ha4TS0Bm4woiOrabVpxtPGyhNbW2IzM/edit#gid=1912543866)
- [Existing public site](https://hudognik88.github.io/relationship-reset-m1/)

## Development and verification

No build or package install is required. Serve this directory as static files. Run `node --test tests/*.test.cjs` for the substantive rule regressions. `node --check app.js` and `node --check reset-logic.js` check syntax. Browser-check the normal flow, safety from free text and structured answers, no-contact, back/restart, intake validation/copy/delete, and narrow-screen layout after changes.
