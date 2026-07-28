# Repository layer — persistence architecture

This document defines **project-agnostic standards for a repository layer** — the boundary between a service layer and a relational database, written by hand (no ORM) so that SQL and code are kept in lockstep deliberately. It is written around a Scala stack (a functional JDBC layer wrapped for an effect type, over PostgreSQL) but the architecture applies to any project that hand-writes its persistence as a layered `Row → Queries → Repository` stack with an effectful transaction boundary.

Use it for the concerns that stay true regardless of the exact database driver or effect library:

- the three-layer split and each layer's single responsibility;
- what a repository accepts as input (never the API request models) and returns;
- transactions, application-side id/timestamp generation, and error mapping;
- layer wiring and how the layer is tested against a real database.

This document owns the **architecture and practices** of the layer, and the **naming** of every identifier in it (absorbed here from the general Scala standard because these names are meaningful only in the persistence context). Two neighbours own the halves it depends on:

- [postgres-new.md](stack/postgres-new.md) — the schema (migrations, table/column naming, types, soft-delete) the code must stay in lockstep with, plus the type-mapping and config mechanics.
- [scala-new.md](stack/scala-new.md) — the general naming/immutability/test rules these specialize; [iron-new.md](stack/iron-new.md) — the refined newtypes a `Row`'s fields are typed with.

## Table of contents

- [Scope](#scope)
- [The three layers](#the-three-layers)
  - [Row — the table shape](#row--the-table-shape)
  - [Queries — the SQL](#queries--the-sql)
  - [Repository — the effect boundary](#repository--the-effect-boundary)
- [Naming the layer](#naming-the-layer)
  - [Repository classes and methods](#repository-classes-and-methods)
  - [Query classes and methods](#query-classes-and-methods)
  - [Row models and their parameters](#row-models-and-their-parameters)
  - [Input models](#input-models)
  - [Values holding repository results](#values-holding-repository-results)
- [Inputs: never the API request models](#inputs-never-the-api-request-models)
- [Return types](#return-types)
- [Transactions](#transactions)
- [IDs & timestamps](#ids--timestamps)
- [Error handling](#error-handling)
- [Type mappings](#type-mappings)
- [Configuration](#configuration)
- [Layer wiring](#layer-wiring)
- [Testing — repository integration specs against a real database](#testing--repository-integration-specs-against-a-real-database)
- [Checklists](#checklists)

## Scope

This document owns how the persistence code is **structured, named, and tested**. It does not own the schema itself — the DDL, table/column naming, column types, constraint naming, and soft-delete rules the code mirrors live in [postgres-new.md](stack/postgres-new.md). The names on both sides must match exactly (a query class references columns by literal string), so a rename is a change in the migration **and** the row/queries/config together — the same discipline the codebase's rename rule applies to docs.

Request flow: **`Service` → `Repository` (effect boundary) → `Queries` (SQL) → database**, with `Row` models as the currency in and `Row`/projections out.

Concretely in this codebase the stack is **Doobie** (`org.typelevel.doobie`) wrapped in **Tranzactio** (`io.github.gaelrenoux.tranzactio`) for ZIO, over PostgreSQL, with code under `backend/gateway/core/.../repository/`.

## The three layers

For an entity `Foo` backed by table `foo_bar`, persistence is three files under one `repository/` source root:

| Layer | File | Type | Responsibility |
| --- | --- | --- | --- |
| Row | `repository/domain/FooBarRow.scala` | `case class FooBarRow` | The table's shape — one field per column, **in column order**. |
| Queries | `repository/queries/FooBarQueries.scala` | `final class FooBarQueries(config)` | The SQL. Returns the driver's effect type. Knows columns, not transactions or errors. |
| Repository | `repository/FooRepository.scala` | `trait FooRepository` + `FooRepositoryImpl` | The effect boundary. Runs queries in transactions, generates ids/timestamps, maps errors. |

Each layer only knows the one below it. The service talks to the `Repository` trait and never sees the driver types or a `Queries` class.

### Row — the table shape

- `case class FooBarRow(...)` whose fields are refined types / enums / audit timestamps, in **the same order as the column list** in the queries fields fragment — whole-row writes are positional, so order is load-bearing, not cosmetic.
- Nullable columns are `Option[...]`; the presence/absence of `not null` in the migration must match. A mismatch is a runtime decode failure, not a compile error.
- A `Row` represents a **persisted row you read back or write whole** — it carries the generated id and the created/updated timestamps. It is **not** an insert input (those are minted in the repository — see [Inputs](#inputs-never-the-api-request-models)).
- A repeated/embedded column that stores a list of small records is typed with the repository-owned **`...Input`** element model, **never** an API request model — request vocabulary must not leak even into the persisted `Row` shape or its codec. So the whole stack — input → `Row` field → embedded codec — speaks one type and the repository needs no `Input → Request` conversion.

**Projections** — a read that is not a whole table row (a join or a subset) gets its own `case class` in `repository/domain/`, still suffixed `Row` but not tied to a table. Example: a `CustomerSummaryRow(customerID, displayName, customerType)` for a list endpoint, where `displayName` resolves from whichever detail source applies.

### Queries — the SQL

- `final class FooBarQueries(config)` + companion providing a `live` layer.
- **Resolve the table from config, never hard-code the name:**
  ```scala
  private val frSchema  = Fragment.const(config.schema)
  private val frFooBar  = Fragment.const(config.fooBarTable)
  private val frTable   = frSchema ++ fr0"." ++ frFooBar
  ```
- Keep **one** `frFooBarFields` fragment (the column list) reused by every `SELECT` / `INSERT` / `RETURNING` — the single place column order is defined; it must match the `Row` case class.
- Methods return the driver's effect type and wrap SQL in the driver's block. Build SQL with fragment interpolation and the driver's helpers for `WHERE`/`SET`/`ORDER BY`. Insert a whole row by interpolating the row. Updates that return the new state end with `RETURNING` + the fields fragment.
- Dynamic update: start the `SET` list with the always-updated timestamp and append only the provided optional fields, so only supplied columns hit the `SET`.
- Method names are **bare operation verbs** (`get`, `insert`, `update`, `delete`, `upsert`, `is...`) — no entity name (the class already carries it), plural for multi-row. Test-only helpers take a `...Testing` suffix.

### Repository — the effect boundary

- `trait FooRepository` (the interface the service depends on) + a private `FooRepositoryImpl` taking the database handle, the `Queries` class(es), a time provider, and an id generator.
- Every method:
  1. mints ids and timestamps (see [IDs & timestamps](#ids--timestamps));
  2. runs its queries inside one transaction (see [Transactions](#transactions));
  3. maps the driver failure through one error mapper (see [Error handling](#error-handling)).
- Provide a `live` layer that narrows the impl to the trait so downstream code depends only on the interface.

## Naming the layer

> This section absorbs the repository-naming rules that were previously stated in the general Scala standard. They live here because every one of them is specific to the persistence layer's roles.

### Repository classes and methods

- **Class** — named after the entity/feature it manages, suffixed `Repository`. ✅ `UserOtpRepository`, `OrganizationRepository` ❌ `UserOtpQueries`, `UserOtpDao`.
- **Methods carry the entity name** (unlike the bare-verb query methods) and are prefixed with the operation verb (`get`, `insert`, `create`, `upsert`, `is`, `update`, `delete`, `getAndIncrease`, …). Plural for methods returning multiple entities. Use a `ByUserID`-style suffix when several methods fetch the same entity by different parameters.
  - ✅ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`, `isOrganizationSlugExists`, `createOrganization`
  - ❌ `insert`, `update`, `delete`, `getAll`, `getByUserID` (those are query-class method names, not repository ones)
- **Method parameters** use the recommended name convention: identifier and enum parameters follow it; the owner prefix may be omitted when the repository is already named after the entity it manages; include `Opt` when optional; include the behavior when the parameter drives a specific action. A repository method **never takes an API `...PostRequest`/`...PutRequest` model** (see [Inputs](#inputs-never-the-api-request-models)) — pass flat params or a repository-owned input, named after its type in lower-camelCase (the full type name, never `request`/`req`/`input`).
  - ✅ `userID`, `organizationID`, `addressLine1OptUpdate`, `organizationStageOptUpdate`, `phoneNumber`, `insertCustomerBusinessInput: InsertCustomerBusinessInput`
  - ❌ `userId`, `id`, `addressLine1UpdateOpt`, `stageOptUpdate`, `insertCustomerBusinessPostRequest: InsertCustomerBusinessPostRequest`

### Query classes and methods

- **Class** — named after the **table** it manages, suffixed `Queries`. ✅ `UserOtpQueries`, `OrganizationQueries` ❌ `UserOtpRepository`.
- **Methods** are **bare operation verbs** with **no entity name** (the class already carries it): `getByUserID`, `insert`, `update`, `delete`, `getAll`, `isSlugExists`. Plural for multi-row; `ByUserID`-style disambiguation as needed.
  - ❌ `getUserOtpByUserID`, `insertUserOtp`, `getAllUserOtps`, `isOrganizationSlugExists` (those repeat the entity name)
- **Test-only query methods** follow the same rules, add `All` after the verb when returning all entities, and take a `Testing` suffix. ✅ `getAllTesting`, `deleteTesting`, `getByUserIDTesting` ❌ `getAllUserOtpsTesting`.

### Row models and their parameters

- A row model is named after the entity it represents, suffixed `Row` (`UserOtpRow`, `OrganizationRow`), never the bare entity name.
- Row parameters follow the same convention as repository method parameters: identifier/enum parameters use the recommended naming; the owner prefix may be dropped when the model already names the entity; `Option[...]` for nullable columns.
  - ✅ `userID`, `organizationID`, `addressLine1Opt`, `organizationStage`, `phoneNumber` ❌ `userId`, `id`

### Input models

- When a repository operation needs a whole aggregate as input (many fields, or a nested/repeated child such as a list of contacts), the repository defines its **own input case classes** rather than accepting the API request models. The service maps the validated request → the input with a field-mapping library (here Chimney), usually a one-line transform since field names line up.
- **Naming & placement.** Suffix `Input` (`InsertCustomerBusinessInput`, `CustomerEmailEntryInput`). Define them **inside the repository's companion object**, mirroring nested request structures with nested `...Input` classes — never reach back into the API request models from the repo. Input fields use the same refined-type conventions as `Row` models, but an input is **not** a `Row`: it carries no generated id and no created/updated timestamps (the repository mints those).
- **An input class exists only to serve a batch (or a repeated child list).** If an operation has a batch form, define one input describing a **single** element; the batch takes `List[ThatInput]` and the **singular op reuses the same element class**. A combined op reuses those same element lists.
- **Never create a class for a single-only operation.** An operation with no batch form (updates, `remove…`) takes **flat params** — `…OptUpdate` for updates, bare ids/lists for removes. Do not invent an `Update…Input`/`Remove…Input`.
- **`Input` reaches the `Row`, not just the method.** When a `Row`'s repeated/embedded column stores a list of these small records, type that field with the `…Input` element (`emails: List[CustomerEmailEntryInput]`), not the API request — one type flows input → `Row` → codec, so the repository never converts `Input → Request`.
- ✅ `object CustomerBookRepository { case class InsertCustomerIndividualInput(…); case class CustomerEmailEntryInput(…) }`; service does `request.transformInto[InsertCustomerIndividualInput]`
- ❌ an `Input` when there is no batch form; a `Row` used as insert input; the repo importing an API request type; an `Input` class defined as a top-level file instead of in the companion

### Values holding repository results

- A repository returns `Row` models, so a value bound to a repository result is named after the row it holds — the entity name suffixed `Row` (or `Rows` for a list). This holds in both service code and tests.
- When a result is `Option[…Row]` keep it optional-named with `RowOpt`; a value unwrapped from the option is a plain `…Row`.
- When the same row appears twice in one scope (the fetched row and the updated row), qualify the second (`userDetailsRowUpdated`), never drop the `Row`.
- ✅ `userDetailsRow <- ...getUserDetails(userID).someOrFail(...)`, `userOtpRowOpt <- ...getUserOtpByUserID(...)`, `userDetailsRowUpdated <- ...updateUserDetails(...)`
- ❌ `userDetails <- ...getUserDetails(...)`, `userOtp <- ...`

## Inputs: never the API request models

A repository method **must not accept an API-derived `...PostRequest`/`...PutRequest` domain model.** Those carry transport vocabulary (`Post`, `Put`, `Request`) and the API's batch/combined grouping — concerns that must not leak into the persistence boundary. The precedent is `createOrganization`, which the service calls with **flat params** destructured from `CreateOrganizationPostRequest`; the request type never reaches the repo.

Two shapes are allowed as input, chosen by field count and structure:

1. **Flat params** — the default. Field-heavy is fine (`createOrganization` has 14). Updates use `...OptUpdate` params (`Option`, default `None`); only the provided fields are written.
2. **Repository-owned input models** — when the input is an aggregate with a **nested/repeated child** or is **batched**. `case class ...Input` defined **inside the repository's companion object**, mirroring nested structures with nested `...Input` classes, using the same refined types as `Row` models but carrying **no** id / timestamps.

The service maps validated request → input with a field-mapping library — usually a one-line transform since field names line up.

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

## Return types

- A repository returns **`Row` models** (or projections), never the driver/effect types and never an API response — the service maps `Row` → response.
- `get` returns `Option[...Row]`; a `create`/`update` returns the affected `...Row` (via `RETURNING`); a multi-row read returns `List[...Row]`.
- Inserts that the caller needs to reference later return the generated id(s).
- Values holding results are named after the row (see [Values holding repository results](#values-holding-repository-results)).

## Transactions

- Each repository method runs its query (or queries) in one transaction boundary.
- **Compose multiple queries into one transaction** by putting them in a single `for`-comprehension inside one transaction block — e.g. insert a parent then its detail row atomically. If one fails, the whole thing rolls back.
- **Batch atomicity is a deliberate choice.** Looping a singular repository call per item gives **per-item** transactions (one bad item doesn't roll back the rest); wrapping the loop in a single transaction gives an **all-or-nothing** batch. Pick per feature and state it.

## IDs & timestamps

- **Generated in the repository, never the DB.** No `default gen_random_uuid()` / `default now()`.
- IDs: generate an application-side identifier (here a UUIDv7 via the id generator); construct the refined id and fail loudly if it somehow doesn't validate:
  ```scala
  customerID <- idGenerator.generateID
    .map(CustomerID.either)
    .flatMap(ZIO.fromEither(_).mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to construct customerID: [$e]")))
  ```
- Timestamps: one "now" per operation, used for both created and updated on insert and to refresh updated on every mutation.
- **Batch inserts pair each generated id with its input at generation time** — a named tuple built inside the one traversal, with every downstream row derived from the paired list and the result read back by name. Never generate an id list and `zip` it back onto the inputs, and never read the pair positionally — this is the persistence-layer application of the general no-`zip`/no-positional-tuple rule in [scala-new.md § Make relationships explicit](stack/scala-new.md#make-relationships-explicit):
  ```scala
  customerIDsWithInputs <- ZIO.foreach(insertCustomerIndividualInputs)(input =>
    generateCustomerID.map(customerID => (customerID = customerID, input = input))
  )
  detailsRows = customerIDsWithInputs.map(customerIDWithInput =>
    buildCustomerIndividualDetailsRow(
      organizationID,
      customerIDWithInput.customerID,
      customerIDWithInput.input,
      instantNow,
    )
  )
  // …
  } yield customerIDsWithInputs.map(_.customerID)
  ```
  ❌ `customerIDs <- ZIO.foreach(inputs)(_ => generateCustomerID)` followed by `inputs.zip(customerIDs)`; `pairs.map(_._1)`.
- This is what makes the layer testable: specs mock the time provider / id generator and assert exact created/updated timestamps and ids.

## Error handling

- The query failure channel is the driver's exception type; the repository maps it to a domain error and nothing else escapes. Route **every** error mapping through one private mapper so both the generic and the conflict cases are handled in one place:
  ```scala
  .mapError(toServiceError(s"Failed to create customer with ID: [$customerID]"))
  ```
- The generic case is a repository error (500): the message names the operation and the key id; the original driver exception is kept as `underlying` (specs assert on it).
- **DB-enforced conflicts get a distinct 409, mapped *in* the repository.** A `unique` violation arrives as a driver exception wrapping the database's unique-violation error (SQL state `23505`); the mapper detects it, reads the **violated constraint name** off the error, and produces a conflict error (409, distinct from the generic 500) whose message states in plain language which rule was broken. This is why DB constraints are **named** (see [postgres-new.md § constraint naming](stack/postgres-new.md)) — the mapper `match`es on the name to pick the message, and an unmapped name falls back to a generic message. The repository does **not** pre-check existence with a `SELECT` (racy); it lets the DB enforce and translates the failure.
  ```scala
  private def toServiceError(errorMessage: String)(dbException: DbException): ServiceError =
    findUniqueConstraintViolated(dbException) match
      case Some(constraint) => ServiceError.ConflictError.UniqueConstraintViolation(uniqueConstraintViolationMessage(constraint), dbException)
      case None             => ServiceError.InternalServerError.RepositoryError(errorMessage, dbException)
  ```
- A conflict error maps to a 409 in the shared HTTP error handler — so the operation's API contract must declare `Conflict` as an error for the response to render as 409 rather than 500 (see [smithy-new.md](stack/smithy-new.md)).

## Type mappings

Derive the driver's read/write codecs for **all** refined types and enums generically (enum case name stored as text), so a new refined type or enum needs **no** per-type codec. Exceptions:

- A refined type over a **non-standard base** still needs a base codec for that base.
- **Embedded/JSON columns** (a `List[...]` field) need one **explicitly named** codec pair per list type. Anonymous codecs of the same shape collide and shadow each other, so name them. The list element is the repository-owned `...Input` model, so these codecs live in the **feature's `Queries` class** next to the `Row` they serve — that file must import the `Input` types and the refined-base codec support (in this codebase, a missing such import surfaces as a macro `StackOverflowError`, not a plain "no given"). See [postgres-new.md § Type mappings](stack/postgres-new.md).

## Configuration

Table names are **config, never string literals** — see [postgres-new.md § Configuration](stack/postgres-new.md). Adding a table touches, at minimum: the table-names config (a field + the all-names list), **every** environment's config copy (including the separate one the acceptance/integration tests carry — easy to forget, and its omission only fails at test time), the migration, and the three code layers.

## Layer wiring

- `Queries`: a `live` layer deriving the class from config.
- `Repository`: a `live` layer that narrows the impl to the trait so downstream layers depend only on the interface.
- Wire the `Queries` and `Repository` `live` layers into the app's dependency graph alongside the time provider, id generator, and the transactor/database handle.

## Testing — repository integration specs against a real database

Repository (and `Queries`) behaviour is proven against a **real database** run in a container, never a mocked/in-memory one — the whole point is to exercise the actual SQL, the real schema/migrations, the codecs, and the DB-enforced constraints. This is the repository-specific recipe; the container-base/one-spec-per-component shape shared with non-repository client specs is in [integration-tests-new.md](integration-tests-new.md).

- **Container stack.** Each suite brings up the database plus a one-shot migration step, so the schema under test is the **real migrations**, not test-only DDL. If a migration is malformed or the schema drifts from the row/queries code, the suite fails at container start — a fast, real signal. The DB is initialised with a test role granted `TRUNCATE` so tests can clean tables between runs.
- **Fresh graph per test.** Each test opens a fresh context that wires a hand-written table-names config (naming exactly the tables the suite uses), the test DB client, each `Queries`, and the `Repository` under test. **Mock the time provider and id generator** — because ids and timestamps are minted *in the repository*, mocking them makes the created `Row` fully deterministic, so you can assert the exact id/created/updated values. A batch/multi-insert expects **one now / one id per item, in order**.
- **What to assert.** Arrange rows by inserting via the query layer directly (bypass the repository so the setup isn't the thing under test), or drive the repository's own create for round-trips. Act through the repository method with the run-op that expects success (or the failure channel for the error path). Assert by reading the table back with a `...Testing` helper and comparing **whole `Row`s**, and assert the injected ids/timestamps. **Cover**: happy path; empty/`None` on missing; **DB-enforced failures** — every `unique` constraint gets its own section asserting the mapped conflict error (type, exact message, underlying exception present), that the table is left unchanged (the losing row rolled back), and, for a multi-statement method, that a mid-transaction failure **rolls back** the earlier writes. Trigger each constraint in isolation — hold one column equal and keep the others distinct so only the intended constraint fires.
- **Isolate.** Each test samples its own data and copies only the field under test — no shared "valid X" fixtures ([scala-new.md § Testing standards](stack/scala-new.md#testing-standards)). Reset (truncate) between tests so specs stay order-insensitive.

Where an invariant is enforced in the **service** rather than the DB, add a repository integration test that guards it directly against the database. Feature-level behaviour lives in acceptance tests — [acceptance-tests-new.md](acceptance-tests-new.md).

## Checklists

Adding a table and adding a column both walk this same stack; the step-by-step (with the traps — positional row order, the extra test-config copy, optional-param defaults hiding missed call sites) lives in [postgres-new.md § Adding a table](stack/postgres-new.md) and § Adding a column.
