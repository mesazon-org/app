# PR 3 — Database schema

Read [PostgreSQL](../../standards/postgres.md). This PR owns DDL/config only; Row/Queries/Repository land in PR 4.

## Mesazon schema

`backend/schemas/`:

- `migrations/V<version>__<name>.sql`;
- `local/postgres/init.sql`: local schema + `flyway`, `local_user`, `local_test_user` roles/grants;
- `local/flyway/flyway.config`: `validateMigrationNaming=true`;
- `Dockerfile`: Flyway image, migrations copied to `/flyway/sql`, `CMD ["clean", "migrate"]`.

`terraform/dev/gateway-flyway/` runs the image as one-shot `app-job`; `FLYWAY_LOCATIONS=/flyway/sql`, `FLYWAY_SCHEMAS=<gateway schema>`. `clean`/`clean-on-validation-error` are dev-only.

Current pre-release migration is `V2025.05.27__init.sql`; extend it only while nothing using it has shipped. Afterwards add an append-only dated migration. Same-day example: `V2026.07.09.1__...sql`. Match the init file's lowercase DDL style.

## Types and lifecycle

- `IDGenerator` creates UUIDv7 IDs in application code.
- `TimeProvider.instantNow` supplies `CreatedAt`/`UpdatedAt`; no DB time defaults.
- Enum-as-text examples: `onboard_stage`, `organization_stage`, `user_role`, `otp_type`, `token_type`, `action_attempt_type`.
- Phone value: `phone_region`, `phone_country_code`, `phone_national_number`, `phone_number_e164`.
- Atomic multi-value contacts: `emails`, `phone_numbers` as `jsonb`.
- `customer.status` is the sole native-enum exception: PG `customer_status`, labels `Active`/`Archived`; archive, never hard-delete.
- `customer_type`: `INDIVIDUAL` or `BUSINESS`, all customer data on `customer`.
  - Individual: `name` full name, `tax_id` null.
  - Business: `name` company, optional `tax_id`.
  - `customer_business_contact` is not a customer/order target and has no status; key `(organization_id, customer_id, customer_business_contact_id)`, tenant-scoped FK.
  - Active name constraint: `uq_customer_name`, tenant/type scoped.
  - Contact constraints: `uq_customer_business_contact_email`, `uq_customer_business_contact_phone_number`.

Feature-specific lifecycle details belong in the feature doc, not here.

## Configuration

Adding a table updates:

1. migration;
2. `RepositoryConfig`: `<entity>Table` + `allTableNames`;
3. `backend/gateway/core/src/main/resources/application.conf`;
4. `backend/gateway/it/src/test/resources/application.conf`;
5. local/deployed role provisioning if new grants are required.

Both configs use the `repository` block and `<entity>-table = "..."`. The acceptance copy is mandatory.

## Required proof

- Start the real repository Postgres/Flyway compose stack; migration must finish successfully.
- Add a schema smoke assertion using `PostgreSQLTestClient.checkIfTableExists` for every new table. If the PR introduces a named constraint/index whose exact definition is critical, assert it via `pg_catalog`/`information_schema`.
- No query tests belong here because no query exists yet; every query and constraint behavior is tested in [PR 4](04-repository.md).
- Update the feature doc's schema status, tables, constraints, lifecycle, config, and remaining work.
