# Authentication and authorization changes

Read only when changing auth traits, middleware, onboard gates, organization roles, `AuthState`, or Tapir security. Handlers do not check credentials: contracts declare policy; middleware/security logic enforces it; handlers read the caller from `AuthState`.

## Route hooks and dispatch

`ServerMiddleware.scala` is `ServerEndpointMiddleware.Simple[Task]`; `HttpApp.buildSmithyRoute` attaches it to every Smithy route. `prepareWithHints(serviceHints, endpointHints)` dispatches:

| Service traits | Before handler |
|---|---|
| `@httpBasicAuth` only | `AuthenticationService.auth(request)` |
| `@httpBearerAuth` only | `AuthorizationService.auth(request, requiresCompletedOnboardStage, roles)` |
| neither | public pass-through |
| both | `InternalServerError` (unsupported) |

Endpoint `@auth([])` overrides are unsupported and return 500; auth is service-level. Organization roles are operation-level.

Tapir is not covered by this middleware. Every Tapir endpoint explicitly mirrors policy through `securedEndpoint` and `zServerSecurityLogic(authorizationService.auth(...))`. A middleware policy change must update Tapir security manually.

Cross-transport results:

- missing bearer → 401;
- missing `X-Organization-ID` → 400;
- disallowed role/stage → 403;
- missing membership → 500;
- malformed organization UUID: Tapir 400, Smithy currently 500 (`UnexpectedError`).

## Basic auth

`AuthenticationService`, in order:

1. Read Basic credentials; missing header → 401; validate email/password format.
2. User by email; unknown → 401.
3. Require `OnboardStage.signInAllowedStages`.
4. Enforce `UserActionAttemptRepository` sign-in maximum/block duration.
5. Verify Argon2 password; success deletes attempt counter.
6. `AuthState.set(AuthedUser(userID))`.

## Bearer auth

`AuthorizationService`, in order:

1. Read bearer; absent → `AuthHeaderMissingError("Authorization")` (401).
2. `JwtService.verifyAccessToken` (signature, expiry, issuer).
3. If `@completedOnboardStage`, load details and require `OnboardStage.completedStages`; otherwise 403. The same stage policy applies to all `verifyOnboardStage` calls.
4. If `@organizationUserRolesAllowed`, verify organization membership/role.
5. Set `AuthState`.

`AuthState` is a request-scoped `FiberRef[Option[AuthedUser]]`. `get` defects when unset because reaching a protected handler without middleware is a wiring error.

## Organization scope

Every organization-scoped input mixes in `OrganizationScopedInput`, which provides required `X-Organization-ID`; never put organization scope in the body/URI. Roles:

- reads: `OWNER`, `ADMIN`, `USER`;
- mutations: `OWNER`, `ADMIN`;
- delete organization: `OWNER`.

Enforcement:

1. Middleware converts the Smithy endpoint hint to domain roles.
2. Request overload parses bearer and organization header.
3. Required header absent → 400.
4. Load `getOrganizationUser(organizationID, userID)`: absent membership → 500; role not allowed → 403 with actual role.
5. No trait means no organization-role check.

Tapir logo upload uses the same header, completed-stage requirement, and admin roles; parsed organization ID is the handler principal.

## Proof

- Unit-test `AuthorizationService`: allowed role, missing/invalid header, missing membership, disallowed role, and token/stage paths changed.
- Functional-test auth orchestration and brute-force/retry behavior.
- Every affected endpoint repeats the complete middleware matrix from [Acceptance testing](acceptance-testing.md); never rely on another route's test.
- Keep Smithy and Tapir contract descriptions/security inputs in parity.

Known gap: `ServerMiddleware` has no direct spec (issue #25); acceptance matrices exercise its route wiring.
