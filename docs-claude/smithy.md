# Smithy — API contract standards

API contracts are smithy-first: shapes live under `backend/gateway/core/src/main/smithy/` and smithy4s generates the Scala code (`io.mesazon.gateway.smithy` package) at compile time. Services implement the generated trait (see the service pattern in existing `service/*.scala`). The auth-related traits declared here are enforced by the HTTP middleware — see [middleware.md](middleware.md) for how that works.

## Naming Conventions

### 1. Services

- Should be named `<Feature>Service` in `PascalCase`
- The file is named exactly after the service it contains
- ✅ `CustomerBookService`, `OrganizationManagementService`
- ❌ `CustomerBookApi`, `CustomersService`, `customerBookService`

### 2. Operations

- Should be named `{Action}{Entity}{HttpMethod}` (or `{Flow}{HttpMethod}` for flow endpoints) — the suffix always mirrors the HTTP method, even when the action is also `Get`
- Batch endpoints use the plural entity name in the operation name, URI and shapes
- ✅ `GetCustomersGet`, `InsertCustomersPost`, `UpdateCustomersPut`, `DeleteCustomersPost`, `CreateOrganizationPost`, `TokenRefreshPost`
- ❌ `CustomersGet`, `GetCustomers` (missing the method suffix), `InsertCustomer`, `CustomerInsert`, `UpdateCustomersPost` (when the method is PUT)

### 3. Request/response structures

- Should be named `<Operation>Request` / `<Operation>Response` and live in the feature's `domain/<Feature>.smithy`
- ✅ `InsertCustomersPostRequest`, `GetCustomerIndividualGetResponse`
- ❌ `CustomerInsertBody`, `GetCustomerResponse` (not matching the operation name)

### 4. Item structures and lists

- Every operation owns its models — never share a common entity structure between operations, even when the fields overlap
- Item structures are named `{Action}{Entity}`, matching the operation they belong to: `InsertCustomer` carries the entity's fields, `UpdateCustomer` a partial update (ID + optional fields), `GetCustomer` the full entity returned by the fetch (ID + fields)
- Batch payloads are named list shapes of a reusable item structure; lists are named as the item's plural without a `List` suffix (like `InvalidFields`)
- ✅ `list InsertCustomers { member: InsertCustomer }`, `list GetCustomers { member: GetCustomer }`, `customerIDs: CustomerIDs`
- ❌ `InsertCustomerList`, a shared `Customer`/`CustomerDetails` used by several operations, inlining the item fields into the request structure

### 5. Members

- Members are `camelCase`
- Identifiers are `alloy#UUID` (`use alloy#UUID`) named `<entity>ID`
- Durations sent to clients are `Long` members named `<thing>ExpiresInSeconds`
- Enum values are `SCREAMING_SNAKE_CASE` (`EMAIL_VERIFIED`); domain↔smithy enum mappers live in `service/service.scala`
- ✅ `customerID: UUID`, `otpExpiresInSeconds: Long`
- ❌ `customerId`, `customer_id`, `otpExpiresIn`

### 6. URIs

- `@http` URIs are verb-first kebab-case: the action comes first, the entity after it (plural for batches)
- ✅ `/insert/customers`, `/update/customers`, `/create/organization`
- ❌ `/customers/insert`, `/insert/customer` (for a batch endpoint)

## Coding Standards

### 1. File layout

- `<Feature>Service.smithy` at the top level — the service definition and its operations
- `domain/<Feature>.smithy` — the request/response structures for that feature
- `domain/HttpErrors.smithy` — shared error structures, never define per-feature error shapes
- `domain/Gateway.smithy` — shared enums, value shapes (e.g. `PhoneNumberRequest`), and the custom traits
- Service files declare `$version: "2"`; domain files declare `$version: "2.0"`
- The namespace is always `io.mesazon.gateway.smithy`
- ✅ `CustomerBookService.smithy` + `domain/CustomerBook.smithy`
- ❌ `customer-book.smithy`, `CustomerBook.smithy` (for the service file), everything in one file

### 2. Service definition

- Should always be annotated `@simpleRestJson` (`use alloy#simpleRestJson`)
- Auth annotation: `@httpBearerAuth` (access-token endpoints), `@httpBasicAuth` (credential endpoints), or none (public endpoints) — never both
- Add `@completedOnboardStage` when **every** endpoint requires completed onboarding
- Service-level `///` docs render as the OpenAPI `info.description` in swagger. When `@completedOnboardStage`, the **only** service doc needed is the onboarding marker `/// **Required Onboard Stage:** COMPLETED` — no other descriptive prose (§3 defines the marker wording)

### 3. Operations

- Body input is always a single wrapper member: `input := { @required @httpPayload request: <Operation>Request }`
- Identifiers travel in the request body; use `@httpLabel` path parameters for GET/bodyless operations (e.g. `/get/individual/{customerID}`) — except `organizationID`, which is always a header (see below)
- `code: 200` with an `output`; `code: 204` and no `output` for operations with nothing to return
- **`@http` placement**: the `@http` trait sits **immediately above the `operation` line**; any other operation traits (e.g. `@organizationUserRolesAllowed`) go above `@http`, so the method + URI always stay paired with the operation they describe
- Organization-scoped operations each carry `@organizationUserRolesAllowed(roles: [...])` declaring which roles may call them, following the **standard role policy**: reads (`GET`) allow `OWNER`/`ADMIN`/`USER`, mutations allow `OWNER`/`ADMIN`, and organization deletion allows `OWNER` only (see [Custom traits → standard role policy](#organizationuserrolesallowedroles-))
- **Swagger documentation markers** — document an operation's gates with two parallel bold-label `///` markers (they render into the operation `description`); keep the wording identical across the smithy and Tapir swaggers:
  - `/// **Required Onboard Stage:** [...]` — a bracketed list of `OnboardStage` enum values in backticks (e.g. `[`EMAIL_VERIFIED`]`), written **per operation**; use `` `N/A` `` for the pre-onboarding "no stage yet" state (e.g. the first sign-up call). Document stages per operation only — don't add a redundant service-level stage doc. The single exception is a `@completedOnboardStage` service, which drops the per-operation markers and carries one service-level `/// **Required Onboard Stage:** COMPLETED` instead
  - `/// **Required Organization User Roles:** [...]` — a bracketed list of role enum values in backticks (e.g. `[`OWNER`, `ADMIN`]`) on every `@organizationUserRolesAllowed` operation
  - Tapir mirrors both verbatim: the stage marker lives in the Tapir OpenAPI `Info.description` built in `FileServiceEndpoints.allRoutesAndDocsEndpoints` (global — every Tapir endpoint requires completed onboarding), and the roles marker is built by the shared `requiredOrganizationRolesDescription(roles)` helper (in `tapir/tapir.scala`) and passed to each endpoint's `.description(...)`
- `errors` lists only shapes from `domain/HttpErrors.smithy`: `[ValidationError, Unauthorized, InternalServerError]` as a base, plus `BadRequest` where the flow can reject a well-formed request (e.g. wrong OTP), plus `Forbidden` on every operation that can fail a role or onboard-stage check (`@organizationUserRolesAllowed`, `@completedOnboardStage`, or an in-handler `verifyOnboardStage`) — role and stage failures are `403`, not `401` — plus `Conflict` (409) where a write can collide with existing state (e.g. a duplicate customer)
- **Always order the `errors` list by HTTP status code, lowest first**; break ties (same code) alphabetically by shape name. The canonical order for the current `HttpErrors.smithy` shapes is: `BadRequest` (400), `ValidationError` (400), `Unauthorized` (401), `Forbidden` (403), `Conflict` (409), `InternalServerError` (500), `ServiceUnavailable` (503) — list whichever subset an operation declares in that relative order (e.g. `[BadRequest, ValidationError, Unauthorized, Forbidden, InternalServerError]`). Apply the same sort when adding a new error shape.
- **Keep `errors` lists in sync with the code**: whenever a `ServiceError` is added, re-homed under a different HTTP status, or a flow gains a new failure mode, update the `errors` list of **every affected operation** in the same change — the smithy contract is what clients and swagger see, and it silently lies if only the Scala side moves (this happened when `InvalidOnboardStage` became `403 Forbidden`)

### 4. Organization scoping — the `X-Organization-ID` header

- **`organizationID` never goes in the body or the URI**: organization-scoped endpoints carry it in the required `X-Organization-ID` header. Declare it **once** via the `OrganizationScopedInput` mixin (in `domain/Gateway.smithy`) and mix it into every org-scoped operation input, rather than repeating the header member per operation:

  ```smithy
  // domain/Gateway.smithy — declared once
  @mixin
  structure OrganizationScopedInput {
      @required
      @httpHeader("X-Organization-ID")
      organizationID: UUID
  }

  // each org-scoped operation mixes it in
  operation GetCustomerBusinessGet {
      input := with [OrganizationScopedInput] {
          @required
          @httpLabel
          businessID: UUID
      }
      // ...
  }
  ```

  Mixins flatten at model-build time, so smithy4s still generates `organizationID` as the operation's first method parameter **and** OpenAPI still renders `X-Organization-ID` as a required header parameter on every operation — the header is defined once but documented per operation. It stays an input member on purpose (not a service-level trait): it is a tenant **scope selector**, most truthfully documented as a header *parameter*, and the middleware reads the header off the raw request itself regardless (see [middleware.md](middleware.md)).
- Rationale: the middleware can read a fixed header without parsing the body (impossible for GETs and streaming uploads), and URIs stay untouched by the scoping standard
- **Make the role requirement obvious in swagger** — neither the `@organizationUserRolesAllowed` trait nor the middleware role check appears in the generated OpenAPI, so a reader of the swagger learns *which role is required* only from the operation's `/// **Required Organization User Roles:** [...]` doc comment (which becomes the operation `description`)
- **Tapir endpoints follow the same standard** — the header is declared as a typed `securityIn` (`header[OrganizationID](AuthorizationService.OrganizationIDHeader.toString)`) and passed to `AuthorizationService.auth` together with the endpoint's allowed roles; missing required headers (`Authorization`, `X-Organization-ID`) are a generic `400 BadRequest` and disallowed-role failures a `403 Forbidden` on **both** transports (see `FileServiceEndpoints.scala` and [middleware.md](middleware.md)). Because the Tapir role check runs inside `zServerSecurityLogic` (invisible to OpenAPI), give the endpoint a `.description(requiredOrganizationRolesDescription(...))` passing the same `OrganizationUserRole` list the security logic enforces (e.g. `OrganizationUserRole.adminRoles`) — the shared helper renders the identical `**Required Organization User Roles:** [...]` marker used on the smithy operations, so the Tapir swagger states the required roles too and cannot drift from the enforced list
- ✅ `input := with [OrganizationScopedInput] { ... }` on every org-scoped operation
- ❌ redeclaring `@httpHeader("X-Organization-ID") organizationID: UUID` inline per operation instead of mixing in `OrganizationScopedInput`, `organizationID` as a request-body field or path parameter (`/insert/customers/{organizationID}`), a Tapir endpoint doing its own org check differently from the smithy middleware

## Custom traits

All custom traits live in `domain/Gateway.smithy` and are enforced by [the HTTP middleware](middleware.md) via smithy4s service hints.

### `@completedOnboardStage`

- Service-level marker trait (no members)
- Declares that every endpoint requires the caller to have **completed onboarding** (`OnboardStage.completedStages` = `PhoneVerified`), on top of a valid bearer token
- Used by: `OrganizationManagementService`, `CustomerBookService`

### `@organizationUserRolesAllowed(roles: [...])`

- **Operation-level** trait carrying the list of `OrganizationUserRole`s (`OWNER`, `ADMIN`, `USER` — mirrors the Scala domain enum `OrganizationUserRole`) allowed to call that operation, so permissions can differ per endpoint within one service
- The caller must be **assigned to the organization** identified by the `X-Organization-ID` header **with one of the declared roles**; a missing header is `400 BadRequest`, a member with a disallowed role is `403 Forbidden`, and a caller with no membership row at all is a `500 InternalServerError` (treated like any missing referenced entity)
- Enum values must be quoted in the trait node value: `@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])`
- Always used together with the `OrganizationScopedInput` mixin (section above) — the mixin declares *which organization* the request is scoped to (the header), this trait declares *who* may call

**Standard role policy (apply to every org-scoped operation unless a feature has a documented reason to differ):**

| Operation kind | Allowed roles | Rationale |
|---|---|---|
| **Reads** — any `GET` that views data | `OWNER`, `ADMIN`, `USER` | A `USER` may **always** view org data. Every `GET` includes `USER`. |
| **Writes/actions** — `POST`/`PUT`/`DELETE` that mutate data | `OWNER`, `ADMIN` | A `USER` can look but not touch — no create/update/delete. |
| **Deleting the organization** | `OWNER` only | The most destructive action; an `ADMIN` cannot delete the org, only its `OWNER` can. |

So the rule of thumb is: `USER` on reads, drop to `OWNER`/`ADMIN` on any mutation, and narrow further to `OWNER` alone for org deletion. When you add a `GET`, it gets `["OWNER", "ADMIN", "USER"]`; when you add a mutation, `["OWNER", "ADMIN"]` (or `["OWNER"]` for org-delete). Keep the `/// **Required Organization User Roles:** [...]` marker in sync with the trait.

- Used by: `CustomerBookService` — reads (`GetCustomerIndividualGet`, `GetCustomerBusinessGet`, `GetCustomersGet`) allow `OWNER`/`ADMIN`/`USER`; every write (`InsertCustomer*`, `UpdateCustomer*`, `AddCustomerBusinessContactsPut`, `RemoveCustomerBusinessContactsPut`) allows `OWNER`/`ADMIN` only
