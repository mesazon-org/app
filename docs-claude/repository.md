# Repository layer — persistence architecture

The repository layer is the boundary between the service layer and PostgreSQL. It is written in Scala over **Doobie** (`org.typelevel.doobie`) wrapped in **Tranzactio** (`io.github.gaelrenoux.tranzactio`) for ZIO — no ORM. This doc owns the **architecture and practices** of that layer; two neighbours own the halves it depends on:

- [postgres.md](postgres.md) — the schema (migrations, table/column naming, types, soft-delete) the code must stay in lockstep with, plus the type-mapping and config mechanics.
- [scala.md](scala.md) — the naming rules for every identifier named here (repository classes/methods/params, `Row` models, query classes). This doc references those sections rather than restating them.

Request flow: **`Service` → `Repository` (ZIO boundary) → `Queries` (SQL) → Postgres**, with `Row` models as the currency in and `Row`/projections out.

## The three layers

For an entity `Foo` backed by table `foo_bar`, persistence is three files under `backend/gateway/core/src/main/scala/io/mesazon/gateway/repository/`:

| Layer | File | Type | Responsibility |
| --- | --- | --- | --- |
| Row | `repository/domain/FooBarRow.scala` | `case class FooBarRow` | The table's shape — one field per column, **in column order**. |
| Queries | `repository/queries/FooBarQueries.scala` | `final class FooBarQueries(config)` | The SQL. Returns `TranzactIO[...]`. Knows columns, not transactions or errors. |
| Repository | `repository/FooRepository.scala` | `trait FooRepository` + `FooRepositoryImpl` | The ZIO boundary. Runs queries in transactions, generates ids/timestamps, maps errors. |

Each layer only knows the one below it. The service talks to the `Repository` trait and never sees Doobie, `TranzactIO`, or a `Queries` class.

### Row — the table shape

- `case class FooBarRow(...)` whose fields are Iron refined types / enums / `CreatedAt` / `UpdatedAt`, in **the same order as the column list** in the queries fields fragment — whole-row `Write` is positional, so order is load-bearing, not cosmetic.
- Nullable columns are `Option[...]`; the presence/absence of `not null` in the migration must match. A mismatch is a runtime decode failure, not a compile error.
- Naming and parameter conventions: [scala.md §4 Repository domain models](scala.md) and [§5 domain model parameters](scala.md).
- A `Row` represents a **persisted row you read back or write whole** — it carries the generated id and `createdAt`/`updatedAt`. It is **not** an insert input (those are minted in the repository — see [Inputs](#inputs-never-the-api-request-models)).
- A `jsonb`/repeated column that stores a list of small records (e.g. `emails`, `phoneNumbers`) is typed with the repository-owned **`...Input`** element model, **never** a smithy `...Request` model — request vocabulary must not leak even into the persisted `Row` shape or its jsonb codec. `CustomerIndividualDetailsRow.emails` is `List[CustomerEmailEntryInput]`, so the whole stack — input → `Row` field → jsonb codec — speaks one type and the repository needs no `Input → Request` conversion.

**Projections** — a read that is not a whole table row (e.g. a join or a subset) gets its own `case class` in `repository/domain/`, still suffixed `Row` but not tied to a table. Example: `CustomerSummaryRow(customerID, displayName, customerType)` for a list endpoint, where `displayName` resolves from whichever detail table applies.

### Queries — the SQL

- `final class FooBarQueries(config: RepositoryConfig)` + companion `object FooBarQueries { val live = ZLayer.derive[FooBarQueries] }`.
- **Resolve the table from config, never hard-code the name:**
  ```scala
  private val frSchema  = Fragment.const(config.schema)
  private val frFooBar  = Fragment.const(config.fooBarTable)
  private val frTable   = frSchema ++ fr0"." ++ frFooBar
  ```
- Keep **one** `frFooBarFields` fragment (the column list) reused by every `SELECT` / `INSERT` / `RETURNING` — the single place column order is defined; it must match the `Row` case class.
- Methods return `TranzactIO[...]` and wrap SQL in `tzio { ... }`. Build SQL with `fr"..."` and the Doobie helpers `whereAnd`, `set`, `orderBy`, `NonEmptyList` for update sets. Insert a whole row with `fr"$fooBarRow"`. Updates that return the new state end with `fr"RETURNING" ++ frFooBarFields` and `.query[FooBarRow].unique`.
- Dynamic update: start the `set` list with `NonEmptyList.of(fr"updated_at = $updatedAt")` and append `optUpdate.map(v => fr"col = $v")` entries, `.flatten` — only the provided fields hit the `SET`.
- Method names are **bare operation verbs** (`get`, `insert`, `update`, `delete`, `upsert`, `is...`) — no entity name (the class already carries it), plural for multi-row. Test-only helpers take a `...Testing` suffix. See [scala.md §7](scala.md) / [§8](scala.md).

### Repository — the ZIO boundary

- `trait FooRepository` (the interface the service depends on) + a private `FooRepositoryImpl` taking `database: DatabaseOps.ServiceOps[Transactor[Task]]`, the `Queries` class(es), `TimeProvider`, and `IDGenerator`.
- Every method:
  1. mints ids (`idGenerator.generateID`, UUIDv7) and timestamps (`timeProvider.instantNow`) — see [IDs & timestamps](#ids--timestamps);
  2. runs its queries inside `database.transactionOrWiden(...)` — see [Transactions](#transactions);
  3. maps the `DbException` failure through one `toServiceError(s"...")` mapper — `RepositoryError` (500) by default, `ConflictError.UniqueConstraintViolation` (409) for a `unique` violation — see [Error handling](#error-handling).
- `val live = ZLayer.derive[FooRepositoryImpl].project[FooRepository](identity)`.
- Naming: methods **carry the entity name** (`getFooByUserID`, `insertFoo`), unlike the bare-verb query methods — [scala.md §1](scala.md) / [§2](scala.md).

## Inputs: never the API request models

A repository method **must not accept a smithy-derived `...PostRequest`/`...PutRequest` domain model.** Those carry HTTP/API vocabulary (`Post`, `Put`, `Request`) and the API's batch/combined grouping — transport concerns that must not leak into the persistence boundary. The precedent is `createOrganization`, which the service calls with **flat params** destructured from `CreateOrganizationPostRequest`; the request type never reaches the repo.

Two shapes are allowed as input, chosen by field count and structure:

1. **Flat params** — the default, like `createOrganization`/`updateOrganization`. Field-heavy is fine (`createOrganization` has 14). Updates use `...OptUpdate` params (`Option`, default `None`); only the provided fields are written.
2. **Repository-owned input models** — when the input is an aggregate with a **nested/repeated child** (a list of contacts) or is **batched**. These are `case class ...Input` defined **inside the repository's companion object**, mirroring nested structures with nested `...Input` classes, using the same refined types as `Row` models but carrying **no** id / `createdAt` / `updatedAt`.

The service maps validated request → input with **Chimney** (`io.scalaland.chimney`; refined types via `iron-chimney`) — usually a one-line `.transformInto[...]` since field names line up.

**An input class exists only to serve a batch (or a repeated child list).** Where a batch form exists, define one input for a **single** element; the batch takes `List[...Input]` and the singular op **reuses the same element class**. Single-only operations (updates, `remove...`) take flat params and get **no** class.

```scala
trait CustomerBookRepository {
  import CustomerBookRepository.*

  def insertCustomerIndividual(organizationID: OrganizationID, insertCustomerIndividualInput: InsertCustomerIndividualInput): IO[ServiceError, CustomerID]
  def insertCustomerIndividuals(organizationID: OrganizationID, insertCustomerIndividualInputs: List[InsertCustomerIndividualInput]): IO[ServiceError, List[CustomerID]]

  def updateCustomerIndividual(organizationID: OrganizationID, customerID: CustomerID, fullNameOptUpdate: Option[CustomerFullName] = None, /* ... */): IO[ServiceError, CustomerIndividualDetailsRow]
}

object CustomerBookRepository {
  case class CustomerEmailEntryInput(email: CustomerEmail, isDefault: Boolean)
  case class InsertCustomerIndividualInput(fullName: CustomerFullName, emails: List[CustomerEmailEntryInput], /* ... */)
}
```

Full rule with ✅/❌: [scala.md §5b Repository input models](scala.md#5b-repository-input-models-decoupling-the-repo-from-the-api-contract).

## Return types

- A repository returns **`Row` models** (or projections), never Doobie/`TranzactIO` types and never a smithy response — the service maps `Row` → smithy.
- `get` returns `Option[...Row]`; a `create`/`update` returns the affected `...Row` (via `RETURNING`); a multi-row read returns `List[...Row]`.
- Inserts that the caller needs to reference later return the generated id(s) (`CustomerID` / `List[CustomerID]`).
- Values holding results are named after the row: `fooBarRow`, `fooBarRowOpt`, `fooBarRows`, and a re-fetched/updated one is qualified (`fooBarRowUpdated`) — [scala.md §9](scala.md).

## Transactions

- Each repository method runs its query (or queries) in `database.transactionOrWiden(...)`.
- **Compose multiple queries into one transaction** by putting them in a single `for`-comprehension inside one `transactionOrWiden` — e.g. insert `customer` then its detail row atomically. If one fails, the whole thing rolls back.
- **Batch atomicity is a deliberate choice.** Looping a singular repository call per item gives **per-item** transactions (one bad item doesn't roll back the rest); wrapping the loop in a single `transactionOrWiden` gives an **all-or-nothing** batch. Pick per feature and state it — e.g. Customer Book batches are per-item (see [customer-book.md](features/customer-book.md)).

## IDs & timestamps

- **Generated in the repository, never the DB.** No `default gen_random_uuid()` / `default now()`.
- IDs: `idGenerator.generateID` yields a UUIDv7; construct the refined id and fail loudly if it somehow doesn't validate:
  ```scala
  customerID <- idGenerator.generateID
    .map(CustomerID.either)
    .flatMap(ZIO.fromEither(_).mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct customerID: [$e]")))
  ```
- Timestamps: one `timeProvider.instantNow` per operation, used for both `CreatedAt` and `UpdatedAt` on insert and to refresh `UpdatedAt` on every mutation.
- **Batch inserts pair each generated id with its input at generation time** — a named tuple built inside the one `ZIO.foreach` (`generateCustomerID.map(customerID => (customerID = customerID, input = input))`), with every downstream row derived from the paired list and the result read as `.map(_.customerID)`. Never generate an id list and `zip` it back onto the inputs, and never read the pair positionally — see [scala.md § Pairing values](scala.md#pairing-values--no-zip-no-_1).
- This is what makes the layer testable: specs mock `TimeProvider`/`IDGenerator` and assert exact `createdAt`/`updatedAt` and ids (see [Testing](#testing)).

## Error handling

- The `Queries`/Tranzactio failure channel is `DbException`; the repository maps it to a domain error and nothing else escapes. Route **every** `mapError` through one private mapper so both the generic and the conflict cases are handled in one place:
  ```scala
  .mapError(toServiceError(s"Failed to create customer with ID: [$customerID]"))
  ```
- The generic case is `ServiceError.InternalServerError.RepositoryError(errorMessage, dbException)` (500): the message names the operation and the key id; the original `DbException` is kept as `underlying` (specs assert `serviceError.underlying.value shouldBe a[DbException]`).
- **DB-enforced conflicts get a distinct 409, mapped *in* the repository.** A `unique` violation arrives as a `DbException` wrapping a Postgres `PSQLException` with SQL state `23505`; the mapper detects it (via `PSQLState.UNIQUE_VIOLATION`), reads the **violated constraint name** off `getServerErrorMessage.getConstraint`, and produces `ServiceError.ConflictError.UniqueConstraintViolation(message, dbException)` — a `ConflictError` (409, distinct from `RepositoryError`) whose `message` states in plain language which rule was broken. This is why the DB constraints are **named** (`uq_<table>_<columns>`, see [postgres.md § constraint naming](postgres.md#constraint-naming--conflict-mapping)) — the mapper `match`es on the name to pick the message, and an unmapped name falls back to a generic "unique constraint was violated: [name]". The repository does **not** pre-check existence with a `SELECT` (racy); it lets the DB enforce and translates the failure.
  ```scala
  private def toServiceError(errorMessage: String)(dbException: DbException): ServiceError =
    findUniqueConstraintViolated(dbException) match
      case Some(constraint) => ServiceError.ConflictError.UniqueConstraintViolation(uniqueConstraintViolationMessage(constraint), dbException)
      case None             => ServiceError.InternalServerError.RepositoryError(errorMessage, dbException)
  ```
- `ServiceError.ConflictError` maps to a 409 (`smithy.Conflict()` / `TapirServerError.ConflictError`) in `HttpErrorHandler` — so the operation's smithy contract must declare `Conflict` as an error for the response to render as 409 rather than 500.

## Type mappings

Given instances in `repository/queries/queries.scala` derive Doobie `Read`/`Write`/`Get`/`Put`/`Meta` for **all** Iron refined types (via `RefinedType.Mirror`) and Scala `enum`s (`Get/Put.deriveEnumString` → the case name as `text`), so a new refined type or enum needs **no** per-type codec. Exceptions:

- A refined type over a **non-standard base** still needs a `Meta` for that base.
- **`jsonb` columns** (a `List[...]` field) need one **explicitly named** given pair per list type — a jsoniter `JsonValueCodec` plus a `Meta` built via `jsonbMeta`. Anonymous givens of the same shape collide and shadow each other, so name them (`customerEmailEntryInputsMeta: Meta[List[CustomerEmailEntryInput]]`). The list element is the repository-owned `...Input` model (see [Row](#row--the-table-shape)), so these givens live in the **feature's `Queries` class** next to the `Row` they serve — that file must `import <Feature>Repository.*` for the `Input` types and `import io.github.iltotore.iron.jsoniter.given` so the jsoniter macro can find the Iron base codecs (missing this import surfaces as a macro `StackOverflowError`, not a plain "no given"). See [postgres.md § Type mappings](postgres.md#type-mappings-repositoryqueriesqueriesscala).

## Configuration

Table names are **config, never string literals** — [postgres.md § Configuration](postgres.md#configuration--table-names-are-config-not-constants). Adding a table touches, at minimum: `RepositoryConfig` (`<entity>Table` field + `allTableNames`), **both** `application.conf`s (`core` main **and** `it` test), the migration, and the three code layers. The `it` copy is easy to forget and its omission only fails at acceptance-test time.

## Layer wiring

- `Queries`: `val live = ZLayer.derive[FooBarQueries]`.
- `Repository`: `val live = ZLayer.derive[FooRepositoryImpl].project[FooRepository](identity)` — the `.project` narrows the impl to the trait so downstream layers depend only on the interface.
- Wire the `Queries` and `Repository` `live` layers into the app's layer graph alongside `TimeProvider`, `IDGenerator`, and the transactor.

## Testing — repository integration specs with Testcontainers

Repository (and `Queries`) behaviour is proven against a **real PostgreSQL** run by [Testcontainers](https://testcontainers.com/), never a mocked/H2 DB — the whole point is to exercise the actual SQL, the Flyway schema, the jsonb/enum codecs, and the DB-enforced constraints. These are the `it` specs under `backend/gateway/core/src/test/scala/io/mesazon/gateway/it/<Entity>RepositorySpec.scala`. `OrganizationManagementRepositorySpec` is the reference to copy.

> "Integration spec" here = repository ⇄ real DB, in the **`gateway-core`** test sources. It is distinct from the black-box HTTP **acceptance** tests in the separate `gateway-it` module ([acceptance-tests.md](acceptance-tests.md)), which spin up the whole app.

### The container stack

Each suite brings up a Docker Compose stack (`src/test/resources/compose/repository.yaml`): a `postgres` service and a one-shot `flyway` service that runs `migrate` against it, so **the schema under test is the real Flyway migrations**, not a test-only DDL.

- Postgres initialises from `backend/schemas/local/postgres/init.sql` (mounted at `/docker-entrypoint-initdb.d`) — this creates the schema and the `local_test_user` role **granted `TRUNCATE`**, which is what lets `beforeEach` clean tables between tests. A new table needs no change here; a new **role/grant** would.
- Flyway applies every `V*.sql` into schema `local_schema`. If a migration is malformed or the schema drifts from the row/queries code, the suite fails at container start — a fast, real signal.

### The base traits

A spec extends **`ZWordSpecBase, RepositoryArbitraries, DockerComposeBase`**:

- **`DockerComposeBase`** (wraps testcontainers' `TestContainerForAll`) — starts the compose stack **once per suite** and tears it down after. Override `dockerComposeFile` (point at `./src/test/resources/compose/repository.yaml`) and `exposedServices` (`PostgreSQLTestClient.ExposedServices`, i.e. postgres:5432). Because the stack is per-suite and shared across that suite's tests, tests must not depend on each other's data — hence the `beforeEach` truncation.
- **`ZWordSpecBase`** = `WordSpecBase` + scalamock ZIO stubs + `ZIOTestOps`. It gives you:
  - `arbitrarySample[T]` / `arbitrarySample[T](n)` — a random `T` from its `Arbitrary` (feature arbitraries come from the mixed-in traits).
  - ZIO run-ops as extension methods: **`.zioValue`** runs an `IO` and returns `A` (throwing the fiber failure on error), **`.zioError`** returns the `E` channel, `.zioEither`/`.zioCause` as needed. These bridge ZIO into synchronous ScalaTest assertions.
  - scalamock helpers `returningZIO` / `failingZIO` / `dyingZIO` for stubbing effectful dependencies.
- **`RepositoryArbitraries`** — `given Arbitrary[<Entity>Row]` for every row (`Gen.resultOf(<Entity>Row.apply)`), composed with the domain arbitraries. **Add your new rows here** so `arbitrarySample[<Entity>Row]` works. Repository `...Input` models get their arbitraries here too. When a `Row`/`Input` has a `List[...Input]` field, give that list an **explicit** `given Arbitrary[List[...Input]]` and **define it above** the `Row` that uses it — Scala 3 won't backtrack from ScalaCheck's generic `arbContainer` to a later given, and the generic Iron `Arbitrary` given then fails looking for a `RefinedType.Mirror`. The explicit list arbitrary is also where you enforce list invariants (e.g. exactly one `isDefault = true`).

### `PostgreSQLTestClient`

The `backend/postgresql-test` module provides `PostgreSQLTestClient`, the test-only door to the DB:

- `PostgreSQLTestClient.live` builds a datasource (as `local_test_user`) → Tranzactio `Database` → the client. Its `databaseLive` is the `Database` layer you feed into the repository/queries under test.
- `checkIfTableExists(schema, table)` — used in `beforeAll` (wrapped in `eventually`) to wait until Flyway has finished before any test runs.
- `truncateTable(schema, table)` — `TRUNCATE … CASCADE` in `beforeEach`, once per table the suite touches.
- `executeQuery(connectionIO | tranzactIO)` — run a `Queries` method (or raw `ConnectionIO`) directly, for **arranging** rows and for **`...Testing`** read-backs, outside the repository under test.

### The `TestContext` pattern

Every test runs in `new TestContext {}` — an inner `trait` that wires a fresh graph per test:

- A hand-written `RepositoryConfig(schema = "local_schema", <entity>Table = "…", …)` naming exactly the tables this suite uses (kept in sync with the migration, same as production config).
- `postgresClient` from `PostgreSQLTestClient.live` + the container-resolved `PostgreSQLTestClientConfig`.
- Each `Queries` and the `Repository` under test, provided via `ZIO.service[…].provide(…live, postgresClient.databaseLive, ZLayer.succeed(config), …)`.
- **Mocked `TimeProvider` and `IDGenerator`** (`mock[TimeProvider]` / `mock[IDGenerator]`) — because ids and timestamps are minted *in the repository*, mocking them makes the created `Row` fully deterministic, so you can assert the exact `customerID`/`createdAt`/`updatedAt`. Set expectations with `inSequence(( () => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(), …)`; a batch/multi-insert expects **one `instantNow`/`generateID` per item, in order**.

### What to assert, and the shape of a test

- **Arrange** with `postgresClient.executeQuery(queries.insert…(arbitrarySample[<Entity>Row]))` (bypass the repository so the setup isn't the thing under test), or drive the repository's own create for round-trips.
- **Act** through the repository method under test with `.zioValue` (or `.zioError` for the failure path).
- **Assert** by reading the table back with a `…Testing` query helper and comparing **whole `Row`s** (`should contain theSameElementsAs`, `have size`), and for repo-minted rows assert the injected `createdAt`/`updatedAt`/ids.
- **Cover**: happy path; `None`/empty on missing; **DB-enforced failures** — every `unique` constraint gets its own section asserting the mapped `ServiceError.ConflictError.UniqueConstraintViolation` (`serviceError shouldBe a[...UniqueConstraintViolation]`, `serviceError.message shouldBe "<the clear message>"`, `underlying.value shouldBe a[DbException]`), that the table is left unchanged (the losing row rolled back), and, for a multi-statement method, that a mid-transaction failure **rolls back** the earlier writes (assert the sibling table is empty). Trigger each constraint in isolation — e.g. for the contact email-vs-phone uniques, hold one column equal and keep the other distinct so only the intended constraint fires.
- **Isolate**: each test samples its own data with `arbitrarySample[…]` and `.copy(…)`s only the field under test — no shared "valid X" fixtures ([scala.md § Testing](scala.md)).

Where an invariant is enforced in the **service** rather than the DB, add a repository integration test that guards it directly against Postgres (e.g. Customer Book asserts no `customer_id` is in both detail tables — [customer-book.md](features/customer-book.md)). Feature-level behaviour lives in acceptance tests — [acceptance-tests.md](acceptance-tests.md).

### Running them

They need Docker. `sbt "gateway-core/testOnly *<Entity>RepositorySpec"` runs one; the compose stack starts and stops around the suite.

## Checklists

Adding a table and adding a column both walk this same stack; the step-by-step (with the traps — positional row order, the `it` `application.conf`, optional-param defaults hiding missed call sites) lives in [postgres.md § Adding a table](postgres.md#adding-a-table--checklist) and [§ Adding a column](postgres.md#adding-a-column-to-an-existing-table--checklist).
