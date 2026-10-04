# Catalogue

Tenant-scoped products/services sold by the organization, each sold through one or more variants.

## Status

Variants are agreed (epic [Catalogue](../../pages/epics/06-catalogue.md)) but **not built**. Today's code still ships the previous item model: `catalogue_item` carries `unit` and an optional price, there is no description, no variant table, and no variant endpoints. Everything below describes the agreed target; this table tracks what is implemented.

| Slice | Done | Remaining |
|---|---|---|
| Endpoints | Six item operations + Tapir image upload (old item shape) | Four variant operations; item shapes lose `unit`/`price`, gain `description` and `catalogueItemVariants`; price shape renamed `CatalogueItemVariantPrice` |
| Validation | Item insert/batch/update with `unit`/`price`; `PriceDomainValidator` | Variant model, description, measure-value bound, at-least-one-variant rule, variant insert/update validators |
| Schema | `catalogue_item` with `unit`/`price` | `description` column; drop `unit`/`price_amount`/`price_currency`; `catalogue_item_variant` table, enum, indexes; config |
| Repository | Item CRUD/archive/list | Variant Row/Queries, transactional item+variant insert, archive cascade, last-variant guard, parent locks |
| Service | Six item operations | Item mapping changes, four variant operations |

## Scope

Owns `catalogue_item` (name, optional description, optional image metadata, one-way archival), `catalogue_item_variant` (sale unit, optional name/SKU/measure/package/price, one-way archival), and the image upload endpoint (see [Image upload](#image-upload)) that writes back the three image columns without changing `CatalogueItemStatus`. Variants have no image. Excludes orders/line items and tenant membership/roles.

## Model and decisions

### `catalogue_item`

- PK `(organization_id, catalogue_item_id)`; all access is tenant-scoped.
- `name text not null`; `CatalogueItemName` (`NonEmptyTrimmed`: 1–255, rejected if untrimmed).
- `description text` nullable; `CatalogueItemDescription` (`NonEmptyTrimmed`). Placed directly after `name`.
- No `unit`, `price_amount`, or `price_currency`: selling details live on variants.
- Image columns: `image_original_s3_bucket_key`, `image_normalized_s3_bucket_key`, and `image_original_file_name` are jointly optional. The application models them as optional `CatalogueItemImageAsset`, a `Pure` newtype over `ImageAsset` with mandatory original bucket key, normalized bucket key, and original file name, so partial image metadata is unrepresentable. Mirrors the organization-logo upload pipeline (see [Organization Management](organization-management.md#logo-upload)), which builds the same shared `ImageAsset`/`ImageOriginalS3BucketKey`/`ImageNormalizedS3BucketKey`/`ImageOriginalFileName` value newtypes into its own contextual `OrganizationLogoImageAsset` wrapper. Insert/update contain no bytes; GET models expose presigned URL fields, generated on the fly from the stored bucket keys.
- `status catalogue_item_status` (`Active|Archived`): native PG enum matching Scala case names. Never hard-delete; no unarchive.
- `uq_catalogue_item_name`: partial unique `(organization_id, name) where status='Active'`; archive frees names. Repository maps its `23505` to 409.

### `catalogue_item_variant`

Columns in Row/field-list order: `organization_id`, `catalogue_item_id`, `catalogue_item_variant_id`, `name`, `sku`, `sale_unit`, `measure_value numeric`, `measure_unit`, `package_type`, `package_count integer`, `price_amount numeric`, `price_currency`, `status catalogue_item_variant_status`, `created_at`, `updated_at`.

- PK `(organization_id, catalogue_item_variant_id)`; FK `(organization_id, catalogue_item_id)` → `catalogue_item`. Supporting index `idx_catalogue_item_variant_catalogue_item` on `(organization_id, catalogue_item_id)`.
- `name` nullable, `CatalogueItemVariantName` (`NonEmptyTrimmed`). `uq_catalogue_item_variant_name`: partial unique `(organization_id, catalogue_item_id, name) where status='Active'`; NULL names never clash.
- `sku` nullable, `CatalogueItemVariantSku` (`NonEmptyTrimmed`: 1–255, rejected if untrimmed), compared exactly (case-sensitive). `uq_catalogue_item_variant_sku`: partial unique `(organization_id, sku) where status='Active'`. The 255 cap also keeps the btree entry far below PostgreSQL's index-row size limit.
- `sale_unit not null`, `CatalogueItemVariantSaleUnit` (`NonEmptyTrimmed`): open vocabulary (`piece`, `kg`, `hour`, bespoke services); rejected enum because new units must not require code/migrations. `measure_unit` (`CatalogueItemVariantMeasureUnit`) and `package_type` (`CatalogueItemVariantPackageType`) are likewise nullable open-text `NonEmptyTrimmed`.
- `measure_value` nullable, `CatalogueItemVariantMeasureValue`: see [Measure value](#measure-value).
- `package_count` nullable, `CatalogueItemVariantPackageCount` (`Int`, `>= 1`).
- `price_amount` + `price_currency`: both null or both present, modelled as optional `CatalogueItemVariantPrice` (`Pure[Price]`); see [Price](#price).
- Measure value, measure unit, package type, and package count are independent of each other.
- `status catalogue_item_variant_status` (`Active|Archived`): a second native PG enum, mirroring `catalogue_item_status`, with Scala `CatalogueItemVariantStatus` in its own file. One-way; never hard-deleted.
- No CHECK constraints: domain types enforce value rules.

`numeric` (price amount, measure value) is the documented exception to the usual primitive set.

### Price

Price validation is shared because amount validity depends on currency. `PriceDomainValidator` owns it. New client input is ISO 4217 only: trim and uppercase the code, resolve it through `java.util.Currency`, and reject unsupported codes or currencies whose fraction digits are `-1`. Amounts must be non-negative with at most 12 integer digits (equivalent to `[0, 1,000,000,000,000)`) and their supplied `BigDecimal` scale must satisfy `scale <= currency.getDefaultFractionDigits`; for non-zero amounts, check the integer-digit bound with widened `precision - scale` arithmetic before normalization so a compact extreme exponent cannot cause unbounded allocation. Zero bypasses that representation-derived bound because its exponent does not change its value. Never round or remove supplied precision. After validation, normalize upward to the currency's exact scale by appending zeros. Thus `JPY 1.00` is invalid, `USD 1` becomes `1.00`, `KWD 1.23` becomes `1.230`, zero is valid at any scale, and a non-zero value with a negative scale is valid only within the amount bound and is normalized exactly. Failures are accumulated `InvalidFieldError`s naming field `amount` (negative, more than 12 integer digits, scale above the currency's fraction digits) or `currency` (unknown ISO code, no fixed fraction digits). `numeric`/Scala `BigDecimal` is exact; never pass through `Double`.

Smithy encodes `BigDecimal` as a JSON number. If clients cannot preserve exact decimal scale, change wire `amount` to `String` and parse it at validation.

### Measure value

`CatalogueItemVariantMeasureValue` is a refined `BigDecimal`: strictly greater than zero, at most 12 integer digits, at most 6 decimal places (scale `<= 6`). The integer-digit bound uses widened `precision - scale` arithmetic, as for price. The supplied scale is kept exactly (`1.50` stays `1.50`); no normalization, no rounding.

The bound exists because of the wire decoder: smithy4s-json reads every `BigDecimal` with jsoniter's `readBigDecimal(null)`, which applies `MathContext.DECIMAL128` (34 significant digits) and silently rounds longer inputs before application code sees them. With at most 18 significant digits accepted, any input long enough to be rounded ends up with a scale above 6 and is rejected instead of being stored rounded. Price is protected the same way by its own integer-digit and currency-scale bounds.

### Invariants, transactions, concurrency

- An active item always has at least one active variant. Enforced in application code inside one transaction, not by a DB trigger.
- Add item (single or batch) inserts every item row then every variant row in one `transactionOrWiden`: all-or-nothing across every item and variant.
- Archive item archives the item and all its active variants in one transaction, with one `UpdatedAt`; missing/archived item is a silent no-op.
- Add variant locks the parent item `FOR SHARE` and requires it `Active` in the same transaction as the insert, so a concurrent item archive cannot leave an active variant under an archived item. Missing/archived/foreign parent → repository returns `None` → service `InternalServerError` (500).
- Archive variant locks the parent item `FOR UPDATE`, counts its active variants, then archives or fails with `ServiceError.ConflictError.CatalogueItemVariantLastActive` (409, nothing changed). The lock serializes concurrent archives of an item's last two variants. Missing/already-archived variant → silent no-op.
- Update variant updates only `status='Active'` rows; under read committed PostgreSQL re-checks that predicate after a concurrent cascade, so updating a variant of a just-archived item is a no-op without an explicit lock.
- The at-least-one rule has no DB backstop; direct SQL writes (e.g. acceptance arrangements via Queries) can create items without variants.

### Conflict messages

Fixed text per constraint; name the field, never the value (the clashing value is not recoverable without parsing PostgreSQL message text, which [Doobie](../standards/doobie.md) forbids, and a batch cannot attribute it):

| Source | Message |
|---|---|
| `uq_catalogue_item_name` | `A catalogue item with the given name already exists in this organization` |
| `uq_catalogue_item_variant_sku` | `A catalogue item variant with the given SKU already exists in this organization` |
| `uq_catalogue_item_variant_name` | `A catalogue item variant with the given name already exists for this catalogue item` |
| `CatalogueItemVariantLastActive` | `A catalogue item must keep at least one active variant` |

Two variants in the same request with the same SKU, or two same-named variants of one item, hit the same indexes inside the insert transaction and return the same 409.

### Breaking change and schema rollout

- Wire contract: `GetCatalogueItemGetResponse` loses required `unit` and optional `price` and gains `description` and required `catalogueItemVariants`; `InsertCatalogueItemPostRequest` loses `unit`/`price` and requires at least one variant (400 otherwise); `UpdateCatalogueItemPutRequest` loses `unit`/`price` (smithy4s ignores the now-unknown members). No API versioning: gateway and clients must ship together.
- Schema: the change is made in place in `V2025.05.27__init.sql` ([single init migration](../features/flow/03-schema.md#mesazon-schema)). Any database already migrated fails Flyway checksum validation; the schema image runs `clean migrate`, so dev and compose databases are recreated and **all their data is wiped**. Persistent local volumes need `docker compose down -v`. Existing item `unit`/`price` values are dropped; there is no data migration.

## Endpoints

`CatalogueService`: bearer + completed onboarding + `X-Organization-ID`. Reads allow `Owner|Admin|User`; writes `Owner|Admin`.

| Method | Path | Operation | Effect/result |
|---|---|---|---|
| POST | `/insert/catalogue-item` | `InsertCatalogueItemPost` | create active item with its variants (≥1); empty response |
| POST | `/insert/catalogue-items` | `InsertCatalogueItemsPost` | atomic batch of items with variants |
| PUT | `/update/catalogue-item` | `UpdateCatalogueItemPut` | update name/description; left-out unchanged; no clearing |
| PUT | `/archive/catalogue-item` | `ArchiveCatalogueItemPut` | archive item + its active variants |
| GET | `/get/catalogue-item/{catalogueItemID}` | `GetCatalogueItemGet` | name, description, normalized image URL, active variants in creation order (ascending UUIDv7 `catalogueItemVariantID`); archived item → empty variant list; no status |
| GET | `/get/catalogue-items` | `GetCatalogueItemsGet` | active-item summaries: name, status, normalized image URL only (unchanged) |
| POST | `/insert/catalogue-item-variant` | `InsertCatalogueItemVariantPost` | `{catalogueItemID, catalogueItemVariant}` into an active item; empty response |
| PUT | `/update/catalogue-item-variant` | `UpdateCatalogueItemVariantPut` | flat optional variant fields; left-out unchanged; no clearing; missing/archived → silent 204 |
| PUT | `/archive/catalogue-item-variant` | `ArchiveCatalogueItemVariantPut` | archive one variant; last active → 409; missing/archived → silent 204 |
| GET | `/get/catalogue-item-variant/{catalogueItemVariantID}` | `GetCatalogueItemVariantGet` | every variant field plus flat `catalogueItemID`; archived variant readable; no status; missing → 500 |

Errors:

- item insert/update, variant insert/update: `BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError`.
- variant archive: `BadRequest, Unauthorized, Forbidden, Conflict, InternalServerError` (last active variant).
- item archive, reads: `BadRequest, Unauthorized, Forbidden, InternalServerError`; pure UUID cannot validation-fail.
- Organization gate (all ten, per [Authentication](../project/authentication.md#organization-scope)): missing `X-Organization-ID` → 400 `BAD_REQUEST_ERROR`; no membership row → 500 `INTERNAL_SERVER_ERROR`, not 403; disallowed role → 403; missing user details → 500; incomplete onboarding → 403; missing/invalid token → 401.

Contract: `smithy/CatalogueService.smithy`, `smithy/domain/Catalogue.smithy`. Members: `description`, `catalogueItemVariants`, `catalogueItemVariantID`, `name`, `sku`, `saleUnit`, `measureValue` (`BigDecimal`), `measureUnit`, `packageType`, `packageCount` (`Integer`), `price`. Optional `price` is `CatalogueItemVariantPrice` (renamed from `CatalogueItemPriceRequest`); if present, both `amount` and `currency` are required. Request lists use `@default([])`; response lists use `@required`.

Shared-shape exception (agreed with the user, deviating from [Smithy](../standards/smithy.md#names) "each operation owns its shapes" and the `...Request` suffix): `CatalogueItemVariant` is the variant request shape shared by item insert, batch insert, and variant insert; `CatalogueItemVariantDetails` is the variant response shape in `GetCatalogueItemGetResponse`; `CatalogueItemVariantPrice` serves requests and responses. These names are client-facing in the epic. `GetCatalogueItemVariantGetResponse` and `CatalogueItemVariantDetails` share their variant fields through one Smithy `@mixin`; the response adds `catalogueItemVariantID` and `catalogueItemID` first.

`CatalogueItemStatus` is a Smithy enum (`Active`/`Archived`) exposed only on the list response; `GatewayClient`'s jsoniter codec for it must be hand-written like `OnboardStage`/`CustomerType` — smithy4s enums are not native Scala 3 enums, so macro derivation silently produces the wrong wire shape without one. Variant status is never exposed.

## Validation

`CatalogueRequestValidator` validates item insert, batch insert, item update, variant insert, and variant update; UUID-only archive/get stay outside it. Trimmed means rejected if untrimmed, for every text field including SKU.

- Domain requests (`backend/domain/.../gateway/Catalogue.scala`): `InsertCatalogueItemPostRequest(name, description, catalogueItemVariants: List[CatalogueItemVariant])`, `InsertCatalogueItemsPostRequest`, `UpdateCatalogueItemPutRequest(catalogueItemID, name, description)`, `CatalogueItemVariant(name, sku, saleUnit, measureValue, measureUnit, packageType, packageCount, price)`, `InsertCatalogueItemVariantPostRequest(catalogueItemID, catalogueItemVariant)`, `UpdateCatalogueItemVariantPutRequest(catalogueItemVariantID, ...all variant fields optional)`.
- An empty `catalogueItemVariants` fails field `catalogueItemVariants` with `At least one catalogue item variant is required` (`List`, not `NonEmptyList`, because Chimney has no cats module on the classpath).
- Each failed variant collapses under `catalogueItemVariant` with its index (`validateAllNested`); a bad price stays one `price` error inside it; in a batch each failed item collapses again under `catalogueItem` with its index. Valid positions produce no error.
- Newtypes (`Newtypes.scala`, `// Catalogue`): `CatalogueItemID`, `CatalogueItemName`, `CatalogueItemDescription`, `CatalogueItemImageAsset`, `CatalogueItemVariantID`, `CatalogueItemVariantName`, `CatalogueItemVariantSku`, `CatalogueItemVariantSaleUnit`, `CatalogueItemVariantMeasureValue`, `CatalogueItemVariantMeasureUnit`, `CatalogueItemVariantPackageType`, `CatalogueItemVariantPackageCount`, `CatalogueItemVariantPrice`. `CatalogueItemUnit` and `CatalogueItemPrice` are removed.

## Repository

`CatalogueRepository` with `CatalogueItemQueries` and `CatalogueItemVariantQueries`; Rows `CatalogueItemRow`, `CatalogueItemSummaryRow`, `CatalogueItemVariantRow`.

- `InsertCatalogueItemInput(name, description, catalogueItemVariants: List[InsertCatalogueItemVariantInput])`; the single variant insert reuses `InsertCatalogueItemVariantInput`.
- `insertCatalogueItem` / `insertCatalogueItems` → generated item IDs; transactional with variants.
- `updateCatalogueItem(nameOptUpdate, descriptionOptUpdate, imageAssetOptUpdate)` → `Option[CatalogueItemRow]`.
- `archiveCatalogueItem` → `Option[CatalogueItemID]`, cascades.
- `getCatalogueItem` → `Option[CatalogueItemRow]` (any status); `getCatalogueItemVariantsActive(organizationID, catalogueItemID)` → `List[CatalogueItemVariantRow]` ordered by `catalogue_item_variant_id`. Open item calls both separately; an item archived between the two reads shows an empty variant list (benign).
- `getCatalogueItemSummariesActive` → `List[CatalogueItemSummaryRow]` (unchanged).
- `insertCatalogueItemVariant` → `Option[CatalogueItemVariantID]` (`None` = parent missing/archived).
- `updateCatalogueItemVariant(...OptUpdate)` → `Option[CatalogueItemVariantRow]`; `priceOptUpdate` flattens across both price columns.
- `archiveCatalogueItemVariant` → `Option[CatalogueItemVariantID]`, or `CatalogueItemVariantLastActive`.
- `getCatalogueItemVariant` → `Option[CatalogueItemVariantRow]` (any status).
- Preserve qualified native-enum write/read casts for both status enums. Generic refined `Meta[BigDecimal]`/`Meta[Int]` provide exact codecs.
- `None` means unchanged on every update; clearing is not supported (epic gaps 4 and 5). New item image metadata remains `None`.
- Do not add unused live layers: wire repository/queries with the service because ZIO rejects unused layers.

## Image upload

`POST /upload/catalogue-item/image` is a Tapir streaming endpoint, not Smithy — Smithy JSON routes cap at 5 MB (`HttpApp.SmithyMaxEntitySize`); Tapir streams binary and allows 20 MB (`HttpApp.TapirMaxEntitySize`, kept equal to `file-service.file-bytes-max`). See [Alternate HTTP](../project/alternate-http.md) for the shared Tapir transport mechanics (docs mounting, error model, security wiring) this endpoint follows alongside [Organization Management](organization-management.md#logo-upload)'s logo upload — the two mirror the same pipeline shape, differing only in entity scoping and persistence target.

Binary body; organization in `X-Organization-ID`, catalogue item in `X-Catalogue-Item-ID`, original file name in `X-File-Name`. Security (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages` (= `PhoneVerified`), and the caller must be assigned to the organization as `Owner` or `Admin` (disallowed role → `403`, no membership row → `500`).

Errors: `TapirServerError` has no validation variant, so every 400 here is `BAD_REQUEST_ERROR` (never `VALIDATION_ERROR`). `HttpErrorHandler.errorResponseHandlerTapir` maps every `ServiceError.BadRequestError` — including `FileScanner`'s `ValidationError` for a missing/unsupported extension or an extension/detected-content mismatch — to `TapirServerError.BadRequestError`; a missing or undecodable `X-Organization-ID`/`X-Catalogue-Item-ID`/`X-File-Name` header goes through `tapir.scala`'s decode-failure handler, also `400 BAD_REQUEST_ERROR`. Missing/archived/foreign item, image decode/processing failure, and over-cap bytes are `500 INTERNAL_SERVER_ERROR`.

`FileService.uploadCatalogueItemImage` runs inside one `ZIO.scoped` block; every intermediate file is a `TempFile.createScoped` (auto-deleted on scope close, even on failure):

1. Load the catalogue item (`CatalogueRepository.getCatalogueItem`) and fail with `InternalServerError` if it's missing or not `Active` (archived items keep their existing image, if any, untouched) — checked before any bytes are read.
2. `FileScanner.scan` validates the existing declared filename header, spools the incoming `ZStream[Byte]` to a temp file, and drains the entire input even past the byte cap (writing at most `fileBytesMax + 1` bytes, discarding the rest) so an oversized request body isn't abandoned mid-read (rationale: [Streaming uploads](../project/streaming-uploads.md)). It detects the actual MIME type with Apache Tika using the filename as a hint, requires the extension and detected MIME to agree, and rejects anything outside `SupportedMediaType.images` (`PNG`, `JPEG`, `WEBP`).
3. `ImageProcessing.normalize` re-detects the format with scrimage's `FormatDetector`, decodes, bounds to 640×640 px (`MaxDimensionPixels`), and re-encodes as lossless WebP. Yields the untouched original stream and the normalized variant.
4. `S3ClientOrganizationMedia.uploadImage` stores both variants at `{catalogueItemImageBucketPathPrefix}/{organizationID}/{catalogueItemID}/{originalFileName|normalizedFileName}` in bucket `organization-media`, returning both bucket keys as an `UploadedImageResult` (`imageOriginalS3BucketKey`/`imageNormalizedS3BucketKey`), from which the service builds a `CatalogueItemImageAsset` (composite `ImageAsset` newtype: original bucket key, normalized bucket key, original file name). `genMediaUrl` takes a single `S3BucketKey` and returns a presigned GET URL (`urlExpiresAtOffset`) as `S3MediaUrl`, shared for both original and normalized variants and across every kind of organization-owned media the client serves — catalogue-item images and (see [Organization Management](organization-management.md#logo-upload)) organization logos alike; images are never served through the gateway. `readiness` does a `HeadBucket` check for the health endpoint.
5. `CatalogueRepository.updateCatalogueItem`'s `imageAssetOptUpdate` persists the `CatalogueItemImageAsset`. `CatalogueItemStatus` is untouched — unlike the logo upload, this endpoint never transitions the owning row's lifecycle state.

### Key files (image upload)

- Orchestration: `service/FileService.scala` (shared with [Organization Management](organization-management.md#logo-upload)'s logo upload)
- Pipeline utils (shared): `utils/FileScanner.scala`, `utils/ImageProcessing.scala`, `utils/TempFile.scala`
- Transport (shared): `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala`; wiring + entity limits: `HttpApp.scala`
- S3: `clients/S3ClientOrganizationMedia.scala` (+ `S3ClientOrganizationMediaConfig`)
- Domain (shared): `backend/domain/src/main/scala/io/mesazon/domain/gateway/SupportedMediaType.scala`
- Config: `FileServiceConfig` (`file-service.file-bytes-max`, shared with organization logo upload)

### Tests (image upload)

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/FileApiSpec.scala`'s `/upload/catalogue-item/image` block — upload happy path asserting both objects land in S3 and the row's image/status, missing `X-Catalogue-Item-ID`/`X-File-Name`/`X-Organization-ID` headers (400), missing token (401), invalid token (401), disallowed stage (403), non-member (500), disallowed role (403), missing/archived/foreign-organization catalogue item (500), and filename/content mismatch (400). Oversized-upload rejection is **not** covered here over real HTTP — see [known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast). Its items are arranged without variants, which the DB allows.
- Functional: `fun/FileServiceSpec.scala`'s `uploadCatalogueItemImage` block, including the oversized-file rejection path via a mocked `FileScanner`
- Unit (shared): `unit/utils/FileScannerSpec.scala` — proves supported filename/content agreement, the size cap (including the one-extra-byte boundary), and mismatch rejection directly against `FileScanner.scan`, independent of HTTP transport
- Integration: `it/S3ClientOrganizationMediaSpec.scala` against `src/test/resources/compose/s3.yaml`

## Key files and config

- Contract: `smithy/CatalogueService.smithy`, `smithy/domain/Catalogue.smithy`
- Domain: `Catalogue.scala`, `CatalogueItemStatus.scala`, `CatalogueItemVariantStatus.scala`, `Newtypes.scala` (`// Catalogue`), `ServiceError.scala` (`ConflictError.CatalogueItemVariantLastActive`)
- Validation: `validation/service/CatalogueRequestValidator.scala`, `validation/domain/PriceDomainValidator.scala`
- Repository: `repository/CatalogueRepository.scala`, `repository/queries/CatalogueItemQueries.scala`, `repository/queries/CatalogueItemVariantQueries.scala`, `repository/domain/CatalogueItemRow.scala`, `CatalogueItemSummaryRow.scala`, `CatalogueItemVariantRow.scala`
- Service: `service/CatalogueService.scala`
- Schema/config: `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable`/`catalogueItemVariantTable`, `allTableNames`, and `catalogue-item-table`/`catalogue-item-variant-table` in both `application.conf` copies

## Tests

Today's suites cover the shipped item model; each is extended or adapted in the slice that changes its behavior.

- Unit: `PriceDomainValidatorSpec` proves ISO normalization, canonical upward scale normalization, the exclusive monetary upper bound, compact non-zero extreme-exponent rejection, extreme-scale zero acceptance, fixed-fraction boundaries for zero-, two-, and three-digit currencies, pseudo/unsupported currency rejection, negative amounts, zero, and bounded non-zero negative scales. `CatalogueRequestValidatorSpec` proves a success round-trip and exact accumulated errors per public validator function, including indexed nested variant errors, the empty-variant-list error, and the measure-value bound.
- Functional: `CatalogueServiceSpec` uses real price validation, a strict `CatalogueRepository` mock, and a strict `S3ClientOrganizationMedia` mock. It proves each operation's mapping and response, validation isolation, missing-item/variant 500s, no-op mutations, unchanged repository failures, and both image branches of the GET endpoints.
- Integration: `CatalogueRepositorySpec` covers every repository method against real PostgreSQL with controlled IDs/timestamps: tenant scope; each unique index on its own (item name, variant SKU, variant name, unnamed variants not clashing, archive freeing names/SKUs); transactional item+variant insert and batch rollback in both tables; archive cascade; last-active-variant refusal leaving state unchanged; insert variant into missing/archived parent; update/no-op semantics; active-only ordered variant reads; any-status reads.
- Acceptance: `CatalogueApiSpec` covers all ten endpoints over the real gateway and PostgreSQL stack, with complete rows/responses, exact validation and conflict bodies, atomic batch rollback, no-op mutations, and every applicable organization middleware branch. Writes prove that `User` is forbidden; reads allow every role, so the disallowed-role case is structurally impossible. Presigned URLs are asserted for presence/absence; `S3ClientOrganizationMediaSpec` proves a presigned URL serves the bytes. `GatewayClient` supplies typed codecs and methods for every operation; `GatewayItContext` exposes `CatalogueItemQueries` and `CatalogueItemVariantQueries`.

## Open decisions

- Exact JSON for JS: keep numeric unless clients require string encoding.
- No unarchive; any future reactivation maps active-name/SKU collisions to 409.
- Epic gaps 1–5 (missing/archived item as 500, no variants in the list, no status on open, no clearing) stay open.
