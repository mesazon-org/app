# Refined newtypes

This document defines **project-agnostic standards for refined newtypes** — domain types that wrap a primitive (or another value) and attach a compile-time-enforced constraint, so that once a value has the type it is guaranteed to satisfy its invariant. It is written around [Iron](https://github.com/Iltotore/iron) (`io.github.iltotore.iron`, a Scala 3 refinement library) but the rules apply to any library or hand-rolled approach that turns "a validated primitive" into a distinct type (opaque types, Scala 3 `RefinedType`, a smart-constructor `case class`, etc.).

Use it for the concerns that stay true regardless of the wrapped base type or the surrounding framework:

- what a refined newtype is *for*, and when to introduce one instead of passing a raw primitive;
- how to **name** a refined newtype and the entity/identifier it models;
- where refined newtypes live in a codebase so they are easy to find and reuse.

Do **not** place validation-flow, persistence, or transport rules here. Those belong to the document that owns the boundary:

- turning an untrusted request field into a refined newtype and accumulating validation errors → [validators-new.md](../validators-new.md);
- mapping a refined newtype to and from a database column/codec → [postgres-new.md](postgres-new.md) and [repository-new.md](../repository-new.md);
- referencing generated transport types and disambiguating them from domain models → [smithy-new.md](smithy-new.md);
- general Scala naming, immutability, and "model invalid states out of existence" → [scala-new.md](scala-new.md). This document is the refined-type *specialization* of that general rule.

## Table of contents

- [Scope](#scope)
- [Why refined newtypes](#why-refined-newtypes)
- [Naming a refined newtype](#naming-a-refined-newtype)
  - [General form](#general-form)
  - [Identifier types](#identifier-types)
  - [Case and acronyms](#case-and-acronyms)
- [Where refined newtypes live](#where-refined-newtypes-live)

## Scope

A refined newtype is the unit that makes the [general "model invalid states out of existence" rule](scala-new.md#prefer-code-that-explains-itself) concrete for scalar values: instead of a `String` that *might* be a valid email and a `UUID` that *might* identify a customer, the domain layer speaks in `EmailAddress` and `CustomerID`, and every function that receives one can trust it without re-checking.

This document owns **what those types are called and where they live**. It does not own how a raw value *becomes* one (validation) or how one is *stored/serialized* (persistence/transport) — each of those is a boundary owned by its own document, which refers back here for the naming rule.

## Why refined newtypes

- **Use a refined newtype instead of a raw primitive** wherever a value carries a domain constraint or a domain meaning that a bare `String`/`UUID`/`Int`/`BigDecimal` would lose. The type is the guarantee: a `PhoneNumberE164` is *always* a well-formed E.164 number, so nothing downstream re-validates it.
- **The constraint is enforced once, at construction.** Past the point where the raw value is refined into the type, illegal states are unrepresentable — the same principle as preferring precise domain types over sentinel values and stringly-typed flags ([scala-new.md § Prefer code that explains itself](scala-new.md#prefer-code-that-explains-itself)).
- **Prefer a distinct type per concept even when two concepts share a base type.** `CustomerID` and `InvoiceID` are both UUID-backed, but they are not interchangeable; separate types stop an invoice id being passed where a customer id is expected.

## Naming a refined newtype

### General form

- Use `PascalCase` and name after the domain concept, following `{{ Owner }}{{ Entity }}` — the owning concept first, the specific attribute after it.
- Spell concepts out; do not abbreviate to save space (see [scala-new.md § General principles](scala-new.md#general-principles)).
- ✅ `PhoneRegion`, `PhoneNumberE164`, `OrganizationLogoOriginalFileName`, `UpdatedAt`
- ❌ `phoneRegion` (not `PascalCase`), `id` (no entity, no owner), `OrgLogoName` (abbreviated)

Prefer the domain concept over the representation or the layer it happens to pass through — a value type is named `EmailAddress`, not `EmailString`; a status is `PaymentStatus`, not `PaymentStatusColumn` (same rule as [scala-new.md § Types and domain language](scala-new.md#types-and-domain-language)).

### Identifier types

- A type that identifies an entity is named after **the entity it identifies**, suffixed with `ID`. Never use a context-free identifier type.
- ✅ `UserID`, `CustomerID`, `InvoiceID`, `SubscriptionID`, `OrganizationID`
- ❌ `ID`, `EntityID`, `Identifier`, `organizationId` (wrong case — see below)

### Case and acronyms

- Newtypes are types, so they are always `PascalCase` — never the lower-camelCase spelling used for *values* of the type (`userID: UserID`).
- `ID` stays **fully uppercase** wherever it appears; every other acronym is treated as a word (`Jwt`, `Http`, `E164` keeps its standard spelling). This is the same acronym policy as [scala-new.md § Acronyms and initialisms](scala-new.md#acronyms-and-initialisms).
- ✅ `UserID`, `OrganizationID`, `JwtSecret`
- ❌ `UserId`, `organizationID` (that camelCase form names a *value*, not the type), `JWTSecret`

## Where refined newtypes live

- **Keep all refined newtypes in one shared, flat location**, wildcard-imported everywhere, regardless of which feature "owns" a given type. Splitting them per feature buys no isolation — every layer references them — and only makes a type harder to find. Group them with a per-feature comment header inside that one place rather than in separate files.
- A **type broader than a single value** — an enum/state machine or role that multiple features read (e.g. an onboarding-stage or user-role enum) — gets **its own file named after the type**, not folded into a feature file, because it tends to grow real behavior later.
- Concretely in this codebase: newtypes live in the single `backend/domain/.../Newtypes.scala` (package `io.mesazon.domain.gateway`, imported via `io.mesazon.domain.gateway.*`), grouped under `// <Feature Name>` comments; shared enums such as `OnboardStage`/`OrganizationUserRole` get their own files. See [adding-a-feature-new.md § Where shared types live](../adding-a-feature-new.md) for the full placement rule.
