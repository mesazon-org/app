# HTTP middleware — authentication & authorization

How every gateway endpoint gets its auth. The rule of thumb: **handlers never check credentials** — by the time a service method runs, the caller is authenticated, authorized, and available via `AuthState`. What gets checked is declared in the smithy contract (see [smithy.md](smithy.md)) and enforced here.

## Where it hooks in

`middleware/ServerMiddleware.scala` implements smithy4s's `ServerEndpointMiddleware.Simple[Task]`. `HttpApp.buildSmithyRoute` attaches it to **every smithy route**. For each endpoint smithy4s calls `prepareWithHints(serviceHints, endpointHints)` — the traits on the smithy service/operation arrive as *hints*, and the middleware returns a wrapper that runs before the endpoint's `HttpApp`.

Tapir endpoints (streaming uploads) are **not** covered by this middleware: they wire the same checks explicitly via `zServerSecurityLogic(authorizationService.auth(...))` in `tapir/TapirEndpoints.scala`. When a rule is added to the middleware, the Tapir security logic must be extended by hand to match.

## Dispatch table

`prepareWithHints` branches on the service's auth traits:

| Service traits | Behavior |
|---|---|
| `@httpBasicAuth` only | `AuthenticationService.auth(request)` runs first (credentials sign-in) |
| `@httpBearerAuth` only | `AuthorizationService.auth(request, requiresCompletedOnboardStage)` runs first |
| neither | pass-through (public endpoints: sign-up, forgot-password, token refresh, health) |
| both | rejected at request time with `InternalServerError` (unsupported) |

Endpoint-level `@auth([])` overrides are unsupported and also produce `InternalServerError` — auth is declared per **service**, never per operation.

## Basic auth — `AuthenticationService` (sign-in)

Runs for `@httpBasicAuth` services (currently only `UserSignInService`). Full flow, in order:

1. Extract `Authorization: Basic` credentials → `BadRequestError.AuthenticationCredentialsMissing` if absent; validate the email/password format.
2. Look up the user by email → `Unauthorized` if unknown.
3. Onboard stage must be in `OnboardStage.signInAllowedStages`.
4. Brute-force guard via `UserActionAttemptRepository` (`ActionAttemptType.SignIn`): over `signInAttemptsMax` within `signInAttemptsBlockDuration` → `AuthenticationTooManySignInAttempts`.
5. Argon2 password verification; success deletes the attempt counter.
6. `AuthState.set(AuthedUser(userID))`.

## Bearer auth — `AuthorizationService`

Runs for `@httpBearerAuth` services and for Tapir endpoints:

1. Extract the `Bearer` token → `UnauthorizedError.AuthorizationTokenMissing` if absent.
2. `JwtService.verifyAccessToken` — signature, expiry, issuer (access tokens are stateless; see [user-token-management](features/user-token-management.md)).
3. If the service carries `@completedOnboardStage` (passed as `requiresCompletedOnboardStage = true`): load user details and require the stage to be in `OnboardStage.completedStages` (= `PhoneVerified`), else `UnauthorizedError.FailedOnboardStage`.
4. `AuthState.set(AuthedUser(userID))`.

## `AuthState` — how handlers learn who is calling

`state/AuthState.scala` wraps a `FiberRef[Option[AuthedUser]]` — request-scoped state. The middleware `set`s it after successful auth; service implementations call `authState.get` as their first step. `get` on an unauthenticated fiber is a defect (`orDie`) — it can only happen if a handler is reachable without the middleware, which is a wiring bug.

## Organization permissions — `@organizationRolesAllowed` + `X-Organization-ID` *(designed, enforcement pending)*

The newest rule, introduced with the customer book. The smithy service declares which organization roles may call it, e.g. `@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])`, and every operation carries the organization scope in the **required `X-Organization-ID` header** (never in the body or URI — a fixed header is readable by the middleware without parsing bodies, and it works identically for GETs, JSON posts and streaming uploads).

Planned enforcement, mirroring the `completedOnboardStage` mechanism:

1. `ServerMiddleware` reads the `OrganizationRolesAllowed` hint from the service hints.
2. When present, it reads the `X-Organization-ID` header from the raw request (missing/malformed → `Unauthorized`).
3. `AuthorizationService` (new check, after token verification) loads the caller's membership — `OrganizationUserRow` by (`organizationID`, `userID`) — and requires `userRole` to be one of the declared roles; not a member or wrong role → `Unauthorized`.
4. Tapir endpoints pass the allowed roles explicitly to `authorizationService.auth(...)`.

**Not yet implemented** — current gaps this design closes:

- `CustomerBookService` handlers are stubs; no membership/role check exists yet.
- `FileService` (logo upload) checks the bearer token + completed onboarding but **not** that the caller belongs to the target organization — any onboarded user can currently upload a logo for any `organizationID`. It also still takes `organizationID` as a path parameter (`/upload/organization/logo/{organizationID}`), predating the header standard.
- Requires a new repository/query method to fetch a single membership row (e.g. `getOrganizationUser(organizationID, userID)` on `OrganizationManagementRepository`).

## Known gaps

- The middleware itself has no direct tests (`// TODO: Test this middleware`, issue #25); it is exercised indirectly by every acceptance spec's error matrix (see [acceptance-tests.md](acceptance-tests.md)).
- The FileService organization check above.
