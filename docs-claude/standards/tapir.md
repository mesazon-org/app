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

## Entity limits

- Configure its entity-size limit separately from the primary transport; keep the route constant and config value synchronized.

## Naming

- Error case: `PascalCase` + `Error`; wire code: `SCREAMING_SNAKE_CASE`.
