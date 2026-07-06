# Organization Management

Owns the organization domain: the organization entity (details, address, slug, stage), the organization↔user membership with roles (`OrganizationUserRow`, creator becomes `UserRole.Owner`), and the creation flow. It is the first feature gated on **completed onboarding**: the smithy service carries `@completedOnboardStage`, so `AuthorizationService` requires the user's stage to be in `OnboardStage.completedStages` (= `PhoneVerified`) on top of a valid access token.

**Scope**: organization rows, membership/roles, creation endpoint, slug uniqueness. The logo upload pipeline that advances the organization to `LogoProvided` lives in [Files Management](files-management.md) — this feature only owns the row it updates.

## Organization stage machine

`OrganizationStage`: `DetailsProvided` → `LogoProvided`. Set to `DetailsProvided` on creation, `LogoProvided` after a logo upload (see [Files Management](files-management.md)).

## Endpoint

| Method | Path | Transport | Auth | Purpose |
|---|---|---|---|---|
| POST | `/create/organization` | smithy (JSON, 2 MB limit) | Bearer + completed onboarding | Create an organization |

Smithy spec: `backend/gateway/core/src/main/smithy/OrganizationManagementService.smithy` (+ `domain/OrganizationManagement.smithy`).

## Flow

### POST /create/organization (`OrganizationManagementService.createOrganizationPost`)
1. Read `AuthedUser` from `AuthState`; validate the request (`CreateOrganizationPostRequestServiceValidator` — name, slug, email, phone number, address fields; each is an iron-refined domain type like `OrganizationSlug`).
2. `OrganizationManagementRepository.createOrganization` inserts **in one transaction**:
   - `OrganizationDetailsRow` (generated `OrganizationID`, stage `DetailsProvided`, logo fields `None`), and
   - `OrganizationUserRow` linking the creator with `UserRole.Owner`.
3. Send an "organization created" email — best-effort: retried, final failure only logged, never fails the request.
4. Response: the new `organizationID`.

The repository also exposes `isOrganizationSlugExists` for slug-uniqueness checks, and `updateOrganization` (used by [Files Management](files-management.md) for logo bucket keys and stage updates).

## Key files

- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/OrganizationManagementService.scala`
- Repository: `repository/OrganizationManagementRepository.scala`; rows: `repository/domain/OrganizationDetailsRow.scala`, `OrganizationUserRow.scala`; queries: `repository/queries/OrganizationDetailsQueries.scala`, `OrganizationUserQueries.scala`
- Completed-stage gate: `middleware/ServerMiddleware.scala` + `service/AuthorizationService.scala`
- Config: `OrganizationManagementConfig` (created-email retries)

## Tests

- Acceptance (see [acceptance-tests.md](../acceptance-tests.md)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/OrganizationManagementServiceSpec.scala` — creation happy path (org + owner rows in DB), duplicate slug failure, plus missing/invalid token, disallowed stage, and validation cases
- Functional: `fun/OrganizationManagementServiceSpec.scala`
- Integration: `it/OrganizationManagementRepositorySpec.scala`
