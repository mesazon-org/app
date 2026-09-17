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

## Image, CSV, and Excel extraction

The epic documents this as one merged step ([step 7](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-an-image-or-file)) with three sources: an image, a CSV file, and an Excel file (`.xls` or `.xlsx`). All use one Tapir streaming endpoint because Smithy JSON routes cap at 5 MB while this upload accepts up to 20 MB without buffering the untrusted body in the JVM heap. See [Alternate HTTP](../project/alternate-http.md) and [Streaming uploads](../project/streaming-uploads.md).

`POST /extract/customer-book` accepts the raw binary body. The organization is required in `X-Organization-ID`; the original filename is required in the existing `X-File-Name` header. Security (`AuthorizationService.auth`) requires a valid access JWT, `OnboardStage.completedStages`, and `OWNER`/`ADMIN` in the organization — identical to [adding a customer](../../pages/epics/05-customer-book.md#1-user-adds-a-customer). The old `/extract/customer-book-photo` and `/extract/customer-book-file` paths were removed, not deprecated.

### Unified upload design

- **One endpoint**, `POST /extract/customer-book`, replaces both `extract/customer-book-photo` and `extract/customer-book-file` outright — no back-compat, both old paths are removed, not deprecated.
- **Transport stays the raw `streamBinaryBody`** the two endpoints already use — not multipart and not Smithy4s. sttp-tapir 1.13.31 has no streaming multipart part type, while http4s multipart decoding and this project's Smithy4s 0.19.12 http4s interpreter buffer the body in JVM heap before application code can apply `FileScanner`'s disk-backed cap. The raw Tapir stream preserves the bounded-write/full-drain behavior required by [Streaming uploads](../project/streaming-uploads.md).
- The raw body is accompanied by the required existing `X-File-Name` header. It is validated, used as a Tika detection hint, compared with the scanned file, and never stored. A missing or malformed header decodes to the existing generic `400 BAD_REQUEST_ERROR`; mismatch details use the same public error and remain visible only in server logs. Client-supplied content type and size are not part of the contract: mobile and cloud providers may report unreliable media types, standard HTTP framing already carries length when known, and the scanner independently determines the media type and actual byte count.
- The endpoint-specific domain newtype in `Newtypes.scala` is `ExtractCustomersFileName` (`String` with `NonEmptyTrimmed`). The endpoint requires it; it is not an `Option`. `FileScanner` remains feature-neutral and receives the validated filename as a plain `String`.
- `FileScanner` exposes only `scan(fileByteStream, fileNameDeclared, supportedMediaTypes, maxFileBytes)`. Every `FileService` upload uses it. It performs the bounded disk write, uses the declared filename as a Tika hint, detects the media type, and validates it against the type selected by the filename extension. Supported filename extensions are compared case-insensitively (`jpg` and `jpeg` both map to JPEG). A malformed declaration, unsupported detected content, or mismatch fails with `ServiceError.BadRequestError`; an unreadable stream and actual content over 20 MB keep the existing `ServiceError.InternalServerError`, and the network stream is still fully drained and counted.
- `FileService.extractCustomerBook(organizationID, extractCustomersFileName, extractCustomersFileByteStream)` calls `scan` once with `SupportedMediaType.extractData`, passing the filename's underlying string, then dispatches on the detected type: images pass the `FileScannedPath` to `AIClient.extractFromImage`; spreadsheets pass it through `SpreadsheetTool.validateAndConvertToCsv`, then pass the returned `CsvValidatedPath` once to `AIClient.extractFromCsv`. Filename rejection happens before either AI method. Nothing is persisted.
- Tests consolidate onto the one endpoint: `GatewayClient` gains one raw-body method whose filename argument is optional only so a rejection test can omit the otherwise-required HTTP header; `FileApiSpec` collapses to one `/extract/customer-book` block, proving the middleware matrix once, image/CSV/Excel success, missing/malformed/mismatched filename, and existing unsupported/plain-zip backstops. `FileScannerSpec` owns filename matching, mismatch, stream draining, and actual-size enforcement. `FileServiceSpec` collapses its two extraction blocks into one and proves image/spreadsheet dispatch plus no AI call after scanner rejection.

#### Implementation history

The refactor was delivered incrementally through these completed areas:

1. Required filename metadata and independent `scan` validation.
2. Unified service orchestration and image/spreadsheet dispatch.
3. Path-based `SpreadsheetTool` and `AIClient` contracts.
4. One raw streaming endpoint and one acceptance-test client method.
5. Removal of the superseded endpoints, methods, stream overloads, and tests.

**Implementation status (2026-09-16):** the unified endpoint, orchestration, path-based spreadsheet and AI clients, and shared `scan` upload pipeline are implemented. The old two endpoints, service methods, stream client overloads, legacy scanner method, and their tests are removed.

Required proof across the slices: focused `FileScannerSpec`, focused `FileServiceSpec`, gateway core/test compile, the real nested `FileApiSpec` selection with a non-zero executed count, `sbt "runLint"`, and diff/doc-link checks. Do not add an actual over-20-MB HTTP acceptance case because the known `EntityLimiter`/Ember hang remains open; prove that boundary directly in `FileScannerSpec`.

**HIGH risk and rollback:** this is a breaking replacement of two public endpoints at the upload-stream boundary, where the open `EntityLimiter`/Ember oversized-body hang makes an accidental buffering or early-stop change material. The design bounds that risk by retaining raw Tapir streaming, keeping the scanner's bounded write/full drain, and excluding the known-hanging HTTP test. There is no schema or stored-data migration; rollback requires a code deployment restoring the two old routes and extraction methods. Because there is intentionally no compatibility overlap, clients and gateway must roll forward or back together.

### Media types

`SupportedMediaType` represents every media family with non-empty extension and MIME sets. `CSV` accepts extension `csv` and both `text/csv` and `text/plain` (Tika's common fallback detection for a real CSV file, since CSV has no binary signature); `JPEG` accepts both `jpg` and `jpeg`. `XLS` and `XLSX` retain their distinct Excel MIME types and extensions. `SupportedMediaType.extractData` is the single image-plus-spreadsheet list used by customer extraction. The narrower `images`, `spreadsheets`, `excel`, and `csv` lists drive service dispatch and `SpreadsheetTool.validateAndConvertToCsv`.

#### `FileScanner` content-sniffing and filename agreement

Plain magic-byte detection cannot tell apart same-container formats: every OOXML type (`.xlsx`, `.docx`, `.pptx`) is a ZIP archive, and every legacy MS Office type (`.xls`, `.doc`, `.ppt`) is an OLE2 compound file. `scan` therefore selects the declared supported type from `X-File-Name`, writes the body to a scoped temp path, and calls Tika with that filename as its detection hint. The detected MIME must belong to the declared type's non-empty MIME set.

Extension selection happens before the body is consumed, so unsupported declarations fail fast. Size enforcement happens while the body is consumed, and media agreement is checked after the temp file is complete. The filename comparison is case-insensitive and only the final extension is used.

**Accepted trade-off:** Tika can classify a generic ZIP container as the declared OOXML subtype when given an `.xlsx` hint. `SpreadsheetTool.validateAndConvertToCsv` remains the structural backstop: Apache POI rejects a plain ZIP that is not a genuine workbook. The equivalent legacy-container ambiguity is also resolved by POI's workbook parse.

### `FileService.extractCustomerBook`

Runs inside one `ZIO.scoped` block:

1. `FileScanner.scan` spools the stream to a scoped temp file, validates it against `SupportedMediaType.extractData`, and enforces the same `fileServiceConfig.maxUploadBytes` cap (20 MB) as every other upload.
2. Images pass the returned `FileScannedPath` and detected media type to `AIClient.extractFromImage`.
3. Spreadsheets pass the same path and media type to `SpreadsheetTool.validateAndConvertToCsv`; its single `CsvValidatedPath` output goes once to `AIClient.extractFromCsv`.
4. `AIClient.extractFromCsv` owns batching internally: it repeats the header across groups of at most 50 non-blank data rows, sends at most 3 groups concurrently, and merges successful responses in group order. Any group failure fails the whole request. Counts are summed, candidate lists concatenated, summaries joined, and same-kind duplicate flags recomputed across the complete merged lists by case-insensitive name.
5. No `ImageProcessing.normalize` step and no `S3Client` call, matching the image: nothing from either new source is ever written to file storage.

`FileService` owns a new `extractCustomersFromFileInstructions` prompt (mirrors `extractCustomersFromImageInstructions`): the model is told it is reading either a CSV file or a plain-text table taken from a spreadsheet's first sheet, that there is no fixed column layout, that a column it doesn't recognize is simply ignored, that a completely blank row is not an entry at all, and that a business row may include a best-effort contact when the row gives enough signal to identify one — matching the epic's requirements 1, 3, 8, and 9 exactly.

### `SpreadsheetTool`

One `utils/SpreadsheetTool.scala` merges what were originally two separate implementations (`ExcelToCsvConverter` and `CsvValidator`, both deleted once this landed) into a single method:

```
def validateAndConvertToCsv(
    fileScannedPath: FileScannedPath,
    supportedMediaType: SupportedMediaType,
): ZIO[Scope, ServiceError, CsvValidatedPath]
```

Keyed on an exhaustive match with an explicit fail-loud third arm (`ZIO.fail(ServiceError.InternalServerError.UnexpectedError(...))` for any type outside both known groups, no silent fallthrough), mirroring `AIClient`'s own dispatch idiom:

- `SupportedMediaType.excel` (`XLS`/`XLSX`): converts with Apache POI (`WorkbookFactory.create`, first-sheet-only via `getSheetAt(0)` — other sheets are never opened, per the epic's requirement 7), writing CSV-shaped rows via Commons CSV's `CSVPrinter`, then validates that converted output the same way the CSV branch does, before returning it.
- `SupportedMediaType.csv` (`CSV`, accepting `text/csv` and `text/plain`): validates the original bytes directly — no conversion — the epic's "genuinely readable as CSV" gate (requirement 6). A Tika MIME match alone is not enough, since an arbitrary text file can be mislabelled `text/csv` or `text/plain`; a file that fails this parse is rejected the same way an unsupported file type is. Returns the original bytes unchanged on success.

Both branches are disk-backed. The scanner already owns the scoped input path, so `SpreadsheetTool` does not spool it again. Excel conversion reads from that path and writes a scoped CSV temp path through POI/`CSVPrinter`; CSV validation reads the original path directly. The returned `CsvValidatedPath` is the original path for CSV or the converted temp path for Excel.

### `AIClient` split

The trait splits into two methods matching each source's real shape — no `supportedMediaType`-keyed dispatch or match lives inside `AIClient` at all:

```
def extractFromImage[A](
    fileScannedPath: FileScannedPath,
    supportedMediaType: SupportedMediaType,
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

def extractFromCsv(
    csvValidatedPath: CsvValidatedPath,
    instructions: String,
): IO[ServiceError, ExtractCustomersResponse]
```

`extractFromImage` reads and base64-encodes the scanned path, then sends `Content.ContentPart.ImageUrl` with a `data:<mime>;base64,...` URL to GPT-5.6 Sol. `extractFromCsv` is intentionally specialized to `ExtractCustomersResponse`: it parses the validated path as CSV, ignores completely blank data rows, repeats the header across groups of at most 50 rows, and sends each group as `Content.TextContent(text)` to GPT-5.6 Luna. It runs at most 3 group requests concurrently and merges their responses in source order. Both source methods share the same private `sendAndDecode` helper for each OpenAI call.

Reads the local path once before the send loop. Retry policy (one-minute per-attempt timeout, two retries after the first attempt, one-/two-second backoff, the same retryable/non-retryable classification), schema handling, and response decoding are unchanged and apply identically to both methods — this is the one AI service call underneath both, with only the message content shape and the instructions text varying.

### New dependencies

- **Apache POI** (`poi`, `poi-ooxml`) — parses both `.xls` (HSSF) and `.xlsx` (XSSF); the only maintained JVM library covering both legacy and current Excel formats. Used only inside `SpreadsheetTool`.
- **Apache Commons CSV** — the structural "genuinely readable as CSV" parse gate; avoids hand-rolling RFC4180 quoting/embedded-newline handling.

Both added to `project/Dependencies.scala` with one centralized version each, wired into `build.sbt` the same way `scrimage`/`tika` already are. Also added: a `log4j-to-slf4j` bridge (pinned to match POI's transitive `log4j-api` version), so POI's internal logging routes through this project's normal Logback pipeline instead of falling back to a console `SimpleLogger`.

### Large spreadsheet handling

There is no application-level row limit. Inside `AIClient.extractFromCsv`, CSV and converted Excel data is divided into groups of at most 50 non-blank data rows, each with the original header, before it reaches the AI model. The client runs at most three group calls concurrently and fails the whole extraction if any group fails; it never returns partial candidates.

`ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` are reused unchanged for CSV and Excel — no new domain types for this delta. Duplicate detection, the AI retry/timeout policy, and the "nothing is ever stored" rule apply uniformly across all three sources. `ExtractCustomerIndividualData` and `ExtractCustomerBusinessData` are the outer metadata wrappers: each keeps the JSON `candidate` field plus `isDuplicate` and `extractionNotes`. Their `candidate` values use the dedicated `ExtractCustomerIndividual` and `ExtractCustomerBusiness` payloads. The complete extraction tree owns individual/business details, email entries, phone entries, a two-field phone, and business contacts while reusing the existing Iron newtypes for field-level constraints. Its JSON field layout mirrors the Smithy insert request: an extracted phone contains only `phoneNationalNumber` and `phoneCountryCode`, never the derived `phoneRegion` or `phoneNumberE164`. Semantic phone-pair validation and the exactly-one-default list rule remain in the normal insert validator; extraction asks the model to satisfy them but does not independently enforce them.

`json/tapir.scala` owns ordinary transport schemas/codecs and derives `ExtractCustomersResponse` once. `json/OpenAIJsonSchema.scala` declares `OpenAIJsonSchema[A]`; `json/ai.scala` owns explicit AI response registrations and `ai.fromTapir`, which adapts that existing schema rather than deriving the model again. The adapter marks every object property required before sttp-ai's strict-mode encoder runs: Tapir treats lists as optional, and otherwise sttp-ai would make non-optional lists nullable. Scala `Option` fields remain nullable, and supported Iron constraints remain intact. sttp-ai itself applies the remaining strict object rules, including `additionalProperties: false`; removing the root JSON Schema dialect declaration is unnecessary. `AIClient` consumes the registered OpenAI schema without doing schema adaptation itself. The legacy `OpenAIClient` remains unchanged and uses ordinary `Schema[A]`; `ai` supplies its ordinary AssistantResponse schema separately. The final live ten-image run on 2026-09-13 confirmed schema acceptance and decoding for every image, with no schema rejection or send/decode failure. All ten full response-equality assertions failed: differences include note wording/language/presence, inferred or localized countries, and address-line grouping. Golden baselines remain unchanged; a green golden run is still open.

Each OpenAI send attempt has a one-minute read timeout. `AIClient` makes at most three attempts, waiting one second before the second and two seconds before the third. It retries connection, read, and timeout failures plus HTTP `408`, `409`, `429`, and `5xx`; it does not retry image-stream read failures, other `4xx` responses (including invalid-schema `400`), OpenAI-envelope deserialization failures, or structured-response JSON/Iron decoding failures. The scanned image stream is consumed once before the send loop and retries replay the same in-memory request. Exhaustion keeps the existing `500 INTERNAL_SERVER_ERROR` result with no partial candidates. A read failure can happen after OpenAI processed the request, so a retry can create and charge for a duplicate generation; worst-case latency is about three minutes plus the one-/two-second backoff.

**Accepted trade-off:** because the whole response is one structured-output JSON document decoded in a single pass, a single field that fails its Iron constraint anywhere in that document fails the entire decode, surfacing as one `500 INTERNAL_SERVER_ERROR` for the whole request rather than dropping just that field or candidate. Cross-field rules that are not encoded by the extraction types — a phone pair being real for its country and exactly one default in a non-empty list — are requested in the prompt and enforced later by the insert validator, not by extraction decoding.

### Key files (image, CSV, Excel extraction)

- Orchestration: `service/FileService.scala` (all uploads call `scan`; unified `extractCustomerBook` dispatches the scanned path to the image or spreadsheet branch)
- AI client: `clients/AIClient.scala` (split `extractFromImage`/`extractFromCsv`, no media-type dispatch inside `AIClient`; registered OpenAI schema, per-attempt timeout, and selective retry policy unchanged), `config/AIClientConfig.scala`; the legacy `clients/OpenAIClient.scala` and its configuration remain unchanged
- Conversion and validation: `utils/SpreadsheetTool.scala` (merges what were originally `ExcelToCsvConverter` and `CsvValidator`, both deleted; Apache POI + Apache Commons CSV, disk-backed throughout)
- Pipeline utils (shared): `utils/FileScanner.scala`
- Domain: `domain/gateway/SupportedMediaType.scala` (`PNG`, `JPEG`, `WEBP`, `CSV`, `XLS`, and `XLSX` members with non-empty extension/MIME sets; `images`, `spreadsheets`, `excel`, and `csv` grouping lists); `domain/gateway/CustomerBook.scala` (the dedicated extraction model tree, including `ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` — unchanged, reused for all three sources)
- Transport: `tapir/FileServiceEndpoints.scala` (`POST /extract/customer-book`, required `X-File-Name`, `OrganizationUserRole.adminRoles`, `requiresCompletedOnboardStage = true`), `tapir/tapir.scala`
- JSON: `json/tapir.scala` (ordinary schemas/codecs, including the bottom-up extraction tree), `json/ai.scala` (AI registrations and required-property adaptation), `json/OpenAIJsonSchema.scala` (the AI schema interface). AI extraction callers import the existing Tapir response codec explicitly; there is no duplicate AI codec, `json.scala` compatibility facade, or Scala 3 export.
- Config: `ai-client` remains separate from `open-ai-client`; the CSV batch size (50) and parallelism (3) are fixed implementation constants inside `AIClient`. `project/Dependencies.scala`/`build.sbt` contain Apache POI, Apache Commons CSV, and the `log4j-to-slf4j` bridge. `Main.scala`'s Utils section wires `SpreadsheetTool.live`.
- Acceptance: `it/FileApiSpec.scala`'s `/extract/customer-book` block and `it/client/GatewayClient.scala`'s `extractCustomersPost`; the image-shaped and text-shaped WireMock mappings serve their corresponding branches.

### Tests (image, CSV, Excel extraction)

`it/AIClientSpec.scala` is the real integration spec for `AIClient`, against a WireMock-stubbed OpenAI chat-completions endpoint (mirrors `TwilioClientSpec`, per [External client](../project/external-client.md)). Its image and CSV blocks construct scoped local paths, proving success, retry/timeout/error handling, schema decoding, and the distinct outbound image/text request shapes. WireMock uses static mappings in `backend/wiremock/mappings/`.

Client test timing: ordinary `AIClientSpec` cases use a one-minute read timeout. Only the delayed-response timeout case overrides it to 100 ms against the 250 ms WireMock delay. A suite-wide 100 ms timeout can cause incidental retries during a slow response and break exact happy-path request counts; keep the one-request assertion rather than relaxing it.

Target coverage, ground each case in the epic's own [Business Scenarios table](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-an-image-or-file) rather than a generic success/failure pair — every numbered scenario there is a candidate test case once its orchestration lands, including the CSV/Excel-specific scenarios 13–17 (best-effort file contacts, multi-sheet Excel, legacy `.xls`, blank rows, unlimited rows) alongside the original image scenarios:

- Acceptance: `FileApiSpec` has one `/extract/customer-book` block. It proves image, CSV, and Excel success; the middleware matrix once; missing `X-File-Name`; filename/content mismatch as `400`; and the plain-ZIP/Excel structural backstop as `500`.
- Functional: `FileServiceSpec` has one `extractCustomerBook` block proving image and spreadsheet dispatch from `FileScannedPath`, one validated CSV-path handoff, and no downstream call after scanner rejection. `Mocks.AIClientMock` records path-native calls because ScalaMock cannot mock those method shapes directly.
- Integration: `it/AIClientSpec.scala` (mirrors `TwilioClientSpec`) against `src/test/resources/compose/wiremock.yaml` and `backend/wiremock/mappings/ai-client-chat-completions*.json` stubs — `extractFromImage`: success decode, HTTP/server and connection-reset retry exhaustion with attempt counts, delayed-response timeout exhaustion, eventual success after rate limiting while reading the image once, image-stream read failure with no outbound requests, non-retryable request rejection, and structured-response JSON/Iron decode failures. `extractFromCsv`: 101 data rows prove three 50-row Luna requests, ordered response merging, count summation, and cross-group duplicate recalculation; an unreadable path fails before any request.
- Unit: `FileScannerSpec` has one successful table covering every supported media type and focused failures for extension, detected-content mismatch, read failure, and actual byte-cap enforcement. `SpreadsheetToolSpec` covers first-sheet-only `.xlsx`, legacy `.xls`, valid CSV reuse, and malformed CSV rejection.
- Golden: `ExtractCustomersFromImageGoldenSpec` is manual-only and sends each of the ten sample images to the real OpenAI API. Every image has its own complete `ExtractCustomersResponse` baseline, and the test asserts full equality for counts, ordered customers, contact details, phone pairs, duplicate flags, extraction notes, and the unidentified-entry summary. `ExtractCustomersFromSpreadsheetGoldenSpec` is a manual inspection suite rather than an assertion suite: it routes the CSV, legacy XLS, and XLSX fixtures through `SpreadsheetTool.validateAndConvertToCsv`, sends each path once through the internally batching `AIClient.extractFromCsv`, and prints the formatted merged response without asserting any field. The checked-in API keys remain empty so every live case is canceled unless a person deliberately supplies a key for a real run.

**Status (image, CSV, Excel extraction):** the unified path-based implementation is complete. `POST /extract/customer-book` accepts all supported sources with required `X-File-Name`; the two old routes and stream-based service/client methods are removed. The image golden suite and spreadsheet inspection suite remain manual and are not treated as automated release proof.

`SupportedMediaType` carries `CSV`/`XLS`/`XLSX` plus the image and spreadsheet grouping lists and the combined `extractData` list. `SpreadsheetTool.validateAndConvertToCsv` consumes a `FileScannedPath` and returns one `CsvValidatedPath`. `AIClient.extractFromImage` and `extractFromCsv` consume their corresponding paths; only the CSV method performs internal batching. Apache POI, Apache Commons CSV, and the Log4j-to-SLF4J bridge remain the spreadsheet dependencies.

`FileService.extractCustomerBook` scans once, dispatches images directly to the AI client, and validates/converts spreadsheets before one AI-client call. `AIClient.extractFromCsv` owns bounded-parallel groups and deterministic response merging. `Main.scala` wires `SpreadsheetTool.live`; the former `ExcelToCsvConverter` and `CsvValidator` components remain deleted.

`FileScanner.scan` is the sole scanner contract. It validates the declared extension, drains and caps the upload into a scoped path, uses the filename as Tika's hint, and requires the detected MIME to agree. The acceptance-layer plain-ZIP case proves that `SpreadsheetTool`'s POI parse remains the structural backstop for hinted OOXML containers.

Acceptance coverage is consolidated under `/extract/customer-book`; verification evidence is reported with the implementing change rather than preserved as stale pass counts here.

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
