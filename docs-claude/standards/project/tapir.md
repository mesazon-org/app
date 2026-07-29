# Alternate transport — Mesazon specifics

Read with the [agnostic alternate-transport rules](../agnostic/tapir.md).

- Tapir (`sttp.tapir`) exists only for streaming organization-logo upload; smithy4s cannot express its streaming body + separate limit. [Rationale](../../features/files-management.md#why-tapir-and-not-smithy).

## File layout

- `tapir/tapir.scala` — shared helpers: `securedEndpoint` (bearer + `X-Organization-ID` security base), `decodeFailureHandler`/`tapirServerErrorOut` (error rendering), `requiredOrganizationRolesDescription` (swagger role marker), `TapirTask`/`TapirEndpoints` type aliases, `tapirServerOptions`.
- `tapir/FileServiceEndpoints.scala` — per-feature endpoints: `uploadOrganizationLogoPostEndpoint`, `docsEndpoint`, plus the ZIO wiring function `allRoutesAndDocsEndpoints`.

## Error model

`TapirServerError` (`backend/domain/.../TapirServerError.scala`): `BadRequestError`, `UnauthorizedError`, `ForbiddenError`, `NotFoundError`, `ConflictError`, `InternalServerError`, `ServiceUnavailableError`; each has `code`/`message`/`schemaName`. `errorResponseHandlerTapir` mirrors smithy's `errorResponseHandler`.

## Security

No `ServerMiddleware`: each endpoint wires `zServerSecurityLogic(authorizationService.auth(...))` through `securedEndpoint` inputs (`auth.bearer[AccessToken]`, `header[OrganizationID](...)`). Upload passes `requiresCompletedOnboardStage = true` and `organizationUserRolesAllowedOpt = Some(OrganizationUserRole.adminRoles)`.

## Swagger/OpenAPI integration

- Tapir generates its own OpenAPI spec via `OpenAPIDocsInterpreter().toOpenAPI(...)`, served from `GET /docs/specs/<namespace>.<ShapeName>.json` (`docsEndpoint`), mimicking the smithy4s doc path.
- The Tapir-backed service is listed in the shared smithy Swagger UI via `smithy4sDocsID` passed to `HttpApp.externalSmithySwaggerRoutes`'s `docs[Task](...)` call.
- This Tapir docs route must be mounted **before** the smithy swagger routes in `HttpApp.externalDocsRoutes` — otherwise smithy4s intercepts the path first and 500s trying to load a non-existent classpath spec (see the `IMPORTANT: Ordering matters` comment in `HttpApp.scala`).
- `requiredOrganizationRolesDescription(roles)` (passed the same `OrganizationUserRole` list the security logic enforces) renders the same `**Required Organization User Roles:** [...]` marker used in smithy operation doc comments (see [smithy.md § Operation rules](smithy.md#operation-rules)).

## Schema/codec quirk

`tapirServerErrorSchemas` (`json/json.scala`) uses smithy4s `<Structure>ResponseContent` names (e.g. `BadRequestResponseContent`). Fallback `Schema[TapirServerError]` is `"ServerError"`. `JsonValueCodec` uses `code`; unknown code calls `in.decodeError`.

## Entity size limits

`HttpApp.scala` sets `SmithyMaxEntitySize` (2 MB) for smithy routes and a separate `TapirMaxEntitySize` (20 MB) for Tapir routes, since Tapir carries the logo upload. Keep `TapirMaxEntitySize` in sync with the `file-service.max-organization-logo-bytes` config value.

## Naming

`TapirServerError` cases are `PascalCase` + `Error` suffix (`BadRequestError`, `ConflictError`); `code` is `SCREAMING_SNAKE_CASE` (`BAD_REQUEST_ERROR`).
