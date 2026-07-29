# Functional JDBC — Mesazon specifics

Concrete values that fill in the placeholders in [doobie-new.md](doobie-new.md). Not a standard on its own — read each fact against the rule it instantiates there.

- Library: [Doobie](https://tpolecat.github.io/doobie/) `1.0.0-RC13`, package `org.typelevel.doobie` (`doobie-core`/`doobie-hikari`/`doobie-postgres`, group `org.typelevel`), wrapped in [Tranzactio](https://github.com/gaelrenoux/tranzactio) `tranzactio-doobie` `6.0.0` (`io.github.gaelrenoux.tranzactio.doobie`) for ZIO integration. Query/repository code imports `org.typelevel.doobie.*`, `org.typelevel.doobie.implicits.*`, `org.typelevel.doobie.postgres.implicits.*`, `org.typelevel.doobie.util.fragments.*`, and `io.github.gaelrenoux.tranzactio.doobie.*`.

## Fragment composition

- Schema/table identifiers are spliced via `Fragment.const(config.schema)` / `Fragment.const(config.fooBarTable)`, never a literal string; a table reference is built as `frSchema ++ fr0"." ++ frFooBarTableName`.
- `fr"..."` interpolates with a leading space (for chaining after a keyword); `fr0"..."` interpolates with no leading space (for tight concatenation, e.g. building an identifier or a `(...)` group).
- The combinators `whereAnd`, `set`, `orderBy`, `in` come from `org.typelevel.doobie.util.fragments.*`.
- **Gotcha — casting into a native enum type outside the search path.** `customer.status` is a native Postgres `customer_status` enum (the one column not using the enum-as-text convention). The app connection's `search_path` doesn't include the schema the type lives in, so every read/write touching `status` must qualify the cast: bind params as `${row.status}::" ++ frCustomerStatusType` (`frCustomerStatusType = frSchema ++ fr0".customer_status"`) on write, and `status::text` on select. A new query touching this column must keep the cast, or it fails to compile against the type/decode at runtime. See `CustomerBookQueries`.

## Reusable field list + positional row codec

- Pattern (`CustomerBookQueries`): one `frCustomerIndividualInsertFields` / `frCustomerBusinessInsertFields` fragment (INSERT column list) and a matching `frCustomerIndividualSelectFields` / `frCustomerBusinessSelectFields` fragment (SELECT/RETURNING column list, casting the enum column to text), each reused by every query against that view of the table.
- Whole-row `INSERT` via the derived positional `Write`: `insertRowWith`/`insertRowsWith` helpers build `INSERT INTO <table> (<fields>) VALUES <values>` where `frValues(row) = fr0"(" ++ fr"$row" ++ fr0")"` — used for rows that map 1:1 to their table (e.g. `CustomerBusinessContactRow`).
- A typed view over a shared/discriminated table (`CustomerIndividualDetailsRow` / `CustomerBusinessDetailsRow`, both views over the one `customer` table) cannot use the whole-row `Write` — `frCustomerIndividualValues`/`frCustomerBusinessValues` build the `VALUES (...)` list explicitly, field by field, via `List(fr0"${row.x}", ...).intercalate(fr",")`.

## Codec derivation for domain types

The shared given block lives in `repository/queries/queries.scala`:

- `Read[FinalType]`/`Write[FinalType]`/`Get[FinalType]`/`Put[FinalType]`/`Meta[FinalType]` are derived generically for every Iron refined type via `RefinedType.Mirror[WrappedType]`, delegating to the base type's instance and `asInstanceOf`-casting (safe because the refined type is a zero-cost wrapper). A refined type over a base Postgres already has a `Meta`/`Get`/`Put` for needs no per-type code.
- `Get`/`Put` for a Scala `enum` derive via `Get.deriveEnumString[A]` / `Put.deriveEnumString[A]` (`inline given` over `Mirror.SumOf[A]`), matching the enum-as-text schema convention.
- `jsonb` columns use `jsonbMeta[A](using JsonValueCodec[A]): Meta[A]`, built as `Meta.Advanced.other[PGobject]("jsonb").timap(decode)(encode)` — a jsoniter `JsonValueCodec` in, a `Meta[A]` out.
- **Named-given gotcha (confirmed, not hypothetical):** every `jsonbMeta`-derived `Meta[List[X]]` is bound to an **explicitly named** `given`, e.g. `given customerEmailEntryInputsMeta: Meta[List[CustomerEmailEntryInput]] = jsonbMeta` in `CustomerBookQueries`, `given organizationEmailEntryRequestsMeta: Meta[List[OrganizationEmailEntryRequest]] = jsonbMeta` in `queries.scala` — two anonymous givens of the same `Meta[List[_]]` shape would collide. The element type is whatever the row's own field uses for that column: a repository-owned `...Input` type where one exists (`CustomerEmailEntryInput`), the request type only where no input model exists yet (`OrganizationEmailEntryRequest`, `OrganizationPhoneNumberEntryRequest` in the shared `queries.scala`).
- `import io.github.iltotore.iron.jsoniter.given` **must** be in scope wherever a `jsonb` list's `JsonValueCodec` is derived (via jsoniter's `JsonCodecMaker.make`) — omitting it makes the jsoniter macro fail to resolve the Iron base codecs, surfacing as a `StackOverflowError` at compile time, not a clear "missing instance" error.
- Codec-owning imports: `com.github.plokhotnyuk.jsoniter_scala.core.*`, `com.github.plokhotnyuk.jsoniter_scala.macros.*`, `io.github.iltotore.iron.RefinedType`, `io.github.iltotore.iron.jsoniter.given`, `org.postgresql.util.PGobject`, `org.typelevel.doobie.{Get, Meta, Put, Read, Write}`.

## Building dynamic queries

- Dynamic `SET` pattern (`CustomerBookQueries.updateCustomerIndividualDetailsRow`): `NonEmptyList.of(fr"updated_at = $updatedAt") ++ List(fullNameOptUpdate.map(v => fr"name = $v"), ...).flatten`, passed to `set(updates)`.
- `whereAnd(fr"organization_id = $organizationID", fr"customer_id = $customerID", ...)` for conditional predicates; `in(fr"customer_business_contact_id", customerBusinessContactIDs)` (a `NonEmptyList[ID]`) for a bulk-id delete (`deleteCustomerBusinessContactRows`).
- Upsert (`UserActionAttemptQueries`): `... ON CONFLICT (user_id, action_attempt_type) DO UPDATE` ++ `set(NonEmptyList.of(fr"attempts = t.attempts + 1", fr"updated_at = EXCLUDED.updated_at"))` ++ `fr"RETURNING" ++ frUserActionAttemptFields`.
- `orderBy(fr"last_update ASC")` / `orderBy(fr"created_at DESC")` (`WahaQueries`) for the combinator form; a hand-written `ORDER BY LOWER(name), customer_id` (`CustomerBookQueries.getCustomerSummaryRows`) where the ordering needs a function call the combinator doesn't express.
- Result extraction: `.query[Row].option` (zero-or-one), `.query[Row].unique` (exactly-one, e.g. an `EXISTS` boolean), `.query[Row].to[List]`, `.update.run` (row count), `.update.run.void`.

## Effect integration

- Every query method returns `TranzactIO[...]` and wraps its SQL in `tzio { ... }` (`io.github.gaelrenoux.tranzactio.doobie.{tzio, TranzactIO}`).
- A repository composes multiple `Queries` calls needing one transaction inside one `for`-comprehension passed to `database.transactionOrWiden(...)` — e.g. `UserOtpRepository.upsertUserOtp` deletes then inserts in one `for`-comprehension under one `transactionOrWiden` call, not two separate calls.
- Every `transactionOrWiden(...)` call chains `.mapError(e => ServiceError.InternalServerError.RepositoryError(s"...", e))` — the query layer never maps errors itself.
- A unique-constraint violation arrives as a `DbException` (`io.github.gaelrenoux.tranzactio.DbException`) wrapping a `PSQLException` with SQL state `23505` (`PSQLState.UNIQUE_VIOLATION`); the violated constraint name is read off `getServerErrorMessage.getConstraint`. This is the library-level mechanism only — the policy of mapping it to `ServiceError.ConflictError.UniqueConstraintViolation` by constraint name belongs to [repository.md § Error handling](../repository.md#error-handling).

## Connection/pool wiring

`PostgresTransactor` (`repository/PostgresTransactor.scala`):

- The datasource is a `HikariDataSource`, built inside `ZLayer.scoped` via `ZIO.acquireRelease` — construct + configure (`setDriverClassName`, `setJdbcUrl`, `setUsername`, `setPassword`, `setMaximumPoolSize` from `DatabaseConfig`) on acquire; `ds.close()` (via `ZIO.attemptBlocking(...).orDie`) plus a `ZIO.logError("HikariDataSource closed")` on release.
- `given DbContext = DbContext(logHandler)`; `val live = datasourceLive >>> Database.fromDatasource`.
- `logHandler: LogHandler[Task]` pattern-matches `org.typelevel.doobie.util.log.{Success, ExecFailure, ProcessingFailure}` and routes each to ZIO structured logging (`ZIO.logDebug` for success; `ZIO.logErrorCause` with `Cause.die(failure, StackTrace.fromJava(fid, failure.getStackTrace))` for the two failure cases) — nothing prints to stdout directly.
- `DatabaseConfig` fields: `name, driver, host, port, username, password, threadPoolSize`; `url = s"jdbc:postgresql://$host:$port/$name"`.

## Testing

`PostgreSQLTestClient` (`backend/postgresql-test`):

- `checkIfTableExists(schema, table)` — `SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = $schema AND table_name = $table)`.
- `truncateTable(schema, table)` — `TRUNCATE TABLE <schema>.<table> CASCADE`, table name spliced via `Fragment.const` (test-only trusted input).
- `executeQuery[A](query: ConnectionIO[A]): IO[DbException, A]` and the `TranzactIO[A]` overload — both go through `database.transactionOrDie(...)`, the raw escape hatch for test assertions the production `Queries` classes don't expose a method for.
- Test datasource is a plain `PGSimpleDataSource` (no pooling) built in a bare `ZLayer`, not `ZLayer.scoped`/Hikari — pooling is a production concern only. Same `DbContext(logHandler)` / `Database.fromDatasource` wiring as production otherwise.
- Backed by testcontainers docker-compose (`ExposedService("postgres", 5432)`); config resolves host/port from the running container via `PostgreSQLTestClientConfig.from(containers, ...)`.
