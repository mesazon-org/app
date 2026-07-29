# Alternate HTTP transport changes

Read [feature endpoints](../features/flow/01-endpoints.md), [agnostic Tapir](../standards/tapir.md), this file, and [Authentication](authentication.md) when changing non-Smithy endpoints. Tapir exists only when Smithy cannot express the transport; currently that is streaming organization-logo upload with a separate size limit.

## Files

- `tapir/tapir.scala`: `securedEndpoint`, decode/error output, role description, aliases/options.
- `tapir/FileServiceEndpoints.scala`: upload endpoint, docs endpoint, route/docs wiring.
- `TapirServerError`: BadRequest, Unauthorized, Forbidden, NotFound, Conflict, InternalServer, ServiceUnavailable; cases are `PascalCaseError`, codes `SCREAMING_SNAKE_CASE`.

Each endpoint explicitly wires `zServerSecurityLogic(authorizationService.auth(...))`; there is no Smithy middleware. `securedEndpoint` supplies bearer and `X-Organization-ID`. Upload requires completed onboarding and `OrganizationUserRole.adminRoles`.

## Docs and errors

- Generate OpenAPI with `OpenAPIDocsInterpreter`, serve `GET /docs/specs/<namespace>.<ShapeName>.json`, and register `smithy4sDocsID` in the shared Swagger UI.
- Mount Tapir docs before Smithy Swagger routes; otherwise Smithy intercepts the path and fails loading a nonexistent classpath spec.
- `requiredOrganizationRolesDescription` receives the exact enforced role list and renders the same role marker as Smithy docs.
- Error handler mirrors Smithy. Schema names use Smithy `<Structure>ResponseContent`; fallback is `ServerError`. Unknown JSON error code calls `in.decodeError`.
- Preserve transport error parity described in [Authentication](authentication.md).

`HttpApp` limits Smithy to 2 MB and Tapir to 20 MB. Keep `TapirMaxEntitySize` equal to `file-service.max-organization-logo-bytes`.

Test security/status/error rendering and the streaming side effect end-to-end; update `files-management.md` when rationale or behavior changes.
