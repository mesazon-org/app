# PostgreSQL schema — Mesazon specifics

Read with the [agnostic Postgres rules](../agnostic/postgres.md). Flyway schema + hand-written Doobie/Tranzactio persistence; no ORM/code-generated schema. See [Doobie specifics](doobie.md).

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
- `TimeProvider.instantNow` supplies `CreatedAt`/`UpdatedAt`; repositories set them.
- Enum-as-text columns: `onboard_stage`, `organization_stage`, `user_role`, `otp_type`, `token_type`, `action_attempt_type`.
- Four-column phone number: `phone_region`, `phone_country_code`, `phone_national_number`, `phone_number_e164` (mirrors the `PhoneNumber` domain type). Multi-valued contact points as `jsonb`: `emails`, `phone_numbers` (customer book tables, `organization_details`).

## Soft-delete & archival

`customer.status` is native PG `customer_status` (`Active`/`Archived` domain case names), the sole enum-as-text exception. Archive, never hard-delete. See [Customer Book](../../features/customer-book.md).

## Discriminated subtypes on one table

`customer` carries `customer_type` (`INDIVIDUAL` | `BUSINESS`); all data lives on it. Individual: `name` = full name, `tax_id` null. Business: `name` = company, optional `tax_id`. `customer_business_contact` is not a customer/order target and has no status; key `(organization_id, customer_id, customer_business_contact_id)`, tenant-scoped FK to `customer`. `uq_customer_name` is unique per active tenant/type; contact email/phone use `uq_customer_business_contact_email` / `uq_customer_business_contact_phone_number`. See [Customer Book](../../features/customer-book.md).

## Configuration

`RepositoryConfig` (`schema`, `<entity>Table = ""`, `allTableNames`) comes from two `repository` configs: `backend/gateway/core/src/main/resources/application.conf` and `backend/gateway/it/src/test/resources/application.conf` (acceptance-test copy). Update both.

## Testing

- `local_test_user` holds the `TRUNCATE` grant used to reset tables between tests (granted in `local/postgres/init.sql`).
- The test-database client/harness, its methods, and its testcontainers wiring are `PostgreSQLTestClient` — see [Doobie tests](doobie.md#tests) (that library owns the client's mechanics; this doc only owns the grant that makes `truncateTable` work).
- Query classes expose `...Testing` helpers (e.g. `getAllTesting`) for assertions.
- Acceptance tests live in `backend/gateway/it` — see [acceptance-tests.md](../../acceptance-tests.md).

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
