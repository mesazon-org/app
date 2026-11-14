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

**Behaviour:** [Customer Book](../pages/epics/05-customer-book.md), step 8, scenarios 20–24 — the AI merges identical addresses of one customer and marks each remaining address shipping or billing as best it can with no limit, and the service then makes one address shipping and billing, keeps a genuine shipping/billing pair or else sets the first shipping and the second billing, and cuts three or more to the first two with a note per address left out (the service rules are covered by `CustomerBookFileServiceSpec` and, over HTTP, by `CustomerBookFileApiSpec`; only the AI's merging and best-effort typing are unverified live). Partial addresses are retained when at least one text field has non-whitespace content, with populated values trimmed and a note for missing or unclear fields; all-empty addresses are omitted. The prompts also state rules that no mocked or stubbed test can check: every AI note has at most ten words and one point (requirement 21), and the processing message takes priority over the default reading rules within the three fixed limits, for example splitting a row or completing a missing name part from the email as an inferred field (scenario 28, requirement 24).

Customer extraction is `CustomerBookFileService`, exposed at `POST /customer-book/file/ai/extract`. `CustomerBookFileApiSpec` runs against stubbed WireMock responses. Besides transport, middleware and failure handling, its rich-candidate case (stub marker `AI_CLIENT_SPEC_CUSTOMER_BOOK_FILE_RICH`) proves the service's own rules over real HTTP: the address cap, retyping and `Address left out: …` note, the duplicate, missing and inferred field paths with `needsAttention`, first-entry defaults, candidate note order and the info notes. A stub cannot show what the model itself returns, so the AI's address merging and typing, note length, and processing-message priority stay unverified. The earlier golden specs were manual, needed a live key, and were not run after the address shape changed — so it was never confirmed that the real service accepts the strict schema or follows the updated instructions. Live-AI verification was accepted as a risk on 2026-10-01 and remains outstanding.

**Where:** `golden/CustomerBookFileAiExtractFromImageGoldenSpec`, `golden/CustomerBookFileAiExtractFromSpreadsheetGoldenSpec` and `golden/CustomerBookFileNotesCompactionGoldenSpec` exist and compile; they are canceled without a key and have not yet been run against the live service. Run them manually with a key (command in the [feature doc](features/customer-book.md)) and inspect the printed responses against these rules (the image spec's processing-message case for the priority rule), and the compaction output against epic requirement 22, before deleting this entry.
