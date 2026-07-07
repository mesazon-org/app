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
- ✅ `InsertCustomersPostRequest`, `GetCustomerGetResponse`
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
- Should document global requirements with `///` doc comments above the service (they render in swagger)

### 3. Operations

- Body input is always a single wrapper member: `input := { @required @httpPayload request: <Operation>Request }`
- Identifiers travel in the request body; use `@httpLabel` path parameters for GET/bodyless operations (e.g. `/get/customer/{customerID}`) — except `organizationID`, which is always a header (see below)
- `code: 200` with an `output`; `code: 204` and no `output` for operations with nothing to return
- Organization-scoped operations each carry `@organizationRolesAllowed(roles: [...])` declaring which roles may call them — reads are typically open to every member, writes to `OWNER`/`ADMIN` only (see [Custom traits](#custom-traits))
- Should document each operation's allowed stages with `/// **Required Onboard Stage:** [...]` (omit when the service is `@completedOnboardStage`) and its allowed roles with `/// **Allowed Organization Roles:** [...]`
- `errors` lists only shapes from `domain/HttpErrors.smithy`: `[Unauthorized, ValidationError, InternalServerError]` as a base, plus `BadRequest` where the flow can reject a well-formed request (e.g. wrong OTP), plus `Forbidden` on every operation carrying `@organizationRolesAllowed` (role failures are `403`, not `401`)

### 4. Organization scoping — the `X-Organization-ID` header

- **`organizationID` never goes in the body or the URI**: organization-scoped endpoints carry it in the required `X-Organization-ID` header, declared as an input member on **every** operation of the service:

  ```smithy
  @required
  @httpHeader("X-Organization-ID")
  organizationID: UUID
  ```

- Rationale: the middleware can read a fixed header without parsing the body (impossible for GETs and streaming uploads), and URIs stay untouched by the scoping standard
- **Tapir endpoints must behave identically to smithy** — the header is declared as a `securityIn` (`header[Option[String]](AuthorizationService.OrganizationIDHeader.toString)`) and passed to `AuthorizationService.auth` together with the endpoint's allowed roles, so missing header/membership/role failures produce the same 401/403 responses on both transports (see `TapirEndpoints.scala` and [middleware.md](middleware.md))
- ✅ `@httpHeader("X-Organization-ID") organizationID: UUID` on every org-scoped operation input
- ❌ `organizationID` as a request-body field or path parameter (`/insert/customers/{organizationID}`), a Tapir endpoint doing its own org check differently from the smithy middleware

## Custom traits

All custom traits live in `domain/Gateway.smithy` and are enforced by [the HTTP middleware](middleware.md) via smithy4s service hints.

### `@completedOnboardStage`

- Service-level marker trait (no members)
- Declares that every endpoint requires the caller to have **completed onboarding** (`OnboardStage.completedStages` = `PhoneVerified`), on top of a valid bearer token
- Used by: `OrganizationManagementService`, `CustomerBookService`

### `@organizationRolesAllowed(roles: [...])`

- **Operation-level** trait carrying the list of `OrganizationUserRole`s (`OWNER`, `ADMIN`, `USER` — mirrors the Scala domain enum `UserRole`) allowed to call that operation, so permissions can differ per endpoint within one service
- The caller must be **assigned to the organization** identified by the `X-Organization-ID` header **with one of the declared roles**; a missing/malformed header is `401 Unauthorized`, while not being a member or having a disallowed role is `403 Forbidden`
- Enum values must be quoted in the trait node value: `@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])`
- Always used together with the `X-Organization-ID` header input member (section above) — the trait declares *who* may call, the header declares *which organization* the request is scoped to
- Used by: `CustomerBookService` — reads (`GetCustomerGet`, `GetCustomersGet`) allow `OWNER`/`ADMIN`/`USER`, writes (insert/update/delete) allow `OWNER`/`ADMIN` only
