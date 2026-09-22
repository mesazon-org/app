# Tapir

Reusable rules for routes Smithy cannot express. Related: [Smithy](smithy.md), [authentication](../project/authentication.md), [Scala](scala.md), [Mesazon alternate HTTP](../project/alternate-http.md).

## File layout

- Shared file: security base, error/decode handling, docs-role helper, aliases/options.
- Feature file: endpoint definitions + dependency/server-endpoint wiring.

## Error model

- Own error enum; mirror the primary contract taxonomy and domain-error mapping case-for-case; do not reuse generated errors.
- Any domain error addition/status move updates both handlers together. The alternate handler's plain match may miss a case silently; test parity.

## Security

- Central middleware does not cover these routes. Each endpoint uses shared typed security inputs and must reproduce equivalent token/tenant/onboard-stage/role checks.
- Any middleware rule change updates every alternate endpoint manually.

## Swagger/OpenAPI integration

- Generate/serve a separate OpenAPI spec at a path matching the primary docs convention; register its ID in the shared Swagger UI.
- Mount alternate docs **before** primary swagger routes or the latter may intercept and fail.
- Security logic is invisible to OpenAPI. Endpoint description must use the shared role-marker helper with the exact enforced role list.

## Error schemas/codecs

- One explicitly named schema per error case, named like the primary contract's equivalent response component; do not collapse statuses into one component.
- Separate fallback schema for unknown-at-definition-time paths such as decode failure.
- Codec round-trips by stable code, not display/schema name; unknown code fails.

## Success-body schema derivation

`Schema.derived[T]` on a deeply nested `T` (a response type whose fields are themselves case classes several levels deep, e.g. for structured AI output) can fail to resolve an inner collection member (`Could not find Schema for type List[Inner]`) even when every leaf type has an Iron/tapir codec. Add explicit `Schema.derived[_]` givens bottom-up — leaf case classes first, then each wrapping type, then the top-level response — rather than assuming one top-level derivation call will walk the whole tree; this is a macro-resolution-order issue, not a missing codec.

## Entity limits

- Configure its entity-size limit separately from the primary transport; keep the route constant and config value synchronized.

## Naming

- Error case: `PascalCase` + `Error`; wire code: `SCREAMING_SNAKE_CASE`.
- Success case: an endpoint's declared output body type is named `<Operation><Method>Response` (e.g. `ExtractCustomersPostResponse`). When this endpoint is the type's only real shape owner, rename the underlying type in place to match — even when other non-Tapir code (an AI client, a repository) also happens to return it — rather than adding a parallel wrapper type. Introduce a separate endpoint-owned wrapper type, mapped via Chimney inline at the endpoint's `serverLogic` wiring (never inside the service, per [Scala](scala.md)'s no-private-mapping-helpers rule), only when the same underlying type is a genuinely distinct response for more than one differently-named endpoint.
