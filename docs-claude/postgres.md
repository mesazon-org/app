# PostgreSQL — schema & persistence standards

Persistence is PostgreSQL accessed from Scala through **Doobie** (`org.typelevel.doobie`) wrapped in **Tranzactio** (`io.github.gaelrenoux.tranzactio`) for ZIO integration. Schema is owned by **Flyway** SQL migrations; there is no ORM and no schema generation from code — the SQL DDL and the Scala row/query code are written and kept in sync by hand.

Two halves, kept in lockstep:

- **Schema** (`backend/schemas/`) — Flyway migrations that create tables, plus the local-dev bootstrap. This is the source of truth for table/column names.
- **Persistence code** (`backend/gateway/core/.../repository/`) — the three-layer `Row` → `Queries` → `Repository` stack that reads/writes those tables, wired by config that carries every table name.

The names on both sides must match exactly (a `Queries` class references columns by literal string), so a rename is a change in the migration **and** the row/queries/config — the same discipline as the [Rename rule](../CLAUDE.md) for docs.

## Schema module layout (`backend/schemas/`)

| Path | Role |
| --- | --- |
| `migrations/V<version>__<name>.sql` | Flyway migrations — the deployed schema. Applied in version order. |
| `local/postgres/init.sql` | Local-dev bootstrap: creates the schema and the `flyway` / `local_user` / `local_test_user` roles and their grants. **Not** a migration — runs once when the local Postgres container is first created. |
| `local/flyway/flyway.config` | Local Flyway config (URL, user, `validateMigrationNaming=true`). |
| `Dockerfile` | `flyway/flyway` image; `COPY ./migrations /flyway/sql`; `CMD ["clean", "migrate"]`. |

- Deployment is a one-shot job: `terraform/dev/gateway-flyway/` runs this image as an `app-job`, pointed at `/flyway/sql` via `FLYWAY_LOCATIONS`, with `FLYWAY_SCHEMAS` = the gateway schema. `clean`/`clean-on-validation-error` are enabled **only in the `dev` environment** — never rely on clean elsewhere.
- New roles/grants a feature needs at the database level go in `local/postgres/init.sql` for local, and are provisioned separately for deployed environments — a migration must not assume a role exists that only `init.sql` created.

### Migration files

- Naming: `V<version>__<description>.sql`, e.g. `V2025.05.27__init.sql`. Version is a **date** (`YYYY.MM.DD`, dot-separated); description is `snake_case`. `validateMigrationNaming=true`, so a malformed name fails the build.
- One migration per change set, **append-only**: never edit a migration that has been applied to a deployed environment — add a new `V<later-date>__<change>.sql`. (Everything today lives in the single `init` migration because nothing has shipped on top of it yet.)
- Two same-day migrations need distinguishable versions (e.g. add a suffix like `V2026.07.09.1__...`); Flyway orders lexically.
- Keep DDL in the same lowercase-keyword style as `V2025.05.27__init.sql` (`create table`, `not null`, `primary key`, `timestamptz`).

## Table & column naming

### 1. Table names

- `snake_case`, **singular**, `{{ owner }}_{{ entity }}` — the owner prefix groups a feature's tables and matches the Scala `{{ Owner }} {{ Entity }}` convention (see [scala.md](scala.md)).
- Child / join tables keep the owner prefix: `organization_user`, `waha_user_activity`, `waha_user_message`.
- ✅ `user_details`, `user_credentials`, `organization_details`, `organization_user`, `waha_user_message`
- ❌ `users`, `user` (no entity), `customers_details` (plural owner), `OrganizationDetails` (not snake_case), `customer_book` (feature name, not owner+entity)

### 2. Column names

- `snake_case`. Multi-word logical fields spell out fully: `phone_national_number`, `logo_original_bucket_key`, `address_line_1`.
- Primary-key / identifier columns are `{{ entity }}_id` and typed `uuid`: `user_id`, `organization_id`, `otp_id`, `token_id`, `action_attempt_id`. IDs are **generated in the application** (UUIDv7 via `IDGenerator`), never a DB `default`.
- ✅ `organization_id`, `phone_number_e164`, `address_line_2`
- ❌ `organizationId`, `id` (unqualified), `phoneNumberE164`

### 3. Column types

- `uuid` — identifiers.
- `text` — **all** strings. No `varchar(n)`; length/format is enforced by Iron refined types in the domain layer, not the database.
- `timestamptz` — all timestamps (`created_at`, `updated_at`, `expires_at`, `last_update`). Never `timestamp` (without zone).
- `boolean`, `int` — as needed.
- Enums are stored as `text`, **not** native PG `enum` types — the value is the Scala enum case name, mapped by `Put/Get.deriveEnumString` (see §Persistence code). Columns: `onboard_stage`, `organization_stage`, `user_role`, `otp_type`, `token_type`, `action_attempt_type`.
- Phone numbers are **four columns**, never one: `phone_region`, `phone_country_code`, `phone_national_number`, `phone_number_e164` (mirrors the `PhoneNumber` domain type).

### 4. Nullability

- Columns are `not null` by default. Omit `not null` **only** for fields modelled as `Option[...]` in the `Row` case class (e.g. `full_name text`, `address_line_2 text`, `logo_original_bucket_key text`).
- The presence/absence of `not null` in the migration must match `Option` vs. non-`Option` in the row — a mismatch is a runtime decode failure, not a compile error.

### 5. Audit columns

- Almost every table carries `created_at timestamptz not null` and `updated_at timestamptz not null`, written last in column order and mapped to `CreatedAt` / `UpdatedAt`. Both are set by the repository from `TimeProvider.instantNow` (`updated_at` refreshed on every mutation), never by a DB trigger or `default now()`.
- Tables with expiry or activity semantics add `expires_at` / `last_update` as needed (e.g. `user_otp`, `user_token`, `waha_user_activity`).

### 6. Keys, constraints, indexes

- Primary key declared inline at the end of the table: `primary key (organization_id)`; composite for join tables: `primary key (organization_id, user_id)`.
- Uniqueness via `unique (...)`: single (`unique (email)`, `unique (slug)`) or composite (`unique (user_id, action_attempt_type)`).
- Foreign keys reference the parent explicitly: `foreign key (user_id) references waha_user (user_id)`.
- Indexes: `create index idx_{{ table }}_{{ purpose }} on {{ table }} [using hash] (...)`. Name starts with `idx_` + table name. Use `using hash` for single-column equality lookups (`idx_user_token_user_id ... using hash (user_id)`); a plain b-tree (with column order / `DESC`) for range/ordering (`idx_waha_user_message_order on waha_user_message (user_id, created_at desc)`).
- ✅ `idx_organization_user_user_id`, `foreign key (organization_id, customer_id) references customer (organization_id, customer_id)`
- ❌ `organization_user_idx`, an FK with no index on the join column when you query by it

## Soft-delete & archival

Some entities own **immutable historical children** — most importantly *orders*, which are financial/audit records. Deleting a parent must never destroy that history. The rule:

- **Entities that can accumulate orders (or similar historical records) are soft-deleted, never hard-deleted.** They carry a `status text not null` lifecycle column mapped to an enum (`ACTIVE` | `ARCHIVED`) — set by the repository on insert (`ACTIVE`), no DB `default`, following the enum-as-text convention. "Deleting" flips `status` to `ARCHIVED`; the row stays, so every child FK keeps resolving and the archive is reversible. Reads filter `status = 'ACTIVE'` by default. Applied to the `customer` parent (its `INDIVIDUAL`/`BUSINESS` detail rows inherit the status). Pure child rows that carry no orders — e.g. `customer_business_contact` — are hard-deleted instead.
- **Historical records are append-only and immutable.** An order is never `DELETE`d; a cancellation/refund is a `status` transition on the order (`CANCELLED`, `REFUNDED`), not a row removal.
- **Snapshot the referenced entity onto the historical record.** An order stores the buyer's details as its own `buyer_*` columns captured at order time, *in addition to* the FK — because the parent's live details (name, address) can change or be archived afterwards, and a historical order must reflect what it was **when it happened**. See `customer_order` (`buyer_name`, `buyer_email`, `buyer_address_line_1`, …).
- **FKs from history to parents use `on delete restrict`, never `cascade`.** With soft-delete the parent is never hard-deleted so the restrict never fires — it's a belt-and-suspenders guard that turns an accidental hard `DELETE` into a DB error instead of lost history.
- **Erasure (GDPR "right to be forgotten") anonymizes, it does not delete.** To honour erasure of a person's data, blank the identifying `buyer_*` snapshot fields on the order (name/email/phone), keep the amounts/dates and the row. Financial-retention obligations generally outrank erasure, and anonymizing satisfies both.
- ✅ `status = 'ARCHIVED'` on `customer`; `customer_order` keeps `buyer_*` snapshot + `on delete restrict` FKs
- ❌ `DELETE FROM customer ...` where orders exist, `on delete cascade` into orders, an order that only FKs the parent with no snapshot

## Customer type — individual vs business

Orders always target a `customer_id`. A **customer** (`customer`) is the single order target and carries a `customer_type text not null` discriminator (enum-as-text, same convention as `status`) that decides which 1:1 detail table holds the rest of its data:

- `INDIVIDUAL` — a standalone person; details live in `customer_individual_details` (`full_name`, contact, address).
- `BUSINESS` — a named B2B account; details live in `customer_business_details` (`business_name`, `tax_id`, contact, address), and it may own any number of `customer_business_contact` rows.

The two detail tables are keyed on `(organization_id, customer_id)` and FK the parent on that composite, so a detail row can only ever attach to a customer in the same tenant. A **business contact** is a person *inside* a company (`customer_business_contact_id`, `full_name`, `role`) — **not** a customer: it has no `customer_id` of its own, is never an order target, and carries no `status`. `customer_business_contact` FKs `customer_business_details (organization_id, customer_id)` — **not** `customer` — so a contact can only hang off a customer that actually has a business-details row (i.e. a `BUSINESS`); attaching one to an `INDIVIDUAL` fails at the DB. Within an organization, `business_name` (on `customer_business_details`) and `full_name` (on `customer_individual_details`) are each `unique (organization_id, <name>)` — no two businesses or two individuals in the same tenant may share a name (names may of course repeat across tenants). See [Customer Book](features/customer-book.md) for the full lifecycle.

Consequences:

- **`customer_type` is the single source of truth for the kind** — which detail table applies is derived from it, never duplicated as a boolean flag.
- **Type↔detail-table correspondence is enforced at the application layer**, not in the DB — consistent with the rest of this schema staying permissive at the DB (no `check`, no trigger, no discriminated FK). The service writes `customer` + the *matching* detail table in one transaction and never the other; the invariant (`BUSINESS` ⇒ a `customer_business_details` row, `INDIVIDUAL` ⇒ a `customer_individual_details` row, and never both) is guarded by an integration test asserting no `customer_id` ever appears in both detail tables. The composite-FK alternative (a constant `generated` `customer_type` on each child FK'd to a type-carrying unique key on `customer`) was considered and deliberately **not** adopted, to keep the DB permissive. (The narrower "contacts only under a business" half of this *is* DB-enforced, for free, by pointing the contact FK at `customer_business_details`.)
- **Contacts belong to their business and share its fate** — archiving a business leaves its contacts in place; removing a contact is a plain row delete (it carries no orders).
- **Names are unique per tenant** — `unique (organization_id, business_name)` and `unique (organization_id, full_name)` on the respective detail tables; scoped by `organization_id`, so uniqueness is per-organization, never global.
- ✅ `customer_type text not null` on `customer`; detail tables FK `(organization_id, customer_id)` → `customer`; `customer_business_contact` FK → `customer_business_details`; per-tenant unique names
- ❌ an `is_individual boolean` (forces a migration + backfill to add a third kind), modelling business contacts as `customer` rows, a shared detail table for both types, a globally-unique name with no `organization_id` in the key

## Persistence code — the three layers

For an entity `Foo` backed by table `foo_bar`, the code is three files, all under `backend/gateway/core/src/main/scala/io/mesazon/gateway/repository/`:

### 1. `repository/domain/FooBarRow.scala` — the row model

- `case class FooBarRow(...)` whose fields are Iron refined types / enums / `CreatedAt` / `UpdatedAt`, in **the same order as the column list** in the queries fragment (whole-row `Write` is positional).
- Named `{{ Entity }}Row`; parameters follow [scala.md §Repository domain model parameters](scala.md) (owner prefix droppable, `Option[...]` for nullable columns).

### 2. `repository/queries/FooBarQueries.scala` — the SQL

- `final class FooBarQueries(config: RepositoryConfig)` with `object FooBarQueries { val live = ZLayer.derive[FooBarQueries] }`.
- Resolve the table once from config, never hard-code the name:
  ```scala
  private val frSchema   = Fragment.const(config.schema)
  private val frFooBar   = Fragment.const(config.fooBarTable)
  private val frTable    = frSchema ++ fr0"." ++ frFooBar
  ```
- Keep one `frFooBarFields` fragment (the column list) reused by every SELECT / INSERT / `RETURNING` — this is the single place column order is defined, and it must match the row case class.
- Methods return `TranzactIO[...]` and wrap the SQL in `tzio { ... }`. Build SQL with `fr"..."` interpolation and the doobie fragment helpers `whereAnd`, `set`, `orderBy`, and `NonEmptyList` for update sets. Insert a whole row with `fr"$fooBarRow"`. Upserts use `ON CONFLICT (...) DO UPDATE/NOTHING`; updates that return the new state end with `RETURNING ++ frFooBarFields`.
- Method names are bare operation verbs (`get`, `insert`, `update`, `delete`, `upsert`, `is...`) — no entity name (the class already carries it), plural for multi-row, `...Testing` suffix for test-only helpers. See [scala.md §Repository query class](scala.md).

### 3. `repository/FooRepository.scala` — the ZIO boundary

- `trait FooRepository` + private `FooRepositoryImpl` taking `database: DatabaseOps.ServiceOps[Transactor[Task]]`, the `Queries`, `TimeProvider`, `IDGenerator`.
- Each method runs its queries inside `database.transactionOrWiden(...)` (compose multiple queries in one `for`-comprehension to get a single transaction) and maps failures to `ServiceError.InternalServerError.RepositoryError(s"...", e)`.
- Generate IDs with `idGenerator.generateID` (UUIDv7) and timestamps with `timeProvider.instantNow` **in the repository**, not the queries or the DB.
- `val live = ZLayer.derive[FooRepositoryImpl].project[FooRepository](identity)`.
- Named `{{ Feature/Entity }}Repository`; methods carry the entity name (`getFooByUserID`, `insertFoo`). See [scala.md §Repository](scala.md).

### Type mappings (`repository/queries/queries.scala`)

Given instances derive Doobie `Read`/`Write`/`Get`/`Put`/`Meta` for **all** Iron refined types (via `RefinedType.Mirror`) and Scala `enum`s (`Get/Put.deriveEnumString` → the case name as `text`). A new refined type or enum needs no per-type codec — but a refined type over a non-standard base still needs a `Meta` for that base.

## Configuration — table names are config, not constants

Table names are **never** string literals in queries; they flow through config so every environment/test can point at the same schema:

- `backend/gateway/core/.../config/RepositoryConfig.scala` — `case class RepositoryConfig(schema, <entity>Table = "", ...)`. Add a field per new table and include it in `allTableNames`.
- `backend/gateway/core/src/main/resources/application.conf` under `repository { ... }` — set `schema` and every `<entity>-table = "foo_bar"` (kebab-case key ↔ camelCase field).
- `backend/gateway/it/src/test/resources/application.conf` under `repository { ... }` — the **integration tests carry their own copy**; add the same `<entity>-table` line here too or the row won't resolve in acceptance tests.

So adding a table touches, at minimum: the migration, `RepositoryConfig`, both `application.conf`s, and the three code layers.

## Testing

- `backend/postgresql-test` provides `PostgreSQLTestClient` (testcontainers docker-compose): `checkIfTableExists(schema, table)`, `truncateTable(schema, table)` (used between tests via the `TRUNCATE`-granted `local_test_user`), and `executeQuery`.
- Query classes expose `...Testing` helpers (e.g. `getAllTesting`) for assertions — see [scala.md §Repository query class methods for testing](scala.md).
- Feature behaviour is proven by acceptance tests in `backend/gateway/it` — see [acceptance-tests.md](acceptance-tests.md).

## Adding a table — checklist

1. Add/extend a Flyway migration in `backend/schemas/migrations/` (new dated `V...__*.sql` once `init` has shipped; append to `init` only pre-release).
2. `RepositoryConfig`: new `<entity>Table` field + add to `allTableNames`.
3. Both `application.conf`s (`core` main + `it` test): `<entity>-table = "..."`.
4. `repository/domain/<Entity>Row.scala` — row model (field order = column order).
5. `repository/queries/<Entity>Queries.scala` — fields fragment + methods + `live`.
6. `repository/<Feature>Repository.scala` — trait/impl + error mapping + `live`; wire the `live` layers into the app's layer graph.
7. Any new DB role/grant → `local/postgres/init.sql` (+ deployed-env provisioning).

## Adding a column to an existing table — checklist

Same layering as adding a table, but the trap is different: **optional parameters with `= None` defaults make missed call sites compile silently**, so nothing forces you through the layers — walk them deliberately.

1. Migration: add the column (append to `init` only pre-release; dated `V...__*.sql` once shipped).
2. `<Entity>Row.scala`: add the field **at the column's position** (row field order = column order in the fields fragment).
3. `<Entity>Queries.scala`: add the column to the fields fragment **and**, if the table has an `update`, a `<field>OptUpdate` parameter + `SET` fragment.
4. Repository trait/impl: thread the field through `create...` and `update...` signatures.
5. Service / validator / smithy / domain model, if the column is client-facing.
6. **Tests — don't rely on defaults compiling.** Update the repository integration spec's *update* test to pass an `arbitrarySample[Option[...]]` for the new `<field>OptUpdate` and assert it (`.orElse` for nullable columns, `.getOrElse` for non-null). If you skip this, the new `SET` fragment is dead code as far as the suite is concerned — this was missed for `company_registration_number`/`tax_id` and only caught in review. Mock-based `expects(...)` call sites (scalamock) *will* fail to compile — fix those by adding the new argument, not by widening matchers.
