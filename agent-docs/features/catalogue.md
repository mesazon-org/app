# Catalogue

Tenant-scoped products/services sold by the organization.

## Scope

Owns `catalogue_item`: name, free-text unit, optional exact price/currency, optional image metadata, one-way archival, and the image upload endpoint (see [Image upload](#image-upload)) that writes back the three image columns without changing `CatalogueItemStatus`. Excludes orders/line items and tenant membership/roles.

## Model and decisions

`catalogue_item`:

- PK `(organization_id, catalogue_item_id)`; all access is tenant-scoped.
- `name text not null`.
- `unit text not null`: open vocabulary (`piece`, `kg`, `hour`, bespoke services); rejected enum because new units must not require code/migrations.
- `price_amount numeric` + `price_currency text`: both null or both present. The application models them as optional `CatalogueItemPrice`, a `Pure` newtype over `Price` with mandatory amount/currency, so a half-price is unrepresentable. `numeric`/Scala `BigDecimal` is exact; never pass through `Double`. New client input is ISO 4217 only: trim and uppercase the code, resolve it through `java.util.Currency`, and reject unsupported codes or currencies whose fraction digits are `-1`.
- Price validation is shared because amount validity depends on currency. Amounts must be non-negative with at most 12 integer digits (equivalent to `[0, 1,000,000,000,000)`) and their supplied `BigDecimal` scale must satisfy `scale <= currency.getDefaultFractionDigits`; for non-zero amounts, check the integer-digit bound with widened `precision - scale` arithmetic before normalization so a compact extreme exponent cannot cause unbounded allocation. Zero bypasses that representation-derived bound because its exponent does not change its value. Never round or remove supplied precision. After validation, normalize upward to the currency's exact scale by appending zeros. Thus `JPY 1.00` is invalid, `USD 1` becomes `1.00`, `KWD 1.23` becomes `1.230`, zero is valid at any scale, and a non-zero value with a negative scale is valid only within the amount bound and is normalized exactly.
- Smithy encodes `BigDecimal` as a JSON number. If clients cannot preserve exact decimal scale, change wire `amount` to `String` and parse it at validation.
- Image columns: `image_original_bucket_key`, `image_normalized_bucket_key`, and `image_original_file_name` are jointly optional. The application models them as optional `CatalogueItemImageAsset`, a `Pure` newtype over `ImageAsset` with mandatory original bucket key, normalized bucket key, and original file name, so partial image metadata is unrepresentable. Mirrors the organization-logo upload pipeline, but uses the shared `ImageAsset`/`ImageOriginalBucketKey`/`ImageNormalizedBucketKey`/`ImageOriginalFileName` value newtypes rather than organization-owned logo newtypes. Insert/update contain no bytes; GET models expose presigned URL fields, generated on the fly from the stored bucket keys.
- `status catalogue_item_status` (`Active|Archived`): native PG enum matching Scala case names. Never hard-delete; no unarchive.
- `uq_catalogue_item_name`: partial unique `(organization_id, name) where status='Active'`; archive frees names. Repository maps its `23505` to 409.

The `numeric` choice is the documented exception to the usual primitive set.

## Endpoints

`CatalogueService`: bearer + completed onboarding + `X-Organization-ID`. Reads allow `OWNER|ADMIN|USER`; writes `OWNER|ADMIN`.

| Method | Path | Operation | Effect/result |
|---|---|---|---|
| POST | `/insert/catalogue-item` | `InsertCatalogueItemPost` | create active item |
| POST | `/insert/catalogue-items` | `InsertCatalogueItemsPost` | atomic batch |
| PUT | `/update/catalogue-item` | `UpdateCatalogueItemPut` | update name/unit/price |
| PUT | `/archive/catalogue-item` | `ArchiveCatalogueItemPut` | archive by ID |
| GET | `/get/catalogue-item/{catalogueItemID}` | `GetCatalogueItemGet` | full item: name, unit, price, both image URLs |
| GET | `/get/catalogue-items` | `GetCatalogueItemsGet` | active-item summaries: name, status, normalized image URL only — no unit/price |

Errors:

- insert/update: `BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError`.
- archive/read: `BadRequest, Unauthorized, Forbidden, InternalServerError`; pure UUID cannot validation-fail and archive cannot create a name conflict.

Contract: `smithy/CatalogueService.smithy`, `smithy/domain/Catalogue.smithy`. Optional `price` is `CatalogueItemPriceRequest`; if present, both `amount` and `currency` are required. Request lists use `@default([])`; response lists use `@required`. `CatalogueItemStatus` is a Smithy enum (`ACTIVE`/`ARCHIVED`) exposed only on the list response; `GatewayClient`'s jsoniter codec for it must be hand-written like `OnboardStage`/`CustomerType` — smithy4s enums are not native Scala 3 enums, so macro derivation silently produces the wrong wire shape without one.

## Image upload

`POST /upload/catalogue-item/image` is a Tapir streaming endpoint, not Smithy — Smithy JSON routes cap at 5 MB (`HttpApp.SmithyMaxEntitySize`); Tapir streams binary and allows 20 MB (`HttpApp.TapirMaxEntitySize`, kept equal to `file-service.max-upload-bytes`). See [Alternate HTTP](../project/alternate-http.md) for the shared Tapir transport mechanics (docs mounting, error model, security wiring) this endpoint follows alongside [Organization Management](organization-management.md#logo-upload)'s logo upload — the two mirror the same pipeline shape, differing only in entity scoping and persistence target.

Binary body; organization in `X-Organization-ID`, catalogue item in `X-Catalogue-Item-ID`, original file name in `X-File-Name`. Security (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages` (= `PhoneVerified`), and the caller must be assigned to the organization as `OWNER` or `ADMIN` (disallowed role → `403`, no membership row → `500`).

`FileService.uploadCatalogueItemImage` runs inside one `ZIO.scoped` block; every intermediate file is a `TempFile.createScoped` (auto-deleted on scope close, even on failure):

1. Load the catalogue item (`CatalogueRepository.getCatalogueItem`) and fail with `InternalServerError` if it's missing or not `Active` (archived items keep their existing image, if any, untouched) — checked before any bytes are read.
2. `FileScanner.scan` spools the incoming `ZStream[Byte]` to a temp file, draining the entire input even past the byte cap (writing at most `maxFileBytes + 1` bytes, discarding the rest) so an oversized request body isn't abandoned mid-read. Detects the actual MIME type with Apache Tika (content sniffing, never the client's declared content type) and rejects anything outside `SupportedMediaTypes.images` (`PNG`, `JPEG`, `WEBP`).
3. `ImageProcessing.normalize` re-detects the format with scrimage's `FormatDetector`, decodes, bounds to 640×640 px (`MaxDimensionPixels`), and re-encodes as lossless WebP. Yields the untouched original stream and the normalized variant.
4. `S3ClientOrganizationMedia.upload` stores both variants at `{catalogueItemImageBucketPathPrefix}/{organizationID}/{catalogueItemID}/{originalFileName|normalizedFileName}` in bucket `organization-media`, returning both bucket keys as a `CatalogueItemImageAsset` (composite `ImageAsset` newtype: original bucket key, normalized bucket key, original file name). `genMediaUrl` takes a single `S3BucketKey` and returns a presigned GET URL (`urlExpiresAtOffset`) as `S3MediaUrl`, shared for both original and normalized variants since the client is shaped to grow into a single client for every kind of organization-owned media; images are never served through the gateway. `readiness` does a `HeadBucket` check for the health endpoint.
5. `CatalogueRepository.updateCatalogueItem`'s `imageAssetOptUpdate` persists the `CatalogueItemImageAsset`. `CatalogueItemStatus` is untouched — unlike the logo upload, this endpoint never transitions the owning row's lifecycle state.

### Key files (image upload)

- Orchestration: `service/FileService.scala` (shared with [Organization Management](organization-management.md#logo-upload)'s logo upload)
- Pipeline utils (shared): `utils/FileScanner.scala`, `utils/ImageProcessing.scala`, `utils/TempFile.scala`
- Transport (shared): `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala`; wiring + entity limits: `HttpApp.scala`
- S3: `clients/S3ClientOrganizationMedia.scala` (+ `S3ClientOrganizationMediaConfig`)
- Domain (shared): `backend/domain/src/main/scala/io/mesazon/domain/gateway/SupportedMediaTypes.scala`
- Config: `FileServiceConfig` (`file-service.max-upload-bytes`, shared with organization logo upload)

### Tests (image upload)

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/FileApiSpec.scala`'s `/upload/catalogue-item/image` block — upload happy path asserting both objects land in S3 and the row's image/status, missing `X-Catalogue-Item-ID`/`X-File-Name`/`X-Organization-ID` headers (400), missing token (401), invalid token (401), disallowed stage (403), non-member (500), disallowed role (403), missing/archived/foreign-organization catalogue item (500), and unsupported file type (500). Oversized-upload rejection is **not** covered here over real HTTP — see [known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast).
- Functional: `fun/FileServiceSpec.scala`'s `uploadCatalogueItemImage` block, including the oversized-file rejection path via a mocked `FileScanner`
- Unit (shared): `unit/utils/FileScannerSpec.scala` — proves the size cap (including the one-extra-byte boundary) and MIME-type rejection directly against `FileScanner.scan`, independent of HTTP transport
- Integration: `it/S3ClientOrganizationMediaSpec.scala` against `src/test/resources/compose/s3.yaml`

## Status

| Slice | Done | Remaining |
|---|---|---|
| Endpoints | six Smithy operations/models plus the Tapir `POST /upload/catalogue-item/image` endpoint; codegen green | — |
| Validation | request models, shared ISO price validation, domain/Smithy arbitraries, validator, unit specs | — |
| Schema | enum, table, partial unique index, config | — |
| Repository | `CatalogueItemRow` (full) and `CatalogueItemSummaryRow` (list projection); Queries, Repository, config; real-Postgres lifecycle proof | — |
| Service | Smithy service, production wiring, functional spec, and acceptance spec; `FileService.uploadCatalogueItemImage` (scan/normalize/upload/persist); both GET endpoints resolve real presigned URLs via `S3ClientOrganizationMedia` | — |

Details:

- Shared price types: `PriceAmount` (`BigDecimal`), `PriceCurrency`, and `Price` with mandatory amount/currency. Shared image types: `ImageOriginalBucketKey`, `ImageNormalizedBucketKey`, `ImageOriginalFileName`, and `ImageAsset` with all three members mandatory. Catalogue owns only the contextual `CatalogueItemPrice` (`Pure[Price]`) and `CatalogueItemImageAsset` (`Pure[ImageAsset]`) wrappers alongside `CatalogueItemID`, `CatalogueItemName`, and `CatalogueItemUnit`; the enum is in `CatalogueItemStatus.scala`.
- `PriceDomainValidator` owns the reusable ISO currency/amount invariant. `CatalogueRequestValidator` validates single insert, batch insert, and update; UUID-only archive remains outside the feature validator. Batch failures use the singular item field `catalogueItem` and preserve each invalid item's stable source index while omitting valid positions from the error list.
- Schema/config: `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable`, `allTableNames`, and `catalogue-item-table` in both configs.
- `CatalogueItemRow.price` spans the adjacent amount/currency columns and `CatalogueItemRow.image` spans the three adjacent image metadata columns through composite refined codecs. `CatalogueItemSummaryRow` (`catalogueItemID`, `name`, `image`, `status`) is a lighter projection returned only by `getCatalogueItemsActive`, backed by a dedicated narrower `SELECT`; it deliberately excludes `unit`/`price`/`organizationID`/timestamps, which the list endpoint doesn't need.
- Validation normalizes ISO currency with trim plus uppercase, accepts amounts in `[0, 1,000,000,000,000)`, rejects values whose supplied scale exceeds the ISO currency's fixed fraction digits, then appends zeros to reach that exact scale without rounding. `XXX` and currencies with `fractionDigits = -1` are rejected.
- `CatalogueItemQueries`: single/batch insert, dynamic update + returning, archive + returning ID, get any status, `getCatalogueItemRow` (full row), `getCatalogueItemRowsActive` → renamed to and now backed by `getCatalogueItemSummaryRowsActive` (summary projection), test getter. Preserve qualified native-enum write/read casts.
- Generic refined `Meta[BigDecimal]` provides exact codec; no bespoke codec.
- `CatalogueRepository`: companion `InsertCatalogueItemInput`, UUIDv7/time generation, named unique mapping. `priceOptUpdate` and `imageOptUpdate` flatten their optional composites across all corresponding columns. `None` means unchanged; clearing price or image is not supported. New item image metadata remains `None`. `getCatalogueItemsActive` returns `List[CatalogueItemSummaryRow]`, not `List[CatalogueItemRow]`.
- Do not add unused live layers: wire repository/queries with the service because ZIO rejects unused layers.

## Completed proof

`PriceDomainValidatorSpec` proves ISO normalization, canonical upward scale normalization, the exclusive monetary upper bound, compact non-zero extreme-exponent rejection, extreme-scale zero acceptance, fixed-fraction boundaries for zero-, two-, and three-digit currencies, pseudo/unsupported currency rejection, negative amounts, zero, and bounded non-zero negative scales. `CatalogueRequestValidatorSpec` proves success round-trips and exact accumulated errors for single insert, indexed batch insert, and update, including absent optional prices.

`CatalogueRepositorySpec` covers every repository method against real PostgreSQL: controlled IDs/timestamps and complete rows sampled from repository arbitraries; absent/present exact ISO-valid prices from the shared correlated generator; absent creation image metadata and composite image updates; tenant scope; active-name conflicts and exact error mapping; batch order, no-op, and rollback; update/no-op semantics; archive/name reuse; active-and-archived reads; and active-only unordered listing of `CatalogueItemSummaryRow`.

## Acceptance proof

`CatalogueApiSpec` follows [Acceptance testing](../project/acceptance-testing.md) and covers all six endpoints over the real gateway and PostgreSQL stack. It proves complete rows/responses, exact validation and conflict bodies, atomic batch rollback and empty batches, update/archive no-ops for missing, archived, or foreign-organization items, archived by-ID visibility, active-only tenant-filtered listing, persisted image-field mapping, and every applicable organization middleware branch. Writes prove that `USER` is forbidden. Reads allow every defined role, so the disallowed-role case is structurally impossible and intentionally omitted. Because presigned URLs are generated on the fly (signature + expiry query params), the GET specs assert URL presence/absence rather than exact string equality — `S3ClientOrganizationMediaSpec` is what proves a presigned URL actually serves the uploaded bytes.

`GatewayClient` supplies typed JSON codecs and HTTP methods for all six Catalogue operations, including required-list encoding for batch insert. `GatewayAcceptanceSpec` registers the child spec, and `GatewayItContext` exposes `CatalogueItemQueries` for direct arrangement and complete state assertions.

See [Image upload](#image-upload) for the `POST /upload/catalogue-item/image` endpoint, its pipeline, and its acceptance matrix.

## Service implementation

`CatalogueService` validates insert/update bodies, maps validated requests into repository-owned insert inputs, preserves the organization ID on every repository call, and maps rows back to Smithy responses. Missing catalogue-item reads become `InternalServerError` with the stable `catalogueItemID` message. Update and archive remain silent `204` no-ops for missing or archived items, matching the repository's active-row mutation semantics.

Both GET operations depend on `S3ClientOrganizationMedia` to resolve real presigned URLs from the persisted bucket keys — `getCatalogueItemGet` resolves both `imageOriginalUrl`/`imageNormalizedUrl` (`Option[String]`, absent when the item has no image); `getCatalogueItemsGet` resolves only `imageNormalizedUrl` per item, via `ZIO.foreach` over the repository's `CatalogueItemSummaryRow` list.

`CatalogueServiceSpec` uses real price validation, a strict `CatalogueRepository` mock, and a strict `S3ClientOrganizationMedia` mock. It proves each of the six operations' mapping and response behavior, validation isolation, missing-item policy, no-op mutations, unchanged repository failures, and both the with-image and without-image branches of the GET endpoints' presigned-URL resolution.

## Open decisions

- Exact JSON for JS: keep numeric unless clients require string encoding.
- No unarchive; any future reactivation maps active-name collision to 409.
