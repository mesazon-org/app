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

## Photo, CSV, and Excel extraction

The epic documents this as one merged step ([step 7](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-a-photo-or-file)) with three sources — a photo, a CSV file, and an Excel file (`.xls` or `.xlsx`) — but only the photo gets its own endpoint; CSV and Excel share one, content-sniffed the same way the photo endpoint already accepts three image formats under one URL. Both are Tapir streaming endpoints, not Smithy — same reason and same transport as [Organization Management](organization-management.md#logo-upload)'s logo upload and [Catalogue](catalogue.md#image-upload)'s item-image upload: Smithy JSON routes cap at 5 MB, Tapir streams binary and allows 20 MB for every source. See [Alternate HTTP](../project/alternate-http.md) for the shared transport mechanics both endpoints follow.

- `POST /extract/customer-book-photo` — binary body is the photo (`SupportedMediaType.images`: `PNG`/`JPEG`/`WEBP`). Unchanged from the original photo-only feature.
- `POST /extract/customer-book-file` — binary body is a CSV or Excel file (`SupportedMediaType.customerBookFiles`: `CSV`, `PLAINTEXT_CSV`, `XLS`, `XLSX`). New.

Both: organization in the `X-Organization-ID` header; security (`AuthorizationService.auth`) is a valid access JWT, `OnboardStage.completedStages`, and `OWNER`/`ADMIN` in the organization — identical gate to [adding a customer](../../pages/epics/05-customer-book.md#1-user-adds-a-customer). No `X-File-Name` header on either: nothing from any of the three sources is ever kept, so there is no original file name to preserve.

### Media types

`SupportedMediaType` gains `CSV` (`text/csv`), `PLAINTEXT_CSV` (`text/plain` — Tika's common fallback detection for a real CSV file, since CSV has no binary signature the way an image or an Office document does), `XLS` (`application/vnd.ms-excel`), and `XLSX` (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`). `SupportedMediaType.customerBookFiles = List(CSV, PLAINTEXT_CSV, XLS, XLSX)` is what `FileScanner.scan` is called with for the file endpoint; `SupportedMediaType.images` is unchanged and still used by the photo endpoint. `CSV` and `PLAINTEXT_CSV` are handled identically downstream — both simply mean "treat these bytes as CSV text."

### `FileService.extractCustomersFromFile`

Runs inside one `ZIO.scoped` block, mirroring `extractCustomersFromPhoto`:

1. `FileScanner.scan` spools the stream to a temp file, content-sniffs against `SupportedMediaType.customerBookFiles`, and enforces the same `fileServiceConfig.maxUploadBytes` cap (20 MB) as every other upload in this product — no separate, smaller cap for files, and no row-count limit at all: a file is processed however many rows it has.
2. Detected `XLS`/`XLSX`: `ExcelToCsvConverter` (new, `utils/ExcelToCsvConverter.scala`) reads the workbook with Apache POI, reads only the first sheet, and writes its rows out as CSV-shaped text. Other sheets are never opened, per the epic's requirement 7.
3. Detected `CSV`/`PLAINTEXT_CSV`: the scanned bytes are used as-is, but must first pass a real structural CSV parse (Apache Commons CSV) — the epic's "genuinely readable as CSV" gate (requirement 6). A Tika mime match alone is not enough, since an arbitrary text file can be mislabelled `text/csv` or `text/plain`; a file that fails this parse is rejected the same way an unsupported file type is.
4. Either way, `FileService` now holds CSV-shaped text bytes — the original CSV, or the POI-converted Excel text — and no longer distinguishes the two sources; it passes them, plus `SupportedMediaType.CSV`, to `AIClient`.
5. No `ImageProcessing.normalize` step and no `S3Client` call, matching the photo: nothing from either new source is ever written to file storage.

`FileService` owns a new `extractCustomersFromFileInstructions` prompt (mirrors `extractCustomersFromPhotoInstructions`): the model is told it is reading either a CSV file or a plain-text table taken from a spreadsheet's first sheet, that there is no fixed column layout, that a column it doesn't recognize is simply ignored, that a completely blank row is not an entry at all, and that a business row may include a best-effort contact when the row gives enough signal to identify one — matching the epic's requirements 1, 3, 8, and 9 exactly.

### `AIClient` generalization

The trait's single extraction method changes from image-only to source-aware, still keyed on `supportedMediaType`:

```
def extract[A](
    contentByteStream: FileByteStreamScanned,
    supportedMediaType: SupportedMediaType,
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]
```

(Renamed from `extractFromImage`; exact name confirmed at the skeleton slice.)

- `supportedMediaType` in `SupportedMediaType.images`: unchanged image branch — base64-encode the bytes and send `Content.ContentPart.ImageUrl` with a `data:<mime>;base64,...` URL, exactly as today.
- Any other `supportedMediaType` (`CSV`, `PLAINTEXT_CSV`, `XLS`, `XLSX`): new text branch — decode the bytes as UTF-8 and send them as plain message text instead of an image part (`sttp-ai` content shape confirmed at the skeleton slice). `XLS`/`XLSX` reach this branch already holding POI-converted CSV text, never raw spreadsheet bytes — `AIClient` never touches POI or Commons CSV itself.

Consumes the stream exactly once either way, same guarantee as today. Retry policy (one-minute per-attempt timeout, two retries after the first attempt, one-/two-second backoff, the same retryable/non-retryable classification), schema handling, and response decoding are unchanged and apply identically to every source — this is the one AI service call underneath all three, with only the message content shape and the instructions text varying.

### New dependencies

- **Apache POI** (`poi`, `poi-ooxml`) — parses both `.xls` (HSSF) and `.xlsx` (XSSF); the only maintained JVM library covering both legacy and current Excel formats. Used only inside `ExcelToCsvConverter`.
- **Apache Commons CSV** — the structural "genuinely readable as CSV" parse gate; avoids hand-rolling RFC4180 quoting/embedded-newline handling.

Both added to `project/Dependencies.scala` with one centralized version each, wired into `build.sbt` the same way `scrimage`/`tika` already are.

### Accepted trade-off (file sizes and AI context limits)

There is no application-level row limit and no chunking of large files, by agreement. A CSV/Excel file whose derived text exceeds the AI model's context window is not specially detected — it falls through to the existing "AI service rejects the request / returns something that can't be decoded" path (epic scenario 10), a non-retried `500 INTERNAL_SERVER_ERROR`. Revisit before this goes live for customers with very large books.

`ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` are reused unchanged for CSV and Excel — no new domain types for this delta. Duplicate detection, the AI retry/timeout policy, and the "nothing is ever stored" rule apply uniformly across all three sources. `ExtractCustomerIndividualData` and `ExtractCustomerBusinessData` are the outer metadata wrappers: each keeps the JSON `candidate` field plus `isDuplicate` and `extractionNotes`. Their `candidate` values use the dedicated `ExtractCustomerIndividual` and `ExtractCustomerBusiness` payloads. The complete extraction tree owns individual/business details, email entries, phone entries, a two-field phone, and business contacts while reusing the existing Iron newtypes for field-level constraints. Its JSON field layout mirrors the Smithy insert request: an extracted phone contains only `phoneNationalNumber` and `phoneCountryCode`, never the derived `phoneRegion` or `phoneNumberE164`. Semantic phone-pair validation and the exactly-one-default list rule remain in the normal insert validator; extraction asks the model to satisfy them but does not independently enforce them.

`json/tapir.scala` owns ordinary transport schemas/codecs and derives `ExtractCustomersResponse` once. `json/OpenAIJsonSchema.scala` declares `OpenAIJsonSchema[A]`; `json/ai.scala` owns explicit AI response registrations and `ai.fromTapir`, which adapts that existing schema rather than deriving the model again. The adapter marks every object property required before sttp-ai's strict-mode encoder runs: Tapir treats lists as optional, and otherwise sttp-ai would make non-optional lists nullable. Scala `Option` fields remain nullable, and supported Iron constraints remain intact. sttp-ai itself applies the remaining strict object rules, including `additionalProperties: false`; removing the root JSON Schema dialect declaration is unnecessary. `AIClient` consumes the registered OpenAI schema without doing schema adaptation itself. The legacy `OpenAIClient` remains unchanged and uses ordinary `Schema[A]`; `ai` supplies its ordinary AssistantResponse schema separately. The final live ten-photo run on 2026-09-13 confirmed schema acceptance and decoding for every photo, with no schema rejection or send/decode failure. All ten full response-equality assertions failed: differences include note wording/language/presence, inferred or localized countries, and address-line grouping. Golden baselines remain unchanged; a green golden run is still open.

Each OpenAI send attempt has a one-minute read timeout. `AIClient` makes at most three attempts, waiting one second before the second and two seconds before the third. It retries connection, read, and timeout failures plus HTTP `408`, `409`, `429`, and `5xx`; it does not retry image-stream read failures, other `4xx` responses (including invalid-schema `400`), OpenAI-envelope deserialization failures, or structured-response JSON/Iron decoding failures. The scanned image stream is consumed once before the send loop and retries replay the same in-memory request. Exhaustion keeps the existing `500 INTERNAL_SERVER_ERROR` result with no partial candidates. A read failure can happen after OpenAI processed the request, so a retry can create and charge for a duplicate generation; worst-case latency is about three minutes plus the one-/two-second backoff.

**Accepted trade-off:** because the whole response is one structured-output JSON document decoded in a single pass, a single field that fails its Iron constraint anywhere in that document fails the entire decode, surfacing as one `500 INTERNAL_SERVER_ERROR` for the whole request rather than dropping just that field or candidate. Cross-field rules that are not encoded by the extraction types — a phone pair being real for its country and exactly one default in a non-empty list — are requested in the prompt and enforced later by the insert validator, not by extraction decoding.

### Key files (photo, CSV, Excel extraction)

- Orchestration: `service/FileService.scala` (shared with logo/catalogue-item image uploads; `extractCustomersFromPhoto` unchanged, new `extractCustomersFromFile`)
- AI client: `clients/AIClient.scala` (generalized `extract` method, renamed from `extractFromImage`; registered OpenAI schema, per-attempt timeout, and selective retry policy unchanged), `config/AIClientConfig.scala`; the legacy `clients/OpenAIClient.scala` and its configuration remain unchanged
- New conversion utility: `utils/ExcelToCsvConverter.scala` (Apache POI, first-sheet-only)
- New validity gate: a CSV structural-parse check (Apache Commons CSV); exact file/name decided at the skeleton slice
- Pipeline utils (shared): `utils/FileScanner.scala`
- Domain: `domain/gateway/SupportedMediaType.scala` (new `CSV`, `PLAINTEXT_CSV`, `XLS`, `XLSX` members, `customerBookFiles` list); `domain/gateway/CustomerBook.scala` (the dedicated extraction model tree, including `ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` — unchanged, reused for all three sources)
- Transport: `tapir/FileServiceEndpoints.scala` (existing `extractCustomersFromPhotoPostEndpoint` unchanged, new `extractCustomersFromFilePostEndpoint` for `POST /extract/customer-book-file`), `tapir/tapir.scala`
- JSON: `json/tapir.scala` (ordinary schemas/codecs, including the bottom-up extraction tree), `json/ai.scala` (AI registrations and required-property adaptation), `json/OpenAIJsonSchema.scala` (the AI schema interface). AI extraction callers import the existing Tapir response codec explicitly; there is no duplicate AI codec, `json.scala` compatibility facade, or Scala 3 export.
- Config: new `ai-client` section in core's `application.conf` only (separate from `open-ai-client`); gateway-it needs no `application.conf` change, only `compose.yaml`'s `AI_CLIENT_HOST: wiremock`, since that file carries neither an `ai-client` nor `twilio-client`/`open-ai-client` section. `project/Dependencies.scala`/`build.sbt` gain Apache POI and Apache Commons CSV.
- Acceptance: `it/FileApiSpec.scala`'s `/extract/customer-book-photo` block (existing) and new `/extract/customer-book-file` block; `it/client/GatewayClient.scala`'s `extractCustomersFromPhotoPost` (existing) and new `extractCustomersFromFilePost`; `backend/wiremock/mappings/ai-client-chat-completions-acceptance.json` (existing, image-shaped) and new mapping(s) for text-shaped CSV/Excel chat-completion requests

### Tests (photo, CSV, Excel extraction)

`it/AIClientSpec.scala` is the real integration spec for `AIClient`, against a wiremock-stubbed OpenAI chat-completions endpoint (mirrors `TwilioClientSpec`, per [External client](../project/external-client.md)). Wiremock has no dynamic stub API here — its stubs are the static mapping files in `backend/wiremock/mappings/` baked into the `local/wiremock:latest` image — so the cases are distinguished by matching on request-body markers, including success, HTTP error, malformed JSON, and invalid refined-field responses. The success mapping also requires the `image_url`/`json_schema` substrings the real request body must contain — proving the outbound request carries the image content part and requests structured output, without needing to add body capture to the shared `WiremockClient` harness. An earlier `unit/clients/AIClientSpec.scala` (a plain, no-dependency test of the then-stub `AIClient.live`) existed only as a stand-in until this real spec landed and has been deleted now that it has.

Client test timing: ordinary `AIClientSpec` cases use a one-minute read timeout. Only the delayed-response timeout case overrides it to 100 ms against the 250 ms WireMock delay. A suite-wide 100 ms timeout can cause incidental retries during a slow response and break exact happy-path request counts; keep the one-request assertion rather than relaxing it.

Target coverage, ground each case in the epic's own [Business Scenarios table](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-a-photo-or-file) rather than a generic success/failure pair — every numbered scenario there is a candidate test case once its orchestration lands, including the CSV/Excel-specific scenarios 13–17 (best-effort file contacts, multi-sheet Excel, legacy `.xls`, blank rows, unlimited rows) alongside the original photo scenarios:

- Acceptance: `FileApiSpec`'s `/extract/customer-book-photo` block (existing) — happy path against a wiremock-stubbed AI response, missing `X-Organization-ID` header (400), missing token (401), invalid token (401), disallowed role (403), disallowed stage (403), non-member (500), unsupported file type (500). Mirrors the exact case set already established for this file's other two endpoints (neither tests a missing-user-details-row 500 case either). AI-service failure is intentionally not re-proven here: it's already covered by `it/AIClientSpec.scala` (the HTTP-failure-mapping itself) and `fun/FileServiceSpec.scala` (the propagation through `FileService`), and the "500 via real HTTP" transport mapping is already proven by the unsupported-file-type case in this same block — adding a third layer for the identical propagation path would be redundant per [Functional testing](../project/functional-testing.md)'s anti-pattern guidance, applied here at the acceptance layer too. The happy path uses a dedicated, marker-independent wiremock stub (`ai-client-chat-completions-acceptance.json`, priority 3, lower than the marker-specific `AIClientSpec` stubs) matching only on the `image_url`/`json_schema` substrings every real request carries, since the acceptance test has no way to control `FileService`'s real fixed `instructions` text the way `AIClientSpec`'s marker strings do; it returns one fully populated `ExtractCustomerIndividualData` wrapper (name, a default email, a default phone number) so the happy path proves real extracted data flows through decode end to end, not just an empty-result shape. New `/extract/customer-book-file` block mirrors this same case set (organization header/token/role/stage/non-member) once each, not once per format, since the middleware gate doesn't vary by detected content — plus a happy path for CSV, a happy path for Excel, and unsupported/malformed file type (500).
- Functional: `FileServiceSpec`'s `extractCustomersFromPhoto` block (existing) covers the success path, `FileScanner` failure propagating with `AIClient` never called, and `AIClient` failure propagating. `fileScannerMock` is a normal ScalaMock mock; `AIClient` is `Mocks.AIClientMock` (`mock/Mocks.scala`, a hand-written test double, not `mock[AIClient]`) because ScalaMock cannot mock its generic `extract[A](...)(using OpenAIJsonSchema[A], JsonValueCodec[A])` shape (renamed from `extractFromImage`) — see [Functional testing](../project/functional-testing.md)'s "Known limitation" section for the full writeup. New `extractCustomersFromFile` block: CSV success, Excel (`.xls` and `.xlsx`) success via the converter, `FileScanner` failure with `AIClient` never called, CSV-validity-gate failure with `AIClient` never called, `AIClient` failure propagating.
- Integration: `it/AIClientSpec.scala` (mirrors `TwilioClientSpec`) against `src/test/resources/compose/wiremock.yaml` and `backend/wiremock/mappings/ai-client-chat-completions*.json` stubs — success decode, HTTP/server and connection-reset retry exhaustion with attempt counts, delayed-response timeout exhaustion, eventual success after rate limiting while reading the image once, image-stream read failure with no outbound requests, non-retryable request rejection, and structured-response JSON/Iron decode failures. Extended for the new text branch with a stub matching a request body carrying text content rather than `image_url`, proving the outbound request is text-shaped for CSV/Excel sources; retry/timeout/decode-failure cases are not re-proven per source, already covered generically.
- Unit: `FileScannerSpec` remains unchanged for the photo endpoint because it adds no new size/type-check behavior. New: an `ExcelToCsvConverter` spec (first-sheet-only, multi-sheet ignored, row/cell text shape) and a CSV-structural-validity spec (well-formed CSV accepted, malformed/binary content rejected). The dedicated schema-only spec was removed by agreement; real OpenAI schema acceptance remains the manual golden suite's responsibility.
- Golden: `ExtractCustomersFromPhotoGoldenSpec` is manual-only and sends each of the ten sample photos to the real OpenAI API. Every photo has its own complete `ExtractCustomersResponse` baseline, and the test asserts full equality for counts, ordered customers, contact details, phone pairs, duplicate flags, extraction notes, and the unidentified-entry summary. Its empty checked-in API key cancels all ten cases unless a person deliberately supplies a key for a real run. Out of scope for CSV/Excel — no equivalent golden suite is requested for this delta.

**Status (photo, CSV, Excel extraction):** photo extraction is implemented. Verification on 2026-09-13: both gateway test modules compile, 28 client/FileService tests pass, all 30 `FileApiSpec` acceptance cases pass through the shared parent harness (including all eight photo-extraction cases), and lint passes. The live ten-photo suite generated and decoded every response but failed all ten exact baselines; the feature is not fully golden-verified. `AIClient` has its own `config/AIClientConfig.scala` and `ai-client` configuration, ordinary and AI JSON concerns live in `json/tapir.scala` and `json/ai.scala`, `Main.scala` provides the client layers, and `POST /extract/customer-book-photo` is routed through `tapir/FileServiceEndpoints.scala`. CSV/Excel extraction is not started; this section describes the agreed technical design pending implementation.

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
