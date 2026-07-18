# Adding a feature

A feature is developed as a set of **small, single-responsibility files, grouped by concern**, following the same layering everywhere: smithy contract → domain models → validator → service → persistence, each with its own arbitraries and tests. Prefer one file per class/trait over grabbing into a shared "kitchen sink" file — the codebase separates stuff into its feature, and the arbitraries/validators are split the same way (`CustomerBookDomainArbitraries`, `CustomerBookRequestValidator`, …), not piled into the generic `GatewayArbitraries` / one giant validator.

This is the checklist for the files a new feature `<Feature>` creates. Not every feature needs every row (a read-only feature has no validators), but when it does, this is *where* the code goes and *what* the file is called.

## File layout

| Concern | File | Notes |
|---|---|---|
| **API contract** | `backend/gateway/core/src/main/smithy/<Feature>Service.smithy` + `smithy/domain/<Feature>.smithy` | Operations + request/response shapes. See [smithy.md](smithy.md). |
| **Domain models** | `backend/domain/src/main/scala/io/mesazon/domain/gateway/<Feature>.scala` | Newtypes + the refined case classes each request validates into. See [scala.md § Iron new types](scala.md#iron-new-types). |
| **Request validator** | `backend/gateway/core/src/main/scala/io/mesazon/gateway/validation/service/<Feature>RequestValidator.scala` | One class, one `validated<Request>` per fallible request. See [validators.md](validators.md). |
| **Service** | `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/<Feature>Service.scala` | Implements the generated trait; calls the validator, then does the work. |
| **Persistence** | `Row → Queries → Repository` (+ `RepositoryConfig`, `application.conf`) | See the [Adding a table checklist](postgres.md#adding-a-table--checklist). |
| **Domain arbitraries** | `backend/test-kit/src/main/scala/io/mesazon/testkit/base/<Feature>DomainArbitraries.scala` | `Arbitrary` for each domain case class. |
| **Smithy arbitraries** | `backend/gateway/core/src/test/scala/io/mesazon/gateway/utils/<Feature>SmithyArbitraries.scala` | `Arbitrary` for each smithy request, **derived from** the domain arbitrary. |
| **Validator spec** | `backend/gateway/core/src/test/scala/io/mesazon/gateway/unit/validation/service/<Feature>RequestValidatorSpec.scala` | Two tests per validator function. See [validators.md § Tests](validators.md#tests). |
| **Service / repository / acceptance specs** | `fun/`, `it/`, `backend/gateway/it` | See [acceptance-tests.md](acceptance-tests.md). |
| **Feature doc** | `docs-claude/features/<feature-name>.md` | Required — see the documentation rule in [CLAUDE.md](../CLAUDE.md). |

## Arbitraries: one trait per feature, two layers

Do **not** add feature arbitraries to the shared `GatewayArbitraries` / `SmithyArbitraries`. Give each feature its own pair of traits, mirroring the domain/smithy split:

- **`<Feature>DomainArbitraries`** (test-kit, `io.mesazon.testkit.base`) — `extends GatewayArbitraries` (to reuse shared arbitraries like `Arbitrary[PhoneNumber]`), and adds one `Arbitrary` per domain case class. Newtype fields need no work — `IronRefinedTypeArbitraries` derives `Arbitrary` for any `RefinedType` whose predicate has an arbitrary — so the whole case class is `Arbitrary(Gen.resultOf(<CaseClass>.apply))`.

  ```scala
  trait CustomerBookDomainArbitraries extends GatewayArbitraries {
    given arbInsertCustomerIndividual: Arbitrary[InsertCustomerIndividual] =
      Arbitrary(Gen.resultOf(InsertCustomerIndividual.apply))
    // …
  }
  ```

- **`<Feature>SmithyArbitraries`** (gateway-core test utils, `io.mesazon.gateway.utils`) — `extends <Feature>DomainArbitraries, IronRefinedTypeTransformer`. Each smithy-request arbitrary is the domain arbitrary mapped through a chimney `transformInto`. `IronRefinedTypeTransformer` unwraps newtypes → base types; add a `Transformer` only where the shapes differ (e.g. `CustomerPhoneNumber → smithy.PhoneNumberRequest`, or a `List` field that is `Option[List]` on the smithy side).

  ```scala
  trait CustomerBookSmithyArbitraries extends CustomerBookDomainArbitraries, IronRefinedTypeTransformer {
    given Transformer[CustomerPhoneNumber, smithy.PhoneNumberRequest] = cpn =>
      smithy.PhoneNumberRequest(cpn.value.phoneNationalNumber.value, cpn.value.phoneCountryCode.value)

    given arbInsertCustomerIndividualPostRequest: Arbitrary[smithy.InsertCustomerIndividualPostRequest] =
      Arbitrary(Arbitrary.arbitrary[InsertCustomerIndividual].map(_.transformInto[smithy.InsertCustomerIndividualPostRequest]))
    // …
  }
  ```

Deriving the smithy arbitrary **from** the domain arbitrary is what makes the validator round-trip test possible (a generated request is always internally consistent and always valid), and it means there is one source of truth for "a valid `<Feature>` value".

### Always name the givens explicitly

Write `given arbInsertCustomerIndividual: Arbitrary[…]`, never an anonymous `given Arbitrary[…]`. Anonymous givens get a **synthesized name from the type**, and a singular/plural pair (`Arbitrary[AddCustomerBusinessContact]` vs `Arbitrary[AddCustomerBusinessContacts]`) synthesizes colliding names — the plural silently shadows the singular, which then fails to resolve with a misleading "no given instance … missing RefinedType.Mirror" error that survives a clean rebuild. Explicit names avoid it.

## Order of work

1. **Smithy** contract (`smithy4sCodegen` must be green).
2. **Domain models** — the refined case classes.
3. **Domain arbitraries**, then **smithy arbitraries** (derived from them).
4. **Validator** + its spec (round-trip success + accumulated-error failure per function).
5. **Service** + persistence + their specs.
6. **Feature doc** in `docs-claude/features/`.
