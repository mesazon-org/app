# Alternate HTTP transport changes

For non-Smithy endpoints read [feature endpoints](../features/flow/01-endpoints.md), [Tapir](../standards/tapir.md), this file, and [Authentication](authentication.md). Tapir is only for transports Smithy cannot express; currently the [Organization Management](../features/organization-management.md#logo-upload) logo upload, [Catalogue](../features/catalogue.md#image-upload) image upload, [Customer Book](../features/customer-book.md#image-csv-and-excel-extraction) extraction upload, and [Catalogue file extraction](../features/catalogue.md#catalogue-file-extraction), all streaming with a separate size limit.

## Files

- `tapir/tapir.scala`: `securedEndpoint`, decode/error output, role description, aliases/options.
- `tapir/FileServiceEndpoints.scala`: organization-logo upload, customer extraction, and their docs route. The legacy `/upload/catalogue-item/image` endpoint is removed.
- `tapir/CatalogueFileServiceEndpoints.scala`: `POST /catalogue/file/upload/image/item`, `POST /catalogue/file/ai/extract`, and their separate docs route. `HttpApp` mounts both file-service route sets and registers both specs in Swagger. `Main` supplies `CatalogueFileService.live` and its config.
- `TapirServerError`: BadRequest, Unauthorized, Forbidden, NotFound, Conflict, InternalServer, ServiceUnavailable; cases are `UpperCamelCase` + `Error`, codes `SCREAMING_SNAKE_CASE`.

Each endpoint explicitly wires `zServerSecurityLogic(authorizationService.auth(...))`; there is no Smithy middleware. `securedEndpoint` supplies bearer and `X-Organization-ID`. Upload requires completed onboarding and `OrganizationUserRole.adminRoles`. File endpoints use the existing required `X-File-Name` header; customer and catalogue extraction use it for media detection and validation without storing the upload. Catalogue extraction also accepts `X-Processing-Message`: trim before validation, blank means absent, and over 2,000 characters maps to the existing `400 BAD_REQUEST_ERROR` code/message body.

## Docs and errors

- Generate OpenAPI with `OpenAPIDocsInterpreter`, serve `GET /docs/specs/<namespace>.<ShapeName>.json`, and register `smithy4sDocsID` in the shared Swagger UI.
- Mount Tapir docs before Smithy Swagger routes; otherwise Smithy intercepts the path and fails loading a nonexistent classpath spec.
- `requiredOrganizationRolesDescription` receives the exact enforced role list and renders the same role marker as Smithy docs.
- Error handler mirrors Smithy. Schema names use Smithy `<Structure>ResponseContent`; fallback is `ServerError`. Unknown JSON error code calls `in.decodeError`.
- Preserve transport error parity described in [Authentication](authentication.md).

`HttpApp` limits Smithy to 5 MB and Tapir to 20 MB. Keep `TapirMaxEntitySize` aligned with `file-service.file-bytes-max` and the endpoint-specific `catalogue-file-service.upload-catalogue-item-image-file-bytes-max` / `catalogue-file-service.extract-catalogue-items-file-bytes-max` defaults. The entity limiter is shared; scanner limits are supplied separately by each service. For how `FileScanner`/`ImageProcessing` handle the streamed bytes against that limit, see [Streaming uploads](streaming-uploads.md).

Test security/status/error rendering and the streaming side effect end-to-end; update the owning feature doc (`organization-management.md`, `catalogue.md`, or `customer-book.md`) when an upload endpoint's rationale or behavior changes.
