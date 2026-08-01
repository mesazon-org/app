# Files Management

Streams uploads without whole-file buffering; enforces size/real MIME, normalizes images, stores in S3, and returns presigned URLs. `FileScanner`, `ImageProcessing`, `TempFile`, and `SupportedMediaTypes` are reusable.

**Scope**: transport (Tapir streaming endpoint), scanning, image processing, temp-file lifecycle, S3 storage/URLs. The *business* effect of an upload (which entity it belongs to, stage changes) belongs to the owning feature — e.g. [Organization Management](organization-management.md) owns the organization row that the logo upload updates.

## Why Tapir (and not smithy)

Smithy JSON routes use a 5 MB `EntityLimiter`; Tapir streams binary and allows 20 MB. Keep `TapirMaxEntitySize` = `file-service.max-upload-bytes`.

When docs are enabled, `OpenAPIDocsInterpreter` serves `GET /docs/specs/io.mesazon.gateway.smithy.FileService.json`; `FileServiceEndpoints.smithy4sDocsID` registers it in shared Swagger. Mount Tapir docs before Smithy Swagger or Smithy intercepts the path and returns 500. See [Tapir](../standards/tapir.md).

## Endpoints (Tapir, bearer auth + completed onboarding)

| Method | Path | Purpose |
|---|---|---|
| POST | `/upload/organization/logo` | Upload an organization logo (binary body; organization in the `X-Organization-ID` header, original file name in the `X-File-Name` header) |
| POST | `/upload/catalogue-item/image` | Upload a [Catalogue](catalogue.md) item's image (binary body; organization in `X-Organization-ID`, catalogue item in `X-Catalogue-Item-ID`, original file name in `X-File-Name`) |

Defined in `tapir/FileServiceEndpoints.scala`. Security logic (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages` (= `PhoneVerified`), **and** the caller must be assigned to the organization from the `X-Organization-ID` header as `OWNER` or `ADMIN` (disallowed role → `403 Forbidden`, no membership row → `500`) — same standard as the smithy services, see [authentication](../project/authentication.md).

## The streaming pipeline (`FileService.uploadOrganizationLogo` / `uploadCatalogueItemImage`)

Everything runs inside one `ZIO.scoped` block; every intermediate file is a `TempFile.createScoped` (auto-deleted on scope close, even on failure).

1. **`FileScanner.scan`** — spools the incoming `ZStream[Byte]` to a temp file. It always drains the *entire* input stream — even past the byte cap — writing at most `maxFileBytes + 1` bytes to disk and discarding the rest; abandoning the remainder of the request body mid-read can stall the server for unrelated connections (see the known-issues entry below). If the running total exceeds `maxFileBytes` the request fails. Then it detects the **actual** MIME type with Apache Tika (content sniffing — the client's declared content type is never trusted) and rejects anything outside the allowed `SupportedMediaTypes` list.
2. **`ImageProcessing.normalize`** — re-detects the image format with scrimage's `FormatDetector` (second, independent format check), decodes, bounds the image to 640×640 px (`MaxDimensionPixels`), and re-encodes as **lossless WebP**. Yields two streams: the untouched original and the normalized variant.
3. **S3 upload** — uploads both variants under `{bucketPathPrefix}/{organizationID}/{fileName|catalogueItemID}/{original|normalized}`, returning both bucket keys. `OrganizationLogosS3Client` for logos, `CatalogueItemImagesS3Client` for catalogue item images — separate client instances, same shape, sharing `file-service.max-upload-bytes` and `HttpApp.TapirMaxEntitySize`.
4. **Persistence** — `OrganizationManagementRepository.updateOrganization` records the logo's bucket keys + original file name and moves the organization to `OrganizationStage.LogoProvided`. `CatalogueRepository.updateCatalogueItem` records the catalogue item's `CatalogueItemPhoto` (composite bucket keys + original file name); it does **not** change `CatalogueItemStatus` — the item must already be `Active` (checked up front; `Archived` or missing → `500`) and stays `Active`. Catalogue reads currently map the persisted bucket keys straight to response URL fields; presigned URL generation for catalogue item images is deferred (see [Catalogue](catalogue.md#status)).

## S3 clients

- `OrganizationLogosS3Client` (`{bucketPathPrefix}/{organizationID}/{fileName}`) and `CatalogueItemImagesS3Client` (`{bucketPathPrefix}/{organizationID}/{catalogueItemID}/{original|normalized}`) each wrap their own `S3AsyncClient` + `S3Presigner` (AWS SDK v2), built as scoped layers with static credentials from their own `*S3ClientConfig`; `useMock = true` switches to path-style access for localstack-style testing.
- `getOriginalUrl` / `getNormalizedUrl` — **presigned GET URLs** expiring after `urlExpiresAtOffset`; uploaded files are never served through the gateway.
- `readiness` — `HeadBucket` check, used by the health check (`ServiceUnavailableError.S3UnavailableError`).
- A new S3 client instance needs its own bucket registered in every environment that provisions one: the S3 mock's `COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS` (`compose/compose.yaml` for local dev, `backend/gateway/it/compose.yaml` for acceptance tests) and the client's `*_URI` env override pointing at the mock host (e.g. `CATALOGUE_ITEM_IMAGES_S3_CLIENT_URI`). Missing either is invisible until an upload acceptance test actually runs — see the complexity contract's [stable examples](../../.agents/contracts/complexity.md#stable-examples).

## Supported media types

`SupportedMediaTypes` (domain enum, `ext` + `mime`): `PNG`, `JPEG`, `WEBP`; `SupportedMediaTypes.images` is the allow-list passed through the pipeline. Add new types there.

## Key files

- Orchestration: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/FileService.scala`
- Pipeline utils: `utils/FileScanner.scala`, `utils/ImageProcessing.scala`, `utils/TempFile.scala`
- Transport: `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala` (error mapping, `TapirTask`); wiring + entity limits: `HttpApp.scala`
- S3: `clients/OrganizationLogosS3Client.scala` (+ `OrganizationLogosS3ClientConfig`), `clients/CatalogueItemImagesS3Client.scala` (+ `CatalogueItemImagesS3ClientConfig`)
- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/SupportedMediaTypes.scala`
- Config: `FileServiceConfig` (`file-service.max-upload-bytes`, shared by both upload endpoints)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/FileApiSpec.scala`
  - `/upload/organization/logo`: upload happy path asserting both objects land in S3, missing `X-File-Name` header, missing token (401), invalid token (401), disallowed stage (403), missing `X-Organization-ID` header (400), non-member (500), disallowed role (403), and unsupported file type
  - `/upload/catalogue-item/image`: the same header/auth/onboard/role matrix plus catalogue-item-specific cases — missing `X-Catalogue-Item-ID` header (400), missing/archived/foreign-organization catalogue item (500), and unsupported file type (500). Oversized-upload rejection is **not** covered here over real HTTP — see [known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast).
- Functional: `fun/FileServiceSpec.scala` (both `uploadOrganizationLogo` and `uploadCatalogueItemImage`, including the oversized-file rejection path via a mocked `FileScanner`)
- Unit: `unit/utils/FileScannerSpec.scala` — proves the size cap (including the one-extra-byte boundary) and MIME-type rejection directly against `FileScanner.scan`, independent of HTTP transport
- Integration (S3 via docker compose, `s3-test` module): `it/OrganizationLogosS3ClientSpec.scala`, `it/CatalogueItemImagesS3ClientSpec.scala` — both against `src/test/resources/compose/s3.yaml`, which registers both buckets
