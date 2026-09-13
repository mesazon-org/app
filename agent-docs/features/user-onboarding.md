# User Onboarding

Guides a user from a verified email to a fully onboarded account: set a password → provide full name + phone number → verify the phone via SMS OTP. Completing it (`PhoneVerified`) is what unlocks the business features gated with `@completedOnboardStage` (currently organization management and file uploads).

**Scope**: the three onboarding steps *after* email verification, plus ownership of the `OnboardStage` state machine itself — the stage enum and every per-flow allowed-stage list live here even though other features consume them. Account/email creation is [User Sign up](user-signup.md); using the password to authenticate later is [User Sign in](user-signin.md). Every flow in the codebase checks the user's current `OnboardStage` against an allowed list (`verifyOnboardStage` in `service/service.scala`) and fails with `ForbiddenError.InvalidOnboardStage` (`403 Forbidden`) otherwise, which makes the stage lists below load-bearing for all features.

## Stage machine

`EmailVerification` → `EmailVerified` → `PasswordProvided` → `PhoneVerification` → `PhoneVerified` (**completed**)

Stages and per-flow allowed lists live in `backend/domain/src/main/scala/io/mesazon/domain/gateway/OnboardStage.scala` (the `OnboardStage` enum + companion — it has its own file since other features read it too, see [domain placement](flow/02-validation.md#domain-placement)). When adding a stage, update the companion-object lists (there's a comment warning about this). The smithy `OnboardStage` enum mirrors the domain enum; mapping helpers `onboardStageFromDomainToSmithy` / `onboardStageFromSmithyToDomain` are in `service/service.scala`.

## Endpoints (smithy, bearer auth — access JWT)

| Method | Path | Required stage | Purpose |
|---|---|---|---|
| POST | `/onboard/password` | `EmailVerified` | Set the account password |
| POST | `/onboard/details` | `PasswordProvided`, `PhoneVerification` | Provide full name + phone number, trigger SMS OTP |
| POST | `/onboard/verify/phone-number` | `PhoneVerification` | Verify SMS OTP, complete onboarding |
| GET | `/onboard/verify/phone-number` | `PhoneVerification` | Fetch current OTP ID + remaining validity |

Defined in `backend/gateway/core/src/main/smithy/UserOnboardService.smithy`. Bearer auth is enforced by `ServerMiddleware` → `AuthorizationService` (verifies the access JWT and puts `AuthedUser` into `AuthState`; the service reads `authState.get`).

## Flow

### POST /onboard/password
Hash the password with Argon2 (`PasswordService`), insert `UserCredentialsRow`, move stage to `PasswordProvided`, then send a welcome email. The welcome email is **best-effort**: retried, but a final failure is only logged (`catchAllCause`), never fails the request.

### POST /onboard/details
`userDetailsRepository.updateUserDetails` runs **unconditionally**, immediately after the onboard-stage check and before the OTP branch is even inspected — every call persists `fullName` + `phoneNumber` and moves the stage to `PhoneVerification`, whether or not the OTP below ends up reused or freshly generated. This single call also doubles as the phone-number uniqueness check (see below): if it fails on the new constraint, the request fails before any OTP/SMS work happens, so nothing else about the request is observable. After that write succeeds, a `PhoneVerification` OTP is generated and sent via SMS (`TwilioClient`, with retries); when a genuinely new OTP is generated, the `PhoneVerificationVerifyOTP` wrong-attempt counter is also reset (deleted). Resend throttling: if an existing OTP is still outside the resend-cooldown window it is reused, no SMS is sent, and the counter is left untouched — the response returns its remaining `otpExpiresInSeconds`. Can be called again from `PhoneVerification` to change the number / resend. Note: hoisting the write to run unconditionally also fixed a prior gap where a cooldown-reused OTP resubmission never persisted the newly submitted `fullName`/`phoneNumber` at all — both branches now behave identically for persistence, only differing in OTP/SMS handling.

**Phone-number uniqueness**: `user_details` carries a named constraint `uq_user_details_phone_number` (`unique (phone_number_e164)`, added to `V2025.05.27__init.sql`) covering every row regardless of onboard stage or phone-verification status — it is not scoped/partial, so it also blocks a number already held by an account that never finished phone verification. `UserDetailsRepository.updateUserDetails` maps a SQLState `23505` violation on this constraint to `ServiceError.ConflictError.UniqueConstraintViolation("The phone number given already belongs to a different account", …)`, using the shared `findUniqueConstraintViolated`/`catchUniqueConstraintViolation` pattern from `04-repository.md`. `OnboardDetailsPost` declares `Conflict` (HTTP `409 CONFLICT_ERROR`) for this. No application-level pre-check exists — the DB constraint is the sole source of truth, so updating a row to a phone number it already holds (self-resubmission) never conflicts, by ordinary unique-constraint semantics; only forward writes are affected, existing duplicate data (there was none pre-release) is neither inspected nor migrated.

### POST /onboard/verify/phone-number
Loads the OTP by (`otpID`, `userID`, type `PhoneVerification`). **Wrong-attempt limit**: increases the `PhoneVerificationVerifyOTP` counter (`UserActionAttemptRepository`, shared generic mechanism also used by Forgot Password and Sign Up); over `otpVerifyAttemptsMaxRetries` (5) → delete the OTP row and fail `UnauthorizedError.OtpVerificationFailedError` **without checking the submitted code** (HTTP: `401 UNAUTHORIZED_OTP_ERROR`) — same response as a naturally expired OTP, remaining-attempts never disclosed. The internal log message for this branch states both the attempt count reached and the configured max, distinguishing it from the not-found and naturally-expired branches, which log their own distinct causes. The counter is only reset by a genuinely-new OTP issued from `/onboard/details` (above); reusing an OTP inside its resend cooldown does not reset it. Expired → OTP deleted + `UnauthorizedError.OtpVerificationFailedError` (HTTP: `401 UNAUTHORIZED_OTP_ERROR`). Wrong OTP → `BadRequestError.OtpVerifyError`. Correct → stage `PhoneVerified`, the OTP is deleted, and the `PhoneVerificationVerifyOTP` wrong-attempt counter is deleted. `PhoneVerified` is the only member of `OnboardStage.completedStages` — it unlocks endpoints marked `@completedOnboardStage` (e.g. organization management). Access token failure returns the standard `401 UNAUTHORIZED_ERROR`.

**Dev mode**: when `user-onboard.is-dev` is true (`IS_DEV` env var), `/onboard/details` skips the Twilio SMS entirely, and `/onboard/verify/phone-number` also accepts the fixed OTP `123QWE` (`DevOtp` / `verifyOtpInDev` in `service/service.scala`). Must stay off in production.

### GET /onboard/verify/phone-number
Returns the pending OTP's `otpID` and remaining seconds so the client can restore the verify screen. If the OTP is inside the resend-cooldown window of its expiry it is deleted and the call fails with `OtpVerificationFailedError` (HTTP: `401 UNAUTHORIZED_OTP_ERROR`; client should re-trigger `/onboard/details`). Access token failure returns the standard `401 UNAUTHORIZED_ERROR`.

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/UserOnboard.scala` (the `OnboardPasswordPostRequest`/`OnboardDetailsPostRequest`/`OnboardVerifyPhoneNumberPostRequest` request models); the `OnboardStage` enum lives in its own `OnboardStage.scala`
- Validator: `validation/service/UserOnboardRequestValidator.scala` (one `validated<Request>` per fallible request)
- Arbitraries: `testkit/base/UserOnboardDomainArbitraries.scala`, `gateway/utils/UserOnboardSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/UserOnboardService.scala`
- Password hashing: `service/PasswordService.scala` (Argon2, `PasswordConfig`)
- SMS: `clients/TwilioClient.scala`; Email: `clients/EmailClient.scala`
- Repository: `UserActionAttemptRepository` (wrong-attempt counter for `/onboard/verify/phone-number`, type `ActionAttemptType.PhoneVerificationVerifyOTP`; shared generic mechanism, no schema change); `UserDetailsRepository.updateUserDetails` (constraint-name-driven `ConflictError` mapping, see above)
- Config: `UserOnboardConfig` (OTP expiry offset, resend cooldown, SMS/email retry settings, `otpVerifyAttemptsMaxRetries`)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/UserOnboardApiSpec.scala` — all four endpoints, each with happy path + the standard error matrix (missing/invalid access token, disallowed stage, validation, wrong/expired/missing OTP), plus the verify-attempt-limit case on `/onboard/verify/phone-number`, the counter reset (genuinely-new OTP) / non-reset (cooldown-reused OTP) cases on `/onboard/details`, a case on `/onboard/verify/phone-number` proving a successful verify deletes the `PhoneVerificationVerifyOTP` wrong-attempt counter, and on `/onboard/details`: `409 Conflict` against a number already held by a different (including never-phone-verified) account in both the new-OTP and cooldown-reuse branches, plus success resubmitting the caller's own already-stored number in both branches
- Functional: `fun/UserOnboardServiceSpec.scala`
- Units: `unit/service/PasswordServiceSpec.scala`, `unit/validation/service/UserOnboardRequestValidatorSpec.scala`
- Integration: `it/UserCredentialsRepositorySpec.scala`, `it/UserDetailsRepositorySpec.scala` (includes the phone-number `ConflictError`/self-update cases), `it/UserOtpRepositorySpec.scala`, `it/TwilioClientSpec.scala`

**Known test-data risk**: `GatewayArbitraries.arbPhoneNumber` draws from a fixed pool of only 5 values. Any test needing two *independently*-arbitrary phone numbers to be genuinely distinct (e.g. simulating two different accounts) must force distinctness explicitly (see the cooldown-conflict case in `UserOnboardApiSpec` for the pattern) — do not assume two independent draws differ.
