# Organization Management

Owns the organization domain: the organization entity (details, address, slug, stage), the organization↔user membership with roles (`OrganizationUserRow`, creator becomes `OrganizationUserRole.Owner`), and the creation flow. It is the first feature gated on **completed onboarding**: the smithy service carries `@completedOnboardStage`, so `AuthorizationService` requires the user's stage to be in `OnboardStage.completedStages` (= `PhoneVerified`) on top of a valid access token.

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

Any org-scoped endpoint added here follows the project-wide [standard role policy](../smithy.md#organizationuserrolesallowedroles-): reads (`GET`) allow `OWNER`/`ADMIN`/`USER`, mutations allow `OWNER`/`ADMIN`. The one carve-out this feature owns: **deleting an organization is `OWNER` only** (`@organizationUserRolesAllowed(roles: ["OWNER"])`) — an `ADMIN` may run every other action but cannot delete the org itself.

## Flow

### POST /create/organization (`OrganizationManagementService.createOrganizationPost`)
1. Read `AuthedUser` from `AuthState`; validate the request (`OrganizationManagementRequestValidator.validatedCreateOrganizationPostRequest` — name, slug, `emails` and `phoneNumbers` — lists of contact-point entries each carrying an `isDefault` flag, validated like Customer Book's (each entry individually valid, and a non-empty list must mark exactly one entry as default via `validateSingleDefault`), stored as `jsonb` columns (`emails`, `phone_numbers`) on `organization_details` — plus optional `tagline` (short "what we sell" line shown under the org name, e.g. on invoices: "Froutagora — Fruits and Vegetable Market"), optional address fields (`addressLine1`, `addressLine2`, `city`, `postalCode`, `country`), `companyRegistrationNumber` and `taxID` (VAT); each is an iron-refined domain type like `OrganizationSlug`, which uses `SlugPredicate` — a URL-friendly slug of lowercase letters, digits and single hyphens (`^[a-z0-9]+(?:-[a-z0-9]+)*$`, trimmed, non-empty, max 63), intended to key a future per-organization store website. The 63-char cap and the character set are the safe intersection of a valid URL path segment and a DNS label, so the slug can serve as either a path or a subdomain).
2. `OrganizationManagementRepository.createOrganization` inserts **in one transaction**:
   - `OrganizationDetailsRow` (generated `OrganizationID`, stage `DetailsProvided`, logo fields `None`), and
   - `OrganizationUserRow` linking the creator with `OrganizationUserRole.Owner`.
3. Send an "organization created" email — best-effort: retried, final failure only logged, never fails the request.
4. Response: the new `organizationID`.

The repository also exposes `isOrganizationSlugExists` for slug-uniqueness checks, and `updateOrganization` (used by [Files Management](files-management.md) for logo bucket keys and stage updates).

## Key files

The feature follows the consolidated per-feature layout of [adding-a-feature.md](../adding-a-feature.md): one domain file, one request validator, one arbitraries trait per layer.

- Domain: `backend/domain/src/main/scala/io/mesazon/domain/gateway/OrganizationManagement.scala` (all `Organization*` newtypes, `OrganizationStage`/`OrganizationUserRole` enums, contact-point entries, `CreateOrganization`)
- Validator: `validation/service/OrganizationManagementRequestValidator.scala`
- Arbitraries: `testkit/base/OrganizationManagementDomainArbitraries.scala`, `gateway/utils/OrganizationManagementSmithyArbitraries.scala`
- Service: `backend/gateway/core/src/main/scala/io/mesazon/gateway/service/OrganizationManagementService.scala`
- Repository: `repository/OrganizationManagementRepository.scala`; rows: `repository/domain/OrganizationDetailsRow.scala`, `OrganizationUserRow.scala`; queries: `repository/queries/OrganizationDetailsQueries.scala`, `OrganizationUserQueries.scala`
- Completed-stage gate: `middleware/ServerMiddleware.scala` + `service/AuthorizationService.scala`
- Config: `OrganizationManagementConfig` (created-email retries)

## Tests

- Acceptance (see [acceptance-tests.md](../acceptance-tests.md)): `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/OrganizationManagementApiSpec.scala` — creation happy path (org + owner rows in DB), duplicate slug failure, plus missing/invalid token, disallowed stage, and validation cases
- Functional: `fun/OrganizationManagementServiceSpec.scala`
- Integration: `it/OrganizationManagementRepositorySpec.scala`
