# Contract-first HTTP API

Project-agnostic Smithy-style rules. Other owners: [middleware](../../middleware.md), [validation](../../validators.md), [newtypes](iron.md), [Scala/tests](scala.md), [alternate transport](tapir.md). Mesazon: [project/smithy.md](../project/smithy.md).

## Names

- Service/file: `<Feature>Service` / `<Feature>Service.smithy`.
- Operation: `{Action}{Entity}{HttpMethod}` or `{Flow}{HttpMethod}`; suffix equals actual method. Batch operation/URI/shapes use plural entity.
- Shapes: `<Operation>Request|Response` in feature domain file.
- Service/operation/shape names use `PascalCase`.
- Contract name is canonical: validated domain request has the exact same name. Rename both together.
- Generated types are always package-qualified; import package, never members.
- When wire/domain types coexist: bare binding = domain; generated binding = full type name + `Smithy`. Applies to handler params, validated outputs, wrappers, tests, and arbitrary givens.
- Each operation owns its shapes; never share a common entity shape across operations.
- Item: `{Action}{Entity}`. Batch list: plural item name, no `List` suffix.
- Contact/value+flag entry: `<Owner><Kind>EntryRequest`; list plural `...EntryRequests`; domain entry has same name.
- Request list member: `@default([])`, never `@required`, so omitted empty JSON decodes to non-optional `Nil`. Response list: `@required`. Never model request list as `Option[List]` solely to default it empty.
- Members: `camelCase`; IDL UUID named `<entity>ID`; client duration integer named `<thing>ExpiresInSeconds`; enum values `SCREAMING_SNAKE_CASE`; centralize domain↔contract enum mappers.
- URI: verb-first kebab-case; plural entity for batches (`/insert/customers`).

## Files

- Top-level `<Feature>Service.smithy`: service + operations.
- `domain/<Feature>.smithy`: request/response shapes.
- `domain/HttpErrors.smithy`: all shared HTTP errors; no feature-local errors.
- `domain/Gateway.smithy`: shared enums/value shapes/custom traits.
- Stable namespace; project-defined version pragma per file kind.

## Service

- Required REST/JSON protocol trait.
- Exactly one service auth mode: bearer, basic, or public.
- Add completed-onboarding service trait iff every endpoint requires it.
- With that trait, service docs contain only the completed-onboarding marker; otherwise gates are operation-level.

## Operation

- Body input is one required payload wrapper containing `<Operation>Request`.
- IDs are body members; GET/bodyless IDs use path labels. Tenant/org ID is always header (below).
- `200` with output; `204` without output.
- Method/URI trait sits immediately above `operation`; other traits above it.
- Every tenant operation carries role allow-list according to project role policy.
- OpenAPI gate markers use identical bold-label text across transports:
  - required onboarding: per-operation bracketed enum list in backticks; `` `N/A` `` before onboarding. Fully gated service uses one service marker instead.
  - required tenant roles: bracketed enum list on every role-gated operation.
  - Alternate transport uses shared marker helpers with the enforced values.
- Errors: base validation/unauthorized/internal; add bad-request for rejected well-formed input, forbidden for role/stage gates, conflict for state collision.
- Order errors by status ascending; ties alphabetically. Apply to new errors.
- Any domain error addition/status move/new failure mode updates every affected operation's error list in the same change.

## Tenant scope

- Tenant ID is one required header, declared once in a mixin and mixed into every scoped operation input; never inline repeatedly, in body, URI, or service trait.
- Mixin flattening must preserve generated parameter and required OpenAPI header per operation.
- Header lets middleware handle GET/streaming without body parsing.
- Role trait/check may be invisible in generated OpenAPI; required-role marker is mandatory and matches enforcement.
- Alternate transport: typed header security input + shared auth with endpoint roles. Missing required auth/tenant header → `400`; disallowed role → `403`; its description uses the same roles.

```smithy
@mixin
structure TenantScopedInput {
    @required
    @httpHeader("X-Tenant-ID")
    tenantID: UUID
}

operation GetEntityGet {
    input := with [TenantScopedInput] {
        @required
        @httpLabel
        entityID: UUID
    }
}
```

## Custom traits

Traits live in shared domain; contract declares, middleware enforces.

- Completed-onboarding: memberless service trait; requires completed stage plus valid bearer auth.
- Role allow-list: operation trait containing quoted role enum values; always paired with tenant mixin. Missing tenant header → `400`; disallowed role → `403`; missing referenced membership → project's internal/missing-entity error.
- Default tenant role policy unless feature docs justify deviation:

| Operation | Roles |
|---|---|
| Read (`GET`) | owner, admin, user |
| Mutation | owner, admin |
| Delete tenant | owner |

Keep role marker synchronized with trait.
