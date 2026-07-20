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
    given arbInsertCustomerIndividualPostRequest: Arbitrary[InsertCustomerIndividualPostRequest] =
      Arbitrary(Gen.resultOf(InsertCustomerIndividualPostRequest.apply))
    // …
  }
  ```

- **`<Feature>SmithyArbitraries`** (gateway-core test utils, `io.mesazon.gateway.utils`) — `extends <Feature>DomainArbitraries, IronRefinedTypeTransformer`. Each smithy-request arbitrary is the domain arbitrary mapped through a chimney `transformInto`. `IronRefinedTypeTransformer` unwraps newtypes → base types; add a `Transformer` only where the shapes differ (e.g. `CustomerPhoneNumber → smithy.PhoneNumberRequest`, or a `List` field that is `Option[List]` on the smithy side).

  ```scala
  trait CustomerBookSmithyArbitraries extends CustomerBookDomainArbitraries, IronRefinedTypeTransformer {
    given Transformer[CustomerPhoneNumber, smithy.PhoneNumberRequest] = cpn =>
      smithy.PhoneNumberRequest(cpn.value.phoneNationalNumber.value, cpn.value.phoneCountryCode.value)

    given arbInsertCustomerIndividualPostRequestSmithy: Arbitrary[smithy.InsertCustomerIndividualPostRequest] =
      Arbitrary(Arbitrary.arbitrary[InsertCustomerIndividualPostRequest].map(_.transformInto[smithy.InsertCustomerIndividualPostRequest]))
    // …
  }
  ```

Deriving the smithy arbitrary **from** the domain arbitrary is what makes the validator round-trip test possible (a generated request is always internally consistent and always valid), and it means there is one source of truth for "a valid `<Feature>` value".

### Always name the givens explicitly

Write `given arb<ExactTypeName>: Arbitrary[…]`, never an anonymous `given Arbitrary[…]`. Anonymous givens get a **synthesized name from the type**, and a singular/plural pair (an `X` and an `Xs` wrapper, or an `X` and a `List[X]`) synthesizes colliding names — the plural silently shadows the singular, which then fails to resolve with a misleading "no given instance … missing RefinedType.Mirror" error that survives a clean rebuild. Explicit names avoid it.

The name is `arb` + the exact type name (`arbInsertCustomerIndividualPostRequest`, `arbCustomerEmailEntryRequests` for the `List` variant). Since domain classes are named after the smithy requests, the smithy trait — which extends the domain trait — appends `Smithy` to avoid clashing with the inherited given: domain `arbInsertCustomerIndividualPostRequest: Arbitrary[InsertCustomerIndividualPostRequest]`, smithy `arbInsertCustomerIndividualPostRequestSmithy: Arbitrary[smithy.InsertCustomerIndividualPostRequest]`.

## Consolidating an existing feature into this layout

Older features left their pieces scattered (newtypes in `gateway.scala`, per-request `ServiceValidator` classes, arbitraries in the shared traits). Consolidating a feature `<Feature>` means moving every piece into the per-feature files above **without changing behavior**. Done for: `CustomerBook` (built this way), `OrganizationManagement`, `UserOnboard`. The steps, in order:

1. **Domain** — create `domain/gateway/<Feature>.scala` holding, in this order: the feature's newtypes (cut from `gateway.scala`), its enums (delete their one-enum files), its entry/value case classes, its request case classes (delete their files). Everything stays in package `io.mesazon.domain.gateway`, so **no call site changes** — this step is pure file moves. While here, align the request case-class names with the [smithy gold standard](smithy.md#3-requestresponse-structures) if they drifted (rename the domain class to the smithy request name, never the reverse).
2. **Validator** — replace the per-request `<Request>ServiceValidator` classes with one `validation/service/<Feature>RequestValidator.scala`: a plain class (not a `ServiceValidator`) with one public `validated<Request>PostRequest(request): IO[ValidationError, <Domain>]` per fallible request, each delegating to a private `UIO[ValidatedNec[InvalidFieldError, <Domain>]]` via `toValidatedRequestIO`. Callers change from `validator.validate(r)` to `validator.validated<Request>PostRequest(r)`; update the service, `Main`'s layer list, and any spec `provide`.
3. **Domain arbitraries** — create `<Feature>DomainArbitraries` in test-kit and move every feature `Arbitrary` (enums included) out of `GatewayArbitraries` into it, naming each given (`arb<Type>`). Generic helpers shared across features (e.g. `genEntriesWithSingleDefault`) stay `protected` in `GatewayArbitraries`.
4. **Smithy arbitraries** — create `<Feature>SmithyArbitraries` in gateway-core test utils and move the feature's `Transformer`s and smithy-request arbitraries out of the shared `SmithyArbitraries`.
5. **Re-wire mixins** — every spec that used a moved arbitrary now mixes in the feature trait instead of (or in addition to) the shared one. Watch the two indirect consumers: `RepositoryArbitraries` (row arbitraries `Gen.resultOf` a row whose fields need the feature's arbitraries — extend the feature's domain trait) and any spec that only used the shared trait *for* this feature's givens (swap the mixin).
6. **Rename specs** — the validator spec becomes `<Feature>RequestValidatorSpec` mixing `<Feature>SmithyArbitraries`.
7. **Docs** — per the [Rename rule](../CLAUDE.md): grep `docs-claude/` for every renamed identifier (old validator class names above all) and update; update the feature doc's key-files section.
8. **Verify** — full `Test/compile`, then the whole `gateway-core` and `gateway-it` suites. A consolidation PR must be behavior-neutral: no smithy, SQL, or assertion changes.

## Order of work

1. **Smithy** contract (`smithy4sCodegen` must be green).
2. **Domain models** — the refined case classes.
3. **Domain arbitraries**, then **smithy arbitraries** (derived from them).
4. **Validator** + its spec (round-trip success + accumulated-error failure per function).
5. **Service** + persistence + their specs.
6. **Feature doc** in `docs-claude/features/`.
