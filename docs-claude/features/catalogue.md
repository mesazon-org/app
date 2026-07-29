# Catalogue

Tenant-scoped products/services sold by the organization.

## Scope

Owns `catalogue_item`: name, free-text unit, optional exact price/currency, optional photo metadata, and one-way archival. Excludes photo upload (future Tapir route reusing [Files Management](files-management.md)), orders/line items, and tenant membership/roles.

## Model and decisions

`catalogue_item`:

- PK `(organization_id, catalogue_item_id)`; all access is tenant-scoped.
- `name text not null`.
- `unit text not null`: open vocabulary (`piece`, `kg`, `hour`, bespoke services); rejected enum because new units must not require code/migrations.
- `price_amount numeric` + `price_currency text`: both null or both present. `numeric`/Scala `BigDecimal` is exact; never pass through `Double`. Free-text currency supports fiat and crypto. `bigint` minor units were rejected because 18-decimal crypto can overflow. Currency scale is a domain/display concern.
- Smithy encodes `BigDecimal` as a JSON number. If JS clients need exact high-scale crypto, change wire `amount` to `String` and parse in validation.
- Photo columns: optional `photo_original_bucket_key`, `photo_normalized_bucket_key`, `photo_original_file_name`; reuse the organization-logo pipeline. Insert/update contain no bytes; GET models already expose presigned URL fields.
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
| Repository | Row, Queries, Repository, config | real-Postgres spec |
| Service | — | implementation/wiring, functional + acceptance specs |

Details:

- Newtypes: `CatalogueItemID`, `CatalogueItemName`, `CatalogueItemUnit`, `CatalogueItemPriceAmount` (`BigDecimal`), `CatalogueItemPriceCurrency`; enum in `CatalogueItemStatus.scala`.
- Schema/config: `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable`, `allTableNames`, and `catalogue-item-table` in both configs.
- `CatalogueItemRow` follows table order. Photo fields temporarily reuse `OrganizationLogo*` newtypes; introduce `CataloguePhoto*` when upload lands.
- `CatalogueItemQueries`: single/batch insert, dynamic update + returning, archive + returning ID, get any status, list active, test getter. Preserve qualified native-enum write/read casts.
- Generic refined `Meta[BigDecimal]` provides exact codec; no bespoke codec.
- `CatalogueRepository`: companion `InsertCatalogueItemInput`, UUIDv7/time generation, named unique mapping. `None` price fields mean unchanged; clearing price is not supported. Photo fields remain `None`.
- Do not add unused live layers: wire repository/queries with the service because ZIO rejects unused layers.

## Required remaining proof

`CatalogueRepositorySpec` must cover every method, `uq_catalogue_item_name`, batch rollback, archive/name-reuse semantics, and high-scale `BigDecimal` round-trip. Repository slice remains incomplete until green.

Service completion adds `CatalogueService.scala`, routes, `Main` layers, `CatalogueServiceSpec`, and `CatalogueApiSpec` with the [acceptance matrix](flow/05-service.md#acceptance-tests-real-app-over-http).

Photo completion adds a Tapir streaming endpoint using `FileScanner`, `ImageProcessing`, and S3; updates the three photo columns; returns presigned URLs; and keeps entity limits synchronized.

## Open decisions

- Exact JSON for JS: keep numeric unless clients require string encoding.
- Currency scale/validation: decide in domain code; crypto makes ISO-only rules insufficient.
- No unarchive; any future reactivation maps active-name collision to 409.
