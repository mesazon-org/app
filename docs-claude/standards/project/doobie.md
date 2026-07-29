# Doobie — Mesazon

Read with the [agnostic Doobie rules](../agnostic/doobie.md).

Doobie `1.0.0-RC13` (`doobie-core/hikari/postgres`) + Tranzactio `6.0.0` (`tranzactio-doobie`) for ZIO.

## SQL

- Imports: `org.typelevel.doobie.*`, `.implicits.*`, `.postgres.implicits.*`, `.util.fragments.*`, `io.github.gaelrenoux.tranzactio.doobie.*`.
- Identifiers: `Fragment.const(config.schema/table)`; table = `frSchema ++ fr0"." ++ frTable`. Never literal.
- `fr` adds a leading space; `fr0` does not. Use `whereAnd`, `set`, `orderBy`, `in`.
- Native `customer_status` is outside `search_path`: write `${status}::<schema>.customer_status`; select `status::text`. Every status query keeps these casts.
- Reuse matching insert/select field fragments. Select fragment casts native enum.
- Whole-row values: `fr0"(" ++ fr"$row" ++ fr0")"` through `insertRowWith`/`insertRowsWith`.
- Typed views over shared `customer` (`CustomerIndividualDetailsRow`, `CustomerBusinessDetailsRow`) bind fields explicitly; positional whole-row `Write` is invalid.
- Dynamic update: mandatory `updated_at` in `NonEmptyList`, plus flattened optional assignments, passed to `set`.
- Bulk IDs use `in(..., NonEmptyList[ID])`. Upserts specify conflict target + `EXCLUDED` assignments. Use combinator `orderBy` unless ordering needs an expression.
- Results: `.query[Row].option|unique|to[List]`; `.update.run[.void]`.

## Codecs

Shared givens: `repository/queries/queries.scala`.

- Iron: generic `Read`/`Write`/`Get`/`Put`/`Meta` via `RefinedType.Mirror`; only a new base representation needs a codec.
- Enum: `Get.deriveEnumString`/`Put.deriveEnumString`.
- `jsonbMeta[A]`: jsoniter `JsonValueCodec[A]` + `Meta.Advanced.other[PGobject]("jsonb")`.
- Every `Meta[List[X]]` is an explicitly named given. Element type equals the Row field: repository `...Input` when present; request type only where no input exists. Feature-owned codecs live in its Queries class.
- jsonb codec derivation requires `io.github.iltotore.iron.jsoniter.given`; omission can surface as macro `StackOverflowError`.
- Codec imports: jsoniter core/macros, `RefinedType`, Iron jsoniter given, `PGobject`, Doobie `Get/Meta/Put/Read/Write`.

## Effects/errors

- Query methods return `TranzactIO[...]`; SQL terminal operation lives in `tzio`.
- One atomic repository operation = one query `for`-comprehension inside one `database.transactionOrWiden`.
- Every boundary call maps errors to `ServiceError.InternalServerError.RepositoryError`; query layer does not map.
- Unique violation: Tranzactio `DbException` wrapping `PSQLException`, state `23505`/`PSQLState.UNIQUE_VIOLATION`; constraint from `getServerErrorMessage.getConstraint`. Mapping policy: [repository error handling](../../repository.md#error-handling).

## Pool/logging

`PostgresTransactor`:

- `HikariDataSource` in `ZLayer.scoped`/`ZIO.acquireRelease`; configure from `DatabaseConfig(name, driver, host, port, username, password, threadPoolSize)`; release with blocking `close`.
- `DbContext(logHandler)` then `Database.fromDatasource`.
- `LogHandler[Task]`: `Success` → structured debug; `ExecFailure`/`ProcessingFailure` → structured error cause/Java stack. Never stdout.

## Tests

`PostgreSQLTestClient` (`backend/postgresql-test`):

- `checkIfTableExists`; `truncateTable` (`TRUNCATE ... CASCADE`, trusted `Fragment.const`); `executeQuery(ConnectionIO|TranzactIO)` through `transactionOrDie`.
- Test datasource: plain `PGSimpleDataSource`; no pool/scoped layer. Reuse production `DbContext`/database wiring.
- Testcontainers service `postgres:5432`; config resolves mapped host/port via `PostgreSQLTestClientConfig.from`.
