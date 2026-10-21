# Refined newtypes

Reusable refined-newtype rules. Related: [validation](../features/flow/02-validation.md), [schema](postgres.md), [codecs](doobie.md), [transport](smithy.md), [Scala naming](scala.md), [Chimney transformations](chimney.md#iron-refined-products).

## Use

- Refine any primitive with a domain constraint; validate once at construction, then trust the type.
- Give distinct concepts distinct newtypes even when their bases match; e.g. separate UUID-backed IDs prevent cross-entity substitution.

## Naming a refined newtype

- Form: `{{Owner}}{{Entity}}` when the meaning is owner-specific; owner first. A genuinely reusable value concept uses its semantic name without a feature owner (`PriceAmount`).
- An identifier type is named after **the entity it identifies**, suffixed `ID`. Never a context-free identifier type.
- ✅ `PhoneRegion`, `PhoneNumberE164`, `OrganizationLogoImageAsset`, `UserID`, `CustomerID`, `SubscriptionID`
- ❌ `phoneRegion` (not `UpperCamelCase`), `id` (no entity, no owner), `OrgLogoName` (abbreviated), `ID`, `EntityID`, `Identifier`
- A newtype is always `UpperCamelCase`, never the `lowerCamelCase` spelling used for a *value* of the type — `UserID` the type, `userID: UserID` the value; `organizationID` is never a type name.
- `ID` stays fully uppercase; every other acronym is treated as a word (`Jwt`, `Http`, `E164` keeps its standard spelling).
- ✅ `UserID`, `OrganizationID`, `JwtSecret`
- ❌ `UserId`, `organizationID` (that camelCase form names a value, not the type), `JWTSecret`

## Where refined newtypes live

- Keep all refined newtypes in **one shared flat location**, wildcard-imported everywhere; group by feature inside it, not into files.
- A type broader than a single value — an enum/state machine or role read by multiple features — gets **its own file named after the type**, not folded into a feature file, because it tends to grow real behavior later.

## Converting between refined newtypes

- To reuse a value across an API shaped around a different but structurally identical newtype (same base type, same constraint), convert with `.to[Target]` (`io.mesazon.domain.to`, defined in `domain.scala` alongside `SameRefinement`) instead of a manual `Target.assume(source.value)`. It only compiles when both newtypes share the same base type and constraint, so a mismatched conversion is a compile error, not a runtime bug; there is no check to write or test.
- Do not add `.to[Target]` for two newtypes that happen to both wrap `String` but encode different constraints — that pair is deliberately excluded, not a bug in the extension.

## Refined newtypes as jsoniter map keys

- `JsonCodecMaker.make` auto-derives a `JsonKeyCodec` for a `Map` key type; for a refined newtype's chained opaque types (`RefinedType.T` through Iron's `IronType`), that auto-derivation `StackOverflowError`s while dealiasing. Avoid it: never put a refined-newtype key inside a macro-derived `Map` codec without a hand-written `JsonKeyCodec` already in scope.
- The fix is a generic hand-written `given JsonKeyCodec[WrappedType]`, scoped to `RefinedType.Mirror[WrappedType] { type BaseType = String }`, in `queries.scala` next to the existing generic `Read`/`Write`/`Put`/`Get`/`Meta` refined-type givens. It is still hand-written, not macro-derived — the macro-derive path stays unused for refined types; this given only reuses one implementation across every `String`-based refined newtype via the `Mirror` type parameter, the same way the existing `Get`/`Put`/`Meta` givens do, instead of hardcoding one per newtype.
- Unlike those existing givens, it cannot delegate to a same-shaped `JsonKeyCodec[String]` instance — jsoniter-scala-core has no default `JsonKeyCodec[String]` to bypass to (string map keys are handled natively inside the macro, not via a given). It encodes/decodes the key directly against the `JsonReader`/`JsonWriter` string-key primitives (`readKeyAsString`/`writeKey`) and casts to/from `mirror.FinalType`, trusting — like the other bypass givens — that a value stored as a map key was already validated on write; it does not re-validate on read.

## Arbitraries

- Shared refined-base generators live in `IronRefinedTypeArbitraries` and are explicitly named.
- Exact `BigDecimal :| Pure` values are non-negative, use a whole component from `0` through `999,999,999,999`, never pass through `Double`, and may cover broad scales for non-monetary refined values. This base arbitrary alone does not prove a valid `Price`.
- Monetary validation preserves and checks the supplied `BigDecimal` through `PriceValidator` and the typed `Currency` enum. Require a non-negative amount with at most 12 integer digits. For non-zero amounts, check widened `precision - scale` before normalization so compact extreme exponents cannot trigger unbounded allocation; zero bypasses that representation-derived bound. `decimalPoints = Some(n)` rejects a supplied scale above `n` and pads lower scales to exactly `n` without rounding or downscaling. `None` preserves the supplied amount with no scale restriction. The current `EUR`, `USD`, and `GBP` cases use `Some(2)`. See [Catalogue validation](../features/catalogue.md#validation).
- Generate valid canonical `Price` values as one correlated arbitrary: choose the typed `Currency` through the shared `arbCurrency` first, then generate a realistically bounded non-negative amount respecting its `decimalPoints`. The current currencies require exactly two stored decimal places. Do not derive `Price` from independent amount and currency fields with `Gen.resultOf`.
