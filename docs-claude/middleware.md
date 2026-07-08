# HTTP middleware — authentication & authorization

How every gateway endpoint gets its auth. The rule of thumb: **handlers never check credentials** — by the time a service method runs, the caller is authenticated, authorized, and available via `AuthState`. What gets checked is declared in the smithy contract (see [smithy.md](smithy.md)) and enforced here.

## Where it hooks in

`middleware/ServerMiddleware.scala` implements smithy4s's `ServerEndpointMiddleware.Simple[Task]`. `HttpApp.buildSmithyRoute` attaches it to **every smithy route**. For each endpoint smithy4s calls `prepareWithHints(serviceHints, endpointHints)` — the traits on the smithy service/operation arrive as *hints*, and the middleware returns a wrapper that runs before the endpoint's `HttpApp`.

Tapir endpoints (streaming uploads) are **not** covered by this middleware: they wire the same checks explicitly via `zServerSecurityLogic(authorizationService.auth(...))` in `tapir/TapirEndpoints.scala` — the bearer token (`auth.bearer[AccessToken]`) and the `X-Organization-ID` header (`header[OrganizationID]`) are typed `securityIn`s, and each endpoint passes its allowed roles; the decoded `OrganizationID` becomes the security principal handed to the handler. **When a rule is added to the middleware, the Tapir security logic must be extended by hand to match.**

Cross-transport consistency, per header: a **missing `Authorization` bearer is `401 Unauthorized` everywhere** (smithy `UnauthorizedError.AuthHeaderMissingError`; tapir's default for a missing `auth` security input), while a **missing `X-Organization-ID` header is `400 BadRequest` everywhere** (smithy `BadRequestError.HeaderMissingError`; tapir's default for a missing plain header). The tapir decode-failure handler keeps tapir's chosen status as-is — no adjustment needed. Disallowed-role failures are `403 Forbidden` and missing membership rows `500` on both. One remaining difference: a *malformed* `X-Organization-ID` UUID is a tapir decode failure (`400`) but an `InternalServerError.UnexpectedError` (`500`) on smithy routes.

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

1. Extract `Authorization: Basic` credentials → `UnauthorizedError.AuthHeaderMissingError` (`401`) if absent; validate the email/password format.
2. Look up the user by email → `Unauthorized` if unknown.
3. Onboard stage must be in `OnboardStage.signInAllowedStages`.
4. Brute-force guard via `UserActionAttemptRepository` (`ActionAttemptType.SignIn`): over `signInAttemptsMax` within `signInAttemptsBlockDuration` → `AuthenticationTooManySignInAttempts`.
5. Argon2 password verification; success deletes the attempt counter.
6. `AuthState.set(AuthedUser(userID))`.

## Bearer auth — `AuthorizationService`

Runs for `@httpBearerAuth` services and for Tapir endpoints:

1. Extract the `Bearer` token → `UnauthorizedError.AuthHeaderMissingError("Authorization")` (`401`) if absent — **a missing `Authorization` bearer is `401 Unauthorized`**, on both transports (tapir's default for a missing `auth` security input); `401` also covers credentials that are present but fail verification.
2. `JwtService.verifyAccessToken` — signature, expiry, issuer (access tokens are stateless; see [user-token-management](features/user-token-management.md)).
3. If the service carries `@completedOnboardStage` (passed as `requiresCompletedOnboardStage = true`): load user details and require the stage to be in `OnboardStage.completedStages` (= `PhoneVerified`), else `ForbiddenError.InvalidOnboardStage` → `403 Forbidden` (this applies to every `verifyOnboardStage` call, including the in-handler stage checks of sign-up/onboard/forgot-password and the sign-in stage check in `AuthenticationService`).
4. If the operation carries `@organizationRolesAllowed` (passed as `organizationRolesAllowedOpt = Some(roles)`): run the organization role check (next section).
5. `AuthState.set(AuthedUser(userID))`.

## `AuthState` — how handlers learn who is calling

`state/AuthState.scala` wraps a `FiberRef[Option[AuthedUser]]` — request-scoped state. The middleware `set`s it after successful auth; service implementations call `authState.get` as their first step. `get` on an unauthenticated fiber is a defect (`orDie`) — it can only happen if a handler is reachable without the middleware, which is a wiring bug.

## Organization permissions — `@organizationRolesAllowed` + `X-Organization-ID`

Each smithy **operation** declares which organization roles may call it, e.g. `@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])` — operation-level so permissions can differ per endpoint within one service (customer book: reads allow `OWNER`/`ADMIN`/`USER`, writes only `OWNER`/`ADMIN`). Every org-scoped operation carries the organization in the **required `X-Organization-ID` header** (never in the body or URI — a fixed header is readable by the middleware without parsing bodies, and it works identically for GETs, JSON posts and streaming uploads).

Enforcement, mirroring the `completedOnboardStage` mechanism:

1. `ServerMiddleware` reads the `OrganizationRolesAllowed` hint from the **endpoint hints**, maps the smithy roles to domain `UserRole`s (`organizationUserRoleFromSmithyToDomain` in `service/service.scala`), and passes them as `organizationRolesAllowedOpt` to `AuthorizationService.auth`.
2. The request overload parses the `Authorization` bearer into a typed `AccessToken` and the `X-Organization-ID` header into a typed `Option[OrganizationID]` before delegating to the token overload; a malformed header UUID fails as `InternalServerError.UnexpectedError` (`500`).
3. `AuthorizationService.verifyOrganizationRole` (after token + onboard-stage verification): header absent when roles are required → `BadRequestError.HeaderMissingError` (`400`).
4. It loads the caller's membership — `OrganizationManagementRepository.getOrganizationUser(organizationID, userID)`. No membership row → `InternalServerError.UnexpectedError` (`500`, the missing-referenced-entity convention). A member whose `userRole` is not in the declared roles → `ForbiddenError.InvalidOrganizationRole` (carrying the caller's actual role) → **`403 Forbidden`** (authenticated but not allowed — distinct from 401).
5. Operations without the trait skip the check entirely (`organizationRolesAllowedOpt = None`).

Covered by `unit/service/AuthorizationServiceSpec` (allowed role, missing header, invalid header, not a member, disallowed role) and by the `FileApiSpec` acceptance cases (missing org header → 400, missing token → 401, non-member → 500, disallowed role → 403).

The Tapir logo upload follows the same standard: `organizationID` moved from the path to the `X-Organization-ID` header (`POST /upload/organization/logo`), the security logic requires `OWNER`/`ADMIN`, and the parsed `OrganizationID` is handed to `FileService` as the security principal.

## Known gaps

- The middleware itself has no direct tests (`// TODO: Test this middleware`, issue #25); it is exercised indirectly by every acceptance spec's error matrix (see [acceptance-tests.md](acceptance-tests.md)). The organization role logic itself is unit-tested in `AuthorizationServiceSpec`.
