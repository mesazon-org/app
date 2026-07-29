# Organization Management

Owns organization details/address/slug/stage, membership roles, and creation. `@completedOnboardStage` requires a valid access token and `OnboardStage.completedStages` (`PhoneVerified`).

**Scope**: organization rows, membership/roles, creation endpoint, slug uniqueness. The logo upload pipeline that advances the organization to `LogoProvided` lives in [Files Management](files-management.md) — this feature only owns the row it updates.

## Organization stage machine

`OrganizationStage`: `DetailsProvided` → `LogoProvided`. Set to `DetailsProvided` on creation, `LogoProvided` after a logo upload (see [Files Management](files-management.md)).

## Endpoint

| Method | Path | Transport | Auth | Purpose |
|---|---|---|---|---|
| POST | `/create/organization` | smithy (JSON, 2 MB limit) | Bearer + completed onboarding | Create an organization |

Smithy spec: `backend/gateway/core/src/main/smithy/OrganizationManagementService.smithy` (+ `domain/OrganizationManagement.smithy`).

`CreateOrganizationPost` carries no `@organizationUserRolesAllowed` because the caller has no membership yet — the flow *creates* the membership, making them `OWNER`.

## Role policy (for future org-scoped endpoints)

Follow the [role policy](../standards/smithy.md#custom-traits): reads `OWNER|ADMIN|USER`; mutations `OWNER|ADMIN`; organization deletion `OWNER` only.

## Flow

### POST /create/organization (`OrganizationManagementService.createOrganizationPost`)
1. Read `AuthedUser`; validate name, slug, contacts, optional tagline/address/company registration/tax ID.
   - `emails`/`phoneNumbers` are JSONB lists of value + `isDefault`; validate every entry and exactly one default when non-empty.
   - `OrganizationSlug`: trimmed, non-empty, max 63, `^[a-z0-9]+(?:-[a-z0-9]+)*$`; safe for URL path or DNS label.
2. `OrganizationManagementRepository.createOrganization` inserts **in one transaction**:
   - `OrganizationDetailsRow` (generated `OrganizationID`, stage `DetailsProvided`, logo fields `None`), and
   - `OrganizationUserRow` linking the creator with `OrganizationUserRole.Owner`.
3. Retry the created email; final failure is logged and does not fail the request.
4. Response: the new `organizationID`.

The repository also exposes `isOrganizationSlugExists` for slug-uniqueness checks, and `updateOrganization` (used by [Files Management](files-management.md) for logo bucket keys and stage updates).

## Key files

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/OrganizationManagement.scala` (contact-point entries, `CreateOrganizationPostRequest`); `Organization*` newtypes live in the shared `Newtypes.scala`, and the `OrganizationStage`/`OrganizationUserRole` enums each have their own file (`OrganizationStage.scala`, `OrganizationUserRole.scala`) — see [domain placement](flow/02-validation.md#domain-placement)
- Validator: `validation/service/OrganizationManagementRequestValidator.scala`
- Arbitraries: `testkit/base/OrganizationManagementDomainArbitraries.scala`, `gateway/utils/OrganizationManagementSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/OrganizationManagementService.scala`
- Repository: `repository/OrganizationManagementRepository.scala`; rows: `repository/domain/OrganizationDetailsRow.scala`, `OrganizationUserRow.scala`; queries: `repository/queries/OrganizationDetailsQueries.scala`, `OrganizationUserQueries.scala`
- Completed-stage gate: `middleware/ServerMiddleware.scala` + `service/AuthorizationService.scala`
- Config: `OrganizationManagementConfig` (created-email retries)

## Tests

- Acceptance (see [service completion](flow/05-service.md#acceptance-tests-real-app-over-http)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/OrganizationManagementApiSpec.scala` — creation happy path (org + owner rows in DB), duplicate slug failure, plus missing/invalid token, disallowed stage, and validation cases
- Functional: `fun/OrganizationManagementServiceSpec.scala`
- Integration: `it/OrganizationManagementRepositorySpec.scala`
