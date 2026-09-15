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
- `POST /extract/customer-book-file` — binary body is a CSV or Excel file (`SupportedMediaType.spreadsheets`: `CSV`, `PLAINTEXT_CSV`, `XLS`, `XLSX`). New.

Both: organization in the `X-Organization-ID` header; security (`AuthorizationService.auth`) is a valid access JWT, `OnboardStage.completedStages`, and `OWNER`/`ADMIN` in the organization — identical gate to [adding a customer](../../pages/epics/05-customer-book.md#1-user-adds-a-customer). No `X-File-Name` header on either: nothing from any of the three sources is ever kept, so there is no original file name to preserve.

### Planned: merge to one endpoint (2026-09-15 refactor, not yet implemented)

Everything above is the shipped, two-endpoint design. It is being replaced — feature behavior is unchanged (see [epic step 7](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-a-photo-or-file), already updated for this), only the transport and internal code shape change:

- **One endpoint**, `POST /extract/customer-book`, replaces both `extract/customer-book-photo` and `extract/customer-book-file` outright — no back-compat, both old paths are removed, not deprecated.
- **Multipart/form-data** body, replacing the raw `streamBinaryBody`: one part carries the file bytes; three new, all-optional parts carry `fileNameDeclared`, `contentTypeDeclared`, and `fileSizeDeclared` — hints only, never stored, never independently trusted. The real content-sniffing (Tika) + structural parse (POI/Commons CSV) + real byte count remain authoritative, exactly as today; a declared value that disagrees with the real file is still rejected the same way an unsupported/oversized file is rejected today.
- `fileSizeDeclared`'s only real effect: when it alone already exceeds `fileServiceConfig.maxUploadBytes`, `FileScanner.scan` fails immediately without ever opening or writing the temp file — the incoming stream is still fully drained (never truncated early), per [Streaming uploads](../project/streaming-uploads.md)'s `EntityLimiter`-is-not-a-hard-cap constraint, which this refactor does not change or attempt to fix. `fileNameDeclared`/`contentTypeDeclared`'s only real effect: `FileScanner.detectMediaType` tries the declared hint first (skipping straight to a single `Tika.detect(InputStream, String)` hinted call) before falling back to today's magic-only-then-bounded-candidate-loop path when absent or when the hint doesn't verify.
- `FileService`'s two trait methods collapse into one, `extractCustomers(organizationID, fileNameDeclared: Option[FileNameDeclared], contentTypeDeclared: Option[String], fileSizeDeclared: Option[FileSizeDeclared], byteStream)`, calling `fileScanner.scan` once against a combined `SupportedMediaType.images ++ SupportedMediaType.spreadsheets` candidate list, then branching once on the detected `SupportedMediaType` to call either `aiClient.extractFromImage` or `spreadsheetTool.convertValidateCsv` + `aiClient.extractFromCsv` — this is the "same endpoint, slightly different logic" the merge is for. `AIClient`, `SpreadsheetTool`, and the retry/timeout policy are unchanged; this refactor only changes transport and dispatch.
- New domain newtypes (`Newtypes.scala`): `FileNameDeclared` (`NonEmptyTrimmed` `String`) and `FileSizeDeclared` (non-negative `Long`, "zero or more" per the epic table — exact Iron constraint TBD by the Lead against the library's available numeric constraints). `contentTypeDeclared` stays a plain `Option[String]`, no Iron refinement, matching the epic table's "no constraint" row.
- Tests consolidate onto the one endpoint: `GatewayClient` gains one multipart-capable method replacing its two; `FileApiSpec` collapses to one `/extract/customer-book` block — the middleware-gate matrix (org header/token/role/stage/non-member) proven once instead of twice, format-specific happy paths (photo/CSV/Excel) and the plain-zip security-backstop case unchanged in substance; `FileServiceSpec` collapses its two blocks into one against the merged `extractCustomers` method.
- **Sequencing**: because this project has no prior multipart usage and streaming uploads on this transport have a documented, previously-reproduced hang bug (`EntityLimiter` is not a hard cap — see [Streaming uploads](../project/streaming-uploads.md)), the first slice is a narrow, real (not throwaway) proof that multipart decode composes correctly with `FileScanner`'s existing disk-backed cap-enforcement fold, before any slice builds further logic on top.

### Media types

`SupportedMediaType` gains `CSV` (`text/csv`), `PLAINTEXT_CSV` (`text/plain` — Tika's common fallback detection for a real CSV file, since CSV has no binary signature the way an image or an Office document does), `XLS` (`application/vnd.ms-excel`), and `XLSX` (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`). `SupportedMediaType.spreadsheets = List(CSV, PLAINTEXT_CSV, XLS, XLSX)` is what `FileScanner.scan` is called with for the file endpoint; `SupportedMediaType.images` is unchanged and still used by the photo endpoint. `CSV` and `PLAINTEXT_CSV` are handled identically downstream — both simply mean "treat these bytes as CSV text." Two narrower lists exist alongside `spreadsheets`: `SupportedMediaType.excel = List(XLS, XLSX)` and `SupportedMediaType.csv = List(CSV, PLAINTEXT_CSV)` — the two list-membership guards `SpreadsheetTool.convertValidateCsv`'s match uses to pick a branch.

#### `FileScanner` content-sniffing for `.xls`/`.xlsx` (no client-supplied file name)

Plain magic-byte detection cannot tell apart same-container formats: every OOXML type (`.xlsx`, `.docx`, `.pptx`) is a ZIP archive, and every legacy MS Office type (`.xls`, `.doc`, `.ppt`) is an OLE2 compound file, so Tika's magic-only match for either lands on the generic container type (`application/zip` / `application/x-tika-msoffice`), not the specific one. Tika's own `detect(Path)`/`detect(File)` only resolve this because they derive a filename hint from the path automatically — but `FileScanner`'s spooled temp files (`TempFile.createScoped`) never carry a real extension, and this feature deliberately never collects one (no `X-File-Name` header for photo/CSV/Excel extraction, per the epic's "nothing from any of the three is ever kept" rule).

`FileScanner.scan` now detects in two phases: a plain magic-only `tika.detect(tempFile)` first (unchanged, and still the only thing that ever runs for images, whose magic bytes are always unambiguous); only if that doesn't already match one of the caller's `supportedMediaTypes` does it retry, trying each candidate in order with a synthetic name hint (`Tika.detect(InputStream, String)`, `upload.<candidate.ext>` — Tika's own documented mechanism for exactly this "no real file name" situation), accepting only a candidate whose hinted result exactly equals that same candidate's own mime type.

**Accepted trade-off:** because OOXML types are registered in Tika's mime registry as specializations of `application/zip`, this hinted retry can let a plain, non-Excel ZIP file (or any other ZIP-based format not in the candidate list) through `FileScanner` as a plausible `XLSX` candidate — the synthetic hint narrows an already-ambiguous magic match, it does not independently verify the file is a genuine workbook. This is bounded, not unguarded: the hint can only ever resolve ambiguity within the same magic-detected family (Tika enforces this itself; ordinary content — a PNG, a real CSV — is never affected, and this exact scenario is what let this ship un-tripped: a non-Excel `.zip`, run all the way through the pipeline via `FileScannerSpec`, is still rejected — just one layer later, by `SpreadsheetTool.convertValidateCsv`'s Apache POI structural parse (`WorkbookFactory.create` throws for anything that isn't a real workbook), not by `FileScanner` itself. Legacy `.xls` does not have this same exposure to a generic-zip mix-up (OLE2 and ZIP are different magic families), but is equally exposed to being accepted as a plausible `.xls` when it's really some other OLE2-based Office format (`.doc`, `.ppt`) — same bounded trade-off, same POI backstop.

### `FileService.extractCustomersFromFile`

Runs inside one `ZIO.scoped` block, mirroring `extractCustomersFromPhoto`, and is only two calls deep — no format branching lives in `FileService` itself, that all lives in `SpreadsheetTool` (below):

1. `FileScanner.scan` spools the stream to a temp file, content-sniffs against `SupportedMediaType.spreadsheets`, and enforces the same `fileServiceConfig.maxUploadBytes` cap (20 MB) as every other upload in this product — no separate, smaller cap for files, and no row-count limit at all: a file is processed however many rows it has.
2. `SpreadsheetTool.convertValidateCsv(scanOutput.fileByteStreamScanned, scanOutput.supportedMediaType)` handles both Excel conversion and CSV structural validation and returns one `ValidatedCsvByteStream`, regardless of which of the four detected types the source was.
3. `AIClient.extractFromCsv[ExtractCustomersResponse](validatedCsv, extractCustomersFromFileInstructions)` sends it — no `supportedMediaType` is passed to `AIClient` for this source at all; the `ValidatedCsvByteStream` newtype is the only "this is genuinely CSV-shaped text" signal `AIClient` needs.
4. No `ImageProcessing.normalize` step and no `S3Client` call, matching the photo: nothing from either new source is ever written to file storage.

`FileService` owns a new `extractCustomersFromFileInstructions` prompt (mirrors `extractCustomersFromPhotoInstructions`): the model is told it is reading either a CSV file or a plain-text table taken from a spreadsheet's first sheet, that there is no fixed column layout, that a column it doesn't recognize is simply ignored, that a completely blank row is not an entry at all, and that a business row may include a best-effort contact when the row gives enough signal to identify one — matching the epic's requirements 1, 3, 8, and 9 exactly.

### `SpreadsheetTool`

One `utils/SpreadsheetTool.scala` merges what were originally two separate implementations (`ExcelToCsvConverter` and `CsvValidator`, both deleted once this landed) into a single method:

```
def convertValidateCsv(
    fileByteStreamScanned: FileByteStreamScanned,
    supportedMediaType: SupportedMediaType,
): ZIO[Scope, ServiceError, ValidatedCsvByteStream]
```

Keyed on an exhaustive match with an explicit fail-loud third arm (`ZIO.fail(ServiceError.InternalServerError.UnexpectedError(...))` for any type outside both known groups, no silent fallthrough), mirroring `AIClient`'s own dispatch idiom:

- `SupportedMediaType.excel` (`XLS`/`XLSX`): converts with Apache POI (`WorkbookFactory.create`, first-sheet-only via `getSheetAt(0)` — other sheets are never opened, per the epic's requirement 7), writing CSV-shaped rows via Commons CSV's `CSVPrinter`, then validates that converted output the same way the CSV branch does, before returning it.
- `SupportedMediaType.csv` (`CSV`/`PLAINTEXT_CSV`): validates the original bytes directly — no conversion — the epic's "genuinely readable as CSV" gate (requirement 6). A Tika mime match alone is not enough, since an arbitrary text file can be mislabelled `text/csv` or `text/plain`; a file that fails this parse is rejected the same way an unsupported file type is. Returns the original bytes unchanged on success.

Both branches are disk-backed throughout and never buffer a whole file into the JVM heap: the input is spooled to a temp file (`TempFile.createScoped` + `ZSink.fromPath`, the same idiom `FileScanner` already uses), Excel conversion reads/writes through `File`-based POI/`CSVPrinter` APIs, and a shared `validateCsvFile` helper opens a buffered `Reader` on the temp file path for Commons CSV's `CSVParser.parse`, then iterates and discards records (`csvParser.iterator().asScala.foreach(_ => ())`) rather than calling `getRecords()`, which would materialize every parsed record into a `List` in memory. Passing a `File` rather than an `InputStream` to POI matters even though the parsed workbook model itself is unavoidably in-memory for every sheet regardless of input source (confirmed against POI's own source: `XSSFWorkbook.onDocumentRead()` parses every sheet unconditionally, not just the first) — `WorkbookFactory.create(File)` lets POI's ZIP handling use true random file access instead of `ZipInputStreamZipEntrySource`'s default full in-memory buffering of the raw archive bytes. The returned `ValidatedCsvByteStream` streams from the same on-disk temp file used for validation — no second copy for either branch.

### `AIClient` split

The trait splits into two methods matching each source's real shape — no `supportedMediaType`-keyed dispatch or match lives inside `AIClient` at all:

```
def extractFromImage[A](
    imageByteStream: FileByteStreamScanned,
    supportedMediaType: SupportedMediaType,
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

def extractFromCsv[A](
    csvByteStream: ValidatedCsvByteStream,
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]
```

`extractFromImage` is the original, unchanged image logic (base64-encode the bytes, send `Content.ContentPart.ImageUrl` with a `data:<mime>;base64,...` URL) under its original pre-generalization name and parameter name. `extractFromCsv` takes a `ValidatedCsvByteStream` — the caller (`SpreadsheetTool`, via `FileService`) has already decided the bytes are genuinely CSV-shaped; `AIClient` doesn't re-derive that from a media type. It decodes the bytes as UTF-8 and sends them as plain message text via `Content.TextContent(text)`, distinct from the image branch's `Content.ArrayContent`/`image_url` array shape. Both methods share the same private `sendAndDecode` helper for the OpenAI call itself.

Consumes the stream exactly once either way, same guarantee as today. Retry policy (one-minute per-attempt timeout, two retries after the first attempt, one-/two-second backoff, the same retryable/non-retryable classification), schema handling, and response decoding are unchanged and apply identically to both methods — this is the one AI service call underneath both, with only the message content shape and the instructions text varying.

### New dependencies

- **Apache POI** (`poi`, `poi-ooxml`) — parses both `.xls` (HSSF) and `.xlsx` (XSSF); the only maintained JVM library covering both legacy and current Excel formats. Used only inside `SpreadsheetTool`.
- **Apache Commons CSV** — the structural "genuinely readable as CSV" parse gate; avoids hand-rolling RFC4180 quoting/embedded-newline handling.

Both added to `project/Dependencies.scala` with one centralized version each, wired into `build.sbt` the same way `scrimage`/`tika` already are. Also added: a `log4j-to-slf4j` bridge (pinned to match POI's transitive `log4j-api` version), so POI's internal logging routes through this project's normal Logback pipeline instead of falling back to a console `SimpleLogger`.

### Accepted trade-off (file sizes and AI context limits)

There is no application-level row limit and no chunking of large files, by agreement. A CSV/Excel file whose derived text exceeds the AI model's context window is not specially detected — it falls through to the existing "AI service rejects the request / returns something that can't be decoded" path (epic scenario 10), a non-retried `500 INTERNAL_SERVER_ERROR`. Revisit before this goes live for customers with very large books.

`ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` are reused unchanged for CSV and Excel — no new domain types for this delta. Duplicate detection, the AI retry/timeout policy, and the "nothing is ever stored" rule apply uniformly across all three sources. `ExtractCustomerIndividualData` and `ExtractCustomerBusinessData` are the outer metadata wrappers: each keeps the JSON `candidate` field plus `isDuplicate` and `extractionNotes`. Their `candidate` values use the dedicated `ExtractCustomerIndividual` and `ExtractCustomerBusiness` payloads. The complete extraction tree owns individual/business details, email entries, phone entries, a two-field phone, and business contacts while reusing the existing Iron newtypes for field-level constraints. Its JSON field layout mirrors the Smithy insert request: an extracted phone contains only `phoneNationalNumber` and `phoneCountryCode`, never the derived `phoneRegion` or `phoneNumberE164`. Semantic phone-pair validation and the exactly-one-default list rule remain in the normal insert validator; extraction asks the model to satisfy them but does not independently enforce them.

`json/tapir.scala` owns ordinary transport schemas/codecs and derives `ExtractCustomersResponse` once. `json/OpenAIJsonSchema.scala` declares `OpenAIJsonSchema[A]`; `json/ai.scala` owns explicit AI response registrations and `ai.fromTapir`, which adapts that existing schema rather than deriving the model again. The adapter marks every object property required before sttp-ai's strict-mode encoder runs: Tapir treats lists as optional, and otherwise sttp-ai would make non-optional lists nullable. Scala `Option` fields remain nullable, and supported Iron constraints remain intact. sttp-ai itself applies the remaining strict object rules, including `additionalProperties: false`; removing the root JSON Schema dialect declaration is unnecessary. `AIClient` consumes the registered OpenAI schema without doing schema adaptation itself. The legacy `OpenAIClient` remains unchanged and uses ordinary `Schema[A]`; `ai` supplies its ordinary AssistantResponse schema separately. The final live ten-photo run on 2026-09-13 confirmed schema acceptance and decoding for every photo, with no schema rejection or send/decode failure. All ten full response-equality assertions failed: differences include note wording/language/presence, inferred or localized countries, and address-line grouping. Golden baselines remain unchanged; a green golden run is still open.

Each OpenAI send attempt has a one-minute read timeout. `AIClient` makes at most three attempts, waiting one second before the second and two seconds before the third. It retries connection, read, and timeout failures plus HTTP `408`, `409`, `429`, and `5xx`; it does not retry image-stream read failures, other `4xx` responses (including invalid-schema `400`), OpenAI-envelope deserialization failures, or structured-response JSON/Iron decoding failures. The scanned image stream is consumed once before the send loop and retries replay the same in-memory request. Exhaustion keeps the existing `500 INTERNAL_SERVER_ERROR` result with no partial candidates. A read failure can happen after OpenAI processed the request, so a retry can create and charge for a duplicate generation; worst-case latency is about three minutes plus the one-/two-second backoff.

**Accepted trade-off:** because the whole response is one structured-output JSON document decoded in a single pass, a single field that fails its Iron constraint anywhere in that document fails the entire decode, surfacing as one `500 INTERNAL_SERVER_ERROR` for the whole request rather than dropping just that field or candidate. Cross-field rules that are not encoded by the extraction types — a phone pair being real for its country and exactly one default in a non-empty list — are requested in the prompt and enforced later by the insert validator, not by extraction decoding.

### Key files (photo, CSV, Excel extraction)

- Orchestration: `service/FileService.scala` (shared with logo/catalogue-item image uploads; `extractCustomersFromPhoto` calls `AIClient.extractFromImage`; `extractCustomersFromFile` calls `SpreadsheetTool.convertValidateCsv` then `AIClient.extractFromCsv`, no pattern match at this layer)
- AI client: `clients/AIClient.scala` (split `extractFromImage`/`extractFromCsv`, no media-type dispatch inside `AIClient`; registered OpenAI schema, per-attempt timeout, and selective retry policy unchanged), `config/AIClientConfig.scala`; the legacy `clients/OpenAIClient.scala` and its configuration remain unchanged
- Conversion and validation: `utils/SpreadsheetTool.scala` (merges what were originally `ExcelToCsvConverter` and `CsvValidator`, both deleted; Apache POI + Apache Commons CSV, disk-backed throughout)
- Pipeline utils (shared): `utils/FileScanner.scala`
- Domain: `domain/gateway/SupportedMediaType.scala` (`CSV`, `PLAINTEXT_CSV`, `XLS`, `XLSX` members; `spreadsheets`, `excel`, `csv` grouping lists); `domain/gateway/CustomerBook.scala` (the dedicated extraction model tree, including `ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersResponse` — unchanged, reused for all three sources)
- Transport: `tapir/FileServiceEndpoints.scala` (existing `extractCustomersFromPhotoPostEndpoint` unchanged; new `extractCustomersFromFilePostEndpoint` for `POST /extract/customer-book-file`, same security/routing pattern — `OrganizationUserRole.adminRoles`, `requiresCompletedOnboardStage = true`), `tapir/tapir.scala`
- JSON: `json/tapir.scala` (ordinary schemas/codecs, including the bottom-up extraction tree), `json/ai.scala` (AI registrations and required-property adaptation), `json/OpenAIJsonSchema.scala` (the AI schema interface). AI extraction callers import the existing Tapir response codec explicitly; there is no duplicate AI codec, `json.scala` compatibility facade, or Scala 3 export.
- Config: new `ai-client` section in core's `application.conf` only (separate from `open-ai-client`); gateway-it needs no `application.conf` change, only `compose.yaml`'s `AI_CLIENT_HOST: wiremock`, since that file carries neither an `ai-client` nor `twilio-client`/`open-ai-client` section. `project/Dependencies.scala`/`build.sbt` gain Apache POI, Apache Commons CSV, and a `log4j-to-slf4j` bridge. `Main.scala`'s Utils section wires `SpreadsheetTool.live`.
- Acceptance: `it/FileApiSpec.scala`'s `/extract/customer-book-photo` block (existing) and new `/extract/customer-book-file` block; `it/client/GatewayClient.scala`'s `extractCustomersFromPhotoPost` (existing) and new `extractCustomersFromFilePost`; `backend/wiremock/mappings/ai-client-chat-completions-acceptance.json` (existing, image-shaped) and new `ai-client-chat-completions-file-acceptance.json` (text-shaped, marker-independent, serves both the CSV and Excel happy paths since both converge to the same request shape by the time they reach `AIClient`)

### Tests (photo, CSV, Excel extraction)

`it/AIClientSpec.scala` is the real integration spec for `AIClient`, against a wiremock-stubbed OpenAI chat-completions endpoint (mirrors `TwilioClientSpec`, per [External client](../project/external-client.md)). It has two `should` blocks matching the split trait: `extractFromImage` (twelve cases: success, duplicate-notes/unidentified-summary/empty-result decoding, retry-while-reading-once, stream-read failure, AI error/rejection/connection-reset/timeout, and undecodable/invalid-refined decode failures) and `extractFromCsv` (one success case, constructing a `ValidatedCsvByteStream` directly rather than picking a random `SupportedMediaType.spreadsheets` member — there is no more media-type dispatch inside `AIClient` left to prove uniform across formats). Wiremock has no dynamic stub API here — its stubs are the static mapping files in `backend/wiremock/mappings/` baked into the `local/wiremock:latest` image — so the cases are distinguished by matching on request-body markers, including success, HTTP error, malformed JSON, and invalid refined-field responses. The success mapping also requires the `image_url`/`json_schema` substrings the real request body must contain — proving the outbound request carries the image content part and requests structured output, without needing to add body capture to the shared `WiremockClient` harness. An earlier `unit/clients/AIClientSpec.scala` (a plain, no-dependency test of the then-stub `AIClient.live`) existed only as a stand-in until this real spec landed and has been deleted now that it has.

Client test timing: ordinary `AIClientSpec` cases use a one-minute read timeout. Only the delayed-response timeout case overrides it to 100 ms against the 250 ms WireMock delay. A suite-wide 100 ms timeout can cause incidental retries during a slow response and break exact happy-path request counts; keep the one-request assertion rather than relaxing it.

Target coverage, ground each case in the epic's own [Business Scenarios table](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-a-photo-or-file) rather than a generic success/failure pair — every numbered scenario there is a candidate test case once its orchestration lands, including the CSV/Excel-specific scenarios 13–17 (best-effort file contacts, multi-sheet Excel, legacy `.xls`, blank rows, unlimited rows) alongside the original photo scenarios:

- Acceptance: `FileApiSpec`'s `/extract/customer-book-photo` block (existing) — happy path against a wiremock-stubbed AI response, missing `X-Organization-ID` header (400), missing token (401), invalid token (401), disallowed role (403), disallowed stage (403), non-member (500), unsupported file type (500). Mirrors the exact case set already established for this file's other two endpoints (neither tests a missing-user-details-row 500 case either). AI-service failure is intentionally not re-proven here: it's already covered by `it/AIClientSpec.scala` (the HTTP-failure-mapping itself) and `fun/FileServiceSpec.scala` (the propagation through `FileService`), and the "500 via real HTTP" transport mapping is already proven by the unsupported-file-type case in this same block — adding a third layer for the identical propagation path would be redundant per [Functional testing](../project/functional-testing.md)'s anti-pattern guidance, applied here at the acceptance layer too. The happy path uses a dedicated, marker-independent wiremock stub (`ai-client-chat-completions-acceptance.json`, priority 3, lower than the marker-specific `AIClientSpec` stubs) matching only on the `image_url`/`json_schema` substrings every real request carries, since the acceptance test has no way to control `FileService`'s real fixed `instructions` text the way `AIClientSpec`'s marker strings do; it returns one fully populated `ExtractCustomerIndividualData` wrapper (name, a default email, a default phone number) so the happy path proves real extracted data flows through decode end to end, not just an empty-result shape. The `/extract/customer-book-file` block mirrors this same case set (organization header/token/role/stage/non-member) once each, not once per format, since the middleware gate doesn't vary by detected content — plus a happy path for CSV, a happy path for Excel, unsupported file type (500, an image upload), and a plain non-Excel `.zip` (500) proving `FileScanner`'s bounded hinted-detection retry and `SpreadsheetTool`'s POI structural parse compose correctly end to end (see the accepted trade-off in the `FileScanner` subsection above) — 10 cases in total (missing token and invalid token are two separate cases, not one).
- Functional: `FileServiceSpec`'s `extractCustomersFromPhoto` block covers the success path, `FileScanner` failure propagating with `AIClient` never called, and `AIClient` failure propagating. `fileScannerMock` is a normal ScalaMock mock; `AIClient` is `Mocks.AIClientMock` (`mock/Mocks.scala`, a hand-written test double, not `mock[AIClient]`) because ScalaMock cannot mock its generic `extractFromImage[A](...)`/`extractFromCsv[A](...)` shapes — see [Functional testing](../project/functional-testing.md)'s "Known limitation" section for the full writeup. `AIClientMock` tracks each method's calls in its own correctly-typed ref (`extractFromImageCallsRef: Ref[List[(FileByteStreamScanned, SupportedMediaType, String)]]`, `extractFromCsvCallsRef: Ref[List[(ValidatedCsvByteStream, String)]]`) — no synthesized marker for either. `extractCustomersFromFile` block: CSV success, Excel success via `SpreadsheetTool` (asserts the *converted* stream, not the original XLSX bytes, reaches `AIClient`), `FileScanner` failure with `AIClient` never called, `SpreadsheetTool` failure with `AIClient` never called, `AIClient` failure propagating — all against one `spreadsheetToolMock` (`mock[SpreadsheetTool]`), `inSequence` after `fileScannerMock.scan` for every case with more than one mock.
- Integration: `it/AIClientSpec.scala` (mirrors `TwilioClientSpec`) against `src/test/resources/compose/wiremock.yaml` and `backend/wiremock/mappings/ai-client-chat-completions*.json` stubs — `extractFromImage`: success decode, HTTP/server and connection-reset retry exhaustion with attempt counts, delayed-response timeout exhaustion, eventual success after rate limiting while reading the image once, image-stream read failure with no outbound requests, non-retryable request rejection, and structured-response JSON/Iron decode failures. `extractFromCsv`: one success case against a stub matching a request body carrying text content rather than `image_url`, proving the outbound request is text-shaped; retry/timeout/decode-failure cases are not re-proven for `extractFromCsv`, already covered generically by `extractFromImage`'s cases since both share `sendAndDecode`.
- Unit: `FileScannerSpec` gained three cases for the file endpoint's content-sniffing, `FileScanner`-only (no `SpreadsheetTool` reference — see the `FileScanner` subsection above for why that coupling was deliberately rejected): a genuine CSV file resolves via magic-only detection alone (`SupportedMediaType.PLAINTEXT_CSV`, no hinted retry needed), and genuine `.xlsx`/`.xls` files each resolve to their real type only via the new hinted-retry path, with no real file name available. Its six pre-existing photo-endpoint cases are unchanged. `SpreadsheetToolSpec` (`unit/utils/SpreadsheetToolSpec.scala`) merges both former specs' scenarios into one: first-sheet-only on a multi-sheet `.xlsx`, legacy `.xls` reads the same as `.xlsx`, well-formed CSV accepted without conversion, structurally malformed CSV rejected with `ServiceError.InternalServerError.UnexpectedError`. The dedicated schema-only spec was removed by agreement; real OpenAI schema acceptance remains the manual golden suite's responsibility.
- Golden: `ExtractCustomersFromPhotoGoldenSpec` is manual-only and sends each of the ten sample photos to the real OpenAI API. Every photo has its own complete `ExtractCustomersResponse` baseline, and the test asserts full equality for counts, ordered customers, contact details, phone pairs, duplicate flags, extraction notes, and the unidentified-entry summary. Its empty checked-in API key cancels all ten cases unless a person deliberately supplies a key for a real run. Out of scope for CSV/Excel — no equivalent golden suite is requested for this delta.

**Status (photo, CSV, Excel extraction):** photo extraction is implemented. Verification on 2026-09-13: both gateway test modules compile, 28 client/FileService tests pass, all 30 `FileApiSpec` acceptance cases pass through the shared parent harness (including all eight photo-extraction cases), and lint passes. The live ten-photo suite generated and decoded every response but failed all ten exact baselines; the feature is not fully golden-verified. `AIClient` has its own `config/AIClientConfig.scala` and `ai-client` configuration, ordinary and AI JSON concerns live in `json/tapir.scala` and `json/ai.scala`, `Main.scala` provides the client layers, and `POST /extract/customer-book-photo` is routed through `tapir/FileServiceEndpoints.scala`.

**CSV/Excel extraction is complete, as of 2026-09-15** — orchestration, transport, and acceptance coverage all landed; nothing in the agreed delta is left not-started. `SupportedMediaType` carries `CSV`/`PLAINTEXT_CSV`/`XLS`/`XLSX` plus the `spreadsheets`/`excel`/`csv` grouping lists. `SpreadsheetTool` (`utils/SpreadsheetTool.scala`) merges Excel conversion and CSV structural validation into one `convertValidateCsv`, disk-backed throughout — spooled temp files, `File`-based POI I/O, a buffered-`Reader`-backed Commons CSV parse that iterates and discards records rather than materializing them — keyed on an exhaustive match with a fail-loud third arm, mirroring `AIClient`'s own dispatch idiom; see the dedicated subsection above for the full design. Its unit spec, `SpreadsheetToolSpec`, merges both former specs' scenarios (first-sheet-only on a multi-sheet `.xlsx`, legacy `.xls`, well-formed CSV accepted, structurally malformed CSV rejected) and all four pass. `AIClient` is split into `extractFromImage`/`extractFromCsv` with no media-type dispatch inside the client at all; `it/AIClientSpec.scala`'s thirteen cases (twelve under `extractFromImage`, one under `extractFromCsv`) pass. Apache POI, Apache Commons CSV, and a `log4j-to-slf4j` bridge (pinned to match POI's transitive `log4j-api` version, so POI's internal logging routes through this project's normal Logback pipeline instead of falling back to a console logger) are added as dependencies.

`FileService.extractCustomersFromFile` is two calls deep with no pattern match of its own: `fileScanner.scan` against `SupportedMediaType.spreadsheets`, then `spreadsheetTool.convertValidateCsv(scanOutput.fileByteStreamScanned, scanOutput.supportedMediaType)`, then `aiClient.extractFromCsv[ExtractCustomersResponse](validatedCsv, extractCustomersFromFileInstructions)`. `Main.scala` wires `SpreadsheetTool.live`, replacing the deleted `ExcelToCsvConverter.live`/`CsvValidator.live`. `FileServiceSpec`'s five `extractCustomersFromFile` cases pass against one `spreadsheetToolMock`: CSV success, Excel success (asserts the *converted* stream, not the original XLSX bytes, is what reaches `AIClient`), `FileScanner` failure, `SpreadsheetTool` failure, and `AIClient` failure — each proving `AIClient` is/isn't called as appropriate. `ExcelToCsvConverter.scala`/`CsvValidator.scala` and their specs are deleted; nothing else in the codebase references them. `POST /extract/customer-book-file` is routed: `tapir/FileServiceEndpoints.scala` gained `extractCustomersFromFilePostEndpoint`, mirroring `extractCustomersFromPhotoPostEndpoint`'s exact shape, wired into both the route list and the OpenAPI docs list.

`FileScanner.scan` also gained a real content-sniffing fix during this delta (discovered via the Excel acceptance case, not part of the original plan): its temp files carry no real file name, and Tika's magic-byte-only detection cannot distinguish same-container formats (every OOXML type is a ZIP, every legacy MS Office type is an OLE2 file) without one, so a genuine `.xls`/`.xlsx` upload was previously always rejected as an unsupported type. `FileScanner.scan` now retries with a bounded, per-candidate synthetic file-name hint (`Tika.detect(InputStream, String)`) only when magic-only detection doesn't already match one of the caller's `supportedMediaTypes`, accepting a candidate only when its hinted result exactly equals that same candidate's own mime type — see the accepted trade-off in the `FileScanner` subsection above. `FileScannerSpec` proves the fix directly (real `.xls`/`.xlsx` now correctly detected, real CSV unaffected, all pre-existing image cases unchanged); the acceptance-layer plain-zip case proves the two-layer defense (bounded `FileScanner` hint plus `SpreadsheetTool`'s POI structural parse) holds end to end.

Acceptance coverage is complete: `FileApiSpec`'s `/extract/customer-book-file` block has all 10 cases (missing organization header, missing token, invalid token, disallowed role, disallowed stage, and non-member once each, a CSV happy path, an Excel happy path, an unsupported-file-type case, and the plain-zip case), all passing against the real gateway with a wiremock-stubbed AI call. Deferred to a future round, out of scope for this delivery: collecting a real client-supplied file name/media-type header for this endpoint (the user's decision, not pursued here).

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
