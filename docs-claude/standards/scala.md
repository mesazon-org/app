# Scala

Project-agnostic Scala naming/coding/test rules. Boundary-specific rules: [Iron](iron.md), [Smithy](smithy.md), [Doobie](doobie.md), [Postgres](postgres.md). Mesazon values are scoped in [validation](../features/flow/02-validation.md), [repository](../features/flow/04-repository.md), and [service](../features/flow/05-service.md).

## Code

- Prefer precise domain types, sealed alternatives, `Option`, and `Either` over nulls, sentinels, string flags, or ambiguous booleans.
- Comments explain only a non-obvious decision, workaround, invariant the compiler cannot express, or unrecoverable context. Never restate code or add section/banner comments; improve structure/names instead.
- Never combine independently built collections with `zip` when correctness requires equal length/order: it truncates silently. `zipWithIndex` on one collection and zipping effects are allowed.
- Create related values together; retain the relation in a named tuple/case class, or `Map` for lookup.
- Never access tuples via `._1`/`._2`; destructure or use named fields.

## Naming

Form: `<concept><source/state><role>`; concept first, qualifiers last.

| Meaning | Use | Avoid |
|---|---|---|
| raw/validated | `emailRaw`, `emailValidated` | `rawEmail`, `validatedEmail` |
| new/existing/updated | `invoiceNew`, `invoiceExisting`, `invoiceUpdated` | `newInvoice`, `updatedInvoice` |
| expected | `paymentExpected` | `expectedPayment` |
| optional raw | `emailRawOpt` | `emailOptRaw`, `optionalEmail` |

- Spell out domain names; only established acronyms and `Impl` may abbreviate. Avoid vague `data`, `item`, `result`, `value`, `helper`, `thing`.
- Types/traits/objects/enums/type aliases/constants: `PascalCase`. Values/fields/parameters/methods: `camelCase`.
- Every `Option`-typed value/field/parameter ends in `Opt`; `Opt` is always the final suffix.
- `ID` stays uppercase in types and values (`CustomerID`, `customerID`, `IDGenerator`). Treat other acronyms as words (`Http`, `Jwt`, `Url`) unless an external standard fixes the spelling.
- Name types for domain concepts, not representations/consumers: `EmailAddress`, not `EmailString`/`EmailColumn`.
- Test values follow the same concept-first form and start with the complete model name. Add qualifiers last: `customerRowIndividual`, `customerRowBusiness`, `customerID1`, `customerID2`; never `individualCustomerRow`, `input`, or `contact`.

## Tests

### Structure and names

- Each test owns its data/state and runs alone; no shared test-data fixtures. Share only stable SUT infrastructure or repetition whose inlining is materially worse.
- Exactly one `should` section per public operation, named after that operation. Put all successes (including no-ops) first, then failures. Never split one operation across scenario sections or combine operations. A genuine cross-operation invariant may have its own section.
- One test exercises one operation. Arrange preconditions directly rather than calling another public operation.
- Success test name = observable success. Failure test name starts `fail with a[n] <concrete error>` then states the condition/side-effect proof.

### Data and assertions

- Name bindings after their exact models; qualifiers follow the model name.
- Build expected mappings independently and field-by-field; never use the mapper/transform/serializer/helper under test to create expected output. Using that transform to arrange test input is allowed.
- Assert the complete returned model. Project only when the field is the test's entire contract (for example, IDs surviving rollback).
- Assert order only when contractual; otherwise compare order-insensitively.
- If distinctness matters, guarantee it during setup and assert `not equal`. With low-cardinality generators, sample twice then modify one value; do not hard-code or sample-and-hope.
- Control time/random/IDs/concurrency. For strict comparisons (`isAfter`, `isBefore`, `>`), exclude an equality-producing random offset when equality can change the expected branch; offset `0` is allowed when every generated value takes the same branch.

### Ownership

- An orchestration/service test proves validation, decisions, mappings, dependency calls, and handled branches. Validation failure must prove downstream dependencies were not called (strict mock/no expectation). For dependency errors merely propagated, test one generic instance propagates unchanged, not every subtype; test a specific subtype only when handled differently.
