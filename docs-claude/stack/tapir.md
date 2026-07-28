# Tapir — the one alternate transport to smithy4s

Tapir (`sttp.tapir`) is used for exactly **one** endpoint: the streaming organization-logo upload. smithy4s can't do streaming binary bodies with a distinct entity-size limit, so this one route bypasses the smithy contract entirely. See [files-management.md § Why Tapir](../features/files-management.md#why-tapir-and-not-smithy) for the full rationale — this doc covers only the conventions a new Tapir endpoint must follow.

## File layout

Two files, split by concern — a new Tapir endpoint follows the same split:

- `tapir/tapir.scala` — shared helpers reused by every Tapir endpoint: `securedEndpoint` (the bearer + `X-Organization-ID` security base), `decodeFailureHandler`/`tapirServerErrorOut` (error rendering), `requiredOrganizationRolesDescription` (swagger role marker), the `TapirTask`/`TapirEndpoints` type aliases, `tapirServerOptions`.
- `tapir/FileServiceEndpoints.scala` — the per-feature endpoint definitions (`uploadOrganizationLogoPostEndpoint`, `docsEndpoint`) plus the ZIO wiring function (`allRoutesAndDocsEndpoints`) that resolves services and builds the `ZServerEndpoint`s.

## Error model — parallel to smithy's, not shared with it

`TapirServerError` (`backend/domain/.../TapirServerError.scala`) is a 7-case enum (`BadRequestError`, `UnauthorizedError`, `ForbiddenError`, `NotFoundError`, `ConflictError`, `InternalServerError`, `ServiceUnavailableError`), each carrying `code`/`message`/`schemaName`. `HttpErrorHandler.errorResponseHandlerTapir` maps `ServiceError` to it, mirroring `errorResponseHandler`'s smithy mapping one case at a time.

**Whenever a `ServiceError` subtype is added or re-homed, update both handlers together.** The smithy side gets a compile-time nudge (the operation's `errors` list), but `errorResponseHandlerTapir`'s `match` does not — a missed case is a silent gap, not a build failure.

## Security is hand-wired to mirror the middleware

Tapir endpoints are **not** covered by `ServerMiddleware` (see [middleware.md](../middleware.md)) — each one wires its own auth via `zServerSecurityLogic(authorizationService.auth(...))`, using `securedEndpoint`'s typed `securityIn`s (`auth.bearer[AccessToken]`, `header[OrganizationID](...)`). `uploadOrganizationLogoPostEndpoint` calls `authorizationService.auth` with `requiresCompletedOnboardStage = true` and `organizationUserRolesAllowedOpt = Some(OrganizationUserRole.adminRoles)` — the same checks a `@completedOnboardStage` + `@organizationUserRolesAllowed` smithy operation gets for free from the middleware.

**When a middleware rule changes, update the Tapir security logic in `FileServiceEndpoints` by hand to match.** This is the transport's single biggest correctness trap, since nothing enforces the two staying in sync.

## Swagger/OpenAPI integration

smithy4s generates no OpenAPI spec for a Tapir service, so Tapir produces its own via `OpenAPIDocsInterpreter().toOpenAPI(...)`, served from a plain `GET /docs/specs/<namespace>.<ShapeName>.json` endpoint (`docsEndpoint` in `FileServiceEndpoints.scala`) that mimics the smithy4s doc path. The Tapir-backed service is still listed in the shared smithy Swagger UI via `smithy4sDocsID` passed to `HttpApp.externalSmithySwaggerRoutes`'s `docs[Task](...)` call.

**This Tapir docs route must be mounted before the smithy swagger routes** in `HttpApp.externalDocsRoutes` — otherwise smithy4s intercepts the path first and 500s trying to load a non-existent classpath spec (see the `IMPORTANT: Ordering matters` comment in `HttpApp.scala`).

Since the Tapir role check runs inside `zServerSecurityLogic` (invisible to OpenAPI generation), add `.description(requiredOrganizationRolesDescription(roles))` to the endpoint, passing the same `OrganizationUserRole` list the security logic enforces. The helper renders the same `**Required Organization User Roles:** [...]` marker used in smithy operation doc comments (see [smithy.md §3](smithy.md#3-operations)), so the two docs can't drift apart.

## Schema/codec quirk — one named schema per error variant

`TapirServerError` needs one **explicitly named** `Schema` per case (`tapirServerErrorSchemas` in `json/json.scala`), so each status code renders as a distinct OpenAPI component rather than one generic error shape. Each schema's name (`schemaName`) mirrors the smithy4s-generated `<Structure>ResponseContent` name (e.g. `BadRequestResponseContent`) so both swagger docs expose consistent component names. A separate fallback `Schema[TapirServerError]` (named `"ServerError"`) covers paths where the specific variant isn't known ahead of time, like the decode-failure handler. The custom `JsonValueCodec[TapirServerError]` round-trips through `code` (not `schemaName`) — decoding an unknown code fails loudly (`in.decodeError`) rather than silently defaulting.

## Entity size limits are transport-specific

`HttpApp.scala` sets `SmithyMaxEntitySize` (2 MB) for smithy routes and a separate `TapirMaxEntitySize` (20 MB) for Tapir routes, since Tapir carries the logo upload. Keep `TapirMaxEntitySize` in sync with the `file-service.max-organization-logo-bytes` config value.

## Naming

`TapirServerError` cases are `PascalCase` + `Error` suffix (`BadRequestError`, `ConflictError`); `code` is `SCREAMING_SNAKE_CASE` (`BAD_REQUEST_ERROR`).
