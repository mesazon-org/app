# Functional JDBC (Doobie)

Project-agnostic standards for a purely-functional, typed SQL library over JDBC in a typed effect system: composing parameterized query fragments, deriving codecs for domain types, and running queries inside a transactional effect boundary. Written around [Doobie](https://tpolecat.github.io/doobie/) but applies to any library with the same shape (fragment-based query building, typed row codecs, an effect-wrapped connection).

Not owned here: schema/DDL naming and typing ([postgres-new.md](postgres-new.md)); the Row→Queries→Repository layer architecture, transaction-as-a-unit composition, error-mapping policy, and the testing harness ([repository-new.md](../repository-new.md)); refined-type naming ([iron-new.md](iron-new.md)); general Scala naming ([scala-new.md](scala-new.md)).

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to this library's usage. Concrete values for this codebase: [doobie-project.md](doobie-project.md).

## Fragment composition

- Build SQL as composable parameterized fragments, never by string-concatenating a value into SQL text — a value goes in through fragment interpolation (parameterized/bound), never through the identifier-splice mechanism.
- The identifier-splice mechanism (splicing raw, unparameterized SQL text into a fragment) is for **trusted, non-user-controlled identifiers only** — a table/column/schema name sourced from config. Never use it for a value that could originate from a caller.
- Prefer the library's built-in fragment combinators (a dynamic `WHERE ... AND ...`, a dynamic `SET`, an `ORDER BY`, an `IN (...)` over a non-empty value list) over hand-building the equivalent SQL text — they get parenthesization, `NULL`-safety, and empty-list handling right in one place.
- Qualify a table reference from its parts (schema fragment + separator + table-name fragment) rather than formatting one interpolated string, so schema and table stay independently swappable from config.

## Reusable field list + positional row codec

- Define **one** column-list fragment per table/query shape, reused by every `SELECT`/`INSERT`/`RETURNING` against it. This is the single place column order is defined.
- A whole-row `INSERT` binds through a derived, **positional** row-to-parameters codec — the codec writes fields in **declaration order**, not by name. This makes the row type's field order load-bearing: it must match the column-list fragment's order exactly, or values silently land in the wrong column with no compile error. (The schema-side half of this obligation — row field order must equal migration column order — belongs to the schema doc.)
- A query shape that is a *typed view* over a shared/discriminated table (selecting a type-specific column subset) cannot use the whole-row positional codec — build its parameter list explicitly, field by field, instead.

## Codec derivation for domain types

- Derive the row-codec typeclasses for a refined/newtype wrapper **generically**, from the wrapper's mirror/reflection of its underlying representation — so introducing a new refined type needs no per-type codec written by hand. Only a refined type over a genuinely new *base* representation needs its own base-level codec.
- Derive an enum's codec generically from its case name (stored as text), not per-enum, matching the schema's enum-as-text convention.
- A column backed by a semi-structured/document type (e.g. `jsonb`) needs an **explicitly named** codec given for its element type — an anonymous/derived-name codec of that shape collides with another anonymous codec of the same shape and silently shadows it. Name every such codec explicitly, and keep it defined next to the query code that owns the column.
- A derivation macro for refined-type codecs may need the refinement library's own codec-integration import in scope to see the wrapped type's base codec — omitting it can fail as an unhelpful low-level macro error (e.g. a stack overflow) rather than a clear compile error. Treat "derivation blows up unhelpfully" as a signal to check for a missing integration import before assuming the type is unsupported.

## Building dynamic queries

- A dynamic, conditionally-present `SET` clause is a non-empty list of mandatory assignments concatenated with the flattened `Some` values of a list of `Option[assignment]` — not a mutable builder or hand-joined string.
- Prefer the combinator for "one of N values" (`IN`) over an `OR`-chain of equality fragments once the caller passes more than one id/value.
- An upsert states its conflict target and its update assignments explicitly (including referencing the attempted-insert's own values where the update should overwrite), rather than doing a pre-check `SELECT` then branching in application code — let the database enforce the conflict.

## Effect integration

- Run every terminal query operation inside the project's transactional effect wrapper — never escape to the raw connection/IO type outside that boundary.
- When multiple query calls must succeed or fail together as one unit, compose them inside a single effect sequence (e.g. one `for`-comprehension) passed to **one** transaction boundary call — not as separate, independently-committed calls.
- Map a transaction failure to the project's own error type at the transaction boundary, not inside the query layer — the query layer returns the library's native result/effect type; only the layer that owns error semantics translates it.
- A unique-constraint violation surfaces from the driver as a structured database exception carrying the violated constraint's name and a standard SQL state for "unique violation" — a repository-layer mapper matches on that, not on parsing a driver error message string.

## Connection/pool wiring

- Build the connection pool as a scoped, released resource (acquire on startup, release on shutdown), not a bare eagerly-constructed value — a pool that isn't explicitly released leaks connections across restarts/tests.
- Attach a log handler that routes the library's structured per-query log events (success, execution failure, result-processing failure) into the project's own structured logging — never leave query logging on its default (usually stdout-print) behavior in a service that has its own logging pipeline.

## Testing

- Expose a minimal "run an arbitrary query against the transactional wrapper" escape hatch in the test harness, kept separate from and never used by production query code — it exists purely so tests can assert on state the production query surface doesn't expose a method for.
- A test-only connection/pool wiring may use a simpler, non-pooling datasource than production's pooled one — pooling is a production concern, not a correctness one.
