# User Token Management

The session/token backbone of the gateway: JWT issuing, verification, refresh-token rotation, and revocation via the `user_token` table.

**Scope**: two halves. (1) The `/token/refresh` endpoint (`UserTokenService`) that keeps a session alive by rotating refresh tokens. (2) The shared infrastructure every other feature leans on: `JwtService` (used by sign-up verify, sign-in, and forgot-password to mint tokens) and `AuthorizationService` + `ServerMiddleware` (which turn a bearer token into an `AuthedUser` in `AuthState` for every protected endpoint, smithy and Tapir alike). *Obtaining* a session belongs to [User Sign in](user-signin.md) / [User Sign up](user-signup.md); this feature governs what a token means once it exists.

## Token model (`JwtService`)

All tokens are signed JWTs (jjwt, HMAC via `JwtConfig.secretKey`, issuer claim required). Time comes from `TimeProvider` — never `Instant.now` directly.

| Token | Audience claim | `jti` (tokenID) | Persisted in DB | Lifetime config |
|---|---|---|---|---|
| Access | — | no | no (stateless) | `accessTokenExpiresAtOffset` |
| Refresh | `auth:refresh` | yes | yes (`user_token`, type `RefreshToken`) | `refreshTokenExpiresAtOffset` |
| Reset password | `auth:reset_password` | yes | yes (`user_token`, type `ResetPasswordToken`) | `resetPasswordTokenExpiresAtOffset` |

- Access tokens carry only `sub = userID` and are verified purely cryptographically (`verifyAccessToken` → `AuthedUserAccess`). They cannot be revoked individually — hence the short lifetime.
- Refresh and reset tokens carry a generated `tokenID` in `jti` and **must also exist in the `user_token` table** to be accepted, which makes them revocable and single-use. Deleting the row kills the token.
- Verification failures from bad/expired JWTs map to `UnauthorizedError.FailedToVerifyJwt`; anything unexpected maps to `InternalServerError.JwtServiceError`.

`user_token` rows are per (`tokenID`, `userID`, `tokenType`); sign-in and sign-up-verify call `deleteAllUserTokens(userID)` first, so a user has a single active refresh token.

## Endpoint (smithy, no header auth — the refresh token is the credential, in the body)

| Method | Path | Purpose |
|---|---|---|
| POST | `/token/refresh` | Rotate refresh token, issue a new access token |

Defined in `backend/gateway/core/src/main/smithy/UserTokenService.smithy`.

### Flow (`UserTokenService.tokenRefreshPost`)
1. Validate request (`UserTokenRequestValidator.validatedTokenRefreshPostRequest`).
2. `jwtService.verifyRefreshToken` — checks signature, expiry, issuer, and `auth:refresh` audience; yields (`tokenID`, `userID`).
3. The token row must exist in `user_token` → otherwise `UnauthorizedError.TokenFailedAuthorization` ("Refresh token not found in database"). This is how sign-in/sign-out-style revocation takes effect.
4. **Rotation**: generate a new access + refresh JWT, then `upsertUserToken(..., tokenIDOptOld = Some(old))` — atomically replaces the old refresh row with the new one, so the old refresh token can never be used again.
5. Response: new `refreshToken`, `accessToken`, `accessTokenExpiresInSeconds`.

## How access tokens gate other endpoints

`AuthorizationService` (wired by `ServerMiddleware` for `@httpBearerAuth` smithy services, and by Tapir endpoints via `AuthorizationService.tapir`) extracts the `Bearer` token, verifies it, optionally enforces `OnboardStage.completedStages` when the service is annotated `@completedOnboardStage`, and sets `AuthedUser` in `AuthState`.

## Key files

The feature follows the consolidated per-feature layout of [adding-a-feature.md](../adding-a-feature.md): one domain file, one request validator, one arbitraries trait per layer.

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/UserToken.scala` (the `TokenRefreshPostRequest` model)
- Validator: `validation/service/UserTokenRequestValidator.scala` (`validatedTokenRefreshPostRequest`)
- Arbitraries: `testkit/base/UserTokenDomainArbitraries.scala`, `gateway/utils/UserTokenSmithyArbitraries.scala`
- Endpoint: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/UserTokenService.scala`
- JWTs: `service/JwtService.scala` (+ `JwtConfig`)
- Bearer auth: `service/AuthorizationService.scala`, `middleware/ServerMiddleware.scala`
- Persistence: `repository/UserTokenRepository.scala`, row `repository/domain/UserTokenRow.scala`

## Tests

- Acceptance (see [acceptance-tests.md](../acceptance-tests.md)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/UserTokenRefreshApiSpec.scala` — successful rotation, missing token (validation), invalid token, and the revocation case: a cryptographically valid refresh token not present in `user_token` is rejected
- Functional: `fun/UserTokenServiceSpec.scala`
- Units: `unit/validation/service/UserTokenRequestValidatorSpec.scala`, `unit/service/JwtServiceSpec.scala`, `unit/service/AuthorizationServiceSpec.scala`
- Integration: `it/UserTokenRepositorySpec.scala`
