# Acceptance test gaps

Known-missing acceptance coverage, found by reading the epics in `pages/epics/` against the specs in `backend/gateway/it/`. Each entry is a documented product behaviour with no black-box test behind it.

This is a backlog, not a guide — write the tests per [Acceptance testing](project/acceptance-testing.md), then delete the entry. An entry left here after its test lands is worse than no list.

Established 2026-09-03 from a review of `01-user-onboarding`, `02-forgot-password`, `03-sign-in`, `04-organization-onboarding`, `05-customer-book`, and `06-catalogue`. Token Refresh has not been audited and may hold gaps of its own.

## Catalogue remaining coverage

All eight JSON operations have acceptance sections. Current coverage and verified runs are recorded in the [Catalogue feature doc](features/catalogue.md#tests-and-verification). The remaining HTTP gaps are below; reduced-scope and typed-client limits are recorded separately.

**Accepted decimal precision limitation:** rounding beyond 34 significant digits in HTTP and database JSON decoding is documented in the [Catalogue review](features/catalogue.md#known-issues-edge-cases-and-larger-follow-ups). The user deferred codec changes and dedicated precision tests because such values are outside expected business use. This is not pending acceptance work for the current scope. If the business needs greater precision later, use typed requests and independent exact database/response assertions; the acceptance client's current rounding codec must not hide the result.

**Deliberately reduced HTTP scope:** at the user’s request, PUT has exactly two simple successes (name update and clearing the item description) plus 14 failures. Dedicated omitted-field and missing/archived/foreign-parent no-op successes, mixed variant insertion, exhaustive field/status replacement, variant nullable clearing, and empty attributes-map replacement are excluded. Item/variant archive no-op combinations are also outside the current HTTP suite. Their production rules remain documented and lower-layer coverage remains; do not claim HTTP proof or restore the removed cases without agreement. Ordinary business statuses share one policy and use arbitraries, rather than duplicate per-status successes.

**Typed-model limit:** unknown closed-enum strings and omitted required transport members cannot be constructed through the approved typed clients. These decoding cases are outside HTTP coverage. Do not add raw JSON clients or mutate serialized payloads to cover them. Representable invalid strings, numbers and empty collections still use typed requests. Exact nested validation indexes remain validator-level proof because HTTP errors expose ordered field names only.

## Deliberately not checked in acceptance

These are proven by lower-layer specs and are deliberately not checked in acceptance: the acceptance suite does not inspect WireMock's request journal, nor decode processed images or follow presigned URLs. Each stays listed so nobody reads the HTTP suite as proof of them.

**Impact:** a wiring bug between layers, such as a service that never calls Twilio, still passes acceptance. Only the lower-layer spec for each piece fails. This is the user's accepted tradeoff for a faster, more stable suite. Restoring end-to-end proof would need agreement first.

### 1. The text message itself is never checked over HTTP

**Behaviour:** "User should receive an SMS with OTP" — [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 1.

Open part: only that the SMS is sent, to which number, with which code. `UserOnboardApiSpec` proves the OTP row, response and details over HTTP, against a WireMock Twilio stub. The send itself is covered by `it/TwilioClientSpec` (the Twilio request) and `fun/UserOnboardServiceSpec` (sent only for a new OTP, retried, skipped in dev mode, a final failure fails the request).

### 5. Re-submitting a changed number while the OTP is still fresh

**Behaviour:** [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 2 — details are saved again (including a changed phone number), the existing code is reused, and no new text is sent.

`UserOnboardApiSpec`, `"POST /onboard/details"`, proves over HTTP that a changed number is saved, the OTP is reused and the verify counter is kept. Open part: only that no new text is sent, covered by the lower-layer specs in entry 1.

### 6. Re-submitting details once the OTP is about to expire

**Behaviour:** [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 3 — a pre-existing code that is about to expire is replaced: details saved, a genuinely new code generated, a new text sent.

`UserOnboardApiSpec`, `"POST /onboard/details"`, proves over HTTP that the about-to-expire OTP is replaced by a new `otpID` and expiry and the verify counter is cleared. Open part: only that a new text is sent, covered by the lower-layer specs in entry 1.

### 15. Email recipient and content

**Behaviour:** each email the gateway sends (email verification, welcome, forgot password, password-change confirmation, organization created) reaches the right recipient with the right content, including any code.

Acceptance reads the MailHog inbox and asserts only its count (`mailHogResponse.total`: 1 when sent, 0 when not). A wrong recipient, subject or code still passes. These are covered by `it/EmailClientSpec` and the `fun/` service specs that send email (`UserSignUpServiceSpec`, `UserOnboardServiceSpec`, `UserForgotPasswordServiceSpec`, `OrganizationManagementServiceSpec`).

### 16. Processed image output and presigned URL contents

**Behaviour:** an uploaded logo or item image is stored as the original plus a normalized WebP, and reads return a working presigned URL to the normalized image.

Acceptance compares the stored original's bytes exactly, but checks only that the normalized object is non-empty. It takes a presigned URL from the response as-is, without parsing or fetching it. A normalized object that is not valid WebP, or a URL that does not resolve, still passes. The WebP output is covered by `unit/utils/ImageProcessingSpec` and the `fun/` file service specs. Presigning is covered by `it/S3ClientOrganizationMediaSpec`.

## Ready to write

### 7. Confirmation email failing does not break a password reset

**Behaviour:** [Forgot Password](../pages/epics/02-forgot-password.md), step 3, scenario 4 — the reset succeeds and the failure is only recorded.

`UserForgotPasswordService` wraps this send in `catchAllCause`, deliberately making it best-effort. Nothing proves that. If the `catchAllCause` were dropped, a mail outage would start failing password resets and every existing test would still pass.

**Where:** `UserForgotPasswordApiSpec`, `"POST /forgot/password/reset"`. Needs a way to make MailHog reject a send.

### 11. ~~A logo upload larger than the cap, over real HTTP~~ — do not write this test

**Correction, 2026-09-03:** this entry originally asked for an acceptance test sending an oversized body to `/upload/organization/logo` over real HTTP. Do not write that test. [Known issues](known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast) already documents that exact request as a reproduction of an open hang: it explicitly says not to add a real end-to-end acceptance test past the entity limit, because it destabilizes the rest of the acceptance suite.

`FileScannerSpec` already proves the byte cap directly against `FileScanner.scan`, including the one-byte-over boundary, independent of HTTP transport — that is deliberate, per the known issue's own prevention note, and is the right level for this. `[Organization Onboarding](../pages/epics/04-organization-onboarding.md)` step 2 now describes the real behaviour (a stall, not a clean rejection) as today's behaviour and a known engineering fault, rather than as untested-but-working behaviour; the fault itself is tracked in known-issues.

Leaving this entry in place, struck through, so nobody re-adds it believing it was simply overlooked.

## Rejections decided at request decoding

### 13. An address with an unknown type or a missing required address object/type

**Behaviour:** [Organization Onboarding](../pages/epics/04-organization-onboarding.md), step 1, scenario 9, and [Customer Book](../pages/epics/05-customer-book.md), step 1, scenario 11 — an address whose type is not `Shipping`, `Billing` or `ShippingAndBilling`, or that omits its required address object or type, is rejected and nothing is stored. All five text fields are optional; a partial address with at least one valid populated field is accepted.

`addressType` is a Smithy enum; the address object and type remain required, so these malformed bodies fail while the request is decoded, before any validator runs. No test sends such a body: the typed `GatewayClient` cannot express an unknown enum value or a missing required member, and a raw-JSON client method was deliberately not added. The exact status body for a decode failure is therefore unproven. Missing text fields reach validation normally; an all-missing address produces the indexed `address` error. Blank, whitespace-only, untrimmed and over-long parts are covered in `AddressValidatorSpec`, and the request validator specs prove the entry index. For Customer Book the representable cases are covered: `CustomerBookApiSpec` POST individual stores a partial address exactly as sent and rejects an all-missing address (`address`) and two `Shipping` entries (`addresses`) with nothing stored; see the [Customer Book tests](features/customer-book.md#tests). Organization Management still has no HTTP case for a partial or all-empty address.

**Where:** `OrganizationManagementApiSpec`. Unknown enum values and omitted required members remain a documented decoding coverage limitation, in both specs, under the [typed-request standard](project/acceptance-testing.md#typed-requests-only); do not add a raw-body client to cover them. Add the representable partial/all-empty organization address cases through the typed client and assert the complete response and stored/unchanged state.

## Behaviour that only a live AI run can show

### 14. Address, note and processing-message rules in customer extraction

**Behaviour:** [Customer Book](../pages/epics/05-customer-book.md), step 8, scenarios 20–24 — the AI merges identical addresses of one customer and marks each remaining address shipping or billing as best it can with no limit, and the service then makes one address shipping and billing, keeps a genuine shipping/billing pair or else sets the first shipping and the second billing, and cuts three or more to the first two with a note per address left out (the service rules are covered by `CustomerBookFileServiceSpec` and, over HTTP, by `CustomerBookFileApiSpec`; only the AI's merging and best-effort typing are unverified live). Partial addresses are retained when at least one text field has non-whitespace content, with populated values trimmed and a note for missing or unclear fields; all-empty addresses are omitted. The prompts also state rules that no mocked or stubbed test can check: every AI note has at most ten words and one point (requirement 21), and the processing message takes priority over the default reading rules within the three fixed limits, for example splitting a row or completing a missing name part from the email as an inferred field (scenario 28, requirement 24). They also copy every name, contact name and role, address text field and tax ID exactly as printed, with only the three allowed space changes, a misspelt-looking value kept with a note and a partly unclear one cut to its visible characters with a note (scenarios 43–45, requirement 32; issue 607 exact-copy round).

Customer extraction is `CustomerBookFileService`, exposed at `POST /customer-book/file/ai/extract`. `CustomerBookFileApiSpec` runs against stubbed WireMock responses. Besides transport, middleware and failure handling, its rich-candidate case (stub marker `AI_CLIENT_SPEC_CUSTOMER_BOOK_FILE_RICH`) proves the service's own rules over real HTTP: the address cap, retyping and `Address left out: …` note, the duplicate, missing and inferred field paths with `needsAttention`, first-entry defaults, candidate note order and the info notes. A stub cannot show what the model itself returns, so the AI's address merging and typing, note length, and processing-message priority stay unverified. The earlier golden specs were manual, needed a live key, and were not run after the address shape changed — so it was never confirmed that the real service accepts the strict schema or follows the updated instructions. Live-AI verification was accepted as a risk on 2026-10-01 and remains outstanding.

**Where:** `golden/CustomerBookFileAiExtractGoldenSpec` (issue 607 rebuild, built 2026-11-24): 23 cases with hand-corrected goldens, reading the moved assets in `assets-test/CustomerBookFileAiExtractGoldenSpec/` ([AI response performance](project/ai-response-performance.md#customer-book-check)). The Customer Book golden specs (image, PDF, spreadsheet, unrelated content, notes compaction) and their golden JSONs are removed (user decision), so these rules have no golden check until Customer Book's is rebuilt the way Catalogue's is ([AI response performance](project/ai-response-performance.md)). The source assets were moved, byte for byte, into `assets-test/CustomerBookFileAiExtractGoldenSpec/` for the rebuild (issue 607, user decision; the unrelated files renumbered `image-11.jpeg` to `image-14.jpeg`, `pdf-3.pdf`, `spreadsheet-5.csv`). The removed specs were last run live on defaults on 2026-11-21. The asset notes below remain the inspection points for the rebuilt check. Inspect against epic step 8 scenarios 7 and 40 and requirements 5 and 28:
- `CustomerBookFileAiExtractGoldenSpec/pdf-1.pdf` (typed, 25 numbered entries over two pages): 25 entries identified and processed; Mira Solberg kept as the second contact of Northwind Marine Services Ltd (entry 13, page 1), not a separate candidate; the title "Customer Directory - Seaside Print Studio" and the `Page n of 2` footer not returned as candidates, with one left-out note per repeated line rather than one per page; any unidentified position counted straight through the pages; `emptyEntryRowIndexes` empty.
- `CustomerBookFileAiExtractGoldenSpec/pdf-2.pdf` (scanned, the English and Greek handwritten lists of `CustomerBookFileAiExtractGoldenSpec/image-1.png` and `-2.png`): both pages read in page order, with positions on page 2 continuing from page 1, and the crossed-out entry on page 1 reported, not invented.
- `CustomerBookFileAiExtractGoldenSpec/pdf-3.pdf` (room temperature readings) and `spreadsheet-5.csv` (water meter readings): no candidates, a first info note `Processed 0 of 0 entries` or `Processed 0 of <n> entries` with the unidentified rows listed, a general note that no relevant customer data was found (for the PDF, `AI could not detect relevant customer data in this document.`), and nothing invented. The PDF cases also show that the configured `pdf-model` accepts the Customer Book schema.

The user runs them; no agent sends anything to OpenAI.

### 17. Catalogue extraction prompts and note compaction

**Behaviour:** [Catalogue](../pages/epics/06-catalogue.md), step 10, after issue 607. The rewritten image and file prompts must make the AI do several things: follow the reading rules of requirement 6; group rows and continue an item only within one image or spreadsheet group (scenarios 5, 6 and 18, gap 12); return nameless candidates only when another detail is readable; keep unit-less variants and currency-less amounts; set `sellingUnitInferred`/`currencyInferred` exactly as requirement 10 defines them (a unit or currency supplied by the processing message is inferred); count `entriesProcessed` as original entries; report image unidentified positions; give the processing message priority within the three limits; write ten-word, unprefixed notes that never restate the field lists; explain in the general notes when nothing usable was detected (scenario 15); and copy every free-text value exactly as printed, with only the three allowed space changes, a misspelt-looking value kept with a note and a partly unclear one cut to its visible characters with a note (scenarios 33–35, requirement 19; issue 607 exact-copy round). The proof of these rules, the copy rule included, is `golden/CatalogueFileAiExtractGoldenSpec` passing against its hand-corrected goldens, every field and note compared ([AI response performance](project/ai-response-performance.md)). The old scored specs, deleted in the issue 607 rework, were last run live on defaults on 2026-11-21 against their own goldens; the compaction spec was not part of that run. The Catalogue compaction prompt must combine only notes that say the same thing.

`CatalogueFileApiSpec` runs against WireMock stubs, so it proves the service's own rules over HTTP (field paths, attention, info notes, compaction wiring), not what the model returns.

**Where:** `golden/CatalogueFileAiExtractGoldenSpec` (issue 607 rework; all 10 goldens are hand-corrected and committed, captured at high effort on 2026-11-22). **First run at defaults, 2026-11-23:** every case answered, all 10 failed on differences ([results](project/ai-response-performance.md#first-run-at-defaults-2026-11-23)). Beyond note wording, the default-effort model broke these rules: counts that do not add up in every spreadsheet case, an unidentified row reported as a left-out note (`spreadsheet-2`, `-3`), a missing packaging selling unit (`spreadsheet-1`), a `KG` column header taken as each row's own unit (`image-2`, 94 inferred flags), misread names and codes (`image-1`, `image-2`), and a missing misspelling note (`pdf-1`). This entry stays open. It compares the raw AI answer with hand-corrected goldens and fails on any difference ([AI response performance](project/ai-response-performance.md)). Its cases cover the image rules (the first image's processing message exercises the priority rule), the PDF rules (the configured `pdf-model` accepts PDF input through `/v1/responses`, an upper-case `.PDF` declared name is accepted, and positions run straight through the pages per requirement 11), the three spreadsheets, note compaction over two groups of `spreadsheet-1.csv`, and the unrelated photo, staff-rota CSV and meeting-notes PDF (epic scenario 15: no candidates, nothing invented). The goldens encode these rules once they are hand-corrected. The check cancels without `AI_CLIENT_DATA_EXTRACTION_API_KEY`. Run it manually with a key (command in the [feature doc](features/catalogue.md#manual-ai-golden-checks)); when every case passes against the hand-corrected goldens, delete this entry (since stored patches, a case never fails on a difference, so read this as "when every case's Current block is empty"; [AI response performance](project/ai-response-performance.md#stored-patches)).

**Responses API migration check (issue 607, step M4, user-run):** moving image, CSV and note-compaction extraction from `/v1/chat/completions` to `/v1/responses` changes how the prompt and file reach the model (prompt as `instructions`, images as `input_image` with `detail: "auto"`, `store: false`) while WireMock tests prove only the request shape. The user runs the goldens themselves with their own key, in one session after the PDF round's last slice (user decision): all eight, being the five Catalogue ones above and the three Customer Book ones in entry 14. No agent sends anything to OpenAI. The migration is not closed until the user reports that every golden completed and its printed output still meets the rules of entries 16 and 17; record the outcome here, then delete this paragraph. The issue 607 golden rework deleted those eight specs: the Catalogue part now runs through `golden/CatalogueFileAiExtractGoldenSpec`, and the Customer Book part waits for its golden rebuild (entry 14). Catalogue outcome: every image, PDF, CSV and note-compaction request completed through `/v1/responses` in the high-effort capture (2026-11-22) and the default run (2026-11-23); whether the output still meets entry 17's rules is recorded above.
