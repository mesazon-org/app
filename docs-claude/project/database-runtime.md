# Database runtime changes

Read [agnostic Doobie](../standards/doobie.md) plus this file only when changing the PostgreSQL transactor, datasource/pool, SQL logging, or shared database test client. Feature SQL/repositories use [Repository](../features/flow/04-repository.md).

Runtime: Doobie `1.0.0-RC13` (`doobie-core`, Hikari, PostgreSQL) plus Tranzactio `6.0.0` (`tranzactio-doobie`) for ZIO.

Production `PostgresTransactor`:

- Allocate `HikariDataSource` in `ZLayer.scoped`/`ZIO.acquireRelease`.
- Configure `DatabaseConfig(name, driver, host, port, username, password, threadPoolSize)`.
- Release with blocking `close`.
- Construct `DbContext(logHandler)`, then `Database.fromDatasource`.
- `LogHandler[Task]`: `Success` → structured debug; `ExecFailure`/`ProcessingFailure` → structured error including cause/Java stack. Never print SQL events to stdout.

Test `PostgreSQLTestClient`:

- Use plain `PGSimpleDataSource`, not a pool/scoped layer; reuse production `DbContext`/database construction.
- `checkIfTableExists`; `truncateTable` uses trusted `Fragment.const` and `TRUNCATE ... CASCADE`; `executeQuery(ConnectionIO|TranzactIO)` uses `transactionOrDie`.
- Testcontainers exposes `postgres:5432`; `PostgreSQLTestClientConfig.from` resolves mapped host/port.

Exercise lifecycle, config, logging branches, and test-client operations affected by the change.
