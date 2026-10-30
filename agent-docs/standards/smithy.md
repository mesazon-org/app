# Contract-first HTTP API

Reusable Smithy rules. Related: [Mesazon endpoints](../features/flow/01-endpoints.md), [authentication](../project/authentication.md), [validation](../features/flow/02-validation.md), [newtypes](iron.md), [Scala/tests](scala.md), [Chimney](chimney.md), [Tapir](tapir.md).

## Reference implementation

`CatalogueService.smithy` + `domain/Catalogue.smithy` (under `backend/gateway/core/src/main/smithy/`) are the canonical reference for the resource-oriented convention this standard describes: operation and shape names, resource paths and labels, the tenant mixin, role markers, error lists, and list defaults. Read both files alongside this standard before building a new endpoint.

`CustomerBookService.smithy` also follows this resource-oriented convention. Services that retain the legacy verb-first style should be read only to maintain their existing contracts; never copy that style into a new or reworked contract.

## Names

General:

- Service/file: `<Feature>Service` / `<Feature>Service.smithy`.
- Service/operation/shape names use `UpperCamelCase`.
- Contract name is canonical: validated domain request has the exact same name. Rename both together.
- Generated types are always package-qualified; import package, never members.
- When wire/domain types coexist: bare binding = domain; generated binding = full type name + `Smithy`. Applies to handler params, validated outputs, wrappers, tests, and arbitrary givens. Typed `GatewayClient` request parameters are an exception: use the plain request name without `Smithy`, since those methods accept only transport inputs.
- Members: `camelCase`; IDL UUID named `<entity>ID`; client duration integer named `<thing>ExpiresInSeconds`; enum values use `UpperCamelCase` and exactly match their mirrored domain enum cases. Follow [Chimney's enum rules](chimney.md#enums) when converting them.
- Member qualifiers: add a role qualifier only when the same shape holds more than one member it could otherwise be confused with. Put it as a suffix after the base noun, never as a verb prefix. `CatalogueItemPostRequest.variants` stays plain because a create carries only one kind of variant list. `CatalogueItemPutRequest` carries two, changes to existing variants and brand-new ones, so they are `variantsUpdate` and `variantsInsert`. Never qualify by reflex.

Operations (suffix always equals the actual HTTP method):

- Resource-oriented (new and reworked contracts): `{Entity}{PathQualifiers}{HttpMethod}`, no leading verb; the method carries it. `{Entity}` and `{PathQualifiers}` are the path's literal segments after the service, in order, labels dropped: `PUT /catalogue/item/{catalogueItemID}/archive` → `CatalogueItemArchivePut`, `POST /catalogue/item/batch` → `CatalogueItemBatchPost`, `GET /catalogue/item/{catalogueItemID}` → `CatalogueItemGet`. A read that returns a projection rather than the full resource names it in its own path segment (`GET /catalogue/item/summary` → `CatalogueItemSummaryGet`), which also keeps it apart from the by-ID read. If two operations would still get the same name, stop and ask; do not invent a qualifier that is not in the path.
- Legacy verb-first (existing live contracts, not renamed until reworked): `{Action}{Entity}{HttpMethod}` or `{Flow}{HttpMethod}`; batch operation/URI/shapes use the plural entity (`InsertCustomerIndividualPost` at `/insert/customer-individual`, `InsertCustomerIndividualsPost` at `/insert/customer-individuals`).

Shapes:

- Operation shapes: only the front-facing input/output shapes bind to the operation, named `<Operation>Request|Response` in the feature domain file, so they follow the operation's style: `CatalogueItemPostRequest`, `CatalogueItemSummaryGetResponse` (resource-oriented), `InsertCustomerIndividualPostRequest` (legacy).
- Nested shapes (resource-oriented): a plain domain name with no operation name or HTTP method in it. The full view of an entity is the entity name alone (`CatalogueItemVariant`). Any other view takes a role suffix after the entity, never a prefix: `CatalogueItemVariantInsert`, `CatalogueItemVariantUpdate`, `CatalogueItemVariantSummary`, `CatalogueItemSummary`. A list is the plural, with no `List` suffix. When the role is a noun, the role is pluralised (`CatalogueItemVariantSummaries`, `CatalogueItemSummaries`); when it is `Insert`/`Update`, the entity is (`CatalogueItemVariantsInsert`, `CatalogueItemsInsert`). Legacy contracts keep their verb-first nested names (`InsertCustomerBusinessContact`, `InsertCustomerIndividuals`) until reworked.
- Keep operation-level request/response wrappers named for their own operation. Within a feature, reuse nested shapes and lists for the same domain concept when their contracts are identical: members, required/optional/default/nullable traits, validation and stored defaults. Do not introduce `Batch`, `Created`, or operation qualifiers solely to duplicate an existing nested view.
  - A batch create lists its single-create sibling's request shape as its entry (`list CatalogueItemsInsert { member: CatalogueItemPostRequest }`).
  - An update's list of new children reuses the create operation's child entry list (`CatalogueItemPutRequest.variantsInsert: CatalogueItemVariantsInsert`).

  Split the views when their contracts differ, such as contact insert/update/read entries with different IDs, requiredness or nullable update semantics. Reusable value shapes, such as Catalogue price and selling unit, may appear in requests and responses when their meaning and member traits are identical. Sharing is based on the same domain concept and contract, not merely the same field layout.
- Owner-named nested entry in a resource-oriented contract: `<Owner><Kind>Entry`; list plural `...Entries` (`CustomerPhoneNumberEntry` / `CustomerPhoneNumberEntries`). Use the same plain names for email and address entries. Legacy contracts keep their `...EntryRequest` / `...EntryRequests` shapes until the last operation using them is reworked; then remove those shapes.
- Owner-less value shapes shared by several features live in `domain/Gateway.smithy` and retain their existing `Request` names (`PhoneNumberRequest` ↔ `PhoneNumber`, `AddressRequest` ↔ `Address`, `AddressEntryRequest` ↔ `AddressEntry`). A reworked feature may wrap them in owner-named entries without `Request`, such as `CustomerAddressEntry` containing `AddressRequest`; legacy owner-named lists remain unchanged until reworked.
- Request list member: `@default([])`, never `@required`, so omitted empty JSON decodes to non-optional `Nil`. Response list: `@required`. Never model request list as `Option[List]` solely to default it empty.

URIs: resource-oriented kebab-case per [Resource paths](#resource-paths) for new and reworked contracts. Legacy services keep their verb-first URIs (`/insert/customers`) until reworked; never add a new verb-first URI.

## Files

- Top-level `<Feature>Service.smithy`: service + operations.
- `domain/<Feature>.smithy`: request/response shapes.
- `domain/HttpErrors.smithy`: all shared HTTP errors; no feature-local errors.
- `domain/Gateway.smithy`: shared enums/value shapes/custom traits.
- Stable namespace; project-defined version pragma per file kind.

## Service

- Required REST/JSON protocol trait.
- Exactly one service auth mode: bearer, basic, or public.
- Add completed-onboarding service trait iff every endpoint requires it.
- With that trait, service docs contain only the completed-onboarding marker; otherwise gates are operation-level.

## Operation

- Body input is one required payload wrapper containing `<Operation>Request`.
- IDs of the addressed resource are path labels per [Resource paths](#resource-paths), on every method, never repeated in the body; other IDs (references, batch lists) are body members or query params. Legacy verb-first contracts keep IDs in the body and label only GET/bodyless paths. The tenant ID is always the header per [Tenant scope](#tenant-scope).
- `200` with output; `204` without output.
- Method/URI trait sits immediately above `operation`; other traits above it.
- Every tenant operation carries role allow-list according to project role policy.
- OpenAPI gate markers use identical bold-label text across transports:
  - required onboarding: per-operation bracketed enum list in backticks; `` `N/A` `` before onboarding. Fully gated service uses one service marker instead.
  - required tenant roles: bracketed enum list on every role-gated operation.
  - Alternate transport uses shared marker helpers with the enforced values.
- Errors: base validation/unauthorized/internal; add bad-request for rejected well-formed input, forbidden for role/stage gates, conflict for state collision.
- Order errors by status ascending; ties alphabetically. Apply to new errors.
- Any domain error addition/status move/new failure mode updates every affected operation's error list in the same change.

## Resource create responses

Resource-creation POSTs follow Catalogue's ID-only response pattern:

- A single create returns `200` with one required `<entity>ID: UUID`, such as `CatalogueItemPostResponse.catalogueItemID`.
- A batch create returns `200` with one required `<entity>IDs` UUID list, such as `CatalogueItemBatchPostResponse.catalogueItemIDs`. Reuse the feature's ID-list shape. Preserve the documented input order; mixed input lists define their traversal order explicitly. If an empty batch is valid for the feature, return an empty ID list.
- Return identifiers of the resources the endpoint creates; do not echo full records, summaries or nested child details. Stored details and child identifiers are obtained through the corresponding detail GET.
- Repository inserts return domain IDs (one ID or a collection); the service maps those IDs directly into the operation response. Keep write Rows and internal row pairs inside persistence rather than exposing them just to build a create response. See [Repository flow](../features/flow/04-repository.md#rows-and-repository-inputs) and [Service flow](../features/flow/05-service.md#implementation-boundary).

This rule applies to resource creation, not every POST operation: authentication, extraction previews and other commands retain their own agreed contracts. Any feature-specific create-response exception must be an explicit product requirement documented in that feature.

## Resource paths

Applies to new and reworked contracts; see [Reference implementation](#reference-implementation). Paths name resources; the HTTP method carries the CRUD meaning, so `insert`/`update`/`get`/`create`/`delete` never appear in a URI.

- Shape: `/{service}/{resource}[/{id}[/{subresource}[/{subId}]]][/{qualifier}]`, kebab-case segments.
- Resource segments are always singular, nested ones included (`/catalogue/item/{catalogueItemID}/variant/{catalogueItemVariantID}`).
- Addressed-resource IDs are path labels named after the entity-ID member (`{catalogueItemID}`), bound with `@required @httpLabel`.
- Methods: `POST` = create, `GET` = read, `PUT` = update (full or partial merge is the operation's own contract). One literal path may serve several methods (`GET`/`PUT /catalogue/item/{catalogueItemID}`).
- Qualifier segments are static and come after the resource they act on:
  - `/batch`: the request body carries several items to write (`POST /catalogue/item/batch`).
  - State transition (archive, ...): `PUT` to a transition segment on the resource (`.../{id}/archive`), bodiless when the labels say everything. `PUT` because it is idempotent: repeating it leaves the same state (archiving an archived item is a no-op). The segment keeps it apart from `PUT` on the resource itself, which updates its fields. This is the only verb allowed in a path.
  - Projection or check that is not a CRUD action on one ID: its own noun segment (`/catalogue/item/summary`, `/catalogue/item/uniqueness`).
- A read over several resources takes no `/batch`; it filters on its plain path with an optional `@default([])` ID-list query param, where empty means all (`GET /catalogue/item/summary?catalogueItemIDs=…`).
- A static segment may sit next to an `{id}` label at the same depth; smithy4s routes the static one first (`HttpEndpoint.moreSpecific`), so `/catalogue/item/summary` never reaches `{catalogueItemID}`.

The [Catalogue endpoint table](../features/catalogue.md#endpoints) applies these conventions; operation names derive from the paths per [Names](#names).

Expose only the operations a feature needs.

## Tenant scope

- Tenant ID is one required header, declared once in a mixin and mixed into every scoped operation input; never inline repeatedly, in body, URI, or service trait.
- Mixin flattening must preserve generated parameter and required OpenAPI header per operation.
- Header lets middleware handle GET/streaming without body parsing.
- Role trait/check may be invisible in generated OpenAPI; required-role marker is mandatory and matches enforcement.
- Alternate transport: typed header security input + shared auth with endpoint roles. Missing required auth/tenant header → `400`; disallowed role → `403`; its description uses the same roles.

```smithy
@mixin
structure TenantScopedInput {
    @required
    @httpHeader("X-Tenant-ID")
    tenantID: UUID
}

@http(method: "GET", uri: "/feature/entity/{entityID}", code: 200)
operation EntityGet {
    input := with [TenantScopedInput] {
        @required
        @httpLabel
        entityID: UUID
    }
}
```

## Custom traits

Traits live in shared domain; contract declares, middleware enforces.

- Completed-onboarding: memberless service trait; requires completed stage plus valid bearer auth.
- Role allow-list: operation trait containing quoted role enum values; always paired with tenant mixin. Missing tenant header → `400`; disallowed role → `403`; missing referenced membership → project's internal/missing-entity error.
- Default tenant role policy unless feature docs justify deviation:

| Operation | Roles |
|---|---|
| Read (`GET`) | owner, admin, user |
| Mutation | owner, admin |
| Delete tenant | owner |

Keep role marker synchronized with trait.
