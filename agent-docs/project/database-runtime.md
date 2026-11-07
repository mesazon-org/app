# Database runtime changes

For transactor, datasource/pool, SQL logging, or shared DB test-client changes, read [Doobie](../standards/doobie.md) and this file. Feature SQL/repositories use [Repository](../features/flow/04-repository.md).

Runtime: Doobie `1.0.0-RC13` (`doobie-core`, Hikari, PostgreSQL) plus Tranzactio `6.0.0` (`tranzactio-doobie`) for ZIO.

Production `PostgresTransactor`:

- Allocate `HikariDataSource` in `ZLayer.scoped`/`ZIO.acquireRelease`.
- Configure `DatabaseConfig(name, driver, host, port, username, password, threadPoolSize)`.
- Release with blocking `close`.
- Construct `DbContext(logHandler)`, then `Database.fromDatasource`.
- `LogHandler[Task]`: `Success` → structured debug; `ExecFailure`/`ProcessingFailure` → structured error including cause/Java stack. Never print SQL events to stdout.

Test `PostgreSQLTestClient`:

- Two layers, both reusing production `DbContext`/`Database.fromDatasource` construction:
  - `live`: plain `PGSimpleDataSource`, nothing to release; used by gateway-core repository specs.
  - `liveScoped`: `HikariDataSource` (pool `postgresql-test-client`, `maximumPoolSize` 3, `minimumIdle` 0) acquired with `ZIO.acquireRelease` and closed with blocking `close()` when the scope ends; used by gateway-it through `GatewayItContext.live`, so each `withContext` call opens and closes its own pool.
- History: an unscoped Hikari pool built per `.provide` was never closed and leaked; `live` moved to `PGSimpleDataSource`, and `liveScoped` now closes its pool on release. Never add an unscoped pool.
- `checkIfTableExists`; `truncateTable(schema, tables*)` sends one `TRUNCATE TABLE schema.t1, schema.t2, ... CASCADE` from trusted `Fragment.const` names (empty varargs is a caller bug, not guarded); `executeQuery(ConnectionIO|TranzactIO)` uses `transactionOrDie`.
- Testcontainers exposes `postgres:5432`; `PostgreSQLTestClientConfig.from` resolves mapped host/port.

Exercise lifecycle, config, logging branches, and test-client operations affected by the change.
