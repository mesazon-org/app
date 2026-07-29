# PostgreSQL schema & persistence standards

Project-agnostic standards for a PostgreSQL schema: migrations-module layout, table/column naming and typing, keys/constraints/indexes named so violations map to clear errors, and the recurring patterns for soft-delete, historical/audit records, and discriminated subtypes. Holds regardless of migration tool, host language, or persistence library — schema design and code architecture are different concerns.

Not owned here: the repository/Row/Queries code architecture — layer responsibilities, transactions, id/timestamp generation, error-mapping, wiring, testing harness ([repository-new.md](../repository-new.md)); the persistence library's own mechanics — fragment building, row/codec derivation, transactional effect wrapping, connection-pool wiring ([doobie-new.md](doobie-new.md)); refined/newtype naming ([iron-new.md](iron-new.md)); general Scala naming ([scala-new.md](scala-new.md)).

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to this schema. Concrete values for this codebase: [postgres-project.md](postgres-project.md).

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

## Migration files

- Name each migration `V<version>__<description>.sql`, where the version segment is chosen so migrations sort into the order they must apply. A date-based version (`YYYY.MM.DD`, dot-separated) is a common choice; description is `snake_case`. Where the migration tool supports it, turn on strict filename validation so a malformed name fails the build rather than silently mis-ordering.
- One migration per change set, and migrations are **append-only**: never edit a migration that has already been applied to a deployed environment — add a new, later-versioned migration instead.
- Two migrations authored on the same day still need distinguishable, order-preserving versions (e.g. append a suffix like `.1`, `.2` to a date-based version) — most tools order versions lexically/numerically, not by wall-clock authoring time.
- Keep the DDL style consistent across every migration in the project (e.g. lowercase keywords throughout: `create table`, `not null`, `primary key`, `timestamptz`) so the migration history reads as one voice rather than one style per author.

## Table & column naming

### Table names

- `snake_case`, **singular**, `{{ owner }}_{{ entity }}` — an owner prefix groups a feature's tables together and should mirror whatever grouping convention the host language uses for the equivalent domain concept.
- Child/join tables keep the owner prefix rather than dropping it.
- ✅ `user_details`, `user_credentials`, `organization_details`, `organization_user`, `waha_user_message`
- ❌ `users`, `user` (no entity), `customers_details` (plural owner), `OrganizationDetails` (not snake_case), `customer_book` (a feature name, not an owner+entity pair)

### Column names

- `snake_case`. Spell multi-word logical fields out in full rather than abbreviating — e.g. `phone_national_number`, `logo_original_bucket_key`, `address_line_1`.
- Primary-key/identifier columns are named `{{ entity }}_id` and typed `uuid`. Generate identifiers in the application, not as a database `default` — this keeps id generation (and its algorithm/version, e.g. UUIDv7) a single, testable concern instead of splitting it between the database and the code.
- ✅ `organization_id`, `phone_number_e164`, `address_line_2`
- ❌ `organizationId`, `id` (unqualified), `phoneNumberE164`

### Column types

- `uuid` — identifiers.
- `text` — **all** strings, with no `varchar(n)`. Length/format constraints belong to a refined type in the domain layer, not to the database column.
- `timestamptz` — every timestamp column. Never plain `timestamp` (without a time zone).
- `boolean`, `int` — as needed for their domain meaning.
- Model an enum as a `text` column holding the enum case's name (mapped by an explicit codec pair on the code side — see [Type mappings](#type-mappings)), rather than as a native Postgres `enum` type, unless a project has a specific reason to prefer the native type (native enums are harder to evolve — adding a value can require special-casing depending on the database version — which is why the text-plus-codec convention is the default here).
- A single logical value that is naturally **multi-part** is often better modeled as **several columns**, one per part, mirroring the domain type's own fields, rather than as one column. A **multi-valued** attribute (a list of small records) is often better modeled as a single `jsonb` column instead of a child table, when the list is small, always read/written as a unit, and does not need to be queried independently.

### Nullability

- Columns are `not null` by default. Omit `not null` **only** for a field modeled as an optional value in the code's row type.
- The presence or absence of `not null` in the migration must match the optionality of the corresponding field on the code side exactly — a mismatch is a **runtime decode failure**, not a compile error, so it will not be caught by the type checker.

### Audit columns

- Give almost every table `created_at timestamptz not null` and `updated_at timestamptz not null`, written last in column order. Set both from the application's own clock/time source at write time (`updated_at` refreshed on every mutation) — never by a database trigger or a `default now()` — so the same time source that the rest of the application uses for business logic and testing also governs audit timestamps.
- Add `expires_at` / `last_update` (or equivalent) to tables with expiry or activity semantics, as needed.

### Keys, constraints, and indexes

- Declare the primary key inline at the end of the table: `primary key (<entity>_id)`; composite for join tables, e.g. `primary key (organization_id, user_id)`.
- Enforce uniqueness with `unique (...)` — single-column or composite. **Name a unique constraint whenever the code maps its violation to a distinct error** (see [Constraint naming & conflict mapping](#constraint-naming--conflict-mapping)); an anonymous `unique (...)` is fine only when a violation should surface as a generic failure.
- Declare foreign keys against the parent's key explicitly, e.g. `foreign key (user_id) references <parent_table> (user_id)`.
- Name indexes `idx_{{ table }}_{{ purpose }}`, optionally with a storage method, e.g. `create index idx_{{ table }}_{{ purpose }} on {{ table }} [using hash] (...)`. Use a hash index for single-column equality lookups and a plain b-tree (with explicit column order/`DESC`) for range or ordering queries.
- ✅ `idx_organization_user_user_id`; `foreign key (organization_id, customer_id) references customer (organization_id, customer_id)`; `idx_user_token_user_id ... using hash (user_id)`; `idx_waha_user_message_order on waha_user_message (user_id, created_at desc)`
- ❌ `organization_user_idx` (index name doesn't start with `idx_`); a foreign-key column with no supporting index when it is queried on

## Constraint naming & conflict mapping

When a `unique` violation should surface to the caller as a **specific, distinguishable conflict** (rather than a generic failure), the code that catches the violation typically does so by matching on the **violated constraint's name** — so that constraint needs a **stable, explicit name**, never the database's autogenerated one. An autogenerated name is also commonly **truncated** at the database's identifier length limit (63 characters in Postgres), which can silently stop it matching what the code expects.

- Name such a constraint explicitly, e.g. `constraint uq_<table>_<distinguishing_columns> unique (...)`. The same applies to a **partial unique index** whose violation is caller-facing — name it explicitly too, e.g. `create unique index uq_<table>_<column> on <table> (...) where <partial-condition>`. Keep the name **stable**: code that matches on the literal constraint-name string will silently fall back to a generic error if the constraint is renamed without updating that match.
- The code typically reads the constraint name off the database driver's error object for the unique-violation SQL state and maps it to a specific error type/message. See [repository-new.md](../repository-new.md).
- A `unique` constraint whose violation is *not* meant to become a distinct caller-facing error can stay anonymous — it just surfaces as a generic internal/database error like any other failure.
- ✅ `constraint uq_customer_business_contact_email unique (organization_id, customer_id, email)`; `create unique index uq_customer_name on customer (organization_id, customer_type, name) where status = 'Active'`
- ❌ an anonymous `unique (organization_id, customer_id, email)` that the code nonetheless tries to translate by name; matching on a truncated autogenerated name such as `customer_business_contact_organization_id_customer_id_phone_num`

## Soft-delete & archival

Some entities own **immutable historical children** — order/invoice-like records that are financial or audit records in their own right. Deleting a parent must never destroy that history. The general rule:

- **An entity that can accumulate historical/audit records (e.g. orders) is soft-deleted, never hard-deleted.** Give it a lifecycle column (e.g. `status text not null`, mapped to an `ACTIVE`/`ARCHIVED`-style enum) set by the code on insert with no DB `default`, following the enum-as-text convention above. "Deleting" the entity flips its status instead of removing the row, so every child foreign key keeps resolving and the archive stays reversible. Reads filter to the active status by default. A pure child row that carries no historical records of its own can still be hard-deleted.
- **Historical records themselves are append-only and immutable.** Never `DELETE` one; a cancellation/refund/reversal is a status transition on that record, not a row removal.
- **Snapshot the referenced entity's relevant fields onto the historical record**, in addition to its foreign key. The parent's live details can change or be archived after the fact, and a historical record must keep reflecting what things were **at the time it happened**.
- **Foreign keys from a historical record to its parent use `on delete restrict`, never `cascade`.** With soft-delete in place the parent should never be hard-deleted, so `restrict` should never actually fire in practice — it exists as a belt-and-suspenders guard that turns an accidental hard `DELETE` into a database error instead of silently losing history.
- **Erasure requests (e.g. GDPR "right to be forgotten") anonymize, they do not delete.** Blank the identifying snapshot fields on the historical record (name/email/phone, etc.) while keeping amounts, dates, and the row itself — financial/audit-retention obligations generally outrank erasure, and anonymizing typically satisfies both at once.
- ✅ `status = 'ARCHIVED'` on `customer` (soft-delete); `customer_order` keeps `buyer_*` snapshot columns captured at order time **and** `on delete restrict` foreign keys back to `customer`
- ❌ `DELETE FROM customer ...` where associated orders exist; `on delete cascade` from an order into its parent; an order that only foreign-keys its parent with no snapshot of the parent's details

## Discriminated subtypes on one table

A recurring modeling question: an entity has a small number of mutually-exclusive subtypes that share the same identity and are targeted by the same downstream relationships (e.g. all subtypes can equally be the target of an order). The general rule is to model this as **one table with a discriminator column**, not as separate per-subtype detail tables:

- Give the table a discriminator column (`<entity>_type text not null`, enum-as-text) and let **all** subtypes' data live on that one table's columns — the subtypes differ only in *which* columns are populated for a given row, not in *which table* the row lives in.
- A **sub-record that belongs to one subtype but is not itself an instance of the discriminated entity** (e.g. a contact person that belongs to a business but is never itself an order target) is a separate child table, keyed by (tenant, parent-entity-id, own-id) and foreign-keyed to the parent scoped by tenant — so it can only ever attach to a parent in the same tenant. The foreign key alone does not need to enforce "only under this subtype"; that can be a service-layer convention (only ever created through that subtype's code paths) rather than a database rule.
- Scope any "unique per active row" rule by tenant and by subtype together, via a partial unique index that filters to the active status: `unique (<tenant_id>, <entity>_type, <distinguishing_column>) where status = 'Active'`.

Consequences of the single-table-plus-discriminator design:

- **The discriminator column is the single source of truth for which subtype a row is.** A row is inherently one subtype (one row, one discriminator value) and can never be "both" — reads/updates that are subtype-specific filter on the discriminator, so a wrong-subtype lookup simply returns "not found".
- This design is generally preferred over separate per-subtype detail tables because it removes a cross-table join on every read and removes the need for an application-level "never both subtypes" invariant that a two-table design would otherwise require.
- **A child record tied to one subtype shares that parent row's fate** — archiving the parent leaves its children in place; removing a child that carries no history of its own is a plain row delete.

- ✅ `customer_type text not null` and all customer columns on one `customer` table; the contact child table foreign-keyed by `(organization_id, customer_id)`; per-active-tenant-subtype uniqueness via a partial index
- ❌ an `is_individual boolean` (forces a migration + backfill to add a third subtype later); modeling a child record as a row of the discriminated entity itself; a globally-unique name with no tenant scoping in the key

## Keeping the schema and persistence code in sync

The schema and the persistence code that reads/writes it are two halves that must be kept in lockstep by hand whenever there is no schema-generation-from-code: **the names on both sides must match exactly**, so a rename is a change to a migration **and** to every place in the code that names that column/table literally.

> This section covers only the schema-sync obligations that a change to the schema imposes on the code. For the persistence layer as an architecture — the responsibilities of each code layer, input models vs. API request models, transactions, id/timestamp generation, error mapping, wiring, and testing — see [repository-new.md](../repository-new.md).

### Row order and the single column-list fragment

- A table's row/record type lists its fields in **the same order as the table's column list**. The persistence library's positional row-codec mechanism is what makes this load-bearing rather than cosmetic (reordering columns without reordering the row type's fields becomes a silent runtime bug, not a compile error) — see [doobie-new.md § Reusable field list + positional row codec](doobie-new.md#reusable-field-list--positional-row-codec) for that mechanism.

### Type mappings

How row-codec typeclasses are derived for refined types, enums, and `jsonb` columns is a persistence-library concern, not a schema one — see [doobie-new.md § Codec derivation for domain types](doobie-new.md#codec-derivation-for-domain-types).

## Configuration — schema and table identifiers are config, not literals

Table (and schema) names should never be string literals scattered through query code — they should flow through configuration, so every environment and every test can point the same code at differently-named physical objects if needed:

- Define one place that holds every table name the persistence code needs (e.g. a config case class with a field per table), and resolve the schema/table fragment from it at the point a query is built, rather than hard-coding an identifier in a SQL string.
- Keep the actual name/value for each table in the environment's configuration (e.g. an `application.conf`-style file), not in code.
- **Watch for configuration that is duplicated per test/deployment environment.** If integration or acceptance tests load their own copy of this configuration (rather than sharing the main service's), adding a table means updating **both** copies — forgetting the test copy means the row/table silently fails to resolve only in that test environment, which is easy to miss because the main application works fine.

## Testing

- Provide a shared test-database client/harness that any repository/integration test can use for schema-level setup and assertions — e.g. checking a table exists, truncating a table between tests, and running an arbitrary query directly against the schema.
- Have the code's query layer expose test-only helper methods (e.g. a "read everything back" helper) purely for use in assertions, distinctly named/suffixed so they're never mistaken for methods the application itself calls.
- The full recipe for a schema-backed repository/query integration test — the container/database stack, base test traits, mocking time and id generation, and what to assert — belongs to the persistence-architecture doc's testing section, not here: see [repository-new.md](../repository-new.md).
- End-to-end feature behaviour built on the schema is proven by the project's acceptance-test layer, not here.

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
6. **Tests — don't rely on defaults compiling.** Update the repository/integration test's *update* test to pass an explicit sampled value for the new optional-update parameter and assert on it. Skipping this leaves the new `SET` fragment untested dead code as far as the suite is concerned. A test double with strict call-expectation matching (e.g. a mocking library's `expects(...)`) will fail to compile once the new argument is threaded through — fix that by adding the new argument to the expectation, not by loosening the matcher.
