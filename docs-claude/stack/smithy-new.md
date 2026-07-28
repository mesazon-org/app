# API contracts (Smithy)

This document defines **project-agnostic standards for a contract-first HTTP API**, where the API surface is declared in an interface-definition language and the server code is generated from it, rather than hand-written and documented after the fact. It is written around [Smithy](https://smithy.io/) with the [smithy4s](https://disneystreaming.github.io/smithy4s/) code generator (shapes under a `smithy/` source directory generate Scala at compile time; services implement the generated trait), but the rules apply to any contract-first stack (OpenAPI-first, Protobuf/gRPC, etc.) where a schema is the source of truth and code is generated from it.

Use it for the conventions that stay true regardless of the specific IDL or generator:

- naming services, operations, request/response shapes, members, and URIs;
- file/layout organization of the contract;
- how authentication/authorization requirements are **declared in the contract** (and enforced elsewhere);
- how to reference generated types from hand-written code without confusing them with domain models.

Do **not** place enforcement, validation, persistence, or general-language rules here. Those belong to the owning document:

- how the declared auth traits are **enforced** at request time → [middleware-new.md](../middleware-new.md);
- turning a generated request into a refined domain model and accumulating field errors → [validators-new.md](../validators-new.md);
- refined newtype naming for identifier/value members → [iron-new.md](iron-new.md);
- general Scala naming, immutability, and test rules → [scala-new.md](scala-new.md);
- an alternate transport for routes the contract can't express (e.g. streaming uploads) → [tapir-new.md](tapir-new.md).

## Table of contents

- [Scope](#scope)
- [Naming conventions](#naming-conventions)
  - [Services](#services)
  - [Operations](#operations)
  - [Request/response structures](#requestresponse-structures)
  - [Referencing generated types — domain vs generated shapes](#referencing-generated-types--domain-vs-generated-shapes)
  - [Item structures and lists](#item-structures-and-lists)
  - [Members](#members)
  - [URIs](#uris)
- [Coding standards](#coding-standards)
  - [File layout](#file-layout)
  - [Service definition](#service-definition)
  - [Operations coding standards](#operations-coding-standards)
  - [Organization scoping — a required tenant header](#organization-scoping--a-required-tenant-header)
- [Custom traits](#custom-traits)
  - [A service-level "completed onboarding" marker](#a-service-level-completed-onboarding-marker)
  - [An operation-level role-allow-list trait](#an-operation-level-role-allow-list-trait)

## Scope

This document owns **what the contract looks like** — the names, layout, and declarations that make up the API definition — and the one hand-written-code rule that a contract-first stack forces: how to reference generated types so they don't collide with the domain models they map to.

It does **not** own what *happens* at request time. The contract merely *declares* that an operation requires a bearer token, a completed onboarding stage, or a particular role; a separate middleware layer reads those declarations and enforces them ([middleware-new.md](../middleware-new.md)). Likewise, the contract declares a request's shape, but turning that shape into a trusted domain value is validation ([validators-new.md](../validators-new.md)).

## Naming conventions

### Services

- Name a service `<Feature>Service` in `PascalCase`; the file is named exactly after the service it contains.
- ✅ `CustomerBookService`, `OrganizationManagementService`
- ❌ `CustomerBookApi`, `CustomersService`, `customerBookService`

### Operations

- Name an operation `{Action}{Entity}{HttpMethod}` (or `{Flow}{HttpMethod}` for flow endpoints) — the suffix always mirrors the HTTP method, even when the action is also `Get`.
- Batch endpoints use the plural entity name in the operation name, URI, and shapes.
- ✅ `GetCustomersGet`, `InsertCustomersPost`, `UpdateCustomersPut`, `DeleteCustomersPost`, `CreateOrganizationPost`, `TokenRefreshPost`
- ❌ `CustomersGet`, `GetCustomers` (missing the method suffix), `InsertCustomer`, `CustomerInsert`, `UpdateCustomersPost` (when the method is PUT)

### Request/response structures

- Name request/response shapes `<Operation>Request` / `<Operation>Response`, living in the feature's contract-domain file.
- ✅ `InsertCustomersPostRequest`, `GetCustomerIndividualGetResponse`
- ❌ `CustomerInsertBody`, `GetCustomerResponse` (not matching the operation name)

**Contract names are the gold standard.** The domain case class a request validates into is named **exactly** after its generated request structure — generated `CreateOrganizationPostRequest` validates into domain `case class CreateOrganizationPostRequest`; generated `InsertCustomerIndividualPostRequest` into domain `InsertCustomerIndividualPostRequest`. The two are distinguished purely by qualification (next section), never by inventing a second name for the same shape. Renaming one side renames the other (and, per the codebase's rename rule, the docs).

### Referencing generated types — domain vs generated shapes

> This section absorbs the rule that was previously stated as a general Scala naming rule; it belongs here because it is a direct consequence of contract-first codegen, where a generated wire type and its refined domain counterpart share a name on purpose.

**Generated types are always referenced qualified.** Hand-written code imports the *package* the generator emits into (e.g. `import io.mesazon.gateway.smithy`, or grouped with the feature's imports as `io.mesazon.gateway.{smithy, ...}`) and writes `smithy.CreateOrganizationPostRequest`. Never import a generated member directly (`import io.mesazon.gateway.smithy.SomeShape` ❌): because the domain and generated shapes share names, the package prefix is what tells the wire shape from the domain model at every use site.

**When both shapes are in scope, the bare name is the domain one and the generated one takes a `Smithy` suffix.** This is the disambiguation to apply everywhere the two coexist:

- **Service handler** (implementing the generated trait): name the generated request parameter after the full request type + `Smithy` (`createOrganizationPostRequestSmithy: smithy.CreateOrganizationPostRequest`), and the validator's output — the domain model — after the domain type (`createOrganizationPostRequest`). Do this for the whole feature's handlers, both the impl and any observed/wrapped variant.
- **Tests**: a value holding a domain sample is named after its type (`insertCustomerIndividualPostRequest`), a value holding a generated sample takes the `Smithy` suffix (`insertCustomerIndividualPostRequestSmithy`) — the same disambiguation the test arbitraries use (see [adding-a-feature-new.md § naming the givens](../adding-a-feature-new.md)).

The general "name a binding after its precise type, not a vague role word" rule lives in [scala-new.md § General principles](scala-new.md#general-principles); this is its contract-first specialization.

### Item structures and lists

- Every operation owns its models — never share a common entity structure between operations, even when the fields overlap.
- Item structures are named `{Action}{Entity}`, matching the operation they belong to: `InsertCustomer` carries the entity's fields, `UpdateCustomer` a partial update (ID + optional fields), `GetCustomer` the full entity returned by the fetch (ID + fields).
- Batch payloads are named list shapes of a reusable item structure; lists are named as the item's plural without a `List` suffix.
- ✅ `list InsertCustomers { member: InsertCustomer }`, `list GetCustomers { member: GetCustomer }`, `customerIDs: CustomerIDs`
- ❌ `InsertCustomerList`, a shared `Customer`/`CustomerDetails` used by several operations, inlining the item fields into the request structure
- Contact-point entry structures (a value plus a flag, e.g. an email/phone plus its `isDefault`) are named `<Owner><Kind>EntryRequest`, list `<Owner><Kind>EntryRequests` — and the domain entry class carries the same name.
- ✅ `CustomerEmailEntryRequest` / `CustomerEmailEntryRequests`, `OrganizationPhoneNumberEntryRequest`
- ❌ `CustomerEmailRequest` (reads as a whole request, not a list entry), `CustomerEmail` (collides with the newtype)
- **List members on *request* structures use an empty-list default, never required.** A request-list member declared with a default of `[]` renders as a non-optional `List[X] = List()` — the same Scala type a required list would produce — but the decoder fills it with **empty when the field is absent** from the body. This matters because JSON clients commonly drop empty collections by default, so a *required* list would reject with a "missing required field" decode error whenever a caller sends no entries — see the general trap in [[jsoniter-transient-empty-required-lists]]. A `[]` default makes an omitted-or-empty list decode to `Nil` on every transport, and the validator treats an empty list as valid (contact lists only require *exactly one default when non-empty*). The domain model stays `List[X]` and validators need no `Option`-to-empty handling. **Response** list members stay required — the server always populates them.
- ✅ `@default([]) emails: CustomerEmailEntryRequests` on `InsertCustomerBusinessPostRequest`; `@required emails: CustomerEmailEntryRequests` on `GetCustomerBusinessGetResponse`
- ❌ a required list on a request structure (rejects when a client omits an empty list), hand-rolling `Option[List[X]]` + `getOrElse(Nil)` in the validator instead of the `[]` default

### Members

- Members are `camelCase`.
- Identifiers use the IDL's UUID type, named `<entity>ID`.
- Durations sent to clients are integer members named `<thing>ExpiresInSeconds`.
- Enum values are `SCREAMING_SNAKE_CASE` (`EMAIL_VERIFIED`); domain↔contract enum mappers live in one central mapping file.
- ✅ `customerID: UUID`, `otpExpiresInSeconds: Long`
- ❌ `customerId`, `customer_id`, `otpExpiresIn`

### URIs

- URIs are verb-first kebab-case: the action comes first, the entity after it (plural for batches).
- ✅ `/insert/customers`, `/update/customers`, `/create/organization`
- ❌ `/customers/insert`, `/insert/customer` (for a batch endpoint)

## Coding standards

### File layout

- `<Feature>Service.<ext>` at the top level — the service definition and its operations.
- `domain/<Feature>.<ext>` — the request/response structures for that feature.
- `domain/HttpErrors.<ext>` — shared error structures, never per-feature error shapes.
- `domain/Gateway.<ext>` — shared enums, value shapes, and the custom traits.
- Keep the IDL version pragma consistent per file type (service files vs domain files) as the toolchain requires.
- Use one stable namespace for the whole contract.
- ✅ `CustomerBookService.smithy` + `domain/CustomerBook.smithy`
- ❌ `customer-book.smithy`, `CustomerBook.smithy` (for the service file), everything in one file

### Service definition

- Annotate the service with the REST/JSON protocol trait the generator expects.
- Auth annotation is declared at the **service** level: a bearer-auth trait (access-token endpoints), a basic-auth trait (credential endpoints), or none (public endpoints) — never both.
- Add the "completed onboarding" marker trait when **every** endpoint in the service requires completed onboarding.
- Service-level doc comments render as the API-description in the generated OpenAPI. When the completed-onboarding marker is present, the **only** service doc needed is the onboarding marker line — no other descriptive prose.

### Operations coding standards

- Body input is always a single wrapper member: one required payload member carrying the `<Operation>Request`.
- Identifiers travel in the request body; use path-label parameters for GET/bodyless operations — except the tenant identifier, which is always a header (see below).
- Return `200` with an output, or `204` and no output for operations with nothing to return.
- **HTTP-method + URI placement**: the method/URI trait sits **immediately above the `operation` line**; any other operation traits go above it, so the method + URI always stay paired with the operation they describe.
- Tenant-scoped operations each carry a role-allow-list trait declaring which roles may call them, following the **standard role policy** (see [the role-allow-list trait](#an-operation-level-role-allow-list-trait)).
- **Swagger documentation markers** — document an operation's gates with parallel bold-label doc-comment markers (they render into the operation `description`); keep the wording identical across every transport's generated docs:
  - a required-onboard-stage marker — a bracketed list of stage enum values in backticks, written **per operation**; use `` `N/A` `` for a pre-onboarding "no stage yet" state. The single exception is a fully-onboarding-gated service, which drops the per-operation markers and carries one service-level marker instead.
  - a required-roles marker — a bracketed list of role enum values in backticks on every role-gated operation.
  - An alternate transport mirrors both verbatim via a shared helper so the two docs cannot drift (see [tapir-new.md](tapir-new.md)).
- The `errors` list declares only shapes from the shared errors file: a base of `[ValidationError, Unauthorized, InternalServerError]`, plus `BadRequest` where a well-formed request can still be rejected, plus `Forbidden` on every operation that can fail a role or onboard-stage check (role/stage failures are `403`, not `401`), plus `Conflict` (409) where a write can collide with existing state.
- **Always order the `errors` list by HTTP status code, lowest first**; break ties alphabetically by shape name. Canonical order: `BadRequest` (400), `ValidationError` (400), `Unauthorized` (401), `Forbidden` (403), `Conflict` (409), `InternalServerError` (500), `ServiceUnavailable` (503) — list whichever subset an operation declares in that relative order. Apply the same sort when adding a new error shape.
- **Keep `errors` lists in sync with the code**: whenever an error is added, re-homed under a different status, or a flow gains a new failure mode, update the `errors` list of **every affected operation** in the same change — the contract is what clients and swagger see, and it silently lies if only the server code moves.

### Organization scoping — a required tenant header

- **The tenant identifier never goes in the body or the URI**: tenant-scoped endpoints carry it in a required header (`X-Organization-ID` here). Declare it **once** via a shared mixin and mix it into every scoped operation input, rather than repeating the header member per operation:

  ```smithy
  // declared once, in the shared domain file
  @mixin
  structure OrganizationScopedInput {
      @required
      @httpHeader("X-Organization-ID")
      organizationID: UUID
  }

  // each scoped operation mixes it in
  operation GetCustomerBusinessGet {
      input := with [OrganizationScopedInput] {
          @required
          @httpLabel
          businessID: UUID
      }
      // ...
  }
  ```

  Mixins flatten at model-build time, so the generator still emits the tenant id as the operation's first method parameter **and** the OpenAPI still renders the header as a required parameter on every operation — defined once, documented per operation. It stays an input member on purpose (not a service-level trait): it is a tenant **scope selector**, most truthfully documented as a header *parameter*, and the middleware reads the header off the raw request regardless.
- Rationale: the middleware can read a fixed header without parsing the body (impossible for GETs and streaming uploads), and URIs stay untouched by the scoping standard.
- **Make the role requirement obvious in swagger** — because neither the role-allow-list trait nor the middleware role check appears in the generated OpenAPI, a swagger reader learns *which role is required* only from the operation's required-roles doc-comment marker (which becomes the operation `description`).
- **An alternate transport follows the same standard** — the header is declared as a typed security input and passed to the shared authorization logic with the endpoint's allowed roles; missing required headers are a generic `400`, disallowed-role failures a `403`, on **both** transports. Because a hand-wired transport's role check is invisible to OpenAPI generation, give its endpoint a description built by the same shared roles-marker helper so its swagger states the required roles and cannot drift from the enforced list (see [tapir-new.md](tapir-new.md)).
- ✅ `input := with [OrganizationScopedInput] { ... }` on every scoped operation
- ❌ redeclaring the header inline per operation, the tenant id as a body field or path parameter, a hand-wired transport doing its own tenant check differently from the middleware

## Custom traits

Custom traits live in the shared domain file and are enforced by [the HTTP middleware](../middleware-new.md) via the generator's service/endpoint hints — the contract *declares*, the middleware *enforces*.

### A service-level "completed onboarding" marker

- A service-level marker trait (no members).
- Declares that every endpoint requires the caller to have **completed onboarding**, on top of a valid bearer token.
- Concretely: `@completedOnboardStage`, used by `OrganizationManagementService` and `CustomerBookService`.

### An operation-level role-allow-list trait

- An **operation-level** trait carrying the list of roles allowed to call that operation, so permissions can differ per endpoint within one service.
- The caller must be **assigned to the tenant** identified by the required header **with one of the declared roles**; a missing header is `400`, a member with a disallowed role is `403`, and a caller with no membership at all is a `500` (treated like any missing referenced entity).
- Enum values are quoted in the trait node value: `@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])`.
- Always used together with the tenant-header mixin — the mixin declares *which tenant* the request is scoped to, this trait declares *who* may call.

**Standard role policy (apply to every tenant-scoped operation unless a feature has a documented reason to differ):**

| Operation kind | Allowed roles | Rationale |
|---|---|---|
| **Reads** — any `GET` that views data | `OWNER`, `ADMIN`, `USER` | A `USER` may **always** view tenant data. Every `GET` includes `USER`. |
| **Writes/actions** — `POST`/`PUT`/`DELETE` that mutate data | `OWNER`, `ADMIN` | A `USER` can look but not touch — no create/update/delete. |
| **Deleting the tenant itself** | `OWNER` only | The most destructive action; an `ADMIN` cannot delete the tenant, only its `OWNER` can. |

So the rule of thumb is: `USER` on reads, drop to `OWNER`/`ADMIN` on any mutation, and narrow further to `OWNER` alone for tenant deletion. Keep the required-roles doc marker in sync with the trait. Concretely, `CustomerBookService` reads allow `OWNER`/`ADMIN`/`USER` and every write allows `OWNER`/`ADMIN` only.
