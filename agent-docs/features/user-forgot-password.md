# User Forgot Password

Three-step password recovery for users who already set a password: request an OTP by email → verify the OTP to get a short-lived reset token → reset the password with that token.

**Scope**: replacing a lost password without being signed in, plus the abuse defenses that make that safe on unauthenticated endpoints — email anti-enumeration, per-user attempt limits on both OTP *requests* and OTP *verifications*, and a single-use DB-backed reset token. It reuses the shared OTP machinery (`UserOtpRepository`, type `ForgotPassword`) and token model ([User Token Management](user-token-management.md)); changing a password while signed in is not implemented anywhere yet. Users still before `PasswordProvided` have nothing to recover and are rejected by stage check.

## Endpoints (smithy, no auth — identity is proven by OTP / reset token)

| Method | Path | Purpose |
|---|---|---|
| POST | `/forgot/password` | Request a recovery OTP by email |
| POST | `/forgot/password/verify-otp` | Exchange OTP for a reset-password token |
| POST | `/forgot/password/reset` (204) | Set the new password using the reset token |

Defined in `backend/gateway/core/src/main/smithy/UserForgotPasswordService.smithy`. Allowed stages for the whole flow: `OnboardStage.forgotPasswordAllowedStages` (= `signInAllowedStages`: `PasswordProvided`, `PhoneVerification`, `PhoneVerified`) — a user without a password yet cannot use recovery.

## Flow

### POST /forgot/password
1. Validate email. **Unknown email → anti-enumeration** (blocks email scanning): return a fake generated `otpID` with the normal response shape; nothing stored or sent. Known user outside the allowed stages → `ForbiddenError.InvalidOnboardStage` (`403 FORBIDDEN_ERROR`) — a different answer from the unknown-email branch, so it reveals that the email is registered (open product gap in the epic).
2. Known user with a still-fresh OTP (`expiresAt - otpResendCooldown` still after now): count the request via `UserActionAttempt` (`ActionAttemptType.ForgotPassword`). Over `otpResetAttemptsMaxRetries` (`attempts > max`) → silently return the existing `otpID` with expiry unchanged (see in-code comment: same-OTP-until-cooldown is deliberate abuse prevention). Under the limit → extend the OTP expiry to now + `otpExpiresAtOffset` and return it. **No email is sent in either branch** — it went out when the OTP was created.
3. No/stale OTP (expired, or inside the resend cooldown): generate a new OTP, upsert with `otpExpiresAtOffset`, reset the `ForgotPasswordVerifyOTP` attempt counter, and email the OTP (retried `sendForgotPasswordEmailMaxRetries` times with exponential backoff from `sendForgotPasswordEmailRetryDelay`; final failure → `InternalServerError.UnexpectedError`, `500`, from `EmailClient`). The `ForgotPassword` request counter is **not** reset here — only a correct verify clears it — so it accumulates across abandoned recoveries (open product gap in the epic).
4. Response `otpExpiresInSeconds` is always `otpExpiresAtOffset` (every branch, including the fake id). In the over-limit branch the real expiry was not extended, so the reported lifetime overstates the remaining time (open product gap in the epic).

### POST /forgot/password/verify-otp
1. Load OTP by `otpID` (type `ForgotPassword`). **OTP ID not recognized** (never existed, or is the fake id handed back for an unregistered email in step 1) → `BadRequestError.OtpVerifyError` (HTTP: `400 BAD_REQUEST_ERROR`), nothing to delete.
2. Load user details for the OTP's `userID`. Missing (orphaned OTP row, a data-integrity issue rather than an OTP-verification outcome) → `InternalServerError.UnexpectedError` (HTTP: `500 INTERNAL_SERVER_ERROR`), distinct from every branch below. Check stage.
3. **Four OTP-outcome failure cases all return the same `BadRequestError.OtpVerifyError` (HTTP: `400 BAD_REQUEST_ERROR`)** — matching the not-recognized case above — so an attacker cannot distinguish a fake OTP id from a real code that is wrong, expired, or exhausted:
   - **OTP ID not recognized** (case 1 above).
   - **Verify attempt limiting**: increase `ForgotPasswordVerifyOTP` attempts on every submission, correct or not, before comparing the code; over `otpVerifyAttemptsMaxRetries` (`attempts > max`) → delete the OTP and reject, even if the submitted code is right.
   - **Expired OTP** → delete the OTP.
   - **Wrong OTP** (still under the attempt limit) → nothing deleted, code stays usable.
4. Correct → delete the OTP and both attempt counters.
   - **Dev mode**: when `user-forgot-password.is-dev` is true (`IS_DEV` env var), the fixed OTP `123QWE` (`DevOtp` / `verifyOtpInDev` in `service/service.scala`) is also accepted. Must stay off in production.
5. Delete every existing token for the user (`UserTokenRepository.deleteAllUserTokens`, same call sign-in makes) **before** issuing the new one, so a token from a session in flight can't survive the wipe. This ends every other session immediately rather than waiting for the password to actually change in step 3; an access token already held by another session keeps working until it naturally expires (access tokens aren't individually revocable) but cannot be renewed.
6. Issue a reset-password JWT (`JwtService.generateResetPasswordToken`, audience `auth:reset_password`) and persist it in `user_token` (type `ResetPasswordToken`). Response: token + expiry.

### POST /forgot/password/reset
Request validation (password rules) runs before any token check, so an invalid password leaves the reset token usable.

1. Verify the reset JWT signature/audience **and** require the token row to exist in `user_token` (revocable, single-use). Invalid or expired JWT → `UnauthorizedError.FailedToVerifyJwt` (`401 UNAUTHORIZED_ERROR`). Valid JWT but no row (already used, or wiped by a later `deleteAllUserTokens`) → `InternalServerError.UnexpectedError` (`500 INTERNAL_SERVER_ERROR`); this status was not a product choice (open product gap in the epic).
2. Hash the new password (Argon2) and update `user_credentials`; delete the reset token row (one use only).
3. Send a password-change confirmation email — best-effort: retried, final failure only logged, request still succeeds (204).

Note: all other sessions are already gone by this point — deleted in step 2 above, as soon as the OTP was verified, not deferred to reset or to sign-in.

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/UserForgotPassword.scala` (the `ForgotPasswordPostRequest`/`ForgotPasswordVerifyOTPPostRequest`/`ForgotPasswordResetPostRequest` request models)
- Validator: `validation/service/UserForgotPasswordRequestValidator.scala` (one `validated<Request>` per fallible request; email goes through the generic `EmailValidator`)
- Arbitraries: `testkit/base/UserForgotPasswordDomainArbitraries.scala`, `gateway/utils/UserForgotPasswordSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/UserForgotPasswordService.scala`
- Tokens: `service/JwtService.scala`; hashing: `service/PasswordService.scala`
- Repositories: `UserOtpRepository`, `UserActionAttemptRepository`, `UserCredentialsRepository`, `UserTokenRepository`, `UserDetailsRepository`
- Config: `UserForgotPasswordConfig`, block `user-forgot-password` in `backend/gateway/core/src/main/resources/application.conf` (defaults, each overridable by a `USER_FORGOT_PASSWORD_*` env var): `otpExpiresAtOffset` 45 seconds, `otpResendCooldown` 15 seconds, `otpResetAttemptsMaxRetries` 3, `otpVerifyAttemptsMaxRetries` 5, `sendForgotPasswordEmailMaxRetries` 5 / `sendForgotPasswordEmailRetryDelay` 100 ms, `sendPasswordChangeConfirmationEmailMaxRetries` 5 / `sendPasswordChangeConfirmationEmailRetryDelay` 100 ms, `isDev` false (`IS_DEV`)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/UserForgotPasswordApiSpec.scala` — all three endpoints: OTP issue/extend/maxed-attempts behavior, anti-enumeration for unknown emails, verify-OTP attempt limit, full reset happy path, plus the standard error matrix
- Functional: `fun/UserForgotPasswordServiceSpec.scala`
- Validator units: `unit/validation/service/UserForgotPasswordRequestValidatorSpec.scala`
- Integration: `it/UserActionAttemptRepositorySpec.scala`, `it/UserOtpRepositorySpec.scala`, `it/EmailClientSpec.scala`
