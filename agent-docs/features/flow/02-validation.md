# PR 2 — Validation

Use after the endpoint contract/models exist. Add validated request domain models, newtypes, feature arbitraries, validators, and validator unit tests. Read [Iron](../../standards/iron.md), [Scala tests](../../standards/scala.md#tests), and the chosen transport standard: [Smithy names](../../standards/smithy.md#names) or [Tapir](../../standards/tapir.md).

Validation is the only boundary where untrusted transport primitives become refined domain values. It returns one 400 `ValidationError` containing every invalid field; never fail fast.

## Domain placement

- Request/entry case classes: `backend/domain/.../gateway/<Feature>.scala`, named after the transport request structure (exact Smithy request name on Smithy routes).
- Every refined newtype lives in shared `Newtypes.scala` and is imported through `io.mesazon.domain.gateway.*`; reusable concepts stay in the common section and feature-owned concepts are grouped under `// <Feature>`. Never create a per-feature newtype file.
- A case class, enum, or model used beyond one request gets its own type-named file.
- Add here any newtype/model needed by validation, including one later shared with the repository. PR 4 adds only persistence-specific Row/input/projection types.

## Domain modeling

- Name reusable value concepts without a feature prefix (`PriceAmount`, `PriceCurrency`, `Price`, `PhotoOriginalBucketKey`, `PhotoNormalizedBucketKey`, `PhotoOriginalFileName`, `Photo`).
- When a shared value shape has a distinct feature meaning, wrap it in an owner-specific `Pure` newtype named for the owning entity (`CatalogueItemPrice`, `CatalogueItemPhoto`).
- Represent coupled optional fields as one `Option` around a composite case class whose members are mandatory. Never expose parallel `Option` fields that permit half-present domain values.

## Arbitraries

Keep feature values out of generic arbitrary traits:

- Shared refined-base generators such as exact `BigDecimal :| Pure` live in `IronRefinedTypeArbitraries`; feature arbitraries reuse them through their newtypes instead of redefining ranges.
- Shared domain case-class generators such as `Price` and `Photo` live in `GatewayArbitraries`; repository-only traits contain only repository Rows/inputs and persistence-specific enums. A valid canonical `Price` is correlated: generate a supported, fixed-fraction ISO currency first, then a realistically bounded non-negative amount at exactly that currency's fraction-digit scale. Never derive `Price` with independent `Gen.resultOf` fields.
- `<Feature>DomainArbitraries` in test-kit extends `GatewayArbitraries`; define one explicitly named `given arb<ExactTypeName>` per domain case class. Use `Arbitrary(Gen.resultOf(Type.apply))` when existing field givens independently produce valid instances; write a custom generator only for correlations, normalization, bounds, or cross-field invariants.
- Smithy route: `<Feature>SmithyArbitraries` in gateway-core test utils extends the domain trait plus `IronRefinedTypeTransformer`. Derive every Smithy arbitrary from the domain arbitrary using Chimney `transformInto`; add explicit `Transformer`s only where shapes differ.
- Tapir route: derive the typed endpoint-input arbitrary from the same domain arbitrary beside the endpoint tests; the domain generator remains the source of valid data.
- Smithy given names append `Smithy` to avoid inherited collisions.
- Never use anonymous givens: singular/plural synthesized names can shadow and produce misleading `RefinedType.Mirror` failures.
- List arbitraries include the empty case and default to a bounded size of `0..50`; use a smaller explicit maximum when nesting, encoded request size, or dependency cost requires it.

One domain generator is the source of valid values and enables the validator round-trip test.

## Shared validation tools

Reuse `validation/service/validation.scala`:

- `validateRequiredField` / `validateOptionalField`: pure newtype construction.
- `toValidatedRequestIO`: accumulated `ValidatedNec` → typed `ValidationError`.
- `validateAll`: validates a flat list and stamps failures with item `index`.
- `validateAllNested`: collapses a composite item's errors under the outer field while preserving inner indexes.
- `validateSingleDefault`: a non-empty defaultable list must have exactly one default; chain after item validation with `.andThen`.

Use `validation/domain/EmailValidator` or `PhoneNumberDomainValidator` for effectful/non-trivial fields, then wrap the result in the feature newtype. Shared helpers are `private[validation]`. New features use one feature validator; legacy `ServiceValidator`/`DomainValidator` remains only for untouched old code (`WahaServiceValidator` is the last user).

## Feature validator

File: `validation/service/<Feature>RequestValidator.scala`.

- One public function per fallible request: `validated<TransportRequestName>(transportRequest): IO[ServiceError.BadRequestError.ValidationError, DomainRequest]`.
- Do not create a validator when wire decoding already guarantees the entire request (for example UUID-only input); construct its newtypes in the service.
- Bind Smithy inputs as `<fullRequestName>Smithy`; qualify Tapir inputs equivalently. Bind validated values as the plain domain request name.
- Compose fields in declaration order with `ValidatedNec` + `mapN`; clients must receive all errors in stable field order.
- `fieldName` exactly equals the Smithy member.
- Singular, batch, and combined requests reuse private per-item/per-field validation; never duplicate field lists.
- For an optional composite, validate mandatory inner members in declaration order, accumulate their errors under the outer field, then wrap the valid case class in its contextual newtype.
- Monetary validation uses `PriceDomainValidator` with JDK `Currency`: normalize trim/uppercase ISO codes, reject unsupported or no-fixed-fraction currencies, and reject negative amounts or supplied scales above the currency fraction digits. Normalize valid lower-scale amounts upward to the exact currency scale by appending zeros; never round or remove supplied precision.
- Pure fields use required/optional helpers with `.either`; effectful fields use domain validators.
- Expose `val live = ZLayer.derive[...]`; PR 5 wires it into the service graph.

## Required proof: two tests per public function

File: `unit/validation/service/<Feature>RequestValidatorSpec.scala`; extend `ZWordSpecBase` and the chosen transport's feature arbitraries.

1. **Success round-trip:** sample the domain request, transform to the transport request, validate, assert the original domain value.
2. **Failure accumulation:** sample a valid transport request, replace all targeted fields with invalid values, and assert the exact ordered `List[InvalidFieldError]`, including nested indexes.

Use fresh `arbitrarySample` data per test; no shared valid fixtures. Add extra cases for list/default/nested behavior when the two required cases cannot prove them.

Run:

```sh
sbt "gateway-core/testOnly *<Feature>RequestValidatorSpec"
```

Update the feature doc with request types, validation rules, and unit coverage.
