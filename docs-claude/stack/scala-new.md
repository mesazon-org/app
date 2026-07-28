# Scala

Project-agnostic Scala 3 naming and test-writing standards — hold regardless of framework, transport, database, or architecture.

Not owned here: refined/identifier-newtype naming ([iron-new.md](iron-new.md)), API-contract naming/transport↔domain mapping, database/repository conventions, framework-specific effect/DI/serialization, test-harness setup — each belongs to the document owning that boundary; link, never duplicate.

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows (camelCase, PascalCase, Arrange-Act-Assert). Record only standards unique to this team. Concrete values for this codebase: [scala-project.md](scala-project.md).

## General coding standards

### Prefer code that explains itself

- Model invalid states out of existence where practical: prefer precise domain types, sealed alternatives, and `Option`/`Either`-style types over sentinel values, nullable references, stringly typed flags, or booleans whose meaning needs a comment.

### Make relationships explicit

- Do not pair independently created collections with `zip` when correctness depends on both collections having matching length and order. `zip` silently discards unmatched elements. (Zipping a collection with its own index, or zipping independent effects rather than data collections, does not carry this risk.)
- Create related values together, then carry the relationship as a named case class, named tuple, or `Map` when lookup is the intent.
- Do not read tuple elements positionally with `._1`, `._2`, and so on. Destructure the tuple immediately or use a named tuple/case class so every access says what it represents.

## Naming conventions

### General principles

Name an identifier after the **concept it represents**, then append only the context needed to distinguish it:

```text
<base concept><source or state><role>
```

Base concept first; when several suffixes apply, order them source/state, then role.

| Meaning | Preferred | Avoid |
| --- | --- | --- |
| A domain value | `paymentSchedule` | `schedule`, `value`, `data` |
| A raw value before parsing/validation | `emailRaw` | `rawEmail` |
| A parsed or validated value | `emailValidated` | `validatedEmail` |
| A new, existing, or changed value | `invoiceNew`, `invoiceExisting`, `invoiceUpdated` | `newInvoice`, `updatedInvoice` |
| An expected test value | `paymentExpected` | `expectedPayment` |
| An optional raw value | `emailRawOpt` | `emailOptRaw`, `optionalRawEmail` |

```scala
val paymentScheduleRaw = decode(payload)
val paymentScheduleValidated = validate(paymentScheduleRaw)
val paymentScheduleExisting = repository.find(paymentScheduleValidated.id)
val paymentScheduleUpdated = update(paymentScheduleExisting, paymentScheduleValidated)
```

Spell concepts out; do not abbreviate merely to save space.

- ✅ `customerEmailAddress`, `maximumRetryCount`, `authorizationHeader`
- ❌ `custEmail`, `maxRetriesCnt`, `authHdr`

Do not use vague role words when a type or domain name would state the meaning.

- ✅ `invoiceDecoder`, `validationErrors`, `paymentCommand`, `customerById`
- ❌ `helper`, `result`, `item`, `data`, `value`, `thing`

A short conventional name (`i`, `A`) is fine only in a small, unambiguous scope — never as a default for domain values.

### Case and grammatical form

Types, traits, objects, and named constants are `PascalCase`; values, fields, parameters, and methods are `camelCase`.

#### Optionals

Suffix an `Option`-typed value, parameter, or field with `Opt`, placed last after every other qualifier.

- ✅ `customerOpt: Option[Customer]`, `emailRawOpt: Option[String]`, `paymentScheduleExistingOpt: Option[PaymentSchedule]`
- ❌ `customer: Option[Customer]` when a non-optional `customer` also exists nearby; `maybeCustomer`, `optionalCustomer`, `emailOptRaw`

#### Acronyms and initialisms

`ID` is the one exception: keep it fully uppercase wherever it appears. Treat every other acronym as a word: `Url`, `Http`, `Jwt`. Preserve another all-caps spelling only when an external public API or domain standard requires it.

- ✅ `CustomerID`, `customerID`, `HttpClient`, `parseJwt`, `serviceUrl`
- ❌ `CustomerId`, `customerId`, `HTTPClient`, `parseJWT`, `serviceURL`

### Types and domain language

Name a type after the domain concept it models, not its representation or consumer.

- ✅ `Money`, `EmailAddress`, `PaymentStatus`, `ShippingAddress`, `CancellationReason`
- ❌ `MoneyString`, `EmailTextField`, `PaymentStatusJson`, `CheckoutAddressForm`, `CancellationReasonColumn`

### Naming test data

Apply concept-first naming to test values too. When a test needs multiple values of the same type, use a meaningful suffix rather than a number — `customerExisting`/`customerNew`, `lineItemFirst`/`lineItemSecond` when order is the behaviour under test — not `customer1`/`customer2`, unless the values are genuinely interchangeable.

## Testing standards

### Test intent and structure

- Group a public operation's success and failure cases in one section, ordered with every success case — including a silent no-op that still succeeds — before any failure case; do not combine unrelated operations in a single section.
- Name a successful test after the outcome. Name a failure test with the concrete expected error or failure mode followed by the triggering condition.
  - ✅ `"return the calculated total for a discounted order"`
  - ✅ `"fail with an InvalidEmail when the address has no domain"`
  - ❌ `"test invalid email"`
- Keep each test independent — it builds its own inputs and state, and can run in any order or alone.

### Test data and assertions

- Build test data in the test that uses it, not a shared mutable "valid request"/"expected result" fixture that couples unrelated tests. Share a fixture only when it is stable test infrastructure or avoids substantial repetition without hiding the scenario.
- Construct expected values independently of the implementation under test. Do not derive an expected mapped value by calling the same mapper, transform, serializer, or helper whose correctness the test is meant to prove.
- Assert the complete returned model when the operation promises a complete model. Use a focused assertion only when the test's contract is explicitly limited to that property.
- Assert collection order only when order is part of the contract; otherwise use an order-insensitive assertion.
- When a scenario requires two values to differ, guarantee that difference during setup and assert the precondition. Do not rely on randomly generated values happening not to collide — when a generator draws from a small pool prone to collisions, sample both values and then modify one so they differ by construction, rather than hand-writing literals or hoping the sample avoids a clash.
- Control clocks, random sources, generated identifiers, and concurrency at the boundary so tests are deterministic. Avoid a random offset that can land exactly on a strict comparison boundary (`isAfter`/`isBefore`/`>`) and flip the branch on a fraction of runs.

### Test ownership

- A test for an orchestration layer should prove its own decisions and mappings, not duplicate every failure taxonomy of a dependency it simply propagates.
