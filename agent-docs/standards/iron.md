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
- Monetary `BigDecimal :| Pure` values are non-negative, use a whole component from `0` through `999,999,999,999`, and never pass through `Double`. Generate scales `0..4` for most samples and occasionally `5..18` for high-precision currencies.
