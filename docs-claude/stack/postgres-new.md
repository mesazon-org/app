# PostgreSQL schema & persistence standards

PostgreSQL is the system of record for a project's structured application data. This document defines **project-agnostic standards for a PostgreSQL schema**: how to lay out a migrations module, how to name and type tables/columns, how to declare keys/constraints/indexes so violations can be mapped to clear errors, and the recurring patterns for soft-delete, historical/audit records, and discriminated subtypes. The rules hold regardless of which migration tool, host language, or persistence library a project pairs with Postgres — schema design and code architecture are two different concerns kept deliberately separate here.

For example, the illustrations throughout this document are drawn from a real codebase where the schema is applied by **Flyway** SQL migrations and read through a hand-written Scala repository layer over **Doobie** (`org.typelevel.doobie`) wrapped in **Tranzactio** (`io.github.gaelrenoux.tranzactio`) for ZIO — no ORM, no schema generation from code. The naming and DDL rules below apply just as well to a project using a different migration tool or a different language/library on the code side.

## Scope

This document owns the **project-agnostic schema/DDL and naming standard**: how tables, columns, keys, constraints, and indexes are named and typed; how migration files are organized and versioned; and the recurring modeling patterns (soft-delete/archival, historical snapshots, discriminated subtypes) that keep a schema correct and evolvable over time. It also owns the boundary rule that schema identifiers (schema/table names) should be resolved through configuration rather than hard-coded in code, since that is a property of the schema's contract with its callers.

It does **not** own:

- **the repository/Row/Queries code architecture** — the three-layer stack that reads and writes the schema (layer responsibilities, transactions, id/timestamp generation, error-mapping, wiring, and its own testing harness) → [`../repository-new.md`](../repository-new.md);
- **the naming of refined/newtype values** that end up as row fields (e.g. an identifier type or a validated string type) → [`iron-new.md`](iron-new.md);
- **general Scala/host-language naming and style conventions** → [`scala-new.md`](scala-new.md).

A rule here may reference one of those documents for how a schema-level concept (a column, a constraint violation, a table rename) is consumed on the code side, but the code-side rule itself is defined there, not duplicated here.

## Table of contents

- [Scope](#scope)
- [Schema module layout](#schema-module-layout)
- [Migration files](#migration-files)
- [Table & column naming](#table--column-naming)
  - [Table names](#table-names)
  - [Column names](#column-names)
  - [Column types](#column-types)
  - [Nullability](#nullability)
  - [Audit columns](#audit-columns)
  - [Keys, constraints, and indexes](#keys-constraints-and-indexes)
- [Constraint naming & conflict mapping](#constraint-naming--conflict-mapping)
- [Soft-delete & archival](#soft-delete--archival)
- [Discriminated subtypes on one table](#discriminated-subtypes-on-one-table)
- [Keeping the schema and persistence code in sync](#keeping-the-schema-and-persistence-code-in-sync)
  - [Row order and the single column-list fragment](#row-order-and-the-single-column-list-fragment)
  - [Type mappings](#type-mappings)
- [Configuration — schema and table identifiers are config, not literals](#configuration--schema-and-table-identifiers-are-config-not-literals)
- [Testing](#testing)
- [Adding a table — checklist](#adding-a-table--checklist)
- [Adding a column to an existing table — checklist](#adding-a-column-to-an-existing-table--checklist)

## Schema module layout

Keep the schema in its own module, separate from application code, with a small number of well-defined artifacts:

| Role | What it is |
| --- | --- |
| Migrations | `migrations/V<version>__<name>.sql` — the versioned migration files that define the deployed schema, applied strictly in version order. |
| Local-dev bootstrap | A one-shot script (not a migration) that creates the schema itself plus whatever local database roles/grants development and test tooling need. It runs once, when the local database container/instance is first created — it must never be treated as a migration and must never be assumed to have run in a deployed environment. |
| Local migration-tool config | The migration tool's local connection settings, plus any validation switches (e.g. strict filename validation) that make a malformed migration fail the build early rather than at deploy time. |
| Deployment packaging | Whatever packages the migrations for a real environment — commonly a container image built from the migrations directory, run as a one-shot job against the target database. |

- Deployment of the schema is typically a one-shot job pointed at the migrations directory, scoped to the project's schema. Any "wipe and rebuild" migration-tool feature (a `clean` command or equivalent) must be restricted to disposable/dev environments only — never enabled where it could run against a deployed environment with real data.
- New database-level roles/grants that a feature needs go in the local bootstrap step for local development, and are provisioned separately (outside the migration files) for deployed environments — a migration must never assume a role exists that only the local bootstrap script created.

For example, in the illustrative codebase this is `backend/schemas/`: `migrations/V<version>__<name>.sql` migrations; `local/postgres/init.sql` as the local-dev bootstrap (creates the schema and the `flyway` / `local_user` / `local_test_user` roles and their grants); `local/flyway/flyway.config` as the local Flyway config (`validateMigrationNaming=true`); and a `Dockerfile` (`flyway/flyway` image, `COPY ./migrations /flyway/sql`, `CMD ["clean", "migrate"]`) as deployment packaging. Deployment there is a `terraform/dev/gateway-flyway/` one-shot `app-job` running that image, pointed at `/flyway/sql` via `FLYWAY_LOCATIONS`, with `FLYWAY_SCHEMAS` set to the project's schema — `clean` / `clean-on-validation-error` are enabled **only** in the `dev` environment, never elsewhere.

## Migration files

- Name each migration `V<version>__<description>.sql`, where the version segment is chosen so migrations sort into the order they must apply. A date-based version (`YYYY.MM.DD`, dot-separated) is a common choice; description is `snake_case`. Where the migration tool supports it, turn on strict filename validation so a malformed name fails the build rather than silently mis-ordering (e.g. Flyway's `validateMigrationNaming=true`).
- One migration per change set, and migrations are **append-only**: never edit a migration that has already been applied to a deployed environment — add a new, later-versioned migration instead. (In the illustrative codebase, everything currently lives in a single `init` migration because nothing has shipped on top of it yet; see the checklists below for the pre-/post-release distinction.)
- Two migrations authored on the same day still need distinguishable, order-preserving versions (e.g. append a suffix like `.1`, `.2` to a date-based version) — most tools order versions lexically/numerically, not by wall-clock authoring time.
- Keep the DDL style consistent across every migration in the project (e.g. lowercase keywords throughout: `create table`, `not null`, `primary key`, `timestamptz`) so the migration history reads as one voice rather than one style per author.

## Table & column naming

### Table names

- `snake_case`, **singular**, `{{ owner }}_{{ entity }}` — an owner prefix groups a feature's tables together and should mirror whatever grouping convention the host language uses for the equivalent domain concept (see [scala-new.md](scala-new.md)).
- Child/join tables keep the owner prefix rather than dropping it.
- ✅ (illustration) `user_details`, `user_credentials`, `organization_details`, `organization_user`, `waha_user_message`
- ❌ `users`, `user` (no entity), `customers_details` (plural owner), `OrganizationDetails` (not snake_case), `customer_book` (a feature name, not an owner+entity pair)

### Column names

- `snake_case`. Spell multi-word logical fields out in full rather than abbreviating — e.g. `phone_national_number`, `logo_original_bucket_key`, `address_line_1`.
- Primary-key/identifier columns are named `{{ entity }}_id` and typed `uuid`. Generate identifiers in the application, not as a database `default` — this keeps id generation (and its algorithm/version, e.g. UUIDv7) a single, testable concern instead of splitting it between the database and the code. (Illustration: this codebase's `IDGenerator`.)
- ✅ `organization_id`, `phone_number_e164`, `address_line_2`
- ❌ `organizationId`, `id` (unqualified), `phoneNumberE164`

### Column types

- `uuid` — identifiers.
- `text` — **all** strings, with no `varchar(n)`. Length/format constraints belong to a refined type in the domain layer (see [iron-new.md](iron-new.md)), not to the database column.
- `timestamptz` — every timestamp column. Never plain `timestamp` (without a time zone).
- `boolean`, `int` — as needed for their domain meaning.
- Model an enum as a `text` column holding the enum case's name (mapped by an explicit `Put`/`Get`-style codec pair on the code side — see [Type mappings](#type-mappings)), rather than as a native Postgres `enum` type, unless a project has a specific reason to prefer the native type (native enums are harder to evolve — adding a value can require special-casing depending on the database version — which is why the text-plus-codec convention is the default here).
- A single logical value that is naturally **multi-part** is often better modeled as **several columns**, one per part, mirroring the domain type's own fields, rather than as one column. A **multi-valued** attribute (a list of small records) is often better modeled as a single `jsonb` column instead of a child table, when the list is small, always read/written as a unit, and does not need to be queried independently.
  - Illustration: an internationalized phone number is stored as **four** columns — `phone_region`, `phone_country_code`, `phone_national_number`, `phone_number_e164` — never one, mirroring a `PhoneNumber` domain type. A multi-valued contact point (a list of emails or phone numbers, each with an `isDefault` flag) is instead a single `jsonb` column (e.g. `emails`, `phone_numbers`), mapped via a `jsonb` codec — see [Type mappings](#type-mappings).

### Nullability

- Columns are `not null` by default. Omit `not null` **only** for a field modeled as an optional value in the code's row type (e.g. `full_name text`, `address_line_2 text`, `logo_original_bucket_key text`).
- The presence or absence of `not null` in the migration must match the optionality of the corresponding field on the code side exactly — a mismatch is a **runtime decode failure**, not a compile error, so it will not be caught by the type checker.

### Audit columns

- Give almost every table `created_at timestamptz not null` and `updated_at timestamptz not null`, written last in column order. Set both from the application's own clock/time source at write time (`updated_at` refreshed on every mutation) — never by a database trigger or a `default now()` — so the same time source that the rest of the application uses for business logic and testing also governs audit timestamps.
- Add `expires_at` / `last_update` (or equivalent) to tables with expiry or activity semantics, as needed.

### Keys, constraints, and indexes

- Declare the primary key inline at the end of the table: `primary key (<entity>_id)`; composite for join tables, e.g. `primary key (organization_id, user_id)`.
- Enforce uniqueness with `unique (...)` — single-column or composite. **Name a unique constraint whenever the code maps its violation to a distinct error** (see [Constraint naming & conflict mapping](#constraint-naming--conflict-mapping)); an anonymous `unique (...)` is fine only when a violation should surface as a generic failure.
- Declare foreign keys against the parent's key explicitly, e.g. `foreign key (user_id) references <parent_table> (user_id)`.
- Name indexes `idx_{{ table }}_{{ purpose }}`, optionally with a storage method, e.g. `create index idx_{{ table }}_{{ purpose }} on {{ table }} [using hash] (...)`. Use a hash index for single-column equality lookups and a plain b-tree (with explicit column order/`DESC`) for range or ordering queries.
- ✅ (illustration) `idx_organization_user_user_id`; `foreign key (organization_id, customer_id) references customer (organization_id, customer_id)`; `idx_user_token_user_id ... using hash (user_id)`; `idx_waha_user_message_order on waha_user_message (user_id, created_at desc)`
- ❌ `organization_user_idx` (index name doesn't start with `idx_`); a foreign-key column with no supporting index when it is queried on

## Constraint naming & conflict mapping

When a `unique` violation should surface to the caller as a **specific, distinguishable conflict** (rather than a generic failure), the code that catches the violation typically does so by matching on the **violated constraint's name** — so that constraint needs a **stable, explicit name**, never the database's autogenerated one. An autogenerated name is also commonly **truncated** at the database's identifier length limit (63 characters in Postgres), which can silently stop it matching what the code expects.

- Name such a constraint explicitly, e.g. `constraint uq_<table>_<distinguishing_columns> unique (...)`. The same applies to a **partial unique index** whose violation is caller-facing — name it explicitly too, e.g. `create unique index uq_<table>_<column> on <table> (...) where <partial-condition>`. Keep the name **stable**: code that matches on the literal constraint-name string will silently fall back to a generic error if the constraint is renamed without updating that match.
- The code typically reads the constraint name off the database driver's error object for the unique-violation SQL state and maps it to a specific error type/message. See [`../repository-new.md`](../repository-new.md) (the illustrative codebase's error-handling section: [repository.md § Error handling](../repository.md#error-handling)).
- A `unique` constraint whose violation is *not* meant to become a distinct caller-facing error can stay anonymous — it just surfaces as a generic internal/database error like any other failure.
- ✅ (illustration) `constraint uq_customer_business_contact_email unique (organization_id, customer_id, email)`; `create unique index uq_customer_name on customer (organization_id, customer_type, name) where status = 'Active'`
- ❌ an anonymous `unique (organization_id, customer_id, email)` that the code nonetheless tries to translate by name; matching on a truncated autogenerated name such as `customer_business_contact_organization_id_customer_id_phone_num`

## Soft-delete & archival

Some entities own **immutable historical children** — order/invoice-like records that are financial or audit records in their own right. Deleting a parent must never destroy that history. The general rule:

- **An entity that can accumulate historical/audit records (e.g. orders) is soft-deleted, never hard-deleted.** Give it a lifecycle column (e.g. `status text not null`, mapped to an `ACTIVE`/`ARCHIVED`-style enum) set by the code on insert with no DB `default`, following the enum-as-text convention above. "Deleting" the entity flips its status instead of removing the row, so every child foreign key keeps resolving and the archive stays reversible. Reads filter to the active status by default. A pure child row that carries no historical records of its own can still be hard-deleted.
- **Historical records themselves are append-only and immutable.** Never `DELETE` one; a cancellation/refund/reversal is a status transition on that record, not a row removal.
- **Snapshot the referenced entity's relevant fields onto the historical record**, in addition to its foreign key. The parent's live details can change or be archived after the fact, and a historical record must keep reflecting what things were **at the time it happened**.
- **Foreign keys from a historical record to its parent use `on delete restrict`, never `cascade`.** With soft-delete in place the parent should never be hard-deleted, so `restrict` should never actually fire in practice — it exists as a belt-and-suspenders guard that turns an accidental hard `DELETE` into a database error instead of silently losing history.
- **Erasure requests (e.g. GDPR "right to be forgotten") anonymize, they do not delete.** Blank the identifying snapshot fields on the historical record (name/email/phone, etc.) while keeping amounts, dates, and the row itself — financial/audit-retention obligations generally outrank erasure, and anonymizing typically satisfies both at once.
- ✅ (illustration, from this codebase's customer/order tables) `status = 'ARCHIVED'` on `customer` (soft-delete); `customer_order` keeps `buyer_*` snapshot columns captured at order time **and** `on delete restrict` foreign keys back to `customer`; `customer.status` is in fact stored as a native `customer_status` Postgres enum type rather than `text` — the one deliberate exception to the text-as-enum convention above, whose labels are the domain `CustomerStatus` case names
- ❌ `DELETE FROM customer ...` where associated orders exist; `on delete cascade` from an order into its parent; an order that only foreign-keys its parent with no snapshot of the parent's details

See the concrete end-to-end example in the codebase's [customer-book feature doc](../features/customer-book.md).

## Discriminated subtypes on one table

A recurring modeling question: an entity has a small number of mutually-exclusive subtypes that share the same identity and are targeted by the same downstream relationships (e.g. all subtypes can equally be the target of an order). The general rule is to model this as **one table with a discriminator column**, not as separate per-subtype detail tables:

- Give the table a discriminator column (`<entity>_type text not null`, enum-as-text) and let **all** subtypes' data live on that one table's columns — the subtypes differ only in *which* columns are populated for a given row, not in *which table* the row lives in.
- A **sub-record that belongs to one subtype but is not itself an instance of the discriminated entity** (e.g. a contact person that belongs to a business but is never itself an order target) is a separate child table, keyed by (tenant, parent-entity-id, own-id) and foreign-keyed to the parent scoped by tenant — so it can only ever attach to a parent in the same tenant. The foreign key alone does not need to enforce "only under this subtype"; that can be a service-layer convention (only ever created through that subtype's code paths) rather than a database rule.
- Scope any "unique per active row" rule by tenant and by subtype together, via a partial unique index that filters to the active status: `unique (<tenant_id>, <entity>_type, <distinguishing_column>) where status = 'Active'`.

Consequences of the single-table-plus-discriminator design:

- **The discriminator column is the single source of truth for which subtype a row is.** A row is inherently one subtype (one row, one discriminator value) and can never be "both" — reads/updates that are subtype-specific filter on the discriminator, so a wrong-subtype lookup simply returns "not found".
- This design is generally preferred over separate per-subtype detail tables because it removes a cross-table join on every read and removes the need for an application-level "never both subtypes" invariant that a two-table design would otherwise require.
- **A child record tied to one subtype shares that parent row's fate** — archiving the parent leaves its children in place; removing a child that carries no history of its own is a plain row delete.

Illustration (from this codebase — see the full lifecycle in the [customer-book feature doc](../features/customer-book.md)): a `customer` row always targets orders and carries `customer_type` (`INDIVIDUAL` | `BUSINESS`) as its discriminator; **all** customer data — individual or business — lives on that one `customer` table (for `INDIVIDUAL`, `name` is the person's full name and `tax_id` is null; for `BUSINESS`, `name` is the company name, `tax_id` may be set, and the row may own any number of `customer_business_contact` rows). A `customer_business_contact` is a person *inside* a company — not itself a customer, never an order target, no `status` of its own — keyed on `(organization_id, customer_id, customer_business_contact_id)` and foreign-keyed to `customer (organization_id, customer_id)`; "contacts only attach to a business" is enforced by only ever creating them through the business code paths, not by the foreign key itself. Within a tenant, an **active** customer's `(customer_type, name)` is unique via the partial index `uq_customer_name` (`unique (organization_id, customer_type, name) where status = 'Active'`), and a business's contacts are additionally unique on email and phone within that business via `uq_customer_business_contact_email` / `uq_customer_business_contact_phone_number`. This merged single-table model was chosen over an earlier separate-`INDIVIDUAL`/`BUSINESS`-detail-tables design specifically to remove the cross-table join and the "never both kinds" invariant.

- ✅ `customer_type text not null` and all customer columns on one `customer` table; the contact child table foreign-keyed by `(organization_id, customer_id)`; per-active-tenant-subtype uniqueness via a partial index
- ❌ an `is_individual boolean` (forces a migration + backfill to add a third subtype later); modeling a child record as a row of the discriminated entity itself; a globally-unique name with no tenant scoping in the key

## Keeping the schema and persistence code in sync

The schema and the persistence code that reads/writes it are two halves that must be kept in lockstep by hand whenever there is no schema-generation-from-code: **the names on both sides must match exactly**, so a rename is a change to a migration **and** to every place in the code that names that column/table literally.

> This section covers only the schema-sync obligations that a change to the schema imposes on the code. For the persistence layer as an architecture — the responsibilities of each code layer, input models vs. API request models, transactions, id/timestamp generation, error mapping, wiring, and testing — see [`../repository-new.md`](../repository-new.md).

### Row order and the single column-list fragment

- The code's row/record type for a table should list its fields in **the same order as the table's column list**, because a whole-row write is commonly positional (an ORM-free `Write`/serializer that writes fields by position, not by name). This makes field order in the row type load-bearing, not cosmetic — reordering a table's columns without reordering the row type's fields is a silent correctness bug, not a compile error.
- Keep **one** column-list fragment/constant per table, reused by every read/write/`RETURNING` operation against it — this is the single place column order is defined, and it is what the row type's field order must be checked against.

### Type mappings

- A codec layer should be able to derive `Read`/`Write`/`Get`/`Put`-style mappings for **every** refined type and enum in the domain automatically (e.g. via a generic derivation over the refined type's underlying representation, and an enum-to-`text` derivation keyed by case name) — so introducing a new refined type or enum needs **no new per-type codec**. A refined type over a non-standard base type is the one case that still needs its own base-level mapping.
- A `jsonb` column that maps to a list of small records needs an **explicitly named** codec pair (a serializer plus a database-object mapping) for that list's element type — anonymous/derived-name codecs of the same shape collide and silently shadow each other, so name each one explicitly. The element type used should be whatever type the row's own field uses for that column (commonly a persistence-owned "input" element model, not a request/transport model — see [`../repository-new.md`](../repository-new.md) for that distinction) — this keeps input → row field → `jsonb` codec speaking one type throughout, with no conversion needed at the boundary. Keep such a codec defined next to the row/queries code that owns the column, and make sure any refinement library's codec-integration import needed for the derivation macro to see the refined types' base codecs is present — omitting it can fail as an unhelpful macro error (e.g. a `StackOverflowError`) rather than a clear message.

## Configuration — schema and table identifiers are config, not literals

Table (and schema) names should never be string literals scattered through query code — they should flow through configuration, so every environment and every test can point the same code at differently-named physical objects if needed:

- Define one place that holds every table name the persistence code needs (e.g. a config case class with a field per table), and resolve the schema/table fragment from it at the point a query is built, rather than hard-coding an identifier in a SQL string.
- Keep the actual name/value for each table in the environment's configuration (e.g. an `application.conf`-style file), not in code.
- **Watch for configuration that is duplicated per test/deployment environment.** If integration or acceptance tests load their own copy of this configuration (rather than sharing the main service's), adding a table means updating **both** copies — forgetting the test copy means the row/table silently fails to resolve only in that test environment, which is easy to miss because the main application works fine.

Illustration: this codebase's `RepositoryConfig` (`schema`, `<entity>Table = ""` per table, collected into `allTableNames`) is populated from `repository { ... }` blocks in **two** separate `application.conf` files — the main service's and the integration-test module's own copy — so adding a table means touching the migration, `RepositoryConfig`, and *both* `application.conf` files, not just the main one.

## Testing

- Provide a shared test-database client/harness that any repository/integration test can use for schema-level setup and assertions — e.g. checking a table exists, truncating a table between tests, and running an arbitrary query directly against the schema.
- Have the code's query layer expose test-only helper methods (e.g. a "read everything back" helper) purely for use in assertions, distinctly named/suffixed so they're never mistaken for methods the application itself calls.
- The full recipe for a schema-backed repository/query integration test — the container/database stack, base test traits, mocking time and id generation, and what to assert — belongs to the persistence-architecture doc's testing section, not here: see [`../repository-new.md`](../repository-new.md) (illustrative codebase: [repository.md § Testing](../repository.md#testing--repository-integration-specs-with-testcontainers)).
- End-to-end feature behaviour built on the schema is proven by the project's acceptance-test layer, not here — see that project's acceptance-testing doc (illustrative codebase: [acceptance-tests.md](acceptance-tests.md)).

## Adding a table — checklist

1. Add (or, pre-release, extend) a migration for the new table.
2. Add the new table to wherever the code centralizes table names (the config described in [Configuration](#configuration--schema-and-table-identifiers-are-config-not-literals)).
3. Update every environment/test configuration copy that duplicates that table-name config, not just the main one.
4. Add the row/record model for the table, with field order matching column order.
5. Add the query layer for the table: its column-list fragment, its operations, and its wiring.
6. Add (or extend) the repository/boundary layer: error mapping and wiring into the application's dependency graph.
7. Add any new database role/grant the table needs to the local-dev bootstrap step, plus provisioning for deployed environments.

## Adding a column to an existing table — checklist

Same layering as adding a table, but the trap is different: **an optional parameter with a default value lets a missed call site keep compiling silently**, so nothing forces every layer to be touched — walk them deliberately.

1. Migration: add the column (append to a pre-release "init"-style migration only before anything has shipped; otherwise a new versioned migration).
2. Row/record model: add the field **at the column's position** — row field order must still equal column order in the column-list fragment.
3. Query layer: add the column to the column-list fragment and, if the table supports an update operation, add the corresponding optional-update parameter and its `SET`-clause fragment.
4. Repository/boundary layer: thread the new field through the relevant create/update method signatures.
5. Any layer further from the database that the column is relevant to (validation, transport contract, domain model), if the column is caller-facing.
6. **Tests — don't rely on defaults compiling.** Update the repository/integration test's *update* test to pass an explicit sampled value for the new optional-update parameter and assert on it. Skipping this leaves the new `SET` fragment untested dead code as far as the suite is concerned — in this codebase that was missed for two columns (`company_registration_number`/`tax_id`) and only caught in review. A test double with strict call-expectation matching (e.g. a mocking library's `expects(...)`) will fail to compile once the new argument is threaded through — fix that by adding the new argument to the expectation, not by loosening the matcher.
