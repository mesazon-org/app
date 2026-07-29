# API contracts (Smithy)

Project-agnostic contract-first HTTP API standards: naming services/operations/shapes/members/URIs, file layout, declaring auth/authz in the contract, and referencing generated types without confusing them with domain models. Written around [Smithy](https://smithy.io/)/[smithy4s](https://disneystreaming.github.io/smithy4s/) (shapes under `smithy/`, Scala generated at compile time) but applies to any contract-first stack (OpenAPI-first, Protobuf/gRPC).

Not owned here: enforcement ([middleware-new.md](../middleware-new.md)), generated→domain validation ([validators-new.md](../validators-new.md)), refined newtype naming ([iron-new.md](iron-new.md)), general Scala/testing ([scala-new.md](scala-new.md)), alternate transports ([tapir-new.md](tapir-new.md)).

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to this contract. Concrete values for this codebase: [smithy-project.md](smithy-project.md).

## Naming conventions

### Services

- `<Feature>Service` in `PascalCase`; file named exactly after the service.
- ✅ `CustomerBookService`, `OrganizationManagementService`
- ❌ `CustomerBookApi`, `CustomersService`, `customerBookService`

### Operations

- `{Action}{Entity}{HttpMethod}` (or `{Flow}{HttpMethod}` for flow endpoints) — suffix always mirrors the HTTP method, even when the action is also `Get`. Batch endpoints use the plural entity name in the operation name, URI, and shapes.
- ✅ `GetCustomersGet`, `InsertCustomersPost`, `UpdateCustomersPut`, `DeleteCustomersPost`, `CreateOrganizationPost`, `TokenRefreshPost`
- ❌ `CustomersGet`, `GetCustomers` (missing method suffix), `InsertCustomer`, `CustomerInsert`, `UpdateCustomersPost` (method is PUT)

### Request/response structures

- `<Operation>Request` / `<Operation>Response`, in the feature's contract-domain file.
- ✅ `InsertCustomersPostRequest`, `GetCustomerIndividualGetResponse`
- ❌ `CustomerInsertBody`, `GetCustomerResponse` (doesn't match operation name)

**Contract names are the gold standard.** The domain case class a request validates into is named **exactly** after its generated request structure (generated `CreateOrganizationPostRequest` → domain `case class CreateOrganizationPostRequest`), distinguished only by qualification (next section), never a second name for the same shape. Rename one side, rename the other (and the docs, per the codebase rename rule).

### Referencing generated types

**Always reference qualified.** Import the generator's package (`import io.mesazon.gateway.smithy`) and write `smithy.CreateOrganizationPostRequest`; never import a generated member directly — the package prefix is what distinguishes wire shape from domain model when both share a name.

**Bare name = domain; generated name takes a `Smithy` suffix**, everywhere both are in scope:

- Service handler: generated param named after its type + `Smithy` (`createOrganizationPostRequestSmithy: smithy.CreateOrganizationPostRequest`); validator output named after the domain type (`createOrganizationPostRequest`). Same for the impl and any observed/wrapped variant.
- Tests: domain sample named after its type (`insertCustomerIndividualPostRequest`); generated sample takes `Smithy` (`insertCustomerIndividualPostRequestSmithy`) — same disambiguation the test arbitraries use ([adding-a-feature-new.md § naming the givens](../adding-a-feature-new.md)).

This specializes the general "name a binding after its precise type, not a vague role word" rule ([scala-new.md § General principles](scala-new.md#general-principles)).

### Item structures and lists

- Every operation owns its models — never share an entity structure across operations, even with overlapping fields.
- Item structures: `{Action}{Entity}`, matching the owning operation — `InsertCustomer` (entity fields), `UpdateCustomer` (ID + optional fields), `GetCustomer` (ID + full fields).
- Batch payloads: list shapes of a reusable item structure, named as the item's plural, no `List` suffix.
- ✅ `list InsertCustomers { member: InsertCustomer }`, `list GetCustomers { member: GetCustomer }`, `customerIDs: CustomerIDs`
- ❌ `InsertCustomerList`, a shared `Customer`/`CustomerDetails` across operations, inlining item fields into the request
- Contact-point entries (value + flag, e.g. email/phone + `isDefault`): `<Owner><Kind>EntryRequest`, list `<Owner><Kind>EntryRequests`; domain entry class carries the same name.
- ✅ `CustomerEmailEntryRequest` / `CustomerEmailEntryRequests`, `OrganizationPhoneNumberEntryRequest`
- ❌ `CustomerEmailRequest` (reads as a whole request), `CustomerEmail` (collides with the newtype)
- **Request-list members default to `[]`, never required.** A `[]`-default list renders as non-optional `List[X] = List()` — same type a required list produces — but decodes an absent field to empty. Required would 400 whenever a client omits an empty list, since JSON clients commonly drop empty collections on the wire (see [[jsoniter-transient-empty-required-lists]]). `[]`-default makes omitted-or-empty decode to `Nil` on every transport; the validator treats empty as valid where the rule is "exactly one default when non-empty." **Response** list members stay required — the server always populates them.
- ✅ `@default([]) emails: CustomerEmailEntryRequests` on a request; `@required emails: CustomerEmailEntryRequests` on a response
- ❌ required list on a request (rejects an omitted empty list), hand-rolled `Option[List[X]]` + `getOrElse(Nil)` instead of the `[]` default

### Members

- `camelCase`. Identifiers: the IDL's UUID type, named `<entity>ID`. Durations to clients: integer, named `<thing>ExpiresInSeconds`. Enum values: `SCREAMING_SNAKE_CASE`; domain↔contract enum mappers live in one central file.
- ✅ `customerID: UUID`, `otpExpiresInSeconds: Long`
- ❌ `customerId`, `customer_id`, `otpExpiresIn`

### URIs

- Verb-first kebab-case: action first, entity after (plural for batches).
- ✅ `/insert/customers`, `/update/customers`, `/create/organization`
- ❌ `/customers/insert`, `/insert/customer` (batch endpoint)

## Coding standards

### File layout

- `<Feature>Service.<ext>` — service definition + operations. `domain/<Feature>.<ext>` — request/response structures. `domain/HttpErrors.<ext>` — shared error structures, never per-feature. `domain/Gateway.<ext>` — shared enums, value shapes, custom traits.
- Consistent IDL version pragma per file type; one stable namespace for the whole contract.
- ✅ `CustomerBookService.smithy` + `domain/CustomerBook.smithy`
- ❌ `customer-book.smithy`, `CustomerBook.smithy` for the service file, everything in one file

### Service definition

- Annotate with the REST/JSON protocol trait the generator expects.
- Auth annotation at the **service** level: bearer-auth, basic-auth, or none — never both.
- Add the completed-onboarding marker trait when **every** endpoint requires it.
- Service doc comments render as the generated API description. With the onboarding marker present, the only service doc needed is the marker line — no other prose.

### Operation rules

- Body input: single required payload member wrapping `<Operation>Request`. Identifiers travel in the body; path-label params for GET/bodyless ops — except the tenant id, always a header (below).
- **Trait placement**: the method/URI trait sits immediately above the `operation` line; other operation traits go above that, so method+URI stay paired with their operation.
- Tenant-scoped operations carry a role-allow-list trait per the standard role policy (below, [role-allow-list trait](#role-allow-list-trait)).
- **Swagger markers** — parallel bold-label doc comments rendering into the operation `description`, worded identically across every transport's generated docs:
  - required-onboard-stage: bracketed stage-enum list in backticks, per operation; `` `N/A` `` for pre-onboarding state. A fully-onboarding-gated service drops per-operation markers for one service-level marker.
  - required-roles: bracketed role-enum list in backticks, on every role-gated operation.
  - An alternate transport mirrors both via a shared helper so the docs can't drift ([tapir-new.md](tapir-new.md)).
- **`errors`**: base `[ValidationError, Unauthorized, InternalServerError]`, plus `BadRequest` where a well-formed request can still be rejected, plus `Forbidden` on any role/onboard-stage-gated operation (role/stage failures are `403`, not `401`), plus `Conflict` (409) where a write can collide with existing state.
- **Order `errors` by HTTP status ascending**, ties alphabetical by shape name: `BadRequest`(400), `ValidationError`(400), `Unauthorized`(401), `Forbidden`(403), `Conflict`(409), `InternalServerError`(500), `ServiceUnavailable`(503) — list whichever subset in that relative order; apply the same sort to any new error shape.
- **Keep `errors` in sync with the code**: any error added, re-homed under a different status, or new failure mode on a flow updates the `errors` list of every affected operation in the same change — the contract is what clients/swagger see.

### Organization scoping (tenant header)

- **Tenant id never in body or URI** — required header (`X-Organization-ID`), declared once via a shared mixin, mixed into every scoped operation input:

  ```smithy
  @mixin
  structure OrganizationScopedInput {
      @required
      @httpHeader("X-Organization-ID")
      organizationID: UUID
  }

  operation GetCustomerBusinessGet {
      input := with [OrganizationScopedInput] {
          @required
          @httpLabel
          businessID: UUID
      }
  }
  ```

  Mixins flatten at model-build time: the generator still emits the tenant id as the first method parameter, and OpenAPI still renders the header as required on every operation — declared once, documented per operation. It stays an input member, not a service-level trait, because it's a tenant **scope selector** most truthfully modeled as a header parameter, and the middleware reads it off the raw request regardless.
- Rationale: a fixed header is readable without parsing the body (impossible for GETs/streaming uploads); URIs stay untouched.
- **Role requirement must be visible in swagger** — neither the role trait nor the middleware check appears in generated OpenAPI, so the required-roles doc-comment marker is the only place a swagger reader learns which role is needed.
- **An alternate transport follows the same standard**: header as a typed security input, passed to shared authorization logic with the endpoint's allowed roles; missing required headers → generic `400`; disallowed role → `403`, on both transports. Since a hand-wired transport's role check is invisible to OpenAPI, its endpoint description uses the same shared roles-marker helper so its swagger can't drift ([tapir-new.md](tapir-new.md)).
- ✅ `input := with [OrganizationScopedInput] { ... }` on every scoped operation
- ❌ header redeclared inline per operation, tenant id as a body field or path parameter, a hand-wired transport doing its own tenant check differently from the middleware

## Custom traits

Live in the shared domain file, enforced by [the HTTP middleware](../middleware-new.md) via the generator's service/endpoint hints — contract *declares*, middleware *enforces*.

### Completed-onboarding marker

- Service-level marker trait, no members. Declares every endpoint requires completed onboarding, on top of a valid bearer token.
- Concretely: `@completedOnboardStage`, used by `OrganizationManagementService`, `CustomerBookService`.

### Role-allow-list trait

- **Operation-level**, carries the roles allowed to call that operation — permissions can differ per endpoint in one service.
- Caller must be assigned to the tenant identified by the required header, with one of the declared roles. Missing header → `400`; disallowed role → `403`; no membership at all → `500` (treated like any missing referenced entity).
- Enum values quoted in the trait node: `@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])`.
- Always paired with the tenant-header mixin — mixin declares *which* tenant, this trait declares *who*.

**Standard role policy** (every tenant-scoped operation, unless a feature has a documented reason to differ):

| Operation kind | Allowed roles | Rationale |
|---|---|---|
| **Reads** — any `GET` | `OWNER`, `ADMIN`, `USER` | `USER` may always view tenant data. |
| **Writes** — `POST`/`PUT`/`DELETE` that mutate | `OWNER`, `ADMIN` | `USER` can look, not touch. |
| **Deleting the tenant itself** | `OWNER` only | Most destructive action; `ADMIN` can't delete the tenant. |

`USER` on reads, `OWNER`/`ADMIN` on mutation, `OWNER` alone for tenant deletion. Keep the required-roles doc marker in sync with the trait. Concretely, `CustomerBookService` reads allow `OWNER`/`ADMIN`/`USER`; every write allows `OWNER`/`ADMIN` only.
