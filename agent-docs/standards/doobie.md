# Functional JDBC (Doobie)

Reusable functional JDBC rules. Related: [schema](postgres.md), [repository](../features/flow/04-repository.md), [database runtime](../project/database-runtime.md), [newtypes](iron.md), [Scala](scala.md).

## Fragment composition

- Parameterize/bind every value; never concatenate it into SQL or identifier splices.
- Raw identifier splices accept only trusted config-owned schema/table/column names.
- Use fragment combinators for dynamic `WHERE`/`SET`/`ORDER BY`/non-empty `IN`.
- Compose table reference from schema + separator + table fragments.

## Reusable field list + positional row codec

- One column-list fragment per table/query shape; reuse for all `SELECT`/`INSERT`/`RETURNING`.
- Whole-row codecs are positional: row declaration order must equal column-list/schema order or values silently misbind.
- A typed subset view over a shared/discriminated table binds fields explicitly; never use whole-row positional codec.

## Codec derivation for domain types

- Derive newtype codecs generically from the underlying representation; only a new base needs a codec.
- Derive enums generically from case names, matching enum-as-text.
- Document/semi-structured columns need explicitly named codecs beside the owning queries; anonymous same-shape codecs may collide/shadow.
- Keep the refinement library's codec-integration import in derivation scope; missing it may appear as a low-level macro failure/stack overflow.

## Building dynamic queries

- Dynamic `SET` = non-empty mandatory assignments + flattened optional assignments; no mutable/string builder.
- Multiple values use `IN`, not `OR` equality chains.
- Upsert declares its conflict target and uses `DO UPDATE` with explicit assignments (including attempted-insert values) or `DO NOTHING`; no racy pre-check.

## Effect integration

- Every terminal query stays inside the transactional effect wrapper.
- One atomic unit = one effect sequence passed to one transaction boundary.
- Map failures at the repository boundary; query layer returns native effects.
- Map unique conflicts by structured SQL state + constraint name, never parsed message text.

## Connection/pool wiring

- Pool is scoped/acquire-release.
- Route structured query success/execution/processing events into project logging; never default stdout.

## Testing

- Test harness may expose arbitrary transactional query execution; production code never uses it.
- Tests may use a non-pooling datasource.
