# PR 2 — Validation

Use after the endpoint contract/models exist. Add validated request domain models, newtypes, feature arbitraries, validators, and validator unit tests. Read [Iron](../../standards/iron.md), [Scala tests](../../standards/scala.md#tests), and the chosen transport standard: [Smithy names](../../standards/smithy.md#names) or [Tapir](../../standards/tapir.md).

Validation is the only boundary where untrusted transport primitives become refined domain values. It returns one 400 `ValidationError` containing every invalid field; never fail fast.

## Domain placement

- Request/entry case classes: `backend/domain/.../gateway/<Feature>.scala`, named after the transport request structure (exact Smithy request name on Smithy routes).
- Every refined newtype: shared `Newtypes.scala`, imported through `io.mesazon.domain.gateway.*`, grouped under `// <Feature>`; never a per-feature newtype file.
- Enum/model used beyond one request: its own type-named file.
- Add here any newtype/model needed by validation, including one later shared with the repository. PR 4 adds only persistence-specific Row/input/projection types.

## Arbitraries

Keep feature values out of generic arbitrary traits:

- `<Feature>DomainArbitraries` in test-kit extends `GatewayArbitraries`; define one explicitly named `given arb<ExactTypeName>` per domain case class, normally `Arbitrary(Gen.resultOf(Type.apply))`.
- Smithy route: `<Feature>SmithyArbitraries` in gateway-core test utils extends the domain trait plus `IronRefinedTypeTransformer`. Derive every Smithy arbitrary from the domain arbitrary using Chimney `transformInto`; add explicit `Transformer`s only where shapes differ.
- Tapir route: derive the typed endpoint-input arbitrary from the same domain arbitrary beside the endpoint tests; the domain generator remains the source of valid data.
- Smithy given names append `Smithy` to avoid inherited collisions.
- Never use anonymous givens: singular/plural synthesized names can shadow and produce misleading `RefinedType.Mirror` failures.

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
