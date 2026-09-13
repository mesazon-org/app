# Customer Book

Tenant-scoped address book for billable people and companies.

## Scope

Owns:

- `customer`: the only future order target; composite PK `(organization_id, customer_id)`.
- `customer_business_contact`: people inside a business; never customers/order targets.
- `INDIVIDUAL`/`BUSINESS` discrimination and one-way archival.

Excludes orders and tenant membership/roles ([Organization Management](organization-management.md)). Here “organization” means the Mesazon tenant; every endpoint uses `X-Organization-ID`.

## Model and invariants

`customer` holds `customer_type`, shared `name`, `emails`/`phone_numbers` JSONB lists, address, optional business-only `tax_id`, `status`, and audit timestamps. `customer_business_contact` holds `(organization_id, customer_id, customer_business_contact_id)`, name, role, optional email/phone, and a tenant-scoped FK to `customer`.

- One row + discriminator makes a customer exactly one type. Typed reads/updates filter `customer_type`; wrong-type lookup is not found.
- Repository views: `CustomerIndividualDetailsRow` (`fullName`) and `CustomerBusinessDetailsRow` (`businessName`, `taxID`); no `CustomerRow`. `CustomerSummaryRow(customerID, name, customerType)` is a projection.
- Contacts exist only for businesses by service convention; the FK enforces tenant, not subtype.
- Customers store email/phone lists. Each entry contains value + `isDefault`; empty is valid, non-empty requires exactly one default. Business contacts retain one optional email/phone.
- Validation accumulates all list errors. `InvalidFieldError.index` identifies the item. Batch customer errors use the outer customer index while preserving nested contact indexes.
- `customer_status` is the native PG enum `Active|Archived`; queries require the casts in [Repository flow](flow/04-repository.md#queries).
- Customers archive; contacts hard-delete. Archive retains contacts. No unarchive.

Named uniqueness:

| Constraint/index | Rule | 409 message |
|---|---|---|
| `uq_customer_name` | active `(organization_id, customer_type, name)`; partial on `status = 'Active'` | `A customer with the given name already exists in this organization` |
| `uq_customer_business_contact_email` | `(organization_id, customer_id, email)` | `A business contact with the given email already exists for this customer` |
| `uq_customer_business_contact_phone_number` | `(organization_id, customer_id, phone_number_e164)` | `A business contact with the given phone number already exists for this customer` |

Nullable contact fields allow multiple `NULL`s. Repository maps only SQL state `23505` + these names to `ConflictError.UniqueConstraintViolation`.

## Endpoints

Service: `CustomerBookService`; bearer + completed onboarding. Reads allow `OWNER|ADMIN|USER`; writes allow `OWNER|ADMIN`.
Smithy JSON requests are limited to 5 MiB by `HttpApp.SmithyMaxEntitySize`.

| Method | Path | Operation | Result/effect |
|---|---|---|---|
| GET | `/get/customer-individual/{customerID}` | `GetCustomerIndividualGet` | individual details |
| GET | `/get/customer-business/{customerID}` | `GetCustomerBusinessGet` | business details |
| GET | `/get/customers` | `GetCustomersGet` | active summaries |
| POST | `/insert/customer-individual` | `InsertCustomerIndividualPost` | one individual, returns its full row |
| POST | `/insert/customer-individuals` | `InsertCustomerIndividualsPost` | atomic batch, returns a summary per row in request order |
| PUT | `/update/customer-individual` | `UpdateCustomerIndividualPut` | update active individual |
| POST | `/insert/customer-business` | `InsertCustomerBusinessPost` | one business + inline contacts, returns the full row incl. generated contact IDs |
| POST | `/insert/customer-businesses` | `InsertCustomerBusinessesPost` | atomic batch, returns a summary per row in request order |
| PUT | `/update/customer-business` | `UpdateCustomerBusinessPut` | update active business |
| POST | `/insert/customers` | `InsertCustomersPost` | atomic mixed batch, returns individual summaries then business summaries, each in request order |
| PUT | `/add/customer-business-contacts` | `AddCustomerBusinessContactsPut` | append contacts |
| PUT | `/remove/customer-business-contacts` | `RemoveCustomerBusinessContactsPut` | hard-delete contacts |
| PUT | `/archive/customer` | `ArchiveCustomerPut` | archive either type |

Smithy: `smithy/CustomerBookService.smithy`, `smithy/domain/CustomerBook.smithy`. Each operation owns its shapes.

Error sets:

- Reads, remove contacts, archive: `BadRequest, Unauthorized, Forbidden, InternalServerError`.
- Other writes: above plus `ValidationError` and `Conflict`.
- Remove/archive contain only pure UUIDs, cannot validation-fail, and cannot create uniqueness conflicts.
- By-ID reads filter type, not status: archived rows still return; missing/wrong type → 500. Mutations use the lenient policy below.

## Flow and decisions

- Insert individual: `customer(type=INDIVIDUAL,status=Active,tax_id=NULL)`.
- Insert business: `customer(type=BUSINESS,status=Active)` plus inline contacts in the same transaction.
- Batch/mixed insert: multi-row statements, one `transactionOrWiden`, all-or-nothing. IDs/timestamps are generated in the repository.
- All 5 insert operations return `200` with the row(s) just persisted instead of `204`: repository insert methods return `CustomerIndividualDetailsRow`/`CustomerBusinessDetailsRow` (+ contact rows, paired via a named tuple `CustomerBusinessInsertRow`) rather than bare IDs, and the service maps them into per-operation response shapes. Singular individual/business inserts return the full row; batch/mixed inserts return a `GetCustomer`-shaped summary (`customerID`, `name`, `customerType`) per item in request order — for the mixed endpoint, individuals then businesses.
- Update: one type- and `Active`-filtered update. Required email/phone lists always overwrite (`Some(list)`); optional scalars use `...OptUpdate` (absent = unchanged).
- Add/remove contacts: transaction first checks `customerActiveExists`; archived/absent parent → silent `204` no-op.
- Archive: type-independent `Active → Archived`; missing/already archived → silent `204`; retains contacts. Partial uniqueness frees the name.
- After archive, update/contact/archive mutations silently no-op. This intentionally differs from by-ID reads: racing archive already satisfies the mutation’s desired outcome.
- `GetCustomersGet`: active rows, SQL order `LOWER(name), customer_id`. No expression index yet; tenant PK narrows rows. Add `(organization_id, lower(name))` only if pagination/scale warrants it.
- Org isolation comes from composite keys/FKs. Future order history must retain customer rows and snapshot buyer fields; use `on delete restrict`.

Repository inputs never use API request types. `CustomerBookRepository` owns batch element inputs in its companion; singular operations reuse them, while single-only updates/removal use flat parameters. Service maps validated request → input with Chimney. JSONB Row fields and named codecs use `List[CustomerEmailEntryInput]` / `List[CustomerPhoneNumberEntryInput]`; `CustomerBookQueries` imports `CustomerBookRepository.*` and `io.github.iltotore.iron.jsoniter.given`.

Open decisions:

- Archive keeps contacts; revisit only if clients must hide them.
- No unarchive; reactivation must resolve active-name conflict.
- Singular, batch, and mixed inserts overlap; retain until client needs justify convergence.

## Photo extraction

`POST /extract/customer-book-photo` is a Tapir streaming endpoint, not Smithy — same reason and same transport as [Organization Management](organization-management.md#logo-upload)'s logo upload and [Catalogue](catalogue.md#image-upload)'s item-image upload: Smithy JSON routes cap at 5 MB, Tapir streams binary and allows 20 MB. See [Alternate HTTP](../project/alternate-http.md) for the shared transport mechanics this endpoint follows.

Binary body; organization in the `X-Organization-ID` header. Security (`AuthorizationService.auth`): valid access JWT, `OnboardStage.completedStages`, and the caller must be `OWNER` or `ADMIN` in the organization — identical gate to [adding a customer](../../pages/epics/05-customer-book.md#1-user-adds-a-customer). No `X-File-Name` header: unlike the logo/catalogue-item uploads, nothing here is ever kept, so there is no original file name to preserve.

`FileService.extractCustomersFromPhoto` runs inside one `ZIO.scoped` block, reusing the existing upload pipeline pieces but stopping short of storage:

1. `FileScanner.scan` spools the incoming `ZStream[Byte]` to a temp file exactly as the two existing uploads do — same `SupportedMediaType.images` (`PNG`, `JPEG`, `WEBP`) content-sniffed check, same `fileServiceConfig.maxUploadBytes` cap. It returns `FileScannerScanOutput` (`utils/utils.scala`: `fileByteStreamScanned`, the matched `SupportedMediaType` member, `fileBytesSize: FileBytesSize`) — the media type and size it already computed for its own checks, not re-detected by any caller. The logo/catalogue-item uploads destructure this and use only `fileByteStreamScanned`, unchanged from before; this endpoint is the first caller that also needs `supportedMediaType`.
2. Unlike the logo/catalogue-item uploads, there is no `ImageProcessing.normalize` step and no `S3Client` call: `FileService` passes `fileByteStreamScanned` straight through to `AIClient`, never writing it to object storage or resizing it.
3. `AIClient.extractFromImage[A](imageByteStream: FileByteStreamScanned, supportedMediaType: SupportedMediaType, instructions: String)(using OpenAIJsonSchema[A], JsonValueCodec[A])` consumes that stream **exactly once** — unwrapping and collecting it, then base64-encoding it — mirroring how `S3ClientOrganizationMedia`'s upload methods take a `ZStream`-wrapping type and do their own internal consumption. Typing the parameter as `FileByteStreamScanned` (not a bare `ZStream`) makes it a compile-time guarantee that only an already-scanned stream can reach `AIClient`. The media type comes from `FileScanner.scan`'s output via `FileService`, not detected inside `AIClient`. It sends the base64 image (built from `supportedMediaType.mime`) plus a system prompt (the `instructions` argument, owned by `FileService`, not `AIClient`) describing the extraction task (classify each recognized entry as an individual or a business; omit candidates and business contacts without readable names; request field values satisfying their refined constraints; require exactly one default in each non-empty email/phone list; show examples where `phoneNationalNumber` excludes `phoneCountryCode`; note anything unclear; flag same-kind same-name duplicates found within this one photo, never against the stored book; report how many entries were identified versus turned into candidates; summarize, in one line, what could not be processed) as an OpenAI structured-output request (`ResponseFormat.JsonSchema`, same mechanism `OpenAIClient` already uses) targeting `ExtractCustomersResponse` directly.
4. The AI's structured response is returned to the caller as-is: `Entries Identified`, `Entries Processed`, `Is Duplicate`, and the unidentified-entries summary are the model's own best-effort output, not recomputed or cross-checked by the service. Nothing is written to `customer`/`customer_business_contact` or anywhere else; the step is fully stateless and safe to repeat.

`ExtractCustomerIndividualData` and `ExtractCustomerBusinessData` are the outer metadata wrappers: each keeps the JSON `candidate` field plus `isDuplicate` and `extractionNotes`. Their `candidate` values use the dedicated `ExtractCustomerIndividual` and `ExtractCustomerBusiness` payloads. The complete extraction tree owns individual/business details, email entries, phone entries, a two-field phone, and business contacts while reusing the existing Iron newtypes for field-level constraints. Its JSON field layout mirrors the Smithy insert request: an extracted phone contains only `phoneNationalNumber` and `phoneCountryCode`, never the derived `phoneRegion` or `phoneNumberE164`. Semantic phone-pair validation and the exactly-one-default list rule remain in the normal insert validator; extraction asks the model to satisfy them but does not independently enforce them.

`json/tapir.scala` owns ordinary transport schemas/codecs and derives `ExtractCustomersResponse` once. `json/ai.scala` owns `OpenAIJsonSchema[A]`, explicit AI response registrations, and `ai.fromTapir`, which adapts that existing schema rather than deriving the model again. The adapter marks every object property required before sttp-ai's strict-mode encoder runs: Tapir treats lists as optional, and otherwise sttp-ai would make non-optional lists nullable. Scala `Option` fields remain nullable, and supported Iron constraints remain intact. sttp-ai itself applies the remaining strict object rules, including `additionalProperties: false`; removing the root JSON Schema dialect declaration is unnecessary. Both AI clients consume the registered OpenAI schema without doing schema adaptation themselves. `AIClientSchemaSpec` checks the actual encoded request, not just a pre-encoding schema. The latest live photo-1 check confirmed schema acceptance and decoding with this minimal adapter, but full response equality failed on model-generated wording; all ten complete golden baselines have not been reverified for this final implementation.

Each OpenAI send attempt has a one-minute read timeout. `AIClient` makes at most three attempts, waiting one second before the second and two seconds before the third. It retries connection, read, and timeout failures plus HTTP `408`, `409`, `429`, and `5xx`; it does not retry image-stream read failures, other `4xx` responses (including invalid-schema `400`), OpenAI-envelope deserialization failures, or structured-response JSON/Iron decoding failures. The scanned image stream is consumed once before the send loop and retries replay the same in-memory request. Exhaustion keeps the existing `500 INTERNAL_SERVER_ERROR` result with no partial candidates. A read failure can happen after OpenAI processed the request, so a retry can create and charge for a duplicate generation; worst-case latency is about three minutes plus the one-/two-second backoff.

**Accepted trade-off:** because the whole response is one structured-output JSON document decoded in a single pass, a single field that fails its Iron constraint anywhere in that document fails the entire decode, surfacing as one `500 INTERNAL_SERVER_ERROR` for the whole request rather than dropping just that field or candidate. Cross-field rules that are not encoded by the extraction types — a phone pair being real for its country and exactly one default in a non-empty list — are requested in the prompt and enforced later by the insert validator, not by extraction decoding.

### Key files (photo extraction)

- Orchestration: `service/FileService.scala` (shared with logo/catalogue-item image uploads)
- AI client: `clients/AIClient.scala` (registered OpenAI schema, per-attempt timeout, and selective retry policy), `config/AIClientConfig.scala`; `clients/OpenAIClient.scala` also consumes `OpenAIJsonSchema`, while its configuration/model/prompt behavior remains unchanged
- Pipeline utils (shared): `utils/FileScanner.scala`
- Transport (shared): `tapir/FileServiceEndpoints.scala`, `tapir/tapir.scala`
- Domain: `domain/gateway/CustomerBook.scala` (the dedicated extraction model tree, including `ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse`)
- JSON: `json/tapir.scala` (ordinary schemas/codecs, including the bottom-up extraction tree), `json/ai.scala` (AI codecs, `OpenAIJsonSchema` registrations, and required-property adaptation); no `json.scala` compatibility facade or Scala 3 exports
- Config: new `ai-client` section in core's `application.conf` only (separate from `open-ai-client`); gateway-it needs no `application.conf` change, only `compose.yaml`'s `AI_CLIENT_HOST: wiremock`, since that file carries neither an `ai-client` nor `twilio-client`/`open-ai-client` section
- Acceptance: `it/FileApiSpec.scala`'s `/extract/customer-book-photo` block, `it/client/GatewayClient.scala`'s `extractCustomersFromPhotoPost`, `backend/wiremock/mappings/ai-client-chat-completions-acceptance.json`

### Tests (photo extraction)

`it/AIClientSpec.scala` is the real integration spec for `AIClient`, against a wiremock-stubbed OpenAI chat-completions endpoint (mirrors `TwilioClientSpec`, per [External client](../project/external-client.md)). Wiremock has no dynamic stub API here — its stubs are the static mapping files in `backend/wiremock/mappings/` baked into the `local/wiremock:latest` image — so the cases are distinguished by matching on request-body markers, including success, HTTP error, malformed JSON, and invalid refined-field responses. The success mapping also requires the `image_url`/`json_schema` substrings the real request body must contain — proving the outbound request carries the image content part and requests structured output, without needing to add body capture to the shared `WiremockClient` harness. An earlier `unit/clients/AIClientSpec.scala` (a plain, no-dependency test of the then-stub `AIClient.live`) existed only as a stand-in until this real spec landed and has been deleted now that it has.

Target coverage, ground each case in the epic's own [Business Scenarios table](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-a-photo) rather than a generic success/failure pair — every numbered scenario there (clear entries, an entry with something unclear, no legible name, same-kind duplicate names, different-kind same names, nothing recognizable, unsupported/oversized file, AI unreachable, disallowed role, repeat calls) is a candidate test case once the real orchestration lands:

- Acceptance: `FileApiSpec`'s `/extract/customer-book-photo` block — happy path against a wiremock-stubbed AI response, missing `X-Organization-ID` header (400), missing token (401), invalid token (401), disallowed role (403), disallowed stage (403), non-member (500), unsupported file type (500). Mirrors the exact case set already established for this file's other two endpoints (neither tests a missing-user-details-row 500 case either). AI-service failure is intentionally not re-proven here: it's already covered by `it/AIClientSpec.scala` (the HTTP-failure-mapping itself) and `fun/FileServiceSpec.scala` (the propagation through `FileService`), and the "500 via real HTTP" transport mapping is already proven by the unsupported-file-type case in this same block — adding a third layer for the identical propagation path would be redundant per [Functional testing](../project/functional-testing.md)'s anti-pattern guidance, applied here at the acceptance layer too. The happy path uses a dedicated, marker-independent wiremock stub (`ai-client-chat-completions-acceptance.json`, priority 3, lower than the marker-specific `AIClientSpec` stubs) matching only on the `image_url`/`json_schema` substrings every real request carries, since the acceptance test has no way to control `FileService`'s real fixed `instructions` text the way `AIClientSpec`'s marker strings do; it returns one fully populated `ExtractCustomerIndividualData` wrapper (name, a default email, a default phone number) so the happy path proves real extracted data flows through decode end to end, not just an empty-result shape.
- Functional: `FileServiceSpec`'s `extractCustomersFromPhoto` block covers the success path, `FileScanner` failure propagating with `AIClient` never called, and `AIClient` failure propagating. `fileScannerMock` is a normal ScalaMock mock; `AIClient` is `Mocks.AIClientMock` (`mock/Mocks.scala`, a hand-written test double, not `mock[AIClient]`) because ScalaMock cannot mock its generic `extractFromImage[A](...)(using OpenAIJsonSchema[A], JsonValueCodec[A])` shape — see [Functional testing](../project/functional-testing.md)'s "Known limitation" section for the full writeup.
- Integration: `it/AIClientSpec.scala` (mirrors `TwilioClientSpec`) against `src/test/resources/compose/wiremock.yaml` and `backend/wiremock/mappings/ai-client-chat-completions*.json` stubs — success decode, connection-reset retry exhaustion with attempt counts, non-retryable request rejection, and structured-response JSON/Iron decode failures. Explicit delayed-response timeout retry coverage remains a gap.
- Unit: `AIClientSchemaSpec` pins the registered OpenAI schema's actual encoded request, including required properties, closed objects, nullable options, non-nullable lists, Iron constraints, and the two-field extracted-phone shape; `FileScannerSpec` remains unchanged because this endpoint adds no new size/type-check behavior.
- Golden: `ExtractCustomersFromPhotoGoldenSpec` is manual-only and sends each of the ten sample photos to the real OpenAI API. Every photo has its own complete `ExtractCustomersResponse` baseline, and the test asserts full equality for counts, ordered customers, contact details, phone pairs, duplicate flags, extraction notes, and the unidentified-entry summary. Its empty checked-in API key cancels all ten cases unless a person deliberately supplies a key for a real run.

**Status (photo extraction):** the pipeline, explicit OpenAI schema adaptation, dedicated extraction model tree, two-field extracted-phone shape, prompt rules, and schema/client/functional coverage are implemented. The latest live photo-1 check proved schema acceptance and decoding, not complete golden equality; the final ten-photo golden verification remains open. Both gateway test modules compile, but the focused acceptance run previously aborted before executing tests because the shared harness supplied a null context. `AIClient` has its own `config/AIClientConfig.scala` and `ai-client` configuration, ordinary and AI JSON concerns live in `json/tapir.scala` and `json/ai.scala`, `Main.scala` provides the client layers, and `POST /extract/customer-book-photo` is routed through `tapir/FileServiceEndpoints.scala`.

## Key files and config

- Contract: `smithy/CustomerBookService.smithy`, `smithy/domain/CustomerBook.smithy`
- Domain/validation: `domain/gateway/CustomerBook.scala`, shared `Newtypes.scala`, `validation/service/CustomerBookRequestValidator.scala`
- Service: `service/CustomerBookService.scala`
- Persistence: `repository/CustomerBookRepository.scala`, `repository/domain/Customer*Row.scala`, `repository/domain/CustomerSummaryRow.scala`, `repository/queries/CustomerBookQueries.scala`
- Schema: `backend/schemas/migrations/V2025.05.27__init.sql`
- Config: `RepositoryConfig` and both core/gateway-it `application.conf` copies

## Status

Implementation, wiring, schema, validation, repository, functional tests, and repository tests are complete. Acceptance: 8/13 endpoints complete.

| Acceptance done | Remaining |
|---|---|
| four singular/batch inserts; three reads; archive | mixed insert; two updates; add/remove contacts |

`Main` provides service, validator, repository, and queries; `HttpApp.externalSmithyRoutes` serves the contract.

## Tests

- Unit: `CustomerBookRequestValidatorSpec` — every validator, accumulation, nested indexes/default rules.
- Integration: `CustomerBookRepositorySpec` — every repository operation; three conflicts; atomic rollback; enum/partial-index semantics; archived mutation guards; whole-row IDs/timestamps.
- Functional: `CustomerBookServiceSpec` — exact org-scoped calls/mappings/responses; validation blocks repository; repository errors propagate.
- Acceptance: `CustomerBookApiSpec` — per completed endpoint, the [acceptance-testing matrix](../project/acceptance-testing.md), full DB state, and no forbidden effects. Also proves duplicate/contact conflicts, by-ID missing → 500, archive state flip, and missing archive → 204.

Structural type exclusivity needs no dedicated “not both” test. Repository tests instead prove cross-type same names are valid, archived names are reusable, and typed reads do not miss rows.
