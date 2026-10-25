# Tapir

Reusable rules for routes Smithy cannot express. Related: [Smithy](smithy.md), [authentication](../project/authentication.md), [Scala](scala.md), [Mesazon alternate HTTP](../project/alternate-http.md).

## File layout

- Shared file: security base, error/decode handling, docs-role helper, aliases/options.
- Feature file: endpoint definitions + dependency/server-endpoint wiring.

## Error model

- Own error enum; mirror the primary contract taxonomy and domain-error mapping case-for-case; do not reuse generated errors.
- Any domain error addition/status move updates both handlers together. The alternate handler's plain match may miss a case silently; test parity.

## Security

- Central middleware does not cover these routes. Each endpoint uses shared typed security inputs and must reproduce equivalent token/tenant/onboard-stage/role checks.
- Any middleware rule change updates every alternate endpoint manually.

## Swagger/OpenAPI integration

- Generate/serve a separate OpenAPI spec at a path matching the primary docs convention; register its ID in the shared Swagger UI.
- Mount alternate docs **before** primary swagger routes or the latter may intercept and fail.
- Security logic is invisible to OpenAPI. Endpoint description must use the shared role-marker helper with the exact enforced role list.

## Verification

- Do not add dedicated endpoint-definition or OpenAPI-document tests unless the user explicitly requests them. Verify definitions by compilation; cover runtime behavior through service and HTTP acceptance tests.

## Error schemas/codecs

- One explicitly named schema per error case, named like the primary contract's equivalent response component; do not collapse statuses into one component.
- Separate fallback schema for unknown-at-definition-time paths such as decode failure.
- Codec round-trips by stable code, not display/schema name; unknown code fails.

## Success-body schema derivation

`Schema.derived[T]` on a deeply nested `T` (a response type whose fields are themselves case classes several levels deep, e.g. for structured AI output) can fail to resolve an inner collection member (`Could not find Schema for type List[Inner]`) even when every leaf type has an Iron/tapir codec. Add explicit `Schema.derived[_]` givens bottom-up — leaf case classes first, then each wrapping type, then the top-level response — rather than assuming one top-level derivation call will walk the whole tree; this is a macro-resolution-order issue, not a missing codec.

## Entity limits

- Configure its entity-size limit separately from the primary transport; keep the route constant and config value synchronized.

## Naming

- New or reworked file routes start with `/<feature>/file`, followed by the operation segments in their intended order. AI extraction includes `/ai/extract`; item-image upload uses `/upload/image/item`. Declare each segment separately in Tapir, for example `.in("catalogue" / "file" / "ai" / "extract")`.
- Endpoint values follow the [Smithy path-based convention](smithy.md#names): concatenate literal path segments in lower camel case, omit path labels, then append the HTTP method and `Endpoint`. Preserve segment order and use `Ai` within a name. Do not add qualifiers absent from the path.
- File-service methods omit the feature and `file` prefix already supplied by their owning service, and omit the HTTP method and `Endpoint` suffix. Keep the remaining operation segments in lower camel case and in path order. For example, `CatalogueFileService.aiExtract` and `CatalogueFileService.uploadImageItem`.
- Existing routes keep their current names until explicitly reworked; these conventions do not authorize unrelated renames or URL changes.

| HTTP route | Endpoint value | File-service method | Response body type |
| --- | --- | --- | --- |
| `POST /catalogue/file/ai/extract` | `catalogueFileAiExtractPostEndpoint` | `aiExtract` | `CatalogueFileAiExtractPostResponse` |
| `POST /catalogue/file/upload/image/item` | `catalogueFileUploadImageItemPostEndpoint` | `uploadImageItem` | Empty body (`Unit`) |

- Error case: `UpperCamelCase` + `Error`; wire code: `SCREAMING_SNAKE_CASE`.
- Success case: an endpoint's declared output body type is named `<Operation><Method>Response` (e.g. `CatalogueFileAiExtractPostResponse`). When this endpoint is the type's only real shape owner, rename the underlying type in place to match — even when other non-Tapir code (an AI client, a repository) also happens to return it — rather than adding a parallel wrapper type. Introduce a separate endpoint-owned wrapper type only when the same underlying type is a genuinely distinct response for more than one differently-named endpoint; follow [Chimney's boundary rule](chimney.md#tests-and-boundaries) for that mapping.
