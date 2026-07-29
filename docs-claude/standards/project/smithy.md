# API contracts — Mesazon specifics

Read with the [agnostic API-contract rules](../agnostic/smithy.md).

- Shapes live under `backend/gateway/core/src/main/smithy/`; smithy4s generates Scala into the `io.mesazon.gateway.smithy` package at compile time. Services implement the generated trait — see the pattern in existing `service/*.scala`. Declared auth traits are enforced by [the HTTP middleware](../../middleware.md).
- Namespace: every file uses `io.mesazon.gateway.smithy`.
- Version pragma: service files declare `$version: "2"`; domain files declare `$version: "2.0"`.

## Members

- Identifiers use `alloy#UUID` (`use alloy#UUID`), named `<entity>ID`.
- Domain↔smithy enum mappers live in `service/service.scala`.

## Service definition

- Protocol trait: `@simpleRestJson` (`use alloy#simpleRestJson`).
- Auth traits: `@httpBearerAuth` (access-token endpoints), `@httpBasicAuth` (credential endpoints).
- Onboarding-gated services carry one service doc: `/// **Required Onboard Stage:** COMPLETED`.

## Operation rules

- Body wrapper syntax: `input := { @required @httpPayload request: <Operation>Request }`.
- Path parameters use `@httpLabel`, e.g. `/get/individual/{customerID}`.
- `code: 200` with an `output`; `code: 204` and no `output` for operations with nothing to return.
- Trait order above `operation`: `@http` sits immediately above the line; other traits (e.g. `@organizationUserRolesAllowed`) go above `@http`.
- Swagger marker literal text: `/// **Required Onboard Stage:** [...]` and `/// **Required Organization User Roles:** [...]`.
- Tapir mirrors both: the stage marker lives in the Tapir OpenAPI `Info.description` built in `FileServiceEndpoints.allRoutesAndDocsEndpoints` (global — every Tapir endpoint requires completed onboarding); the roles marker is built by the shared `requiredOrganizationRolesDescription(roles)` helper in `tapir/tapir.scala` and passed to each endpoint's `.description(...)`.
- `Forbidden` triggers: `@organizationUserRolesAllowed`, `@completedOnboardStage`, or an in-handler `verifyOnboardStage` check.
- Base errors: `ValidationError`, `Unauthorized`, `InternalServerError`; add `BadRequest`, `Forbidden`, `Conflict`, `ServiceUnavailable` when applicable. Canonical order: `BadRequest`(400), `ValidationError`(400), `Unauthorized`(401), `Forbidden`(403), `Conflict`(409), `InternalServerError`(500), `ServiceUnavailable`(503).
- The Scala type to watch for errors-sync is `ServiceError` — its subtypes map to contract error shapes. Precedent: `InvalidOnboardStage` once moved to `403 Forbidden` in code without every affected operation's `errors` list being updated to match — the reason this rule exists.

## Organization scoping

- `OrganizationScopedInput` in `domain/Gateway.smithy` owns required header `@httpHeader("X-Organization-ID") organizationID: UUID`.
- Tapir wiring: the header is a typed `securityIn` — `header[OrganizationID](AuthorizationService.OrganizationIDHeader.toString)` — passed to `AuthorizationService.auth` together with the endpoint's allowed roles. Missing required headers (`Authorization`, `X-Organization-ID`) are a generic `400 BadRequest`; disallowed-role failures are `403 Forbidden`; on both transports (see `FileServiceEndpoints.scala`).
- The Tapir role check runs inside `zServerSecurityLogic`, invisible to OpenAPI generation — this is why the endpoint needs an explicit `.description(...)` marker to state the required roles at all.
- Tapir endpoint description: `.description(requiredOrganizationRolesDescription(roles))`, passing the same `OrganizationUserRole` list the security logic enforces (e.g. `OrganizationUserRole.adminRoles`).

## Custom traits

- `@completedOnboardStage` sets `OnboardStage.completedStages = PhoneVerified`.
- `@organizationUserRolesAllowed(roles: [...])` roles mirror the Scala domain enum `OrganizationUserRole` (`OWNER`, `ADMIN`, `USER`).
- Role node values are quoted. Missing organization header → `400`; disallowed role → `403`; no membership row → `500`.
- `CustomerBookService` role assignment by operation: reads — `GetCustomerIndividualGet`, `GetCustomerBusinessGet`, `GetCustomersGet`; writes — `InsertCustomer*`, `UpdateCustomer*`, `AddCustomerBusinessContactsPut`, `RemoveCustomerBusinessContactsPut`.
