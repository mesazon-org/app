# User Sign in

Password sign-in over HTTP Basic auth: verifies email + password, defends against brute force, and starts a fresh session by rotating out all previously issued tokens.

**Scope**: authenticating a user who already has a password and issuing the session tokens. Credential *verification* happens in middleware (`AuthenticationService`), not in the endpoint handler — the handler only runs for an already-authenticated user and its job is to issue tokens. Adjacent concerns live elsewhere: recovering a lost password is [User Forgot Password](user-forgot-password.md); keeping the session alive afterwards is [User Token Management](user-token-management.md); users who never set a password must finish [User Sign up](user-signup.md) first.

## Endpoint (smithy, `@httpBasicAuth`)

| Method | Path | Required stage | Purpose |
|---|---|---|---|
| POST | `/signin` | `PasswordProvided`, `PhoneVerification`, `PhoneVerified` | Authenticate and issue access + refresh tokens |

Defined in `backend/gateway/core/src/main/smithy/UserSignInService.smithy`. No request body — email/password travel in the `Authorization: Basic` header. Basic credentials are only base64-encoded, so the endpoint depends on an encrypted (TLS) connection.

**Error surface**: `HttpErrorHandler` maps every `UnauthorizedError` to a bare `smithy.Unauthorized()` (`401 UNAUTHORIZED_ERROR`), so missing header, unknown email, wrong password and lockout are indistinguishable on the wire (anti-enumeration). There is no credentials-specific error code: sign-in reuses the same `UNAUTHORIZED_ERROR` that bearer-token failures (missing/invalid/expired access token) return on every other endpoint. The disallowed-stage refusal is the exception — `ForbiddenError.InvalidOnboardStage` → `403 FORBIDDEN_ERROR`.

## Flow

### 1. Middleware authentication (`AuthenticationService.auth`)
Wired by `ServerMiddleware` for any smithy service annotated `@httpBasicAuth`:

1. Extract Basic credentials from the `Authorization` header → `UnauthorizedError.AuthHeaderMissingError` (`401`) if absent (a non-Basic scheme counts as absent); validate format (`UserSignInRequestValidator.validatedBasicCredentialsRequest`, which turns the raw `AuthenticationService.BasicCredentialsRequest` into the `BasicCredentials` domain model; invalid email → `400 VALIDATION_ERROR`).
2. Look up user by email → `UnauthorizedError.AuthenticationEmailNotFound` if unknown. No attempt row is written for an unknown email.
3. Stage must be in `OnboardStage.signInAllowedStages` — a user can sign in as soon as they have a password, even before phone verification (so they can resume onboarding). This runs before the attempt counter and the password check, so a disallowed stage returns `403` without a password being checked and without counting an attempt — which is how the endpoint reveals that an address is registered but not yet past password setup.
4. **Brute-force protection**: `UserActionAttemptRepository.getAndIncreaseUserActionAttempt(userID, ActionAttemptType.SignIn)`. If attempts exceed `AuthenticationConfig.signInAttemptsMax` and the last attempt is within `signInAttemptsBlockDuration`, fail `UnauthorizedError.AuthenticationTooManySignInAttempts`. Attempt counter is deleted on successful password verification (block auto-expires after the duration).
   - Mechanics (`UserActionAttemptQueries.getAndIncrease`): upserts `attempts = attempts + 1, updated_at = now` and returns the **pre-increment** row (or the fresh row with `attempts = 1` on the first attempt). The block check therefore compares the previous count and the previous attempt's `updated_at` + `signInAttemptsBlockDuration` against now.
   - Every attempt that reaches this step increments and restarts the window — wrong passwords and attempts made while already blocked alike. The row is deleted only after a verified password, which the block prevents, so a block lapses only after a full `signInAttemptsBlockDuration` with no attempts at all.
   - The row is keyed by (`user_id`, `action_attempt_type`) only — per account, not per source IP or device — so anyone's failures count towards the owner's block.
5. Verify password against the stored Argon2 hash (`PasswordService.verifyPassword`) → `UnauthorizedError.AuthenticationInvalidCredentials` on mismatch.
6. On success, store `AuthedUser(userID)` in `AuthState` (request-scoped) for the handler.

### 2. Handler (`UserSignInService.signInPost`)

> **In progress (issue 507):** steps 2–3 and the `organizations` response field are agreed but not built yet. Remove this note in the slice that ships them.

1. Read `AuthedUser` from `AuthState`, load user details.
2. **Load the user's organizations** — `OrganizationManagementRepository.getUserOrganizations(userID)`, in one transaction:
   - `OrganizationUserQueries.getAllByUserID`: every membership row for the user, `ORDER BY created_at DESC, organization_id ASC` (newest membership first, stable tie-break). Served by `idx_organization_user_user_id_created_at`.
   - If there are none, return `Nil` without a second query. Otherwise `OrganizationDetailsQueries.getAllByIDs(NonEmptyList[OrganizationID])` (`WHERE organization_id IN (...)`), then pair each membership with its details row in membership order, as a named tuple `(organizationDetailsRow, organizationUserRow)`.
   - A membership whose `organization_details` row is missing is a data-integrity error → `InternalServerError.UnexpectedError` (500). There is no FK from `organization_user` to `organization_details`; the app only ever writes both in one transaction.
   - No filter on `organizationStage`: organizations mid-onboarding (`DetailsProvided`) are included.
3. **Sign each logo link** — for an organization with a `logoImageAsset`, `S3ClientOrganizationMedia.genMediaUrl(imageNormalizedS3BucketKey)` produces a presigned GET URL (local signing, no network call), mirroring `CatalogueService`'s `imageNormalizedUrl`. No logo → `logoUrl` absent.
   - Steps 2–3 run **before** any token is touched, so a failure there returns 500 and leaves every existing session intact.
4. **Delete all existing user tokens** — signing in invalidates every previously issued refresh/reset token (single active session policy). Nothing notifies the user or the other device that its session was revoked.
5. Generate access JWT + refresh JWT (`JwtService`), persist the refresh token (`user_token` table, type `RefreshToken`).
6. Respond with `accessToken`, `refreshToken`, `accessTokenExpiresInSeconds`, the current `onboardStage` (client uses it to resume onboarding if incomplete), and `organizations`: a `@required` list of `SignInOrganization { organizationID, name, slug, role, logoUrl? }` in the order from step 2. The matching `OrganizationUserRole` domain and Smithy enums convert directly with Chimney rather than named mapping helpers. The list is empty for a user with no memberships, which is the common case at sign-in.

## Key files

The feature follows the [current consolidated layout](../project/feature-consolidation.md), with one deviation: `SignInPost` has **no request body** (credentials travel in the Basic-auth header), so there is no smithy request shape and therefore no smithy-arbitraries trait — just the domain model, the validator, and a domain-arbitraries trait.

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/UserSignIn.scala` (the `BasicCredentials` model)
- Validator: `validation/service/UserSignInRequestValidator.scala` (`validatedBasicCredentialsRequest`; email goes through the generic `EmailValidator`). Its input, the raw `BasicCredentialsRequest` (email/password strings), is defined in `AuthenticationService` where the Basic header is parsed.
- Arbitraries: `testkit/base/UserSignInDomainArbitraries.scala`
- Handler: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/UserSignInService.scala`
- Credential auth: `service/AuthenticationService.scala`; middleware wiring: `middleware/ServerMiddleware.scala`
- Request-scoped auth state: `state/AuthState.scala` (`io.mesazon.gateway.state`)
- Attempt tracking: `repository/UserActionAttemptRepository.scala`
- Organizations: `repository/OrganizationManagementRepository.scala` (`getUserOrganizations`), `repository/queries/OrganizationUserQueries.scala`, `repository/queries/OrganizationDetailsQueries.scala`; logo links: `clients/S3ClientOrganizationMedia.scala`; index migration `backend/schemas/migrations/V2026.09.26__organization_user_user_id_index.sql`
- Config: `AuthenticationConfig` (`signInAttemptsMax`, `signInAttemptsBlockDuration`)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/UserSignInApiSpec.scala` — happy path (asserts exactly one refresh-token row + attempt counter cleared), token rotation on re-sign-in, lockout after max failed attempts (correct password still rejected), plus missing credentials / invalid email / wrong password / disallowed stage
- Functional: `fun/UserSignInServiceSpec.scala`, `fun/AuthenticationServiceSpec.scala`
- Integration: `it/UserActionAttemptRepositorySpec.scala`, `it/UserCredentialsRepositorySpec.scala`, `it/UserTokenRepositorySpec.scala`, `it/OrganizationManagementRepositorySpec.scala` (`getUserOrganizations`: ordering and tie-break, empty, orphan membership → 500)
- Acceptance for organizations: signing in returns the seeded memberships in order with role; `logoUrl shouldBe defined` only when a logo asset exists (URL correctness is proven once in `S3ClientOrganizationMediaSpec`); a user with no memberships gets an empty list.
