# Alternate transport — Mesazon specifics

Concrete values that fill in the placeholders in [tapir-new.md](tapir-new.md). Not a standard on its own — read each fact against the rule it instantiates there.

- Tapir (`sttp.tapir`) is used for exactly **one** endpoint: the streaming organization-logo upload. smithy4s can't do streaming binary bodies with a distinct entity-size limit, so this one route bypasses the smithy contract entirely — see [files-management.md § Why Tapir](../features/files-management.md#why-tapir-and-not-smithy) for the full rationale.

## File layout

- `tapir/tapir.scala` — shared helpers: `securedEndpoint` (bearer + `X-Organization-ID` security base), `decodeFailureHandler`/`tapirServerErrorOut` (error rendering), `requiredOrganizationRolesDescription` (swagger role marker), `TapirTask`/`TapirEndpoints` type aliases, `tapirServerOptions`.
- `tapir/FileServiceEndpoints.scala` — per-feature endpoints: `uploadOrganizationLogoPostEndpoint`, `docsEndpoint`, plus the ZIO wiring function `allRoutesAndDocsEndpoints`.

## Error model

`TapirServerError` (`backend/domain/.../TapirServerError.scala`) is a 7-case enum — `BadRequestError`, `UnauthorizedError`, `ForbiddenError`, `NotFoundError`, `ConflictError`, `InternalServerError`, `ServiceUnavailableError` — each carrying `code`/`message`/`schemaName`. `HttpErrorHandler.errorResponseHandlerTapir` maps `ServiceError` to it, mirroring `errorResponseHandler`'s smithy mapping one case at a time.

## Security

Tapir endpoints are not covered by `ServerMiddleware` (see [middleware.md](../middleware.md)); each wires `zServerSecurityLogic(authorizationService.auth(...))` via `securedEndpoint`'s typed `securityIn`s (`auth.bearer[AccessToken]`, `header[OrganizationID](...)`). `uploadOrganizationLogoPostEndpoint` calls `authorizationService.auth` with `requiresCompletedOnboardStage = true` and `organizationUserRolesAllowedOpt = Some(OrganizationUserRole.adminRoles)`.

## Swagger/OpenAPI integration

- Tapir generates its own OpenAPI spec via `OpenAPIDocsInterpreter().toOpenAPI(...)`, served from `GET /docs/specs/<namespace>.<ShapeName>.json` (`docsEndpoint`), mimicking the smithy4s doc path.
- The Tapir-backed service is listed in the shared smithy Swagger UI via `smithy4sDocsID` passed to `HttpApp.externalSmithySwaggerRoutes`'s `docs[Task](...)` call.
- This Tapir docs route must be mounted **before** the smithy swagger routes in `HttpApp.externalDocsRoutes` — otherwise smithy4s intercepts the path first and 500s trying to load a non-existent classpath spec (see the `IMPORTANT: Ordering matters` comment in `HttpApp.scala`).
- `requiredOrganizationRolesDescription(roles)` (passed the same `OrganizationUserRole` list the security logic enforces) renders the same `**Required Organization User Roles:** [...]` marker used in smithy operation doc comments (see [smithy-project.md § Operation rules](smithy-project.md#operation-rules)).

## Schema/codec quirk

`tapirServerErrorSchemas` (in `json/json.scala`) names each case's schema after the smithy4s-generated `<Structure>ResponseContent` name (e.g. `BadRequestResponseContent`), so both swagger docs expose consistent component names. A fallback `Schema[TapirServerError]` named `"ServerError"` covers paths where the specific variant isn't known ahead of time (e.g. the decode-failure handler). The custom `JsonValueCodec[TapirServerError]` round-trips through `code` (not `schemaName`); decoding an unknown code fails loudly via `in.decodeError` rather than silently defaulting.

## Entity size limits

`HttpApp.scala` sets `SmithyMaxEntitySize` (2 MB) for smithy routes and a separate `TapirMaxEntitySize` (20 MB) for Tapir routes, since Tapir carries the logo upload. Keep `TapirMaxEntitySize` in sync with the `file-service.max-organization-logo-bytes` config value.

## Naming

`TapirServerError` cases are `PascalCase` + `Error` suffix (`BadRequestError`, `ConflictError`); `code` is `SCREAMING_SNAKE_CASE` (`BAD_REQUEST_ERROR`).
