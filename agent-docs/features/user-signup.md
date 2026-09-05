# User Sign up

Email-based sign up with OTP email verification. This feature owns the **start of the user lifecycle**: it is the only place a `UserDetails` row is created, and it carries the user through the first two onboard stages (`EmailVerification` → `EmailVerified`).

**Scope**: proving ownership of an email address and creating the account shell. It does *not* collect a password, name, or phone number — that is [User Onboarding](user-onboarding.md), which the client continues into using the access/refresh tokens issued at the end of email verification (token semantics in [User Token Management](user-token-management.md)). Both endpoints are unauthenticated by design; abuse is mitigated with resend cooldowns and anti-enumeration responses rather than auth.

## Endpoints (smithy, no auth)

| Method | Path | Purpose |
|---|---|---|
| POST | `/signup/email` | Start sign up: create user, generate OTP, email it |
| POST | `/signup/verify/email` | Verify the OTP, mark email verified, issue tokens |

Defined in `backend/gateway/core/src/main/smithy/UserSignupService.smithy`.

## Flow

### POST /signup/email
1. Validate email (`UserSignUpRequestValidator.validatedSignUpEmailPostRequest`).
2. Look up user by email, then look up an existing `EmailVerification` OTP:
   - **New user** — insert `UserDetailsRow` at stage `EmailVerification`, generate OTP, upsert it with expiry `now + otpEmailVerificationExpiresAtOffset`, send verification email.
   - **Existing user still in a sign-up stage** (`OnboardStage.signUpEmailStages` = `EmailVerification`, `EmailVerified`) — resend path: the user's stage is reset to `EmailVerification` (an `EmailVerified` user re-signing up must verify again). The current OTP (if any) is treated as genuinely new-worthy when it is missing, expired, or inside the resend-cooldown window (`isEmptyOrExpiredOrExpiringSoon`), **or when it has already been silently reused this way `otpEmailVerificationResendAttemptsMaxRetries` (5) times in a row** — tracked as a per-user request count via `ActionAttemptType.EmailVerificationOtpLifetime` on the shared `UserActionAttemptRepository` (`getAndIncreaseUserActionAttempt`/`deleteUserActionAttempt`, the same generic mechanism `/signup/verify/email` uses for its own counter), not by how long the OTP has been alive — there is no separate real-time ceiling. When either condition holds, a new OTP code is generated, **both** the `EmailVerificationOtpLifetime` and `EmailVerificationVerifyOTP` wrong-attempt counters are reset (deleted), and the OTP is emailed. Otherwise (existing, non-expiring-soon, under the resend-attempts limit) the existing OTP *code* is reused and neither counter is touched, and **no email is sent** (resend throttling). In both cases the new/reused OTP is persisted with `UserOtpRepository.upsertUserOtp`, which always issues a fresh `otpID`/`createdAt` — even on reuse, only the passcode value itself survives unchanged, its id changes every time. The limit exists so repeated cooldown-window resends cannot keep one passcode alive forever; once it is hit, the next resend restarts both the expiry window and the reuse count, exactly like an outright-expired OTP.
   - **Existing user past sign-up stages** — anti-enumeration: returns a *fake* freshly generated `otpID` with the normal response shape, sends nothing, writes nothing. Callers cannot detect whether an email is registered.
3. Response: `otpID` + `otpExpiresInSeconds`.

### POST /signup/verify/email
1. Validate, load OTP by `otpID` (type `EmailVerification`), load user details.
2. Stage must be in `OnboardStage.signUpVerifyEmailStages` (= `EmailVerification`), else `ForbiddenError.InvalidOnboardStage` (`403`).
3. **OTP outcomes**: Four failure cases all return the same HTTP error — `BadRequestError.OtpVerifyError` (`400 BAD_REQUEST_ERROR`) — to prevent enumeration attacks distinguishing registered emails from failed verifications:
   - **OTP ID not recognized** (never existed or is fake) — no row to delete.
   - **Wrong-attempt limit exceeded** — increase the `EmailVerificationVerifyOTP` counter (`UserActionAttemptRepository`, shared generic mechanism also used by Forgot Password); over `otpVerifyAttemptsMaxRetries` (5) → delete the OTP row **without checking the submitted code**. Remaining-attempts never disclosed. The internal log message for this branch states both the attempt count reached and the configured max, distinguishing it from other branches, which log their own distinct causes. The counter is only reset by a genuinely-new OTP issued from `/signup/email` (step above); reusing an OTP inside its resend cooldown does not reset it.
   - **OTP expired** — delete OTP row.
   - **Wrong OTP** (under limit) — nothing deleted, code stays usable.
4. Correct OTP → stage set to `EmailVerified`, OTP deleted, and the `EmailVerificationVerifyOTP` wrong-attempt counter deleted.
   - **Dev mode**: when `user-sign-up.is-dev` is true (`IS_DEV` env var), the fixed OTP `123QWE` (`DevOtp` / `verifyOtpInDev` in `service/service.scala`) is also accepted. Must stay off in production.
5. All existing user tokens are deleted, then a fresh access JWT + refresh JWT are issued and the refresh token is persisted (`user_token` table). Response returns both tokens, expiry, and the new `onboardStage`.

Email sends are retried with `Schedule.recurs(maxRetries) && Schedule.exponential(delay)`; on this flow a final failure fails the request.

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/UserSignUp.scala` (the `SignUpEmailPostRequest`/`SignUpVerifyEmailPostRequest` request models)
- Validator: `validation/service/UserSignUpRequestValidator.scala` (one `validated<Request>` per fallible request; email goes through the generic `EmailValidator`)
- Arbitraries: `testkit/base/UserSignUpDomainArbitraries.scala`, `gateway/utils/UserSignUpSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/UserSignUpService.scala`
- Repositories: `UserDetailsRepository`, `UserOtpRepository`, `UserTokenRepository`, `UserActionAttemptRepository` — this feature owns two `ActionAttemptType`s on the shared generic counter mechanism (no schema change): `EmailVerificationOtpLifetime` (resend-attempts count on `/signup/email`) and `EmailVerificationVerifyOTP` (wrong-attempt count on `/signup/verify/email`)
- Stage lists: `backend/domain/src/main/scala/io/mesazon/domain/gateway/OnboardStage.scala`
- Config: `UserSignUpConfig` (`otpEmailVerificationExpiresAtOffset`, `otpEmailVerificationResendCooldown`, `otpEmailVerificationResendAttemptsMaxRetries`, `sendEmailVerificationEmailMaxRetries`, `sendEmailVerificationEmailRetryDelay`, `otpVerifyAttemptsMaxRetries`)

## Tests

- Acceptance (black-box HTTP against the running gateway, see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/UserSignUpApiSpec.scala` — happy paths for new/re-sign-up, anti-enumeration path, the verify-attempt-limit case (seeded near/at the limit, asserting the OTP-deleted/expired-response state), the counter reset (genuinely-new OTP via the expiring-soon trigger) and non-reset (cooldown-reused OTP) cases on `/signup/email` (the cooldown-reused case also proves the OTP `otpID`/`createdAt` change on every resend while the `otp` code itself and a second `EmailVerificationOtpLifetime` counter row survive/appear), the resend-attempts boundary on `/signup/email` seeded via `UserActionAttemptRow`s of type `EmailVerificationOtpLifetime` — exactly at the limit (still a reuse) and one past it (genuinely new: new OTP row, new email, both counters cleared), a case on `/signup/verify/email` proving a successful verify deletes the `EmailVerificationVerifyOTP` wrong-attempt counter, plus the standard error matrix (validation, wrong/expired OTP, disallowed stage)
- Functional (mocked repos/clients): `src/test/scala/io/mesazon/gateway/fun/UserSignUpServiceSpec.scala` — including the cooldown-reuse case (`UserOtpRepository.upsertUserOtp`, new `otpID` every time, `otp` code unchanged) and the resend-attempts boundary (just under, exactly at, and one past `otpEmailVerificationResendAttemptsMaxRetries`)
- Integration (Postgres via docker compose): `it/UserOtpRepositorySpec.scala`, `it/UserDetailsRepositorySpec.scala`, `it/UserTokenRepositorySpec.scala`, `it/UserActionAttemptRepositorySpec.scala` (proves `getAndIncreaseUserActionAttempt` returns the pre-existing row as-is, pre-increment, which is what the resend-attempts and verify-attempts boundary tests above seed against)
- Validator units: `unit/validation/service/UserSignUpRequestValidatorSpec.scala`
