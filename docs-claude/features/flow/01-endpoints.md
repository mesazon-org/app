# PR 1 — Endpoints and transport models

Declare endpoints and transport models before implementation. Default: [Smithy](../../standards/smithy.md). Use [Tapir](../../standards/tapir.md) + [Mesazon alternate HTTP](../../project/alternate-http.md) only when Smithy cannot express the transport (currently streaming upload). Exclude validation, schema, repository, and service behavior.

## Smithy files

- Shapes: `backend/gateway/core/src/main/smithy/`; generated Scala package: `io.mesazon.gateway.smithy`.
- Namespace: `io.mesazon.gateway.smithy`.
- Service file `$version: "2"`; domain file `$version: "2.0"`.
- Domain↔Smithy enum mappers belong in `service/service.scala` when implementation lands.

For Tapir, define the typed endpoint inputs/outputs/errors and OpenAPI metadata in the feature endpoint file following [Alternate HTTP](../../project/alternate-http.md). The endpoint remains unwired until PR 5.

## Smithy contract values

- IDs: `alloy#UUID` with `use alloy#UUID`, member `<entity>ID`.
- Protocol: `@simpleRestJson` with `use alloy#simpleRestJson`.
- Service auth: `@httpBearerAuth` for access-token endpoints, `@httpBasicAuth` for credentials, none for public; never both.
- Completed-onboarding service: `@completedOnboardStage` plus sole service doc `/// **Required Onboard Stage:** COMPLETED`.
- Body: `input := { @required @httpPayload request: <Operation>Request }`.
- GET/bodyless path values use `@httpLabel`.
- Success: `200` with output; `204` without output.
- Trait order: role/custom traits, then `@http` immediately above `operation`.
- Swagger gate markers are literal:
  - `/// **Required Onboard Stage:** [...]`
  - `/// **Required Organization User Roles:** [...]`

## Organization scope

Every scoped Smithy operation mixes `OrganizationScopedInput` from `domain/Gateway.smithy`; it owns required `@httpHeader("X-Organization-ID") organizationID: UUID`. Tapir uses the same typed header as a security input. Never put organization ID in body/URI or redeclare the header.

Pair it with `@organizationUserRolesAllowed(roles: [...])`; values are quoted and mirror `OrganizationUserRole`:

| Operation | Roles |
|---|---|
| Read (`GET`) | `OWNER`, `ADMIN`, `USER` |
| Mutation | `OWNER`, `ADMIN` |
| Delete organization | `OWNER` |

Keep the role marker identical to the trait. Missing organization header → `400`; disallowed role → `403`; absent membership → `500`.

Tapir mirrors stage/role markers: global stage marker in `FileServiceEndpoints.allRoutesAndDocsEndpoints`; roles via `requiredOrganizationRolesDescription(roles)` using the enforced list.

## Smithy errors

Base: `ValidationError`, `Unauthorized`, `InternalServerError`. Add `BadRequest`, `Forbidden`, `Conflict`, `ServiceUnavailable` when the declared flow can produce them.

Canonical order: `BadRequest`(400), `ValidationError`(400), `Unauthorized`(401), `Forbidden`(403), `Conflict`(409), `InternalServerError`(500), `ServiceUnavailable`(503). Same-status ties alphabetical.

`Forbidden` is required for `@organizationUserRolesAllowed`, `@completedOnboardStage`, or in-handler `verifyOnboardStage`. Any `ServiceError` addition/status change updates every affected operation in the same PR.

Tapir endpoints use the project `TapirServerError` model and parity rules from [Alternate HTTP](../../project/alternate-http.md).

## Required proof

1. Create/update the mandatory [feature doc](README.md#feature-doc-mandatory-in-pr-1), including endpoint/auth/stage/role table and slice status.
2. Smithy: run `sbt smithy4sCodegen`; Tapir: compile the typed endpoint and generated OpenAPI/docs definition.
3. Run the affected module compile.
4. Confirm every transport model needed by validation exists, generated Smithy types are qualified, and contract/domain names can remain identical in PR 2.

No acceptance test is required yet: there is no runnable handler. Acceptance coverage is mandatory in [PR 5](05-service.md).
