# Catalogue

Tenant-scoped products/services sold by the organization.

## Scope

Owns `catalogue_item`: name, free-text unit, optional exact price/currency, optional photo metadata, and one-way archival. Excludes photo upload (future Tapir route reusing [Files Management](files-management.md)), orders/line items, and tenant membership/roles.

## Model and decisions

`catalogue_item`:

- PK `(organization_id, catalogue_item_id)`; all access is tenant-scoped.
- `name text not null`.
- `unit text not null`: open vocabulary (`piece`, `kg`, `hour`, bespoke services); rejected enum because new units must not require code/migrations.
- `price_amount numeric` + `price_currency text`: both null or both present. The application models them as optional `CatalogueItemPrice`, a `Pure` newtype over `Price` with mandatory amount/currency, so a half-price is unrepresentable. `numeric`/Scala `BigDecimal` is exact; never pass through `Double`. New client input is ISO 4217 only: trim and uppercase the code, resolve it through `java.util.Currency`, and reject unsupported codes or currencies whose fraction digits are `-1`.
- Price validation is shared because amount validity depends on currency. Amounts must be non-negative with at most 12 integer digits (equivalent to `[0, 1,000,000,000,000)`) and their supplied `BigDecimal` scale must satisfy `scale <= currency.getDefaultFractionDigits`; for non-zero amounts, check the integer-digit bound with widened `precision - scale` arithmetic before normalization so a compact extreme exponent cannot cause unbounded allocation. Zero bypasses that representation-derived bound because its exponent does not change its value. Never round or remove supplied precision. After validation, normalize upward to the currency's exact scale by appending zeros. Thus `JPY 1.00` is invalid, `USD 1` becomes `1.00`, `KWD 1.23` becomes `1.230`, zero is valid at any scale, and a non-zero value with a negative scale is valid only within the amount bound and is normalized exactly.
- Smithy encodes `BigDecimal` as a JSON number. If clients cannot preserve exact decimal scale, change wire `amount` to `String` and parse it at validation.
- Photo columns: `photo_original_bucket_key`, `photo_normalized_bucket_key`, and `photo_original_file_name` are jointly optional. The application models them as optional `CatalogueItemPhoto`, a `Pure` newtype over `Photo` with mandatory original bucket key, normalized bucket key, and original file name, so partial photo metadata is unrepresentable. Reuse the organization-logo upload pipeline, but use the shared `Photo*` value newtypes rather than organization-owned logo newtypes. Insert/update contain no bytes; GET models already expose presigned URL fields.
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
| GET | `/get/catalogue-item/{catalogueItemID}` | `GetCatalogueItemGet` | full item |
| GET | `/get/catalogue-items` | `GetCatalogueItemsGet` | active items |

Errors:

- insert/update: `BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError`.
- archive/read: `BadRequest, Unauthorized, Forbidden, InternalServerError`; pure UUID cannot validation-fail and archive cannot create a name conflict.

Contract: `smithy/CatalogueService.smithy`, `smithy/domain/Catalogue.smithy`. Optional `price` is `CatalogueItemPriceRequest`; if present, both `amount` and `currency` are required. Request lists use `@default([])`; response lists use `@required`.

## Status

| Slice | Done | Remaining |
|---|---|---|
| Endpoints | six operations/models; codegen green | photo upload excluded/deferred |
| Validation | request models, shared ISO price validation, domain/Smithy arbitraries, validator, unit specs | — |
| Schema | enum, table, partial unique index, config | — |
| Repository | Row, Queries, Repository, config; real-Postgres lifecycle proof | — |
| Service | — | implementation/wiring, functional + acceptance specs |

Details:

- Shared price types: `PriceAmount` (`BigDecimal`), `PriceCurrency`, and `Price` with mandatory amount/currency. Shared photo types: `PhotoOriginalBucketKey`, `PhotoNormalizedBucketKey`, `PhotoOriginalFileName`, and `Photo` with all three members mandatory. Catalogue owns only the contextual `CatalogueItemPrice` (`Pure[Price]`) and `CatalogueItemPhoto` (`Pure[Photo]`) wrappers alongside `CatalogueItemID`, `CatalogueItemName`, and `CatalogueItemUnit`; the enum is in `CatalogueItemStatus.scala`.
- `PriceDomainValidator` owns the reusable ISO currency/amount invariant. `CatalogueRequestValidator` validates single insert, batch insert, and update; UUID-only archive remains outside the feature validator. Batch failures use the singular item field `catalogueItem` and preserve each invalid item's stable source index while omitting valid positions from the error list.
- Schema/config: `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable`, `allTableNames`, and `catalogue-item-table` in both configs.
- `CatalogueItemRow.price` spans the adjacent amount/currency columns and `CatalogueItemRow.photo` spans the three adjacent photo metadata columns through composite refined codecs.
- Validation normalizes ISO currency with trim plus uppercase, accepts amounts in `[0, 1,000,000,000,000)`, rejects values whose supplied scale exceeds the ISO currency's fixed fraction digits, then appends zeros to reach that exact scale without rounding. `XXX` and currencies with `fractionDigits = -1` are rejected.
- `CatalogueItemQueries`: single/batch insert, dynamic update + returning, archive + returning ID, get any status, list active, test getter. Preserve qualified native-enum write/read casts.
- Generic refined `Meta[BigDecimal]` provides exact codec; no bespoke codec.
- `CatalogueRepository`: companion `InsertCatalogueItemInput`, UUIDv7/time generation, named unique mapping. `priceOptUpdate` and `photoOptUpdate` flatten their optional composites across all corresponding columns. `None` means unchanged; clearing price or photo is not supported. New item photo metadata remains `None`.
- Do not add unused live layers: wire repository/queries with the service because ZIO rejects unused layers.

## Completed proof

`PriceDomainValidatorSpec` proves ISO normalization, canonical upward scale normalization, the exclusive monetary upper bound, compact non-zero extreme-exponent rejection, extreme-scale zero acceptance, fixed-fraction boundaries for zero-, two-, and three-digit currencies, pseudo/unsupported currency rejection, negative amounts, zero, and bounded non-zero negative scales. `CatalogueRequestValidatorSpec` proves success round-trips and exact accumulated errors for single insert, indexed batch insert, and update, including absent optional prices.

`CatalogueRepositorySpec` covers every repository method against real PostgreSQL: controlled IDs/timestamps and complete rows sampled from repository arbitraries; absent/present exact ISO-valid prices from the shared correlated generator; absent creation photo metadata and composite photo updates; tenant scope; active-name conflicts and exact error mapping; batch order, no-op, and rollback; update/no-op semantics; archive/name reuse; active-and-archived reads; and active-only unordered listing.

## Required remaining proof

Service completion adds `CatalogueService.scala`, routes, `Main` layers, `CatalogueServiceSpec`, and `CatalogueApiSpec` with the [acceptance matrix](flow/05-service.md#acceptance-tests-real-app-over-http).

Photo completion adds a Tapir streaming endpoint using `FileScanner`, `ImageProcessing`, and S3; updates the three photo columns; returns presigned URLs; and keeps entity limits synchronized.

## Open decisions

- Exact JSON for JS: keep numeric unless clients require string encoding.
- No unarchive; any future reactivation maps active-name collision to 409.
