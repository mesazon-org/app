# Alternate HTTP transport changes

For non-Smithy endpoints read [feature endpoints](../features/flow/01-endpoints.md), [Tapir](../standards/tapir.md), this file, and [Authentication](authentication.md). Tapir is only for transports Smithy cannot express; currently the [Organization Management](../features/organization-management.md#logo-upload) logo upload and [Catalogue](../features/catalogue.md#image-upload) image upload, both streaming with a separate size limit.

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

`HttpApp` limits Smithy to 5 MB and Tapir to 20 MB. Keep `TapirMaxEntitySize` equal to `file-service.max-upload-bytes`. For how `FileScanner`/`ImageProcessing` handle the streamed bytes against that limit, see [Streaming uploads](streaming-uploads.md).

Test security/status/error rendering and the streaming side effect end-to-end; update the owning feature doc (`organization-management.md` or `catalogue.md`) when an upload endpoint's rationale or behavior changes.
