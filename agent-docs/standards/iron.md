# Refined newtypes

Reusable refined-newtype rules. Related: [validation](../features/flow/02-validation.md), [schema](postgres.md), [codecs](doobie.md), [transport](smithy.md), [Scala naming](scala.md).

## Use

- Refine any primitive with a domain constraint; validate once at construction, then trust the type.
- Give distinct concepts distinct newtypes even when their bases match; e.g. separate UUID-backed IDs prevent cross-entity substitution.

## Naming a refined newtype

- Form: `{{Owner}}{{Entity}}` when the meaning is owner-specific; owner first. A genuinely reusable value concept uses its semantic name without a feature owner (`PriceAmount`, `PriceCurrency`).
- An identifier type is named after **the entity it identifies**, suffixed `ID`. Never a context-free identifier type.
- ✅ `PhoneRegion`, `PhoneNumberE164`, `OrganizationLogoOriginalFileName`, `UserID`, `CustomerID`, `SubscriptionID`
- ❌ `phoneRegion` (not `PascalCase`), `id` (no entity, no owner), `OrgLogoName` (abbreviated), `ID`, `EntityID`, `Identifier`
- A newtype is always `PascalCase`, never the lower-camelCase spelling used for a *value* of the type — `UserID` the type, `userID: UserID` the value; `organizationID` is never a type name.
- `ID` stays fully uppercase; every other acronym is treated as a word (`Jwt`, `Http`, `E164` keeps its standard spelling).
- ✅ `UserID`, `OrganizationID`, `JwtSecret`
- ❌ `UserId`, `organizationID` (that camelCase form names a value, not the type), `JWTSecret`

## Where refined newtypes live

- Keep all refined newtypes in **one shared flat location**, wildcard-imported everywhere; group by feature inside it, not into files.
- A type broader than a single value — an enum/state machine or role read by multiple features — gets **its own file named after the type**, not folded into a feature file, because it tends to grow real behavior later.

## Arbitraries

- Shared refined-base generators live in `IronRefinedTypeArbitraries` and are explicitly named.
- Exact `BigDecimal :| Pure` values are non-negative, use a whole component from `0` through `999,999,999,999`, never pass through `Double`, and may cover broad scales for non-monetary refined values. This base arbitrary alone does not prove a valid `Price`.
- ISO monetary validation first preserves and checks the supplied `BigDecimal`. Validate a non-negative amount with at most 12 integer digits (equivalent to `[0, 1,000,000,000,000)`) jointly with a JDK-supported, fixed-fraction currency and require the supplied scale not to exceed the currency fraction digits. For non-zero amounts, check the integer-digit bound with widened `precision - scale` arithmetic before normalization so compact extreme-exponent inputs cannot trigger unbounded allocation; zero bypasses that representation-derived bound because its exponent does not change its value. Then normalize upward to the currency's exact scale by appending zeros. Zero at any scale and non-zero negative-scale values within the amount bound are valid; rounding, downscaling, and removal of supplied precision are forbidden.
- Generate valid canonical `Price` values as one correlated arbitrary: choose the ISO currency first, then generate a realistically bounded non-negative amount at exactly its fraction-digit scale. Do not combine independent `PriceAmount` and `PriceCurrency` arbitraries with `Gen.resultOf`.
