# Organization Management

Owns organization details/address/slug/stage, membership roles, and creation. `@completedOnboardStage` requires a valid access token and `OnboardStage.completedStages` (`PhoneVerified`).

**Scope**: organization rows, membership/roles, creation endpoint, slug uniqueness, and the logo upload endpoint that advances the organization to `LogoProvided`.

## Organization stage machine

`OrganizationStage`: `DetailsProvided` → `LogoProvided`. Set to `DetailsProvided` on creation, `LogoProvided` after a logo upload (see [Logo upload](#logo-upload) below).

## Endpoint

| Method | Path | Transport | Auth | Purpose |
|---|---|---|---|---|
| POST | `/create/organization` | smithy (JSON, 5 MB limit) | Bearer + completed onboarding | Create an organization |

Smithy spec: `backend/gateway/core/src/main/smithy/OrganizationManagementService.smithy` (+ `domain/OrganizationManagement.smithy`).

`CreateOrganizationPost` carries no `@organizationUserRolesAllowed` because the caller has no membership yet — the flow *creates* the membership, making them `OWNER`.

## Role policy (for future org-scoped endpoints)

Follow the [role policy](../standards/smithy.md#custom-traits): reads `OWNER|ADMIN|USER`; mutations `OWNER|ADMIN`; organization deletion `OWNER` only.

## Flow

### POST /create/organization (`OrganizationManagementService.createOrganizationPost`)
1. Read `AuthedUser`; validate name, slug, contacts, optional tagline/address/company registration/tax ID.
   - `emails`/`phoneNumbers` are JSONB lists of value + `isDefault`; validate every entry and exactly one default when non-empty.
   - `OrganizationSlug`: trimmed, non-empty, max 63, `^[a-z0-9]+(?:-[a-z0-9]+)*$`; safe for URL path or DNS label.
2. `OrganizationManagementRepository.createOrganization` inserts **in one transaction**:
   - `OrganizationDetailsRow` (generated `OrganizationID`, stage `DetailsProvided`, logo fields `None`), and
   - `OrganizationUserRow` linking the creator with `OrganizationUserRole.Owner`.
3. Retry the created email; final failure is logged and does not fail the request.
4. Response: the new `organizationID`.

The repository also exposes `isOrganizationSlugExists` for slug-uniqueness checks, and `updateOrganization` (used by the logo upload below for bucket keys and stage updates).

## Logo upload

`POST /upload/organization/logo` is a Tapir streaming endpoint, not Smithy — Smithy JSON routes cap at 5 MB (`HttpApp.SmithyMaxEntitySize`); Tapir streams binary and allows 20 MB (`HttpApp.TapirMaxEntitySize`, kept equal to `file-service.max-upload-bytes`). See [Alternate HTTP](../project/alternate-http.md) for the shared Tapir transport mechanics (docs mounting, error model, security wiring) this endpoint follows alongside [Catalogue](catalogue.md#image-upload)'s catalogue-item-image upload.

Binary body; organization in the `X-Organization-ID` header, original file name in the `X-File-Name` header. Security (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages` (= `PhoneVerified`), and the caller must be assigned to the organization as `OWNER` or `ADMIN` (disallowed role → `403`, no membership row → `500`).

`FileService.uploadOrganizationLogo` runs inside one `ZIO.scoped` block; every intermediate file is a `TempFile.createScoped` (auto-deleted on scope close, even on failure):

1. `FileScanner.scan` spools the incoming `ZStream[Byte]` to a temp file, draining the entire input even past the byte cap (writing at most `maxFileBytes + 1` bytes, discarding the rest) so an oversized request body isn't abandoned mid-read (rationale: [Streaming uploads](../project/streaming-uploads.md)). Detects the actual MIME type with Apache Tika (content sniffing, never the client's declared content type) and rejects anything outside `SupportedMediaTypes.images` (`PNG`, `JPEG`, `WEBP`).
2. `ImageProcessing.normalize` re-detects the format with scrimage's `FormatDetector`, decodes, bounds to 640×640 px (`MaxDimensionPixels`), and re-encodes as lossless WebP. Yields the untouched original stream and the normalized variant.
3. `OrganizationLogosS3Client.upload` stores both variants at `{bucketPathPrefix}/{organizationID}/{originalFileName|normalizedFileName}` in bucket `organization-logo-bucket`, returning both bucket keys. `getOriginalUrl`/`getNormalizedUrl` return presigned GET URLs (`urlExpiresAtOffset`); logos are never served through the gateway. `readiness` does a `HeadBucket` check for the health endpoint.
4. `OrganizationManagementRepository.updateOrganization` records both bucket keys + the original file name and moves the organization to `OrganizationStage.LogoProvided`.

### Key files (logo upload)

- Orchestration: `service/FileService.scala` (shared with [Catalogue](catalogue.md#image-upload)'s image upload)
- Pipeline utils (shared): `utils/FileScanner.scala`, `utils/ImageProcessing.scala`, `utils/TempFile.scala`
- Transport (shared): `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala`; wiring + entity limits: `HttpApp.scala`
- S3: `clients/OrganizationLogosS3Client.scala` (+ `OrganizationLogosS3ClientConfig`)
- Domain (shared): `backend/domain/src/main/scala/io/mesazon/domain/gateway/SupportedMediaTypes.scala`
- Config: `FileServiceConfig` (`file-service.max-upload-bytes`, shared with catalogue item image upload)

### Tests (logo upload)

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/FileApiSpec.scala`'s `/upload/organization/logo` block — upload happy path asserting both objects land in S3, missing `X-File-Name` header, missing token (401), invalid token (401), disallowed stage (403), missing `X-Organization-ID` header (400), non-member (500), disallowed role (403), and unsupported file type
- Functional: `fun/FileServiceSpec.scala`'s `uploadOrganizationLogo` block
- Unit (shared): `unit/utils/FileScannerSpec.scala` — proves the size cap (including the one-extra-byte boundary) and MIME-type rejection directly against `FileScanner.scan`, independent of HTTP transport
- Integration: `it/OrganizationLogosS3ClientSpec.scala` against `src/test/resources/compose/s3.yaml`

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/OrganizationManagement.scala` (contact-point entries, `CreateOrganizationPostRequest`); `Organization*` newtypes live in the shared `Newtypes.scala`, and the `OrganizationStage`/`OrganizationUserRole` enums each have their own file (`OrganizationStage.scala`, `OrganizationUserRole.scala`) — see [domain placement](flow/02-validation.md#domain-placement)
- Validator: `validation/service/OrganizationManagementRequestValidator.scala`
- Arbitraries: `testkit/base/OrganizationManagementDomainArbitraries.scala`, `gateway/utils/OrganizationManagementSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/OrganizationManagementService.scala`
- Repository: `repository/OrganizationManagementRepository.scala`; rows: `repository/domain/OrganizationDetailsRow.scala`, `OrganizationUserRow.scala`; queries: `repository/queries/OrganizationDetailsQueries.scala`, `OrganizationUserQueries.scala`
- Completed-stage gate: `middleware/ServerMiddleware.scala` + `service/AuthorizationService.scala`
- Config: `OrganizationManagementConfig` (created-email retries)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/OrganizationManagementApiSpec.scala` — creation happy path (org + owner rows in DB), duplicate slug failure, plus missing/invalid token, disallowed stage, and validation cases
- Functional: `fun/OrganizationManagementServiceSpec.scala`
- Integration: `it/OrganizationManagementRepositorySpec.scala`
