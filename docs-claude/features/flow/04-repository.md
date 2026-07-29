# PR 4 — Repository

Use after schema migration. Add persistence-only domain types, Row/Queries/Repository code, codecs, config, layer definitions, and real-Postgres tests. Read [PostgreSQL](../../standards/postgres.md), [Doobie](../../standards/doobie.md), and [Scala](../../standards/scala.md).

Flow: `Service → Repository (ZIO boundary) → Queries (SQL) → PostgreSQL`. Services never see Doobie, `TranzactIO`, Queries, or Smithy request types at the repository boundary.

## Files and names

| Layer | File/type | Rule |
|---|---|---|
| Row | `repository/domain/<Entity>Row.scala` | One field per column in query-field order; nullable fields are `Option` and end `Opt`. Non-table projections also end `Row`. |
| Queries | `repository/queries/<Table>Queries.scala` | `final class`; bare verbs (`get`, `getByUserID`, `insert`, `update`, `delete`, `upsert`, `is...`); plural for many, `All` for all rows, `Testing` for test-only reads. |
| Repository | `repository/<Entity>Repository.scala` | Trait `<Entity>Repository`; private `<Entity>RepositoryImpl`; methods include entity + operation and `By<Selector>` where needed. |

Use project acronyms (`UserID`, `OtpID`, `IDGenerator`, `JwtService`, `WahaClient`), state suffixes (`otpNew`, `rowUpdated`), and optional suffixes (`emailRawOpt`, `addressLine1OptUpdate`). Repository result bindings retain the exact row shape: `userDetailsRow`, `userOtpRowOpt`, `userDetailsRows`, `userDetailsRowUpdated`.

`Impl` exists only to distinguish the concrete implementation from its trait; never name a trait `...Impl` or use a bare `...Implementation`. Domain concepts are named directly (`OtpType`, `TokenType`, `OrganizationUserRole`), never after storage representation (`...String`, `...Column`).

## Rows and repository inputs

- Row field order must equal the shared SQL field fragment: whole-row `Read`/`Write` is positional. Migration nullability must equal `Option` usage.
- A persisted Row contains generated ID and audit fields; it is not an insert input.
- Repeated/`jsonb` fields use repository-owned `...Input` elements, never Smithy `...Request` types.
- Default repository inputs are flat parameters with full domain names. Do not pass API/Smithy request models.
- Define `<Operation><Entity>Input` in the repository companion only for a batch element or repeated/nested child. It has no generated ID/audit fields. Singular and batch operations reuse the same element input; single-only update/remove operations remain flat.
- The service maps validated request → input with Chimney/`iron-chimney` (`transformInto`).
- Repository returns Rows/projections, not Smithy or Doobie types: `get` → `Option[Row]`, create/update → affected Row where useful, many → `List[Row]`; insert returns generated ID(s) when later references need them.

## Queries

```scala
private val frSchema = Fragment.const(config.schema)
private val frFoo    = Fragment.const(config.fooTable)
private val frTable  = frSchema ++ fr0"." ++ frFoo
```

- Never hard-code identifiers. Reuse one `frFooFields` column fragment for every select/insert/returning; it defines Row order.
- Import Doobie core/implicits/postgres implicits/fragments and Tranzactio Doobie. Remember: `fr` adds a leading space; `fr0` does not.
- Methods return `TranzactIO[...]`; terminal SQL runs inside `tzio`.
- Use Doobie combinators (`whereAnd`, `set`, `orderBy`, `in`) and `NonEmptyList`. Results use `.query[Row].option|unique|to[List]`; updates use `.update.run[.void]`.
- Whole-row values use `fr0"(" ++ fr"$row" ++ fr0")"` through `insertRowWith`/`insertRowsWith`. Typed views over shared `customer` bind fields explicitly; positional whole-row writes are invalid.
- Dynamic updates always set `updated_at`, then append flattened `...OptUpdate` assignments.
- Bulk IDs use `in(..., NonEmptyList[ID])`. Upserts state the conflict target and use `EXCLUDED` assignments or `DO NOTHING`.
- Native `customer_status` is outside `search_path`: writes cast `${status}::<schema>.customer_status`; selects cast `status::text`. Preserve casts in every status query.
- Each Queries class exposes `val live = ZLayer.derive[...]`.

## Codecs

Shared givens live in `repository/queries/queries.scala`:

- Iron: generic `Read`/`Write`/`Get`/`Put`/`Meta` via `RefinedType.Mirror`; add one only for a new base representation.
- Enum: `Get.deriveEnumString`/`Put.deriveEnumString` (case name stored as text).
- JSONB: `jsonbMeta[A]` uses jsoniter `JsonValueCodec[A]` and `Meta.Advanced.other[PGobject]("jsonb")`.

Every `Meta[List[X]]` is explicitly named; its element type exactly matches the Row field. Feature-owned JSONB codecs live in its Queries class, import repository companion inputs, and import `io.github.iltotore.iron.jsoniter.given`. Missing the Iron import may appear as a macro `StackOverflowError`.

When changing the transactor, pool, SQL logging, or database test-client runtime, also read [Database runtime](../../project/database-runtime.md).

## Repository boundary

- Dependencies: database `ServiceOps[Transactor[Task]]`, Queries, `TimeProvider`, and `IDGenerator`.
- One atomic operation is one `for`-comprehension inside one `database.transactionOrWiden`. State whether a batch is all-or-nothing or per-item; do not create accidental transaction boundaries.
- Generate UUIDv7 IDs and timestamps in the repository, never with DB defaults. Use one `instantNow` per operation for insert `CreatedAt`/`UpdatedAt` and mutation `UpdatedAt`.
- Refine generated IDs and fail with `UnexpectedError` if construction fails.
- For batches, pair each generated ID with its input inside one `ZIO.foreach` using a named tuple. Derive rows/results from those pairs; never generate an ID list then `zip`, and do not access pair positions.
- Map every `DbException` through one `toServiceError(operationMessage)` function. Default: `InternalServerError.RepositoryError(message, underlying)`.
- A `PSQLException` with state `23505`/`UNIQUE_VIOLATION` maps by constraint name to `ConflictError.UniqueConstraintViolation` (409), retaining the underlying exception. Never race-prone pre-check a uniqueness constraint.
- Ensure the Smithy operation declares `Conflict`; otherwise the conflict may render as 500.
- Repository layer: `ZLayer.derive[FooRepositoryImpl].project[FooRepository](identity)`. Repository specs provide Queries/repository with the transactor, clock, and ID generator; PR 5 adds them to the full application graph.
- Table config stays in lockstep: `RepositoryConfig` field + `allTableNames`, core `application.conf`, gateway-it `application.conf`, migration, Row, Queries, Repository, and layer graph.

## Required proof: real PostgreSQL

Every query and repository method added or changed in this PR is tested in `gateway/core/.../it/<Entity>RepositorySpec`; every DB constraint gets its own isolated test. Never mock/H2 the database.

Harness:

- Extend `ZWordSpecBase, RepositoryArbitraries, DockerComposeBase`.
- `src/test/resources/compose/repository.yaml` starts PostgreSQL plus one-shot Flyway. `backend/schemas/local/postgres/init.sql` supplies schema/role and `TRUNCATE`; add only new role/grant needs.
- Wait for `checkIfTableExists`; truncate every touched table before each test.
- `PostgreSQLTestClient.live` exposes `databaseLive`; `executeQuery` arranges/reads via Queries outside the repository under test.
- The test client uses `PGSimpleDataSource` (not Hikari/scoped pooling), reuses production `DbContext`, runs `executeQuery(ConnectionIO|TranzactIO)` through `transactionOrDie`, and resolves the Testcontainers `postgres:5432` mapping with `PostgreSQLTestClientConfig.from`.
- Each test uses a fresh `TestContext` with container-derived config, Queries/repository layers, and fresh mocked `TimeProvider`/`IDGenerator`. Batch expectations cover one call per item in order.
- Add Row/input arbitraries to `RepositoryArbitraries`. Explicitly define `Arbitrary[List[Input]]` before any Row/input that uses it and encode list invariants there.

For each method:

1. Arrange directly through Queries or the repository create path.
2. Act through the repository.
3. Read back and compare whole Rows, including exact injected IDs/timestamps.
4. Cover happy, missing (`None`/empty), every unique/foreign/check constraint behavior, and error message/type/underlying exception.
5. On failure assert no losing row; for multi-statement operations prove rollback of earlier writes.
6. Trigger one constraint at a time and use fresh arbitrary data per test.
7. Add a direct database guard for invariants enforced by service code rather than a DB constraint.

Run:

```sh
sbt "gateway-core/testOnly *<Entity>RepositorySpec"
```

Update the feature doc with schema/config/files, transaction/constraint decisions, and test coverage.
