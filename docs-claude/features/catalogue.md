# Catalogue

Owns the client's **catalogue of items they sell** — a per-organization list of products/services, each with a name, a unit of sale, an optional price, and an optional photo. One table (`catalogue_item`), org-scoped and soft-deleted, mirroring the [Customer Book](customer-book.md) shape.

**Vocabulary.** "Organization" is the **Mesazon tenant** (the client themselves — see [Organization Management](organization-management.md)), not a third party. Every row here is scoped by `organization_id`, carried in the `X-Organization-ID` header on every endpoint.

**Scope**: the `catalogue_item` table, its name/unit/price/photo fields, and archival (soft-delete via the `status` column). **Excludes**: the photo **upload transport** (a Tapir streaming endpoint reusing the [Files Management](files-management.md) pipeline — deferred, see [Status](#status)); orders/line-items that will later reference a `catalogue_item_id` and snapshot its details (a future feature — see the [historical-data rules](../standards/postgres.md#historical-data)); and the tenant/membership/role model ([Organization Management](organization-management.md)).

## Data model

```
catalogue_item (organization_id, catalogue_item_id)  PK
  name                          (required)
  unit                          (required; free text — 'piece', 'kg', 'hour', 'session', …)
  price_amount    numeric       (optional)  ┐ both null together, or both set
  price_currency  text          (optional)  ┘ 'EUR' | 'BTC' | 'ETH' | …  (free text, fiat + crypto)
  photo_original_bucket_key     (optional)  ┐
  photo_normalized_bucket_key   (optional)  ├ set by the (deferred) photo-upload endpoint
  photo_original_file_name      (optional)  ┘
  status         catalogue_item_status enum: Active | Archived
```

- **Org isolation via a composite PK** `(organization_id, catalogue_item_id)` — a caller can never reference another tenant's item. Same pattern as `customer`.
- **Soft-delete, never hard-delete.** `status` is a native PostgreSQL enum (`catalogue_item_status`, labels `Active`/`Archived` = the Scala `CatalogueItemStatus` case names verbatim), exactly like `customer.status`. Rationale: a catalogue item will be referenced by future order line-items, so it must never be physically deleted (see [historical-data rules](../standards/postgres.md#historical-data)). Archiving flips `status`; there is deliberately **no unarchive** (matching Customer Book).
- **`uq_catalogue_item_name`** — a **partial** unique index `unique (organization_id, name) where status = 'Active'`: an org can't hold two *active* items with the same name. Because it is partial on `status = 'Active'`, archiving frees the name, and same-name items may accumulate once archived. **Named** so the repository maps its `23505` violation to a specific `409` (see [constraint rules](../standards/postgres.md#keys-constraints-indexes)).

### Unit is free text (not an enum)

`unit` is a plain `text` column / `String` member, not a curated enum. A curated `CatalogueItemUnit` enum was considered and **rejected**: businesses sell in open-ended units (physical measures, packages, time, bespoke services), and an enum would force a code+migration change per new unit. The validation-layer domain newtype only enforces non-empty/trimmed.

### Price is exact decimal, fiat **and** crypto

`price` is stored as **`numeric`** (unqualified — exact, arbitrary precision) + a free-text `price_currency`, deliberately **not** integer minor-units-in-`bigint`. Reasons this shape was chosen:

- **Never a float.** `numeric` is exact; the whole stack maps to **`scala.BigDecimal`**, and **no code may ever route this value through a `Double`** — that is the one rule to carry through every layer.
- **Crypto-safe.** Integer minor-units in a `bigint` overflows for high-precision crypto — ETH has **18** decimals (1 ETH = 10¹⁸ wei) and signed `int64` maxes at ~9.2×10¹⁸, i.e. barely ~9 ETH. `numeric` has no such ceiling and represents ¥1000, €12.99, and 0.000000000000000001 ETH exactly.
- **Currency is free text**, so fiat (ISO 4217) and crypto codes share one column with no registry limit. The per-currency decimal-places question (which ISO 4217 doesn't answer for crypto) is a **display/validation** concern for the domain layer, not a stored column — `numeric` already holds the exact value.

This departs from the postgres.md "only uuid/text/timestamptz/boolean/int" numeric convention; money is the case that justifies `numeric`. Note the wire format: smithy4s encodes `BigDecimal` as a **JSON number** (exact on the wire, but a JS client parsing into a native `number`/double would lose precision on 18-decimal values). If a JS frontend must handle crypto amounts, revisit switching the smithy `amount` member to `String` (parsed to `BigDecimal` in the validator).

### Photo follows the organization-logo pattern

The photo is modelled as the three nullable bucket-key columns (`photo_original_bucket_key`, `photo_normalized_bucket_key`, `photo_original_file_name`) that `organization_details` uses for its logo — **not** a single key — so the item can reuse the [Files Management](files-management.md) streaming/scan/normalize/S3 pipeline. The insert/update JSON operations carry **no** photo bytes; the GET shapes already declare optional presigned-URL members (`photoOriginalUrl`/`photoNormalizedUrl` on the single GET, `photoNormalizedUrl` on the list). The actual `POST /upload/...` Tapir endpoint that populates these columns is **deferred** (see [Status](#status)).

## Endpoints

One service — `CatalogueService`, `@completedOnboardStage` (Bearer + completed onboarding). Every operation carries the `X-Organization-ID` header via the `OrganizationScopedInput` mixin (see [tenant scope](../standards/smithy.md#tenant-scope)) and an `@organizationUserRolesAllowed` trait following the [standard role policy](../standards/smithy.md#custom-traits): reads allow `OWNER`/`ADMIN`/`USER`, writes allow `OWNER`/`ADMIN`. URIs are verb-first (`/insert/...`, `/get/...`), no feature prefix.

| Method | Path | Operation | Roles | Effect / Returns |
|---|---|---|---|---|
| POST | `/insert/catalogue-item` | `InsertCatalogueItemPost` | OWNER, ADMIN | new active item |
| POST | `/insert/catalogue-items` | `InsertCatalogueItemsPost` | OWNER, ADMIN | batch of the above |
| PUT | `/update/catalogue-item` | `UpdateCatalogueItemPut` | OWNER, ADMIN | edit name/unit/price in place |
| PUT | `/archive/catalogue-item` | `ArchiveCatalogueItemPut` | OWNER, ADMIN | soft-delete (`status` → `Archived`) by `catalogueItemID` |
| GET | `/get/catalogue-item/{catalogueItemID}` | `GetCatalogueItemGet` | OWNER, ADMIN, USER | one item's full details |
| GET | `/get/catalogue-items` | `GetCatalogueItemsGet` | OWNER, ADMIN, USER | every active item |

**Errors** (ordered by status code, per [operation rules](../standards/smithy.md#operation)):

- Writes that validate a body and can collide on the name — inserts and `UpdateCatalogueItemPut` — declare `[BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]`.
- `ArchiveCatalogueItemPut` carries only a `catalogueItemID` (a `Pure` UUID that cannot fail refinement) and archiving only *removes* a row from the active-name set (never violates `uq_catalogue_item_name`), so — like `ArchiveCustomerPut` — it declares **no `ValidationError`** and **no `Conflict`**: `[BadRequest, Unauthorized, Forbidden, InternalServerError]`.
- The reads carry exactly the four middleware errors `[BadRequest, Unauthorized, Forbidden, InternalServerError]` (no body to validate, nothing to conflict).

Smithy: `smithy/CatalogueService.smithy` (+ `smithy/domain/Catalogue.smithy`). `price` is the optional `CatalogueItemPriceRequest { amount: BigDecimal, currency: String }` value shape (both members `@required` — the *whole price* is optional, but a present price needs both). Request list members are `@default([])` (see [[jsoniter-transient-empty-required-lists]]); response list members stay `@required`.

## Key files

- Smithy: `backend/gateway/core/src/main/smithy/CatalogueService.smithy`, `smithy/domain/Catalogue.smithy`; the `catalogue_item` value shapes live there. (No feature enum — `unit` is `String`; `status` is internal, not exposed in smithy.)
- Migration: `backend/schemas/migrations/V2025.05.27__init.sql` — the `catalogue_item_status` enum, the `catalogue_item` table, and the `uq_catalogue_item_name` partial unique index (appended to `init`; pre-release, nothing has shipped on top of it).

## Status

Tracks each [feature-flow](flow/README.md) slice; update in every PR per [AGENTS.md](../../AGENTS.md).

| Slice | Done | Remaining |
|---|---|---|
| Endpoints | Smithy service, six operations, request/response models; codegen green | Photo upload is excluded/deferred as a separate Tapir extension |
| Validation | Shared newtypes and `CatalogueItemStatus` exist | Request/entry models, arbitraries, validator, unit tests |
| Schema | Enum, table, partial unique index, config | — |
| Repository | Row, Queries, Repository implementation, config | Real-Postgres `CatalogueRepositorySpec`; slice is not complete until it passes |
| Service | — | Service implementation/wiring, functional and acceptance tests |

### Endpoints

- **Smithy contract** — `CatalogueService` with all six operations and every `domain/Catalogue.smithy` request/response shape; `smithy4sCodegen` is **green** (generates `CatalogueItemPriceRequest(amount: BigDecimal, currency: String)` and the six operations).

### Validation

- **Done:** `CatalogueItemID`/`CatalogueItemName`/`CatalogueItemUnit`/`CatalogueItemPriceAmount` (`BigDecimal`)/`CatalogueItemPriceCurrency` newtypes in `Newtypes.scala`; `CatalogueItemStatus` enum (`CatalogueItemStatus.scala`, labels `Active`/`Archived`).
- **Remaining:** entry/request case classes in `domain/gateway/Catalogue.scala`, `CatalogueDomainArbitraries`, `CatalogueSmithyArbitraries`, `CatalogueRequestValidator`, and validator unit spec.

### Schema

- **Done:** `catalogue_item_status` enum + `catalogue_item` table + `uq_catalogue_item_name` partial unique index in `V2025.05.27__init.sql`; `RepositoryConfig.catalogueItemTable` (+ `allTableNames`) and `catalogue-item-table = "catalogue_item"` in both `application.conf`s.

### Repository

- **`CatalogueItemRow`** (`repository/domain/CatalogueItemRow.scala`) — case class in table-column order. The three `photo_*` columns reuse the `OrganizationLogo*` bucket-key/file-name newtypes (structurally identical S3 key/filename strings; revisit with dedicated `CataloguePhoto*` types when photo upload lands).
- **`CatalogueItemQueries`** (`repository/queries/CatalogueItemQueries.scala`) — single/batch insert, partial `update` (dynamic `SET` + `RETURNING`), `archive` (status flip + `RETURNING id`), single get (any status), list active, and a testing getter. Native-enum casts (`::<schema>.catalogue_item_status` on writes, `status::text` on reads) mirror `CustomerBookQueries`. **No bespoke `numeric ↔ BigDecimal` `Meta`**: `CatalogueItemPriceAmount` is `RefinedType[BigDecimal, Pure]`, so the generic `RefinedType.Mirror` given in `queries.scala` lifts doobie's `Meta[BigDecimal]` — round-trips with no precision loss.
- **`CatalogueRepository`** (`repository/CatalogueRepository.scala`) — trait + private impl + `InsertCatalogueItemInput` companion model; ID via `IDGenerator` → `CatalogueItemID.either`, timestamps via `TimeProvider`; `23505` on `uq_catalogue_item_name` → `ConflictError.UniqueConstraintViolation`, else `RepositoryError`. Update treats `None` price fields as keep-existing (no clear-price — see [Open design decisions](#open-design-decisions)); photo fields hardcoded to `None` (upload deferred).
- **Remaining proof:** `CatalogueRepositorySpec` against real Postgres, covering `uq_catalogue_item_name`, batch rollback, archive semantics, and a precision-preserving `BigDecimal` round-trip. The repository slice remains incomplete until this passes.

### Service

- **Remaining:** `CatalogueService.scala`, `HttpApp.externalSmithyRoutes`, `CatalogueRepository.live` + `CatalogueItemQueries.live` in `Main` (deferred because ZIO rejects unused layers), `CatalogueServiceSpec`, and `CatalogueApiSpec` with the full middleware-gate matrix.
- **Photo upload** — a Tapir streaming endpoint reusing the [Files Management](files-management.md) pipeline (`FileScanner`/`ImageProcessing`/S3) to populate the three `photo_*` columns and surface presigned URLs in the GET responses; keep entity-size limits in sync (see files-management.md).

## Open design decisions

- **`BigDecimal` on the wire.** Encoded as a JSON number today; switch the smithy `amount` to `String` if a JS client must preserve 18-decimal crypto precision (see [Price](#price-is-exact-decimal-fiat-and-crypto)).
- **Per-currency decimal places / validation.** Not stored (a `numeric` holds the exact value). If display or input validation needs each currency's scale — which ISO 4217 doesn't define for crypto — decide in the domain layer whether to hardcode a table or pull in a money library (Joda-Money / JavaMoney, both needing custom-currency registration for crypto).
- **No unarchive** (matches Customer Book). Reactivation would need to handle the `uq_catalogue_item_name` collision as a `Conflict`.
