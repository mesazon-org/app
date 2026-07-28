# Scala

This document defines **project-agnostic Scala standards** for implementation code and automated tests. It applies to Scala 3 code regardless of framework, architecture, transport, database, or test library.

Use it for rules that remain true when a project changes technologies:

- expressing intent with Scala’s type system and language constructs;
- naming types, values, functions, and test data;
- keeping code and tests readable, deterministic, and independently understandable.

Do **not** place technology, transport, persistence, or architecture rules here. Those belong to the document that owns the boundary or technology, including:

- API-contract naming and mappings between generated transport types and domain models;
- database schemas, SQL, repository/query naming, and persistence input models;
- framework-specific effect, dependency-injection, serialization, or error-handling patterns;
- unit, functional, integration, acceptance, or end-to-end test harnesses and coverage expectations.

A rule may link here for general naming or test-writing guidance, but this document must not duplicate the technology-specific rule.

## General coding standards

### Prefer code that explains itself

- Make types, names, and small focused functions the primary documentation.
- Add a comment only for information that code cannot make clear: a non-obvious decision, external constraint, workaround, invariant, or trade-off.
- Do not add comments that restate the next expression or use banner comments to compensate for poorly structured code.
- Keep a function focused on one coherent operation. Extract a named function when a block has an independently useful concept, needs its own name, or obscures the caller’s intent; do not extract trivial one-line indirection.
- Prefer immutable values. Introduce mutation only when it is local, clearly simpler, and its lifecycle is obvious.
- Model invalid states out of existence where practical. Prefer precise domain types, sealed alternatives, and `Option`/`Either`-style types over sentinel values, nullable references, stringly typed flags, or booleans whose meaning needs a comment.

### Make relationships explicit

- Do not pair independently created collections with `zip` when correctness depends on both collections having matching length and order. `zip` silently discards unmatched elements.
- Create related values together, then carry the relationship as a named case class, named tuple, or `Map` when lookup is the intent.
- Do not read tuple elements positionally with `._1`, `._2`, and so on. Destructure the tuple immediately or use a named tuple/case class so every access says what it represents.
- Prefer collection transformations that preserve the domain relationship over parallel collections and index-based coordination.

## Naming conventions

### General principles

Name an identifier after the **concept it represents**, then append only the context needed to distinguish it:

```text
<base concept><source or state><role>
```

The base concept comes first and remains intact. Source, representation, lifecycle, and test roles are suffixes, in that order when more than one is necessary.

| Meaning | Preferred | Avoid |
| --- | --- | --- |
| A domain value | `paymentSchedule` | `schedule`, `value`, `data` |
| A raw value before parsing/validation | `emailRaw` | `rawEmail` |
| A parsed or validated value | `emailValidated` | `validatedEmail` |
| A new, existing, or changed value | `invoiceNew`, `invoiceExisting`, `invoiceUpdated` | `newInvoice`, `updatedInvoice` |
| An expected test value | `paymentExpected` | `expectedPayment` |
| An optional raw value | `emailRawOpt` | `emailOptRaw`, `optionalRawEmail` |

This ordering keeps bindings for one concept together when scanning a scope:

```scala
val paymentScheduleRaw = decode(payload)
val paymentScheduleValidated = validate(paymentScheduleRaw)
val paymentScheduleExisting = repository.find(paymentScheduleValidated.id)
val paymentScheduleUpdated = update(paymentScheduleExisting, paymentScheduleValidated)
```

Use complete and meaningful words. Do not abbreviate a concept merely to save space.

- ✅ `customerEmailAddress`, `maximumRetryCount`, `authorizationHeader`
- ❌ `custEmail`, `maxRetriesCnt`, `authHdr`

Do not use vague role words when a type or domain name would state the meaning.

- ✅ `invoiceDecoder`, `validationErrors`, `paymentCommand`, `customerById`
- ❌ `helper`, `result`, `item`, `data`, `value`, `thing`

A short conventional name is fine when its scope makes it unambiguous, such as `i` in a small index-only loop or `A` for a conventional generic type parameter. Do not let that exception become the default for domain values.

### Case and grammatical form

#### Values, fields, parameters, and methods

Use `camelCase`.

- ✅ `customerEmail`, `retryCount`, `calculateInvoiceTotal`, `activeUsers`
- ❌ `CustomerEmail`, `retry_count`, `CalculateInvoiceTotal`

Use a **singular noun** for one value and a **plural noun** for a collection.

- ✅ `invoice`, `invoices`, `invoiceById`, `invoicesByCustomerId`
- ❌ `invoiceList`, `invoiceMap`, `allInvoice`, `invoiceData`

Name an action or transformation with a verb.

- ✅ `validateEmail`, `calculateTotal`, `decodeToken`, `loadActiveUsers`
- ❌ `emailValidation`, `totalCalculation`, `tokenDecoderResult`, `activeUsersLoader`

Name a Boolean so reading it in a condition is natural. Prefer a question or capability prefix where it adds meaning.

- ✅ `isActive`, `hasPermission`, `canRetry`, `shouldRefresh`
- ❌ `active`, `permission`, `retry`, `refreshFlag`

For a numeric value, include its unit whenever readers could otherwise misinterpret it.

- ✅ `timeoutMillis`, `retryDelaySeconds`, `fileSizeBytes`, `discountPercentage`
- ❌ `timeout`, `delay`, `fileSize`, `discount`

#### Types and named constants

Use `PascalCase` for classes, traits, objects, enums, type aliases, and named constants.

- ✅ `CustomerEmail`, `RetryPolicy`, `PaymentStatus`, `MaxRetries`, `DefaultTimeout`
- ❌ `customerEmail`, `retry_policy`, `PAYMENT_STATUS`, `maxRetries`

Name a type after the domain concept it models rather than its UI, transport, persistence, or implementation detail.

- ✅ `Money`, `EmailAddress`, `PaymentStatus`, `CustomerID`
- ❌ `MoneyString`, `EmailTextField`, `PaymentStatusColumn`, `CustomerIDValue`

Name an identifier type after the entity it identifies; never use a context-free identifier type.

- ✅ `CustomerID`, `InvoiceID`, `SubscriptionID`
- ❌ `ID`, `EntityID`, `Identifier`

Use `Impl` only when a concrete type must be distinguished from a stable interface.

- ✅ `trait TokenStore` with `final class TokenStoreImpl`
- ❌ `trait TokenStoreImpl`; `final class PaymentProcessorImplementation`

#### Optionals

Use one explicit convention for optional values throughout a codebase. This document recommends the `Opt` suffix when absence is important at the call site; place it last after every other qualifier.

- ✅ `customerOpt: Option[Customer]`
- ✅ `emailRawOpt: Option[String]`
- ✅ `paymentScheduleExistingOpt: Option[PaymentSchedule]`
- ❌ `customer: Option[Customer]` when a non-optional `customer` also exists in nearby code
- ❌ `maybeCustomer`, `optionalCustomer`, `emailOptRaw`

#### Acronyms and initialisms

Use `ID` as the one exception: keep it fully uppercase wherever it appears. Treat every other acronym as a word in ordinary Scala identifiers: `Url`, `Http`, `Jwt`. Use this policy consistently in types and values. Preserve another all-caps spelling only when an external public API or domain standard requires it.

- ✅ `CustomerID`, `customerID`, `HttpClient`, `parseJwt`, `serviceUrl`
- ❌ `CustomerId`, `customerId`, `HTTPClient`, `parseJWT`, `serviceURL`

#### Qualifiers and precise names

Use a suffix only when it distinguishes values in the same scope or conveys semantics not already clear from the type.

- ✅ `invoiceExpected`, `invoiceActual`, `emailEncoded`, `emailDecoded`, `amountRounded`
- ❌ `newInvoiceValue`, `invoiceData`, `emailString`, `amountValue`

Name a binding after its precise type rather than a generic wrapper or role.

```scala
val createOrderCommand = decodeCreateOrder(payload)
val validationErrors = validate(createOrderCommand)
val orderResponse = createOrder(createOrderCommand)
```

Not:

```scala
val command = decodeCreateOrder(payload)
val errors = validate(command)
val result = createOrder(command)
```

### Types and domain language

Use types to preserve domain meaning instead of passing the same primitive through every layer.

```scala
final case class CustomerID(value: UUID)
final case class EmailAddress(value: String)
final case class Money(amount: BigDecimal, currency: Currency)
```

This is preferable to repeatedly using `UUID`, `String`, and `BigDecimal` where the intended concept is not evident.

Name algebraic data types and their cases after the domain alternatives they represent so pattern matches are clear:

```scala
enum PaymentStatus:
  case Pending, Authorized, Settled, Failed

paymentStatus match
  case PaymentStatus.Pending    => queueForProcessing()
  case PaymentStatus.Authorized => capture()
  case PaymentStatus.Settled    => recordSettlement()
  case PaymentStatus.Failed     => notifyFailure()
```

Avoid names tied only to one representation or consumer:

- ✅ `PaymentStatus`, `ShippingAddress`, `CancellationReason`
- ❌ `PaymentStatusJson`, `CheckoutAddressForm`, `CancellationReasonColumn`

### Naming test data

Apply the same naming rules in tests. Keep the model name first and append the value’s test role.

- ✅ `payment`, `paymentExpected`, `paymentActual`, `paymentInvalid`, `paymentUpdated`
- ❌ `expectedPayment`, `validPaymentFixture`, `actual`, `data1`

When a test needs multiple values of the same type, distinguish them with meaningful suffixes.

- ✅ `customerExisting`, `customerNew`, `invoiceBeforeUpdate`, `invoiceAfterUpdate`
- ❌ `customer1`, `customer2`, `invoiceA`, `invoiceB`

Use numbered names only where the values are truly interchangeable and no meaningful distinction exists; otherwise express the relationship in the name.

- ✅ `emailPrimary`, `emailSecondary`
- ✅ `lineItemFirst`, `lineItemSecond` when order is the behaviour under test
- ❌ `email1`, `email2` when one is the primary address

```scala
val payment = paymentBuilder.withDiscount(discount).build()
val paymentExpected = Payment(total = Money(90, Currency.Usd), status = PaymentStatus.Pending)

val paymentActual = paymentService.calculate(payment)

paymentActual shouldBe paymentExpected
```

## Testing standards

### Test intent and structure

- Test observable behaviour and contracts, not incidental implementation details. Assert dependency interactions only when those interactions are part of the unit’s contract.
- Give each test one clear behaviour. Group a public operation’s success and failure cases together, but do not combine unrelated operations in a single test section.
- Name a successful test after the outcome. Name a failure test with the concrete expected error or failure mode followed by the triggering condition.
  - ✅ `"return the calculated total for a discounted order"`
  - ✅ `"fail with an InvalidEmail when the address has no domain"`
  - ❌ `"test invalid email"`
- Structure each test so its setup, action, and assertions are visually distinguishable. Use explicit Arrange–Act–Assert labels only when the structure would otherwise be unclear.
- Keep a test independent: it creates its own inputs, controls its own state, and may run in any order or alone.

### Test data and assertions

- Build test data in the test that uses it. Share a fixture or helper only when it represents stable test infrastructure or avoids substantial repetition without hiding the scenario.
- Do not use a shared mutable “valid request”, “expected result”, or default entity fixture that couples unrelated tests. Start with a valid sample/builder per test and override only the fields relevant to the behaviour.
- Name test values with the same rules as production values. Keep the model name and append the role: `paymentExpected`, `paymentUpdated`, `paymentInvalid`.
- Construct expected values independently of the implementation under test. Do not derive an expected mapped value by calling the same mapper, transform, serializer, or helper whose correctness the test is meant to prove.
- Assert the complete returned model when the operation promises a complete model. Use a focused assertion only when the test’s contract is explicitly limited to that property.
- Assert collection order only when order is part of the contract; otherwise use an order-insensitive assertion.
- When a scenario requires two values to differ, guarantee that difference during setup and assert the precondition. Do not rely on randomly generated values happening not to collide.
- Make tests deterministic. Control clocks, random sources, generated identifiers, concurrency, and external state at the boundary. Avoid random offsets that can land exactly on a strict comparison boundary.

### Test ownership

- Put a behaviour in the narrowest test layer that can prove it reliably. Keep framework, transport, database, and external-service behaviour in the tests for those boundaries.
- A test for an orchestration layer should prove its own decisions and mappings, not duplicate every failure taxonomy of a dependency it simply propagates.
- Every test must leave no state that can affect another test. Reset or isolate external resources in the test layer that owns them.
