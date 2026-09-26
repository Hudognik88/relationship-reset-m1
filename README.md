# Relationship Reset — M1 CIS

Existing Relationship Reset project, adapted for the Russian-speaking paid-pilot hypothesis on 25 September 2026. The public frontend remains static. This branch also includes the first staging PHP/MySQL backend and Beget deployment tooling. The international $149 / 30-day hypothesis is deferred in the existing sales package; the prior code remains in Git history.

## Kotlin migration — 26 September 2026

The Kotlin/Ktor staging service is developed separately in [backend-kotlin](backend-kotlin/README_RU.md). It preserves the PHP API contract, the exact MySQL migration and retry semantics. Its workflow builds and tests the service and container; it does not switch Beget hosting. The existing PHP deployment remains the fallback. Both backends are operator-only rehearsals with fictional data; client accounts, real orders and payment integration are subsequent work.

The Beget PHP deployment, MySQL setup and authenticated HTTPS readiness check were completed after the earlier snapshot below. Kotlin hosting has not been provisioned or switched. See [three fictional pilot examples and interview questions](docs/PILOT_EXAMPLES_RU.md) for the parallel product work.

## Earlier conversion snapshot — staging only, no commercial release

Status as of **25 September 2026, 19:18 Moscow time**: the user has created the Beget account and purchased **poslessory.ru**, as confirmed by the domain/DNS/SSL screenshots. Six public staging files have been uploaded to `movereed.beget.tech/public_html`. An Android screenshot confirms the first screen on the technical domain; the full questionnaire flow, HTTPS and public operation of the final domain have not been verified. The existing repository, `main` and GitHub Pages demo remain unchanged. The commercial release is not open. Keep the repository and history; complete the deployment on Beget and the purchased domain rather than registering another account or buying the domain again.

The draft lowers the sequential price test to **RUB 990 for the first three independent buyers, then RUB 1,490 for the next seven**, for the same service scope. RUB 2,490 is deferred. The existing public demo may still show the preceding RUB 1,490 / RUB 2,490 hypothesis until an approved release is published; do not treat this draft as a live price change. Align the landing, terms, Telegram posts, sales package, and tracker before accepting the first order.

## Product experience in this draft

- Russian landing → 10-step free questionnaire → conservative local orientation → optional local intake review/copy.
- Planned paid pilot: 7 days from first delivery, first personalized answer + two outcome-based revisions. First three independent buyers: RUB 990; next seven: RUB 1,490. RUB 2,490 is deferred. No subscription or unlimited chat.
- AI drafts and human review apply to the planned **paid** service. The free page uses deterministic JavaScript rules, not an LLM or human review.
- First paid answer: within 24 hours after both verified payment and receipt of intake. Follow-ups requested during the seven days: response within 24 hours.
- **No payment links, checkout, order collection, analytics, AI API, or backend connection are enabled in the public frontend.** Opening `success.html` does not verify payment or submit an order.

## Safety and data behavior

Structured safety/age/contact-boundary questions take precedence over the relationship goal. A conservative RU/EN free-text guard also stops obvious risk descriptions even when safety is marked No. It is not an exhaustive classifier; it can miss risks or match negated/historical statements. Do not market the free result as a safety assessment or full natural-language personalization.

No-contact requests and requests for space never produce a message script. Failed contact attempts lead to private reflection. The only conversation wording is conditional on an already ongoing, voluntary calm conversation, clear boundaries and no reported failed attempt. Time alone never cancels a boundary.

Completed STANDARD cases are stored in `sessionStorage` under `rr_case` and `rr_diag`, using `schemaVersion: m1-cis-v1` and a matching opaque `cis_` case ID. Safety/minor paths clear prior stored intake and hide the paid offer. Restart and intake deletion clear both keys. If storage is unavailable, the free result still works but review/copy is unavailable. No questionnaire text goes into URLs or network requests. Hosting can keep technical access logs.

`success.html` validates the schema, case ID, enumerated answers, date and safety mode before displaying intake. It rejects legacy or malformed cases and never treats URL payment parameters as evidence.

## Seller decision — 25 September 2026

The user confirmed that his wife agrees to become the actual seller of the pilot and take responsibility for delivery, support and refunds. Her NPD status is user-reported, not independently verified. This decision supersedes the earlier founder-only seller plan; she is not merely a nominal payment recipient. Use the existing sales package for confirmed seller details rather than duplicating personal identifiers in technical documentation. Relationship Reset remains the product brand.

## Contacts and onboarding — 25 September 2026

The project Telegram channel and public support contact are confirmed in the existing sales package. The user has verified the email in the Prodamus application. Prodamus has sent the payment-page access email and reports that the issued payment page is in working mode. This does not establish that the full payment, NPD receipt and delivery flow has passed testing; there is still no payment button on the site. Do not connect the project Gmail account to the assistant: the user will report incoming messages. No email access or sending is authorized by this update.

The published contact is for general questions and data requests. Do not request sensitive intake answers, personal relationship histories, or third-party correspondence by email while the paid-intake process and data handling remain undefined. The Telegram channel is for public project updates, not a verified private intake channel.

## Before accepting money

Seller identity is published. Confirmation of her current NPD status and receipt setup in «Мой налог», the service and buyer geography allowed by the provider, the paid-intake and delivery process, final terms/refunds/privacy/retention, and a verified payment-to-delivery test remain open. The issued Prodamus page alone is not an end-to-end readiness check. Do not add a payment URL before these are resolved. Confirm a manual payment verification process (status, amount/currency, customer, case and duplicate check) for this bounded pilot. Do not substitute a redirect for payment verification.

Pilot measurements and price changes are manual. This site does not count purchases or switch from RUB 990 to RUB 1,490 automatically. Update all public price text before offering the fourth paid order. No paid advertising is authorized by this change.

## Sources of truth

- [Sales package — active M1-CIS strategy](https://docs.google.com/document/d/1o32d45w3e9G6vfzOHz50ate5QAiqvJ-JyTurZ6aUnAc/edit)
- [Tracker — M1 CIS](https://docs.google.com/spreadsheets/d/1Y3pAa27q3Lr5ha4TS0Bm4woiOrabVpxtPGyhNbW2IzM/edit#gid=1912543866)
- [Existing public demo on GitHub Pages](https://hudognik88.github.io/relationship-reset-m1/)

## Development and verification

### Test hosting before release

The staged rollout is documented in [DEPLOYMENT_RU.md](DEPLOYMENT_RU.md). Run `python3 scripts/package_staging.py` to build `dist/relationship-reset-test.zip` from the existing public files. Only the generated copies receive the test banner and noindex metadata; the source pages stay unchanged. The ZIP is for invited fake-data testing on Beget's technical domain, not a sales launch. The Beget account and six-file staging deployment already exist. Use the archive instructions for repeat deployment, not a new signup. The user has purchased `poslessory.ru`; complete final-domain binding, HTTPS, payment setup and verification before commercial release. Do not upload the entire repository or the personalized Prodamus PDF.

No build or package install is required. Serve this directory as static files. Run `node --test tests/*.test.cjs` for the substantive rule regressions. `node --check app.js` and `node --check reset-logic.js` check syntax. Browser-check the normal flow, safety from free text and structured answers, no-contact, back/restart, intake validation/copy/delete, and narrow-screen layout after changes.

## Conversion draft verification

The first-payment redesign remains on a separate branch and is uploaded as a staging copy to Beget. Existing rule regressions (12) pass; JavaScript syntax, local links, unique IDs and required DOM targets were checked. An Android first-screen screenshot on the technical domain is available, but complete browser/mobile and questionnaire QA remains unverified. The user has created the hosting account and bought `poslessory.ru`; no commercial release or payment enablement on the site has occurred. No customer has been contacted automatically.

The Beget panel shows A records for the apex and `www` pointing to `87.236.16.38`, as requested by Beget. SSL issuance previously failed. A DNS recheck and retry were recommended; the reason for failure is not established, and DNS propagation must not be recorded as the confirmed cause. Final-domain binding and HTTPS must be independently verified before release.

## Parallel work while deployment checks remain open

- Prepare or publish owner-approved Telegram posts to measure interest in the format and price, without accepting paid orders or collecting sensitive histories.
- Rehearse the first AI-assisted delivery on a fictional case and record the human review time and quality.
- Configure NPD/«Мой налог» and the provider's demo test, then verify the permitted payment/receipt/refund path before any customer checkout.
- Select the real intake and delivery channel, permitted AI service and data handling process; these remain unresolved.

These tasks can proceed without waiting for domain troubleshooting. The commercial launch still depends on the open technical, operational and payment checks.

## Backend and GitHub → Beget deployment — 25 September 2026

The user requested automatic deployment, a database and a small backend while Prodamus testing is deferred. Continue this branch and the existing Beget site; no new hosting service or paid infrastructure was purchased.

- [Backend contract and setup](docs/BACKEND_RU.md): PHP 8.3+, PDO MySQL, authenticated synthetic case storage/read and unreviewed draft storage. Public frontend behavior remains local. No public intake, payment confirmation or external LLM calls are enabled.
- [Deployment setup](docs/BEGET_AUTODEPLOY_RU.md): staged archive with an explicit file allowlist, private backend outside `public_html`, preserved private config, scoped SSH receiver, backup and rollback. Migrations are a separate owner-run CLI step.
- The authenticated Beget panel was inspected: MySQL 8.4 is available at `localhost`, the database user equals the database name, there are no databases yet and SSH access is off. Both domains are linked to the existing site directory in the panel; public DNS/HTTPS verification is separate.
- Deployment source preparation is not proof of an active connection. Creating database credentials, authorizing SSH and configuring GitHub secrets remain owner steps before the first hosted run. No real customer data belongs in this staging API.

The workflow's first verified deployment and authenticated database readiness must be recorded separately from local test results. See the pull request and Actions run for the current verification result.
