# Catalogue

Tenant-scoped products/services sold by the organization.

## Scope

Owns `catalogue_item`: name, free-text unit, optional exact price/currency, optional photo metadata, and one-way archival. Excludes photo upload (future Tapir route reusing [Files Management](files-management.md)), orders/line items, and tenant membership/roles.

## Model and decisions

`catalogue_item`:

- PK `(organization_id, catalogue_item_id)`; all access is tenant-scoped.
- `name text not null`.
- `unit text not null`: open vocabulary (`piece`, `kg`, `hour`, bespoke services); rejected enum because new units must not require code/migrations.
- `price_amount numeric` + `price_currency text`: both null or both present. The application models them as optional `CatalogueItemPrice`, a `Pure` newtype over `Price` with mandatory amount/currency, so a half-price is unrepresentable. `numeric`/Scala `BigDecimal` is exact; never pass through `Double`. Free-text currency supports fiat and crypto. `bigint` minor units were rejected because 18-decimal crypto can overflow. Currency scale is a domain/display concern.
- Smithy encodes `BigDecimal` as a JSON number. If JS clients need exact high-scale crypto, change wire `amount` to `String` and parse in validation.
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
| Validation | newtypes; `CatalogueItemStatus` | request models, domain/Smithy arbitraries, validator, unit spec |
| Schema | enum, table, partial unique index, config | — |
| Repository | Row, Queries, Repository, config; real-Postgres lifecycle proof | — |
| Service | — | implementation/wiring, functional + acceptance specs |

Details:

- Shared price types: `PriceAmount` (`BigDecimal`), `PriceCurrency`, and `Price` with mandatory amount/currency. Shared photo types: `PhotoOriginalBucketKey`, `PhotoNormalizedBucketKey`, `PhotoOriginalFileName`, and `Photo` with all three members mandatory. Catalogue owns only the contextual `CatalogueItemPrice` (`Pure[Price]`) and `CatalogueItemPhoto` (`Pure[Photo]`) wrappers alongside `CatalogueItemID`, `CatalogueItemName`, and `CatalogueItemUnit`; the enum is in `CatalogueItemStatus.scala`.
- Schema/config: `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable`, `allTableNames`, and `catalogue-item-table` in both configs.
- `CatalogueItemRow.price` spans the adjacent amount/currency columns and `CatalogueItemRow.photo` spans the three adjacent photo metadata columns through composite refined codecs.
- `CatalogueItemQueries`: single/batch insert, dynamic update + returning, archive + returning ID, get any status, list active, test getter. Preserve qualified native-enum write/read casts.
- Generic refined `Meta[BigDecimal]` provides exact codec; no bespoke codec.
- `CatalogueRepository`: companion `InsertCatalogueItemInput`, UUIDv7/time generation, named unique mapping. `priceOptUpdate` and `photoOptUpdate` flatten their optional composites across all corresponding columns. `None` means unchanged; clearing price or photo is not supported. New item photo metadata remains `None`.
- Do not add unused live layers: wire repository/queries with the service because ZIO rejects unused layers.

## Required remaining proof

`CatalogueRepositorySpec` covers every repository method against real PostgreSQL: controlled IDs/timestamps and complete rows sampled from repository arbitraries; absent/present exact `BigDecimal` prices from the shared monetary generator; absent creation photo metadata and composite photo updates; tenant scope; active-name conflicts and exact error mapping; batch order, no-op, and rollback; update/no-op semantics; archive/name reuse; active-and-archived reads; and active-only unordered listing.

Service completion adds `CatalogueService.scala`, routes, `Main` layers, `CatalogueServiceSpec`, and `CatalogueApiSpec` with the [acceptance matrix](flow/05-service.md#acceptance-tests-real-app-over-http).

Photo completion adds a Tapir streaming endpoint using `FileScanner`, `ImageProcessing`, and S3; updates the three photo columns; returns presigned URLs; and keeps entity limits synchronized.

## Open decisions

- Exact JSON for JS: keep numeric unless clients require string encoding.
- Currency scale/validation: decide in domain code; crypto makes ISO-only rules insufficient.
- No unarchive; any future reactivation maps active-name collision to 409.
