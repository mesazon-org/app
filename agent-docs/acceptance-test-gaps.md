# Acceptance test gaps

Known-missing acceptance coverage, found by reading the epics in `pages/epics/` against the specs in `backend/gateway/it/`. Each entry is a documented product behaviour with no black-box test behind it.

This is a backlog, not a guide — write the tests per [Acceptance testing](project/acceptance-testing.md), then delete the entry. An entry left here after its test lands is worse than no list.

Established 2026-09-03 from a review of `01-user-onboarding`, `02-forgot-password`, and `03-sign-in`. Areas not covered by that review — Catalogue, Customer Book, Files, Organization, Token Refresh — have not been audited and may hold gaps of their own.

## Blocked on harness work

### 1. No SMS is ever verified, anywhere

**Behaviour:** "User should receive an SMS with OTP" — [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 1.

`grep -ril "twilio|sms|sendSms"` over `backend/gateway/it/` returns nothing, and `GatewayItContext` exposes no Twilio or wiremock client. Email is observable through `mailHogClient`; SMS has no equivalent. The onboard-details success test asserts `mailHogClient.readInbox().total shouldBe 0`, which proves no *email* went out but says nothing about the text message.

So the phone half of onboarding is verified only as far as the OTP row in Postgres. Whether a message was ever dispatched, to which number, carrying which code, is untested at every level.

**Prerequisite:** a way to observe Twilio from the harness — most likely the existing `backend/wiremock` module wired into `GatewayItContext.build`, since `application.conf`'s `twilio-client` already points at `localhost:8080` and looks intended for exactly that. `compose.yaml` does not expose wiremock on a host port today, so this needs deciding before any test can be written. See [External client](project/external-client.md).

**Do first.** Gaps 5 and 6 below want the same assertion, so building this unblocks three entries at once.

## Ready to write

### 2. Reset password token that is invalid or expired

**Behaviour:** `401 UNAUTHORIZED_ERROR` — [Forgot Password](../pages/epics/02-forgot-password.md), step 3.

`ForgotPasswordResetPost` declares `Unauthorized` in `UserForgotPasswordService.smithy`, and the epic documents it. The reset section of `UserForgotPasswordApiSpec` has five tests — success, validation, forbidden, 500 token-not-found, 500 user-not-found — and none of them is a 401.

A declared error path with no coverage at all. A signature or audience check could regress silently.

**Where:** `UserForgotPasswordApiSpec`, `"POST /forgot/password/reset"`.

### 3. Recovery code that exists but has gone stale

**Behaviour:** [Forgot Password](../pages/epics/02-forgot-password.md), step 1, scenario 2 — asking again after the previous code went stale regenerates and re-sends.

Step 1 tests cover three branches: no code at all, a fresh code that gets extended, and a fresh code with attempts maxed out. The fourth — a code that exists but is outside the resend-cooldown window — is a different branch of `UserForgotPasswordService.forgotPasswordPost` and is never taken.

**Where:** `UserForgotPasswordApiSpec`, `"POST /forgot/password"`. Assert a *new* code replaces the old one and a second email is sent.

### 4. Verifying an email with a code id we no longer hold

**Behaviour:** `500 INTERNAL_SERVER_ERROR` on code id not found — [User Onboarding](../pages/epics/01-user-onboarding.md), step 2.

`UserOnboardApiSpec` covers this for phone verification (`"fail with InternalServerError when OTP is missing"`), but `UserSignUpApiSpec` has no equivalent for email verification. The `smithy.InternalServerError` occurrences in that file are the client call's error type parameter, not assertions.

Purely asymmetric coverage of the same shape of bug. Note this is also [gap 4 in the epic](../pages/epics/01-user-onboarding.md#4-ordinary-situations-answer-with-a-server-error) — the 500 is itself questionable, so whoever changes that status should write this test alongside it.

**Where:** `UserSignUpApiSpec`, `"POST /signup/verify/email"`.

### 5. Re-submitting details while the code is still inside its waiting period

**Behaviour:** [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 2 — details are saved again, the existing code is reused, and no new text is sent.

`"POST /onboard/details"` has five tests: one success and four gate/validation failures. There is no second call to the endpoint anywhere, so neither re-submit branch is exercised.

This is the branch that lets someone correct a mistyped phone number, so it matters to a real user, not just to coverage.

**Where:** `UserOnboardApiSpec`, `"POST /onboard/details"`. Needs gap 1 to assert "no new text sent".

### 6. Re-submitting details once the waiting period has passed

**Behaviour:** [User Onboarding](../pages/epics/01-user-onboarding.md), step 4, scenario 3 — details saved, a new code generated, a new text sent.

Same missing second call as gap 5, opposite branch.

**Where:** `UserOnboardApiSpec`, `"POST /onboard/details"`. Needs gap 1 to assert the new text.

### 7. Confirmation email failing does not break a password reset

**Behaviour:** [Forgot Password](../pages/epics/02-forgot-password.md), step 3, scenario 4 — the reset succeeds and the failure is only recorded.

`UserForgotPasswordService` wraps this send in `catchAllCause`, deliberately making it best-effort. Nothing proves that. If the `catchAllCause` were dropped, a mail outage would start failing password resets and every existing test would still pass.

**Where:** `UserForgotPasswordApiSpec`, `"POST /forgot/password/reset"`. Needs a way to make MailHog reject a send.

### 8. Signing in with an email address that has no account

**Behaviour:** [Sign In](../pages/epics/03-sign-in.md), step 1, scenario 4 — an unknown address is refused in exactly the same way as a wrong password.

`"fail with Unauthorized when credentials are invalid"` inserts a user and then signs in with `userDetailsRow.email` and a wrong password. It only ever exercises a *known* email. Nothing tries an address with no account behind it.

That is the case the whole anti-enumeration guarantee rests on: the two refusals must be indistinguishable. `HttpErrorHandler` returns a bare `smithy.Unauthorized()` today, so they are — but nothing would catch a change that started returning a distinguishing message or status for an unknown address.

**Where:** `UserSignInApiSpec`, `"POST /signin"`. Assert the response is byte-for-byte the same shape as the wrong-password case, and that no attempt row is written for an account that does not exist.

### 9. Signing in destroys an outstanding password-reset token

**Behaviour:** [Sign In](../pages/epics/03-sign-in.md), step 1 outcome — every token the account held is deleted, including a reset token in flight.

`"successfully sign in ... should delete all users refresh tokens and create a new one"` seeds only `TokenType.RefreshToken` and asserts `all(userTokenRowsAll.map(_.tokenType)) shouldBe TokenType.RefreshToken`. The handler calls `deleteAllUserTokens`, which removes *every* type, but no test seeds a `ResetPasswordToken` to prove it.

This is a cross-feature interaction: someone asks for a password reset, then signs in with their old password while the reset email is still unread. Whether that reset token still works afterwards is a real product question, and nothing pins the answer.

**Where:** `UserSignInApiSpec`, `"POST /signin"`. Seed both token types, then assert only the new refresh token survives.

## Weak assertion in an existing test

### 10. Re-signup does not prove the code is unchanged

`UserSignUpApiSpec`, `"successfully re-sign up a user already seen user with stages before completion"`.

The test proves no second email is sent (`mailHogClient.readInbox().total shouldBe 1`) and that the expiry moved out (`assert(userOtpRowsAll1.head.expiresAt.value.isBefore(userOtpRowsAll2.head.expiresAt.value))`). But it builds its expected row from `userOtpRowsAll2.head.otp` — the value it is checking — so it cannot detect the code changing.

If a change made the code regenerate while still suppressing the email, the user would be left holding a code that can never work, and this test would stay green. Capture the first code and assert equality instead.

**Fix in place; no new test needed.**
