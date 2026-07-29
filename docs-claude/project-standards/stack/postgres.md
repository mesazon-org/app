# PostgreSQL schema — Mesazon specifics

Concrete values that fill in the placeholders in [postgres-new.md](postgres-new.md). Not a standard on its own — read each fact against the rule it instantiates there. Schema is applied by **Flyway** SQL migrations and read through a hand-written Scala repository layer over **Doobie**/**Tranzactio** — no ORM, no schema generation from code (see [doobie-project.md](doobie-project.md) for the persistence-library facts).

## Schema module layout

`backend/schemas/`:

- `migrations/V<version>__<name>.sql` — migrations.
- `local/postgres/init.sql` — local-dev bootstrap: creates the schema and the `flyway` / `local_user` / `local_test_user` roles and their grants.
- `local/flyway/flyway.config` — local Flyway config (`validateMigrationNaming=true`).
- `Dockerfile` — `flyway/flyway` image; `COPY ./migrations /flyway/sql`; `CMD ["clean", "migrate"]`.

Deployment: `terraform/dev/gateway-flyway/` runs this image as a one-shot `app-job`, pointed at `/flyway/sql` via `FLYWAY_LOCATIONS`, with `FLYWAY_SCHEMAS` set to the gateway schema. `clean` / `clean-on-validation-error` are enabled **only** in the `dev` environment.

## Migration files

- Live migration file: `V2025.05.27__init.sql` — everything currently lives in this one `init` migration because nothing has shipped on top of it yet.
- Same-day suffix example: `V2026.07.09.1__...sql`.
- DDL style reference: `V2025.05.27__init.sql` (lowercase keywords: `create table`, `not null`, `primary key`, `timestamptz`).

## Column types

- `IDGenerator` generates identifiers (UUIDv7) in the application.
- Enum-as-text columns: `onboard_stage`, `organization_stage`, `user_role`, `otp_type`, `token_type`, `action_attempt_type`.
- Four-column phone number: `phone_region`, `phone_country_code`, `phone_national_number`, `phone_number_e164` (mirrors the `PhoneNumber` domain type). Multi-valued contact points as `jsonb`: `emails`, `phone_numbers` (customer book tables, `organization_details`).

## Soft-delete & archival

`customer.status` is stored as a native PostgreSQL `customer_status` enum type rather than `text` — the one deliberate exception to the enum-as-text convention, whose labels are the domain `CustomerStatus` case names. See the end-to-end example in the [customer-book feature doc](../features/customer-book.md).

## Discriminated subtypes on one table

`customer` carries `customer_type` (`INDIVIDUAL` | `BUSINESS`); all customer data lives on that one table (for `INDIVIDUAL`, `name` is the person's full name and `tax_id` is null; for `BUSINESS`, `name` is the company name, `tax_id` may be set). `customer_business_contact` is the child table, keyed `(organization_id, customer_id, customer_business_contact_id)`, foreign-keyed to `customer (organization_id, customer_id)`. See the full lifecycle in the [customer-book feature doc](../features/customer-book.md).

## Configuration

`RepositoryConfig` (`schema`, `<entity>Table = ""` per table, collected into `allTableNames`) is populated from `repository { ... }` blocks in **two** separate `application.conf` files: `backend/gateway/core/src/main/resources/application.conf` (main service) and `backend/gateway/it/src/test/resources/application.conf` (integration-test module's own copy) — adding a table means touching the migration, `RepositoryConfig`, and *both* files.

## Testing

- `local_test_user` holds the `TRUNCATE` grant used to reset tables between tests (granted in `local/postgres/init.sql`).
- The test-database client/harness, its methods, and its testcontainers wiring are `PostgreSQLTestClient` — see [doobie-project.md § Testing](doobie-project.md#testing) (that library owns the client's mechanics; this doc only owns the grant that makes `truncateTable` work).
- Query classes expose `...Testing` helpers (e.g. `getAllTesting`) for assertions.
- Acceptance tests live in `backend/gateway/it` — see [acceptance-tests.md](acceptance-tests.md).

## Adding a table — checklist file paths

1. `backend/schemas/migrations/V...__*.sql`.
2. `RepositoryConfig`: new `<entity>Table` field + `allTableNames`.
3. Both `application.conf`s (`core` main + `it` test): `<entity>-table = "..."`.
4. `repository/domain/<Entity>Row.scala`.
5. `repository/queries/<Entity>Queries.scala`.
6. `repository/<Feature>Repository.scala`.
7. `local/postgres/init.sql` for any new role/grant.

## Adding a column — precedent

Missed for `company_registration_number`/`tax_id` and only caught in review: the update-test coverage step was skipped, leaving the new `SET` fragment untested — the reason that checklist step exists.
