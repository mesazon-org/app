# Refined newtypes

Project-agnostic standards for refined newtypes — domain types that wrap a primitive (or another value) and attach a compile-time-enforced constraint, so a value of the type is guaranteed to satisfy its invariant. Written around [Iron](https://github.com/Iltotore/iron) (`io.github.iltotore.iron`) but applies to any refinement approach that turns "a validated primitive" into a distinct type (opaque types, Scala 3 `RefinedType`, a smart-constructor `case class`).

Not owned here: turning an untrusted value into a refined newtype and accumulating validation errors ([validators-new.md](../validators-new.md)); mapping a refined newtype to/from a database column or codec ([postgres-new.md](postgres-new.md), [doobie-new.md](doobie-new.md)); referencing generated transport types ([smithy-new.md](smithy-new.md)); the general type-naming/domain-language principle this specializes ([scala-new.md § Types and domain language](scala-new.md#types-and-domain-language)).

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to refined-newtype usage. Concrete values for this codebase: [iron-project.md](iron-project.md).

## Why a refined newtype

- Use a refined newtype instead of a raw primitive wherever a value carries a domain constraint the primitive itself can't express — the type *is* the guarantee (a well-formed `PhoneNumberE164` never gets re-validated downstream), enforced once at construction, not at every use site.
- Give two concepts sharing the same base type distinct newtypes, even when both are, say, UUID-backed — a distinct type is what stops an invoice id being passed where a customer id is expected. A raw shared base type provides no such protection.

## Naming a refined newtype

- Form: `{{ Owner }}{{ Entity }}` — the owning concept first, the specific attribute after it.
- An identifier type is named after **the entity it identifies**, suffixed `ID`. Never a context-free identifier type.
- ✅ `PhoneRegion`, `PhoneNumberE164`, `OrganizationLogoOriginalFileName`, `UserID`, `CustomerID`, `SubscriptionID`
- ❌ `phoneRegion` (not `PascalCase`), `id` (no entity, no owner), `OrgLogoName` (abbreviated), `ID`, `EntityID`, `Identifier`
- A newtype is always `PascalCase`, never the lower-camelCase spelling used for a *value* of the type — `UserID` the type, `userID: UserID` the value; `organizationID` is never a type name.
- `ID` stays fully uppercase; every other acronym is treated as a word (`Jwt`, `Http`, `E164` keeps its standard spelling).
- ✅ `UserID`, `OrganizationID`, `JwtSecret`
- ❌ `UserId`, `organizationID` (that camelCase form names a value, not the type), `JWTSecret`

## Where refined newtypes live

- Keep all refined newtypes in **one shared, flat location**, wildcard-imported everywhere, regardless of which feature "owns" a given type — splitting per feature buys no isolation (every layer references them) and only makes a type harder to find. Group by feature with a comment header inside that one place, not separate files.
- A type broader than a single value — an enum/state machine or role read by multiple features — gets **its own file named after the type**, not folded into a feature file, because it tends to grow real behavior later.
