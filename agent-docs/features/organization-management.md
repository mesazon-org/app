# Organization Management

Owns organization details/addresses/slug/stage, membership roles, and creation. `@completedOnboardStage` requires a valid access token and `OnboardStage.completedStages` (`PhoneVerified`).

**Scope**: organization rows, membership/roles, creation endpoint, slug uniqueness, and the logo upload endpoint that advances the organization to `LogoProvided`.

## Organization stage machine

`OrganizationStage`: `DetailsProvided` → `LogoProvided`. Set to `DetailsProvided` on creation, `LogoProvided` after a logo upload (see [Logo upload](#logo-upload) below).

## Endpoint

| Method | Path | Transport | Auth | Purpose |
|---|---|---|---|---|
| POST | `/create/organization` | smithy (JSON, 5 MB limit) | Bearer + completed onboarding | Create an organization |

Smithy spec: `backend/gateway/core/src/main/smithy/OrganizationManagementService.smithy` (+ `domain/OrganizationManagement.smithy`).

`CreateOrganizationPost` carries no `@organizationUserRolesAllowed` because the caller has no membership yet — the flow *creates* the membership, making them `Owner`.

## Role policy (for future org-scoped endpoints)

Follow the [role policy](../standards/smithy.md#custom-traits): reads `Owner|Admin|User`; mutations `Owner|Admin`; organization deletion `Owner` only.

## Flow

### POST /create/organization (`OrganizationManagementService.createOrganizationPost`)
1. Read `AuthedUser`; validate name, slug, contacts, addresses, optional tagline/company registration/tax ID.
   - `emails`/`phoneNumbers` are JSONB lists of value + `isDefault`; validate every entry and exactly one default when non-empty.
   - `addresses` is a JSONB list of `OrganizationAddressEntry` (`RefinedType[AddressEntry, Pure]`, like `OrganizationPhoneNumber` over `PhoneNumber`); `AddressEntry(address: Address, addressType: AddressType)`, where `Address` holds optional `addressLine1`, `addressLine2`, `city`, `postalCode`, and `country` owner-less `NonEmptyTrimmed` newtypes. Today only `addressLine2` is optional and Smithy rejects a missing required part before validation; after this change all five Smithy members and domain fields are optional. `AddressValidator` validates every supplied text value unchanged: missing/null fields become `None`, while empty, whitespace-only, untrimmed, or overlong strings produce field-specific errors containing the original value. At least one field must contain non-whitespace text. The address-type combination is checked first; an invalid combination returns only the `addresses` error and skips every entry's field validation. For a valid combination, `validateAddressHasAtLeastOneFieldNonEmpty` uses `trimAndFilterNonEmpty` only to check for non-whitespace content; field validation receives the original values unchanged. The presence error accumulates before field errors: all-missing entries return only the indexed `address` error, while blank-only entries also report each supplied invalid field. No default rule. Allowed combinations remain empty, exactly one entry typed `ShippingAndBilling`, or exactly two entries, one `Shipping` and one `Billing` in either order; contents are not compared. Stored lists are not re-checked or cleaned up, and existing JSONB documents with populated fields remain readable without migration. This is an intentionally breaking single release: after a partial address is stored, the prior binary cannot safely read that row, coexist in a rolling deployment, or serve as a rollback target. The request validator wraps validated entries in `OrganizationAddressEntry`. On the wire the list is `OrganizationAddressEntryRequests` of shared `AddressEntryRequest { address: AddressRequest, addressType: AddressType }`; the address object and `addressType` remain required.
   - `OrganizationSlug`: trimmed, non-empty, max 63, `^[a-z0-9]+(?:-[a-z0-9]+)*$`; safe for URL path or DNS label.
2. `OrganizationManagementRepository.createOrganization` inserts **in one transaction**:
   - `OrganizationDetailsRow` (generated `OrganizationID`, stage `DetailsProvided`, `logoImageAsset` `None`), and
   - `OrganizationUserRow` linking the creator with `OrganizationUserRole.Owner`.
   - Slug uniqueness is enforced only by the database unique constraint at insert; a duplicate fails the transaction as a `RepositoryError` → `500 INTERNAL_SERVER_ERROR`, and neither row is stored.
3. Retry the created email; final failure is logged and does not fail the request.
4. Response: the new `organizationID`.

The repository also exposes `isOrganizationSlugExists` (`OrganizationManagementRepository.scala`), which is **currently uncalled** — no service or validator invokes it; only `OrganizationManagementRepositorySpec` exercises it. Epic gap 1 (taken short name reported as a server error) is the open product question about using it. `updateOrganization` is used by the logo upload below for `logoImageAssetOptUpdate` and stage updates.

## Logo upload

`POST /upload/organization/logo` is a Tapir streaming endpoint, not Smithy — Smithy JSON routes cap at 5 MB (`HttpApp.SmithyMaxEntitySize`); Tapir streams binary and allows 20 MB (`HttpApp.TapirMaxEntitySize`, kept equal to `file-service.file-bytes-max`). See [Alternate HTTP](../project/alternate-http.md) for the shared Tapir transport mechanics (docs mounting, error model, security wiring) this endpoint follows alongside [Catalogue](catalogue.md#image-upload)'s catalogue-item-image upload — the two mirror the same pipeline shape, differing only in entity scoping and persistence target.

Binary body; organization in the `X-Organization-ID` header, original file name in the `X-File-Name` header (metadata travels in headers because the body is the raw image). A missing or malformed header (non-UUID organization id; file name not 1–255 trimmed characters) is a Tapir decode failure → `400 BAD_REQUEST_ERROR` via `tapir.scala`'s `decodeFailureHandler`. Security (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages` (= `PhoneVerified`), and the caller must be assigned to the organization as `Owner` or `Admin` (disallowed role → `403`, no membership row → `500`).

Error codes on this endpoint are Tapir's `TapirServerError` codes (`TapirServerError.scala`), mapped from `ServiceError` by `HttpErrorHandler.errorResponseHandlerTapir`: every 400 is `BAD_REQUEST_ERROR` — there is no `VALIDATION_ERROR`/field list on this endpoint, unlike the Smithy create endpoint.

`FileService.uploadOrganizationLogo` runs inside one `ZIO.scoped` block; every intermediate file is a `TempFile.createScoped` (auto-deleted on scope close, even on failure):

1. `FileScanner.scan` validates the existing declared filename header, spools the incoming `ZStream[Byte]` to a temp file, and drains the entire input even past the byte cap (writing at most `fileBytesMax + 1` bytes, discarding the rest) so an oversized request body isn't abandoned mid-read (rationale: [Streaming uploads](../project/streaming-uploads.md)). It detects the actual MIME type with Apache Tika using the filename as a hint, requires the extension and detected MIME to agree, and rejects anything outside `SupportedMediaType.images` (`PNG`, `JPEG`, `WEBP`). The file type is therefore judged from content, never from the extension or client-declared content type alone. Status mapping: file name with no extension or an unsupported extension (checked before the body is read), or detected content not matching the extension → `BadRequestError.ValidationError` → `400 BAD_REQUEST_ERROR`; unreadable stream or more than `fileBytesMax` bytes → `InternalServerError.UnexpectedError` → `500` (over real HTTP an oversized body never reaches `scan` — it hangs upstream, see [Known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast)).
2. `ImageProcessing.normalize` re-detects the format with scrimage's `FormatDetector`, decodes, bounds to 640×640 px (`MaxDimensionPixels`), and re-encodes as lossless WebP. Yields the untouched original stream and the normalized variant. A file that passed `scan` (e.g. a damaged image with a supported name, such as the acceptance asset `organization-malformed-it-1.png`, a PNG signature and valid `IHDR` followed by a cut-off `IDAT` chunk with no `IEND`) but that scrimage cannot detect as a supported format or decode fails with `InternalServerError.UnexpectedError` → `500 INTERNAL_SERVER_ERROR` (epic gap 2).
3. `S3ClientOrganizationMedia.uploadOrganizationLogo` stores both variants at `{organizationLogoBucketPathPrefix}/{organizationID}/{originalFileName|normalizedFileName}` in bucket `organization-media`, returning both bucket keys as an `UploadedImageResult` (`imageOriginalS3BucketKey`/`imageNormalizedS3BucketKey`), from which the service builds an `OrganizationLogoImageAsset` (composite `ImageAsset` newtype: original bucket key, normalized bucket key, original file name). `genMediaUrl` takes a single `S3BucketKey` and returns a presigned GET URL (`urlExpiresAtOffset`) as `S3MediaUrl`; logos are never served through the gateway. `readiness` does a `HeadBucket` check for the health endpoint.
4. `OrganizationManagementRepository.updateOrganization`'s `logoImageAssetOptUpdate` persists the `OrganizationLogoImageAsset` and moves the organization to `OrganizationStage.LogoProvided` — unlike the catalogue-item image upload, this endpoint always transitions the owning row's lifecycle state. A repeat upload overwrites the persisted `logoImageAsset` and re-sets the stage to `LogoProvided` (no-op transition). The bucket keys depend only on the organization ID, so the repeat upload also overwrites both S3 objects in place; no earlier variant remains. Every refusal (security, header decode, `scan`, `normalize`) happens before anything reaches S3 or the row, so a refused upload stores nothing and leaves the organization unchanged.

### Key files (logo upload)

- Orchestration: `service/FileService.scala` (shared with [Catalogue](catalogue.md#image-upload)'s image upload)
- Pipeline utils (shared): `utils/FileScanner.scala`, `utils/ImageProcessing.scala`, `utils/TempFile.scala`
- Transport (shared): `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala`; wiring + entity limits: `HttpApp.scala`
- S3 (shared): `clients/S3ClientOrganizationMedia.scala` (+ `S3ClientOrganizationMediaConfig`)
- Domain (shared): `backend/domain/src/main/scala/io/mesazon/domain/gateway/SupportedMediaType.scala`
- Config: `FileServiceConfig` (`file-service.file-bytes-max`, shared with catalogue item image upload)

### Tests (logo upload)

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/FileApiSpec.scala`'s `/upload/organization/logo` block — upload happy path asserting the original object equals the uploaded bytes, the normalized object exists and is non-empty (its lossless WebP format and 640×640 bound are not checked here; `ImageProcessingSpec` proves them with golden WebP outputs), the saved `logoImageAsset` keys and file name, and the `LogoProvided` stage; a repeat upload with a different file name (the row's `logoImageAsset` becomes the second upload's and differs from the first's, the stage stays `LogoProvided`, and the bucket holds only the two organization-scoped keys — both uploads send the same fixture bytes, so object content replacement itself is not distinguishable); missing `X-File-Name` header (400), and a damaged `.png` from an admin-role member (`organization-malformed-it-1.png`, a truncated PNG that Tika detects as `image/png` but scrimage cannot decode) that passes `FileScanner` but fails `ImageProcessing` (500). Its `middleware` section holds one test per organization gate on the single endpoint, in gate order: missing `X-Organization-ID` header (400), missing token (401), invalid token (401), incomplete onboard stage (403), disallowed role (403), missing user details (500, with a membership row present), and non-member (500); each seeds a logo-less organization row. Every rejection asserts the `organization_details` table is unchanged (the seeded row, or empty when none is seeded) and that no logo object exists in the `organization-media` bucket. 11 tests. Filename/content mismatch (400) is proven for this shared pipeline by the catalogue-item block and `FileScannerSpec`, not by this block. Oversized-upload rejection is **not** covered over real HTTP — see [known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast).
- Functional: `fun/FileServiceSpec.scala`'s `uploadOrganizationLogo` block
- Unit (shared): `unit/utils/FileScannerSpec.scala` — proves supported filename/content agreement, the size cap (including the one-extra-byte boundary), and mismatch rejection directly against `FileScanner.scan`, independent of HTTP transport
- Integration (shared): `it/S3ClientOrganizationMediaSpec.scala`'s `uploadOrganizationLogo` block against `src/test/resources/compose/s3.yaml`

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/OrganizationManagement.scala` (contact-point entries, `CreateOrganizationPostRequest`); the shared `Address` and `AddressEntry` case classes (`Address.scala`, `AddressEntry.scala`) and `AddressType` enum (`Shipping`/`Billing`/`ShippingAndBilling`, Smithy enum in `domain/Gateway.smithy`) has its own file `AddressType.scala`; `Organization*` newtypes live in the shared `Newtypes.scala`, and the `OrganizationStage`/`OrganizationUserRole` enums each have their own file (`OrganizationStage.scala`, `OrganizationUserRole.scala`) — see [domain placement](flow/02-validation.md#domain-placement)
- Validator: `validation/service/OrganizationManagementRequestValidator.scala`
- Arbitraries: `testkit/base/OrganizationManagementDomainArbitraries.scala`, `gateway/utils/OrganizationManagementSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/OrganizationManagementService.scala`
- Repository: `repository/OrganizationManagementRepository.scala`; rows: `repository/domain/OrganizationDetailsRow.scala` (its `logoImageAsset: Option[OrganizationLogoImageAsset]` is one composite field over three adjacent columns, not parallel `Option`s — see [Logo upload](#logo-upload)), `OrganizationUserRow.scala`; queries: `repository/queries/OrganizationDetailsQueries.scala`, `OrganizationUserQueries.scala`
- Completed-stage gate: `middleware/ServerMiddleware.scala` + `service/AuthorizationService.scala`
- Config: `OrganizationManagementConfig` (created-email retries)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/OrganizationManagementApiSpec.scala` — the `POST /create/organization` section holds the creation happy path (org + owner rows in DB), creation with only the required fields (empty contact/address lists and absent optional fields stored as empty/`None`), an empty name (`400 ValidationError` on `name`; no organization or membership row, no email), and duplicate slug (`500`; the existing organization row is unchanged, no membership row is stored — proving the one-transaction rollback — and no email). The `middleware` section holds one test per applicable gate on its single endpoint: missing token (`401`), invalid token (`401`), incomplete onboard stage (`403`), and missing user details (`500`); each asserts no organization or membership row and no email. The organization-header, role, and membership gates do not apply: the endpoint takes no organization header and creates the membership. 8 tests
- Functional: `fun/OrganizationManagementServiceSpec.scala`
- Integration: `it/OrganizationManagementRepositorySpec.scala`
