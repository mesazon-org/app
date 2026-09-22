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

`POST /extract/customers` accepts the raw binary body. The organization is required in `X-Organization-ID`; the original filename is required in the existing `X-File-Name` header. Security (`AuthorizationService.auth`) requires a valid access JWT, `OnboardStage.completedStages`, and `OWNER`/`ADMIN` in the organization — identical to [adding a customer](../../pages/epics/05-customer-book.md#1-user-adds-a-customer). The old `/extract/customer-book-photo` and `/extract/customer-book-file` paths were removed, not deprecated; the endpoint was later renamed again from `/extract/customer-book` to `/extract/customers`.

### Unified upload design

- **One endpoint**, `POST /extract/customers` (originally `POST /extract/customer-book`, renamed once the response type was renamed to `ExtractCustomersPostResponse`), replaces both `extract/customer-book-photo` and `extract/customer-book-file` outright — no back-compat, both old paths are removed, not deprecated.
- **Transport stays the raw `streamBinaryBody`** the two endpoints already use — not multipart and not Smithy4s. sttp-tapir 1.13.31 has no streaming multipart part type, while http4s multipart decoding and this project's Smithy4s 0.19.12 http4s interpreter buffer the body in JVM heap before application code can apply `FileScanner`'s disk-backed cap. The raw Tapir stream preserves the bounded-write/full-drain behavior required by [Streaming uploads](../project/streaming-uploads.md).
- The raw body is accompanied by the required existing `X-File-Name` header. It is validated, used as a Tika detection hint, compared with the scanned file, and never stored. A missing or malformed header decodes to the existing generic `400 BAD_REQUEST_ERROR`; mismatch details use the same public error and remain visible only in server logs. Client-supplied content type and size are not part of the contract: mobile and cloud providers may report unreliable media types, standard HTTP framing already carries length when known, and the scanner independently determines the media type and actual byte count.
- The endpoint-specific domain newtype in `Newtypes.scala` is `ExtractCustomersFileName` (`String` with `NonEmptyTrimmed`). The endpoint requires it; it is not an `Option`. `FileScanner` remains feature-neutral and receives the validated filename as a plain `String`.
- `FileScanner` exposes only `scan(fileByteStream, fileNameDeclared, supportedMediaTypes, fileBytesMax)`. Every `FileService` upload uses it. It performs the bounded disk write, uses the declared filename as a Tika hint, detects the media type, and validates it against the type selected by the filename extension. Supported filename extensions are compared case-insensitively (`jpg` and `jpeg` both map to JPEG). A malformed declaration, unsupported detected content, or mismatch fails with `ServiceError.BadRequestError`; an unreadable stream and actual content over 20 MB keep the existing `ServiceError.InternalServerError`, and the network stream is still fully drained and counted.
- `FileService.extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)` calls `scan` once with `SupportedMediaType.extractData`, passing the filename's underlying string, then dispatches on the detected type: images pass the `FileScannedPath` to `AIClientDataExtraction.extractFromImage`; Excel files pass it through `SpreadsheetTool.convertExcelToCsv`, CSV files through `SpreadsheetTool.convertToCsv`, then pass the returned `ZStream[Scope, ServiceError, CSVRecord]` directly to `AIClientDataExtraction.extractFromCsv` via `FileService`'s private `extractFromCsvRecordStream` helper. Filename rejection happens before either AI method. Nothing is persisted.
- Tests consolidate onto the one endpoint: `GatewayClient` gains one raw-body method whose filename argument is optional only so a rejection test can omit the otherwise-required HTTP header; `FileApiSpec` collapses to one `/extract/customers` block, proving the middleware matrix once, image/CSV/Excel success, missing/malformed/mismatched filename, and existing unsupported/plain-zip backstops. `FileScannerSpec` owns filename matching, mismatch, stream draining, and actual-size enforcement. `FileServiceSpec` collapses its two extraction blocks into one and proves image/spreadsheet dispatch plus no AI call after scanner rejection.

#### Implementation history

The refactor was delivered incrementally through these completed areas:

1. Required filename metadata and independent `scan` validation.
2. Unified service orchestration and image/spreadsheet dispatch.
3. Path-based `SpreadsheetToolOld` and `AIClient` contracts.
4. One raw streaming endpoint and one acceptance-test client method.
5. Removal of the superseded endpoints, methods, stream overloads, and tests.

**Implementation status (2026-09-16):** the unified endpoint, orchestration, path-based spreadsheet and AI clients, and shared `scan` upload pipeline are implemented. The old two endpoints, service methods, stream client overloads, legacy scanner method, and their tests are removed.

Required proof across the slices: focused `FileScannerSpec`, focused `FileServiceSpec`, gateway core/test compile, the real nested `FileApiSpec` selection with a non-zero executed count, `sbt "runLint"`, and diff/doc-link checks. Do not add an actual over-20-MB HTTP acceptance case because the known `EntityLimiter`/Ember hang remains open; prove that boundary directly in `FileScannerSpec`.

**HIGH risk and rollback:** this is a breaking replacement of two public endpoints at the upload-stream boundary, where the open `EntityLimiter`/Ember oversized-body hang makes an accidental buffering or early-stop change material. The design bounds that risk by retaining raw Tapir streaming, keeping the scanner's bounded write/full drain, and excluding the known-hanging HTTP test. There is no schema or stored-data migration; rollback requires a code deployment restoring the two old routes and extraction methods. Because there is intentionally no compatibility overlap, clients and gateway must roll forward or back together.

### Media types

`SupportedMediaType` represents every media family with non-empty extension and MIME sets. `CSV` accepts extension `csv` and both `text/csv` and `text/plain` (Tika's common fallback detection for a real CSV file, since CSV has no binary signature); `JPEG` accepts both `jpg` and `jpeg`. `XLS` and `XLSX` retain their distinct Excel MIME types and extensions. `SupportedMediaType.extractData` is the single image-plus-spreadsheet list used by customer extraction. The narrower `images`, `spreadsheets`, `excel`, and `csv` lists drive `FileService`'s own dispatch among `AIClientDataExtraction.extractFromImage`, `SpreadsheetTool.convertExcelToCsv`, and `SpreadsheetTool.convertToCsv`.

#### `FileScanner` content-sniffing and filename agreement

Plain magic-byte detection cannot tell apart same-container formats: every OOXML type (`.xlsx`, `.docx`, `.pptx`) is a ZIP archive, and every legacy MS Office type (`.xls`, `.doc`, `.ppt`) is an OLE2 compound file. `scan` therefore selects the declared supported type from `X-File-Name`, writes the body to a scoped temp path, and calls Tika with that filename as its detection hint. The detected MIME must belong to the declared type's non-empty MIME set.

Extension selection happens before the body is consumed, so unsupported declarations fail fast. Size enforcement happens while the body is consumed, and media agreement is checked after the temp file is complete. The filename comparison is case-insensitive and only the final extension is used.

**Accepted trade-off:** Tika can classify a generic ZIP container as the declared OOXML subtype when given an `.xlsx` hint. `SpreadsheetTool.convertExcelToCsv` remains the structural backstop: Apache POI rejects a plain ZIP that is not a genuine workbook. The equivalent legacy-container ambiguity is also resolved by POI's workbook parse.

### `FileService.extractCustomers`

Runs inside one `ZIO.scoped` block:

1. `FileScanner.scan` spools the stream to a scoped temp file, validates it against `SupportedMediaType.extractData`, and enforces the same `fileServiceConfig.fileBytesMax` cap (20 MB) as every other upload.
2. Images pass the returned `FileScannedPath` and detected media type to `AIClientDataExtraction.extractFromImage`.
3. Excel files pass the same path to `SpreadsheetTool.convertExcelToCsv`; CSV files pass it to `SpreadsheetTool.convertToCsv`. Either way, the resulting `ZStream[Scope, ServiceError, CSVRecord]` goes once to `AIClientDataExtraction.extractFromCsv` via `FileService`'s private `extractFromCsvRecordStream` helper.
4. `AIClientDataExtraction.extractFromCsv` owns batching internally: it indexes the whole stream with `.zipWithIndex` (header included, so the header itself lands at raw index 0) so every row carries its real position in the original file, peels the header off, then groups the data records into batches of at most `aiClientConfig.csvBatchMaxDataRows` rows — blank rows are not filtered out beforehand, so a blank row consumes a slot in the window exactly like any other row. Each row's real row number (its raw index plus one) is embedded as a leading "Row Number" column in the CSV text sent to the AI, and the header gets a `"Row Number"` label in that same column. It sends at most `aiClientConfig.csvBatchParallelism` groups concurrently and returns one decoded result per group as a `NonEmptyChunk`, in group order — it does not merge them.
5. `FileService`'s private, effectful `mergeExtractCustomersPostResponses` does everything else in one `for`-comprehension, not a pure merge followed by a separate enrichment step: any group failure fails the whole request; on success it sums counts, concatenates candidate lists and both row lists across batches, recomputes same-kind duplicate flags across the complete merged lists by case-insensitive name, and collects every batch's raw `unidentifiedEntriesNotes` into one list. It skips the `AIClientDataExtraction.noteCompaction` call — and the field stays `None` — only when that raw-notes list is empty, regardless of whether `emptyEntryRows`/`unidentifiedEntryRows` are empty or not: those two lists are already fully reported structurally, so compaction's only real job is turning scattered free-text batch notes into one message, and there is nothing for it to do when no batch wrote one. Otherwise it calls `noteCompaction` with the merged counts, both row lists, the raw notes list, and `AIInstructions.noteCompactionInstructions`, and uses the returned `NoteCompactionOutput.note`. If that call fails, `mergeExtractCustomersPostResponses` logs the failure with `ZIO.logErrorCause` (via `.catchAllCause`, never a bare `.catchAll` that would discard the cause) and falls back to a private, local, non-AI `unidentifiedEntriesNotesFallback` — a plain-English sentence built only from the merged counts and the two row lists (deliberately never the raw notes, since summarizing that free text coherently is exactly what just failed), e.g. `"1 of 4 entries could be added automatically. Rows 3, 5 were blank. Rows 9 had no name we could read."` — so `extractCustomers` never fails solely because compaction did.
6. No `ImageProcessing.normalize` step and no `S3Client` call, matching the image: nothing from either new source is ever written to file storage.

`AIInstructions` (`service/AIInstructions.scala`, its own top-level object alongside `FileService`'s, matching this codebase's one-object-per-file convention) owns a new `extractCustomersFromFileInstructions` prompt, declared `lazy val` like its two siblings (mirrors `extractCustomersFromImageInstructions`): the model is told it is reading either a CSV file or a plain-text table taken from a spreadsheet's first sheet, that there is no fixed column layout other than the leading "Row Number" column present on every row (including the header), that this column is never data but that row's real file position and must always be used — never the model's own count — whenever it names a row anywhere in its response, that a row where every column after Row Number is empty is completely blank and never an entry (not counted, but its Row Number goes in `emptyEntryRows`), that a non-blank row with no readable name is not returned as a candidate but has its Row Number added to `unidentifiedEntryRows`, that `unidentifiedEntriesNotes` is reserved for anything else worth flagging beyond those two row lists, and that a business row may include a best-effort contact when the row gives enough signal to identify one — matching the epic's requirements 1, 3, 4, 8, 9, 13, and 16 exactly. `extractCustomersFromImageInstructions` explicitly instructs the model to always return `emptyEntryRows`/`unidentifiedEntryRows` as empty lists for an image, since an image has no Row Number concept, and to keep using `unidentifiedEntriesNotes` as the only way to flag what could not be read. `AIInstructions` owns a third prompt the same way, `noteCompactionInstructions`, passed into `AIClientDataExtraction.noteCompaction`'s `instructions` parameter at its call site inside `FileService.mergeExtractCustomersPostResponses` rather than baked into the client — so a different caller of `noteCompaction` could supply its own business language without touching `AIClientDataExtraction`. All three stay `private[gateway]`, matching their prior visibility on `FileService` itself.

### `SpreadsheetTool`

`utils/SpreadsheetTool.scala` is the sole spreadsheet conversion contract, wired into `Main.scala` (`SpreadsheetTool.live`) and called directly by `FileService.extractCustomers`. It replaced `SpreadsheetToolOld` (2026-09-21 deletion; `SpreadsheetToolOld` itself had merged what were two separate implementations, `ExcelToCsvConverter` and `CsvValidator`, both deleted earlier when that merge landed, and returned a validated `CsvValidatedPath` rather than a stream — that newtype is deleted too, along with `SpreadsheetToolOld`'s own unit spec `SpreadsheetToolOldSpec`; `SpreadsheetToolSpec` is the only remaining unit spec for this concern).

```
def convertExcelToCsv(
    excelFileScannedPath: FileScannedPath
): ZStream[Scope, ServiceError, CSVRecord]

def convertToCsv(
    csvFileScannedPath: FileScannedPath
): ZStream[Scope, ServiceError, CSVRecord]
```

Neither method takes `supportedMediaType`: Apache POI's `WorkbookFactory.create` already detects XLS vs XLSX from the file itself, and CSV needs no such dispatch at all. Each method returns the parsed CSV content as a lazy stream of records rather than a validated path: the consumer (`AIClientDataExtraction.extractFromCsv`) processes records in fixed-size batches (`aiClientConfig.csvBatchMaxDataRows`), so it never needs the whole parsed CSV in memory at once — it can pull one batch, send it, then pull the next, bounding memory to one batch's worth of records regardless of file size.

- `convertExcelToCsv` (Excel: `XLS`/`XLSX`) writes a scoped temp CSV file first, via Apache POI (`WorkbookFactory.create`, first-sheet-only via `getSheetAt(0)` — other sheets are never opened, per the epic's requirement 7) and Commons CSV's `CSVPrinter` writing straight to disk with no in-memory buffering, then streams records lazily from that written file through the same private stream-producing helper `convertToCsv` uses.
- `convertToCsv` (CSV, accepting `text/csv` and `text/plain`) streams records lazily from the given CSV path directly, with no in-memory buffering.

Records stream one at a time from Apache Commons CSV's own lazy `CSVParser#iterator()` — never `.getRecords()`, which would eagerly materialize the entire parsed CSV into a `List` regardless of source size. `SpreadsheetTool` does not filter blank rows itself; every row it emits, blank or not, reaches its caller (`AIClientDataExtraction.extractFromCsv`) unchanged. The underlying reader/parser is acquired as a scoped resource and closed when the stream completes, fails, or is interrupted, matching this codebase's convention of surfacing `Scope` explicitly in the environment type for resource-bearing operations (as `FileScanner` also does).

In both methods, parsing itself is the structural "genuinely readable as CSV" validation gate (epic requirement 6) — there is no separate validation step before parsing, and a mid-stream parse failure surfaces as the same `ServiceError.InternalServerError.UnexpectedError` as an upfront one. A Tika MIME match alone is not enough, since an arbitrary text file can be mislabelled `text/csv` or `text/plain`; a file that fails this parse is rejected the same way an unsupported file type is.

### `AIClientDataExtraction`

`AIClientDataExtraction` is the sole AI client for image/CSV/Excel extraction, replacing `AIClient` (deleted 2026-09-21, along with its own integration spec `AIClientSpec.scala`; `it/AIClientDataExtractionSpec.scala` is the only remaining spec for this concern). It splits into two methods matching each source's real shape — no `supportedMediaType`-keyed dispatch or match lives inside it at all — plus a third, `noteCompaction`, that compacts already-extracted metadata rather than reading a source:

```
def extractFromImage[A](
    fileScannedPath: FileScannedPath,
    supportedMediaType: SupportedMediaType,
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): IO[ServiceError, A]

def extractFromCsv[A](
    csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
    instructions: String,
)(using OpenAIJsonSchema[A], JsonValueCodec[A]): ZIO[Scope, ServiceError, NonEmptyChunk[A]]

def noteCompaction(
    entriesIdentified: Long,
    entriesProcessed: Long,
    emptyEntryRows: List[Long],
    unidentifiedEntryRows: List[Long],
    unidentifiedEntriesNotes: List[String],
    instructions: String,
): IO[ServiceError, NoteCompactionOutput]
```

`extractFromImage` reads and base64-encodes the scanned path, then sends `Content.ContentPart.ImageUrl` with a `data:<mime>;base64,...` URL to GPT-5.6 Sol — unchanged from the deleted `AIClient`.

`extractFromCsv` is fully generic — it knows nothing about `ExtractCustomersPostResponse`'s shape. It takes the `ZStream[Scope, ServiceError, CSVRecord]` `SpreadsheetTool` produces directly, rather than reading and parsing a validated path itself. It indexes the whole stream with `.zipWithIndex` (header included, so the header itself lands at raw index 0), peels the header record off, then groups the remaining data records into batches of at most `aiClientConfig.csvBatchMaxDataRows` rows (still always at least one batch, even with zero data rows) and runs them with `aiClientConfig.csvBatchParallelism`-bounded concurrency. Every row's real row number — its raw index plus one, since the header is row 1 and a 0-based counter needs that offset to become "the row number as seen in the file" — is embedded as an extra leading "Row Number" column in the CSV text sent to the AI; the header itself gets a `"Row Number"` label in that same leading column. Blank rows are not filtered out or tracked separately: a blank row is expected to be rare enough in practice that it flows into a batch exactly like any other row, consuming one slot in the batch window, with its real row number and empty field values sent the same way — the caller's own instructions (`AIInstructions.extractCustomersFromFileInstructions`) are what tell the AI to report that row's number in its response, the same way it is told to report a row it could not identify a name for; `extractFromCsv` itself carries no opinion on how a blank or unidentified row is reported, since it is generic over the response type. `extractFromCsv` sends each batch as `Content.TextContent(text)` to GPT-5.6 Luna and returns one decoded `A` per batch as a `NonEmptyChunk`, preserving batch order; it performs no merging itself. `FileService` supplies `ExtractCustomersPostResponse` as the concrete type argument and merges the returned chunk (summed counts, concatenated candidate lists, concatenated `emptyEntryRows`/`unidentifiedEntryRows`, cross-batch duplicate recalculation, raw per-batch `unidentifiedEntriesNotes` collected rather than joined) after the call returns — the `FileService.extractCustomers` section above describes the merge/compaction split and `noteCompaction`'s role in it in full. Both methods share the same private `sendAndDecode` helper for each OpenAI call.

Reads the local path once before the send loop for images; the CSV stream is consumed once, incrementally, as each batch is pulled. Retry policy (one-minute per-attempt timeout, two retries after the first attempt, one-/two-second backoff, the same retryable/non-retryable classification), schema handling, and response decoding are unchanged and apply identically to both methods — this is the one AI service call underneath both, with only the message content shape and the instructions text varying.

`noteCompaction` is a third, non-generic method that takes an `instructions` parameter the same way `extractFromImage`/`extractFromCsv` do — its system prompt is not baked into `AIClientDataExtraction`, so a different caller could supply its own business language for a note-compaction call without changing this client. `AIInstructions` owns the actual prompt text, `noteCompactionInstructions`, declared alongside `extractCustomersFromImageInstructions`/`extractCustomersFromFileInstructions` and passed in at its `noteCompaction` call site inside `FileService.mergeExtractCustomersPostResponses` — the same ownership split the other two methods already use. `noteCompaction` takes the merged extraction counts, the two merged row-number lists, and the raw (not-yet-joined) per-batch `unidentifiedEntriesNotes` strings, formats them into one plain-text `Content.TextContent` message, and sends it plus the caller-supplied `instructions` to GPT-5.4-mini (`ChatCompletionModel.GPT54Mini`) through the same `sendAndDecode` helper — so it shares the identical retry/timeout policy described below. `noteCompaction` returns the decoded domain type directly, `IO[ServiceError, NoteCompactionOutput]`, rather than unwrapping to a bare string internally — `domain/gateway/AIClient.scala` declares `final case class NoteCompactionOutput(note: String)`, its own domain file (not `CustomerBook.scala`, since this is an AI-client output concept rather than a customer-book one) alongside `AssistantResponse.scala`'s equivalent one-type-per-file pattern. `json/ai.scala` registers its `Schema`/`OpenAIJsonSchema`/`JsonValueCodec` givens the same way it registers `AssistantResponse`'s — `Schema.derived` directly in `ai.scala` (not sourced from `tapir.scala`, since this type, like `AssistantResponse`, has no ordinary/transport life outside AI extraction) plus `ai.fromTapir` for the `OpenAIJsonSchema` and a `JsonCodecMaker`-derived codec; `AIClientDataExtraction.scala` only imports those givens (`json.ai.given`) rather than deriving or declaring the type itself. It exists to produce one coherent, friendly compacted message from several batches' worth of raw notes, replacing a bare `mkString` join; `FileService.mergeExtractCustomersPostResponses` extracts `.note` from the returned `NoteCompactionOutput` to build the response's `Option[String]` field. `FileService`'s call site, its skip-when-nothing-to-report check, and its local non-AI fallback on failure are described in the `FileService.extractCustomers` section above (steps 4–5).

`AIInstructions.extractCustomersFromFileInstructions` matches this contract exactly: it tells the model that every row it is given, including the header, carries a leading "Row Number" column that is not data but that row's real position in the original file; that it must always use that value — never its own count — whenever it refers to a row anywhere in its response; and that a row where every column after Row Number is empty is completely blank, is never counted or identified as an entry, but has its Row Number added to `emptyEntryRows` — matching the epic's requirements 4, 13, 15, 16, and 17 (step 7's Business Scenarios and Requirements). `entriesIdentified`/`entriesProcessed` counts are unaffected — a blank row is still never counted as an entry, only tracked via `emptyEntryRows`.

### New dependencies

- **Apache POI** (`poi`, `poi-ooxml`) — parses both `.xls` (HSSF) and `.xlsx` (XSSF); the only maintained JVM library covering both legacy and current Excel formats. Used only inside `SpreadsheetTool`.
- **Apache Commons CSV** — the structural "genuinely readable as CSV" parse gate; avoids hand-rolling RFC4180 quoting/embedded-newline handling.

Both added to `project/Dependencies.scala` with one centralized version each, wired into `build.sbt` the same way `scrimage`/`tika` already are. Also added: a `log4j-to-slf4j` bridge (pinned to match POI's transitive `log4j-api` version), so POI's internal logging routes through this project's normal Logback pipeline instead of falling back to a console `SimpleLogger`.

### Large spreadsheet handling

There is no application-level row limit. Inside `AIClientDataExtraction.extractFromCsv`, CSV and converted Excel data (including any blank rows, which are not filtered out beforehand) is divided into groups of at most `aiClientConfig.csvBatchMaxDataRows` rows, each with the original header — now carrying a leading "Row Number" column — before it reaches the AI model. The client runs at most `aiClientConfig.csvBatchParallelism` group calls concurrently and fails the whole extraction if any group fails; on success it returns one decoded result per group as a `NonEmptyChunk`, never partial candidates. `FileService` merges that chunk into the final `ExtractCustomersPostResponse` once every group has succeeded.

`ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersPostResponse` are reused unchanged for CSV and Excel — no new domain types for this delta. Duplicate detection, the AI retry/timeout policy, and the "nothing is ever stored" rule apply uniformly across all three sources. `ExtractCustomerIndividualData` and `ExtractCustomerBusinessData` are the outer metadata wrappers: each keeps the JSON `candidate` field plus `isDuplicate` and `extractionNotes`. Their `candidate` values use the dedicated `ExtractCustomerIndividual` and `ExtractCustomerBusiness` payloads. The complete extraction tree owns individual/business details, email entries, phone entries, a two-field phone, and business contacts while reusing the existing Iron newtypes for field-level constraints. Its JSON field layout mirrors the Smithy insert request: an extracted phone contains only `phoneNationalNumber` and `phoneCountryCode`, never the derived `phoneRegion` or `phoneNumberE164`. Semantic phone-pair validation and the exactly-one-default list rule remain in the normal insert validator; extraction asks the model to satisfy them but does not independently enforce them.

`json/tapir.scala` owns ordinary transport schemas/codecs and derives `ExtractCustomersPostResponse` once. `json/OpenAIJsonSchema.scala` declares `OpenAIJsonSchema[A]`; `json/ai.scala` owns explicit AI response registrations and `ai.fromTapir`, which adapts that existing schema rather than deriving the model again. The adapter marks every object property required before sttp-ai's strict-mode encoder runs: Tapir treats lists as optional, and otherwise sttp-ai would make non-optional lists nullable. Scala `Option` fields remain nullable, and supported Iron constraints remain intact. sttp-ai itself applies the remaining strict object rules, including `additionalProperties: false`; removing the root JSON Schema dialect declaration is unnecessary. `AIClientDataExtraction` consumes the registered OpenAI schema without doing schema adaptation itself. The legacy `OpenAIClient` remains unchanged and uses ordinary `Schema[A]`; `ai` supplies its ordinary AssistantResponse schema separately. The final live ten-image run on 2026-09-13 confirmed schema acceptance and decoding for every image, with no schema rejection or send/decode failure. All ten full response-equality assertions failed: differences include note wording/language/presence, inferred or localized countries, and address-line grouping. Golden baselines remain unchanged; a green golden run is still open.

Each OpenAI send attempt has a one-minute read timeout. `AIClientDataExtraction` makes at most three attempts, waiting one second before the second and two seconds before the third. It retries connection, read, and timeout failures plus HTTP `408`, `409`, `429`, and `5xx`; it does not retry image-stream read failures, other `4xx` responses (including invalid-schema `400`), OpenAI-envelope deserialization failures, or structured-response JSON/Iron decoding failures. The scanned image stream is consumed once before the send loop and retries replay the same in-memory request. Exhaustion keeps the existing `500 INTERNAL_SERVER_ERROR` result with no partial candidates. A read failure can happen after OpenAI processed the request, so a retry can create and charge for a duplicate generation; worst-case latency is about three minutes plus the one-/two-second backoff.

**Accepted trade-off:** because the whole response is one structured-output JSON document decoded in a single pass, a single field that fails its Iron constraint anywhere in that document fails the entire decode, surfacing as one `500 INTERNAL_SERVER_ERROR` for the whole request rather than dropping just that field or candidate. Cross-field rules that are not encoded by the extraction types — a phone pair being real for its country and exactly one default in a non-empty list — are requested in the prompt and enforced later by the insert validator, not by extraction decoding.

### Key files (image, CSV, Excel extraction)

- Orchestration: `service/FileService.scala` (all uploads call `scan`; unified `extractCustomers` dispatches the scanned path to the image or spreadsheet branch; private `extractFromCsvRecordStream` composes `AIClientDataExtraction.extractFromCsv` with the record stream `SpreadsheetTool` returns; private `mergeExtractCustomersPostResponses` is the one effectful `for`-comprehension that merges the returned batches, calls `AIClientDataExtraction.noteCompaction` only when there are raw batch notes to compact, and logs-then-falls-back to `unidentifiedEntriesNotesFallback` if that call fails), `service/AIInstructions.scala` (its own top-level object, matching this codebase's one-object-per-file convention; holds all three AI prompt strings as `private[gateway] lazy val`s — `extractCustomersFromImageInstructions`, `extractCustomersFromFileInstructions`, `noteCompactionInstructions`)
- AI client: `clients/AIClientDataExtraction.scala` (fully generic `extractFromImage[A]`/`extractFromCsv[A]`, no media-type dispatch and no response merging inside it — `extractFromCsv` takes the `ZStream[Scope, ServiceError, CSVRecord]` `SpreadsheetTool` produces directly and returns one decoded result per batch as a `NonEmptyChunk` for its caller to merge; registered OpenAI schema, per-attempt timeout, and selective retry policy unchanged; also the non-generic `noteCompaction`, which takes a caller-supplied `instructions` prompt the same way `extractFromImage`/`extractFromCsv` do and sends GPT-5.4-mini that prompt plus the merged counts/row lists/raw notes, returning the decoded domain type `NoteCompactionOutput` directly rather than unwrapping it to a bare string; its `Schema`/`OpenAIJsonSchema`/`JsonValueCodec` givens are registered in `json/ai.scala`, so this file only imports them (`json.ai.given`)), `config/AIClientDataExtractionConfig.scala` (owns the configurable CSV batch size/parallelism); the legacy `clients/OpenAIClient.scala` and its configuration remain unchanged
- Conversion: `utils/SpreadsheetTool.scala` (the sole spreadsheet contract; Apache POI + Apache Commons CSV, disk-backed throughout; each method returns a lazy `ZStream[Scope, ServiceError, CSVRecord]`)
- Pipeline utils (shared): `utils/FileScanner.scala`
- Domain: `domain/gateway/SupportedMediaType.scala` (`PNG`, `JPEG`, `WEBP`, `CSV`, `XLS`, and `XLSX` members with non-empty extension/MIME sets; `images`, `spreadsheets`, `excel`, and `csv` grouping lists); `domain/gateway/CustomerBook.scala` (the dedicated extraction model tree, including `ExtractCustomerIndividualData`, `ExtractCustomerBusinessData`, and `ExtractCustomersPostResponse` — unchanged, reused for all three sources); `domain/gateway/AIClient.scala` (`NoteCompactionOutput(note: String)`, `noteCompaction`'s decoded return type — its own file since it is an AI-client output concept, not a customer-book one, mirroring `AssistantResponse.scala`'s one-type-per-file pattern)
- Transport: `tapir/FileServiceEndpoints.scala` (`POST /extract/customers`, required `X-File-Name`, `OrganizationUserRole.adminRoles`, `requiresCompletedOnboardStage = true`), `tapir/tapir.scala`
- JSON: `json/tapir.scala` (ordinary schemas/codecs, including the bottom-up extraction tree), `json/ai.scala` (AI registrations and required-property adaptation: `ExtractCustomersPostResponse` adapts the ordinary schema `tapir.scala` already derived via `fromTapir`; `AssistantResponse` and `NoteCompactionOutput` each derive their own `Schema` directly here instead, since neither has an ordinary/transport life outside AI extraction — `NoteCompactionOutput` also gets its `OpenAIJsonSchema` via `fromTapir` and a `JsonCodecMaker`-derived `JsonValueCodec`, the same three-given shape `ExtractCustomersPostResponse` has, just with a self-derived base `Schema`), `json/OpenAIJsonSchema.scala` (the AI schema interface). AI extraction callers import the existing Tapir response codec explicitly; there is no duplicate AI codec, `json.scala` compatibility facade, or Scala 3 export.
- Config: `ai-client-data-extraction` (renamed 2026-09-21 from `ai-client`, alongside its Scala type and env vars, to name it after its client) remains separate from `open-ai-client`; the CSV batch size and parallelism are configurable via `AIClientDataExtractionConfig.csvBatchMaxDataRows`/`csvBatchParallelism` (`ai-client-data-extraction.csv-batch-max-data-rows`/`csv-batch-parallelism` in `application.conf`, defaulting to 50/3, fed by `AI_CLIENT_DATA_EXTRACTION_*` env vars in `terraform/dev/gateway/{variables,app}.tf` and `backend/gateway/it/compose.yaml`). `project/Dependencies.scala`/`build.sbt` contain Apache POI, Apache Commons CSV, and the `log4j-to-slf4j` bridge. `Main.scala`'s Utils section wires `SpreadsheetTool.live` and `AIClientDataExtractionConfig.live`/`AIClientDataExtraction.live`.
- Acceptance: `it/FileApiSpec.scala`'s `/extract/customers` block and `it/client/GatewayClient.scala`'s `extractCustomersPost`; the image-shaped and text-shaped WireMock mappings serve their corresponding branches.

### Tests (image, CSV, Excel extraction)

`it/AIClientDataExtractionSpec.scala` is the real integration spec for `AIClientDataExtraction`, against a WireMock-stubbed OpenAI chat-completions endpoint (mirrors `TwilioClientSpec`, per [External client](../project/external-client.md)). Its image block constructs scoped local paths; its CSV block builds records from an in-memory CSV stream; its `noteCompaction` block calls the method directly with fixed counts/row lists and a marker string passed as `instructions`, the same per-test marker convention `extractFromImage`/`extractFromCsv` already use. All three prove success, retry/timeout/error handling, and schema decoding; the CSV block additionally proves batch-window sizing (including that a blank row still consumes a batch slot rather than being excluded beforehand) and that each row's real file position reaches the AI in a leading "Row Number" column. WireMock uses static mappings in `backend/wiremock/mappings/`.

Client test timing: ordinary `AIClientDataExtractionSpec` cases use a one-minute read timeout. Only the delayed-response timeout case overrides it to 100 ms against the 250 ms WireMock delay. A suite-wide 100 ms timeout can cause incidental retries during a slow response and break exact happy-path request counts; keep the one-request assertion rather than relaxing it.

Target coverage, ground each case in the epic's own [Business Scenarios table](../../pages/epics/05-customer-book.md#7-user-extracts-customers-from-an-image-or-file) rather than a generic success/failure pair — every numbered scenario there is a candidate test case once its orchestration lands, including the CSV/Excel-specific scenarios 13–17 (best-effort file contacts, multi-sheet Excel, legacy `.xls`, blank rows, unlimited rows) alongside the original image scenarios:

- Acceptance: `FileApiSpec` has one `/extract/customers` block. It proves image, CSV, and Excel success; the middleware matrix once; missing `X-File-Name`; filename/content mismatch as `400`; and the plain-ZIP/Excel structural backstop as `500`.
- Functional: `FileServiceSpec` has one `extractCustomers` block proving image and spreadsheet dispatch from `FileScannedPath`, a CSV record-stream handoff, and no downstream call after scanner rejection. `Mocks.AIClientDataExtractionMock` consumes the incoming stream (`.runCollect`) and records the collected `List[CSVRecord]` alongside the instructions string, because ScalaMock cannot mock `AIClientDataExtraction`'s generic method shapes directly; `noteCompaction` has a concrete, non-generic signature, so `Mocks.AIClientDataExtractionMock` also implements it directly (call log plus canned result, same shape as its two sibling methods) rather than needing the stream-consuming workaround, and a plain `mock[AIClientDataExtraction]` could mock it too if a case ever needed that instead. The block also proves: the multi-batch merge case configures `noteCompaction`'s canned result and asserts both the exact merged counts/row-lists/raw-notes/instructions passed to it and that its result becomes `unidentifiedEntriesNotes`; two skip cases assert `noteCompaction` is never called — one with everything empty, one with populated `emptyEntryRows`/`unidentifiedEntryRows` but no raw batch notes — together pinning that the skip condition depends only on the raw notes list, never the row lists; and a `noteCompaction`-failure case asserts the request still succeeds with candidates intact and `unidentifiedEntriesNotes` is the local `unidentifiedEntriesNotesFallback` message instead of the raw batch note that failed to compact.
- Integration: `it/AIClientDataExtractionSpec.scala` (mirrors `TwilioClientSpec`) against `src/test/resources/compose/wiremock.yaml` and `backend/wiremock/mappings/ai-client-chat-completions*.json` stubs. `AIClientDataExtraction` is generic (`extractFromImage[A]`/`extractFromCsv[A]`) and knows nothing about `ExtractCustomersPostResponse`'s shape, so every case here uses a minimal test-local `ExtractedTestResult(value: String)` type: `extractFromImage`: success decode, HTTP/server and connection-reset retry exhaustion with attempt counts, delayed-response timeout exhaustion, eventual success after rate limiting while reading the image once, image-stream read failure with no outbound requests, non-retryable request rejection, and two distinct decode failures (unparsable JSON, and JSON that doesn't match the target shape). `extractFromCsv`: 101 rows and a configured smaller batch size each prove one decoded `ExtractedTestResult` per batch is returned in order, with the expected request/parallelism counts; a header-only stream sends exactly one batch; a stream failure before any batch is sent fails with that same error and no outbound requests; a blank row between two data rows still consumes a batch-window slot instead of being excluded beforehand; and a dedicated WireMock stub proves the exact CSV text sent to the AI carries each row's real file position in a leading "Row Number" column, blank rows included. `noteCompaction`: success decode of the returned `NoteCompactionOutput.note`, retryable-failure exhaustion (three attempts), non-retryable rejection (one attempt), and a decode failure — the smaller four-case set this method needs since it shares `sendAndDecode`'s retry/timeout policy with the other two methods, already proven exhaustively there. **Accepted coverage gap:** merging/deduplicating `ExtractCustomersPostResponse` candidates across batches moved to `FileService.scala` and is proven by `FileServiceSpec`'s mocked `"merge and deduplicate candidates from multiple CSV batches"` case, but no automated (CI-running) test any longer sends a real `ExtractCustomersPostResponse`-shaped payload through the real JSON/schema decode pipeline — `FileServiceSpec` bypasses real JSON via mocks, and the golden specs below that do exercise the real shape are manual-only and never run in CI. This was a deliberate, user-approved tradeoff for `AIClientDataExtractionSpec` simplicity and speed, not an oversight.
- Unit: `FileScannerSpec` has one successful table covering every supported media type and focused failures for extension, detected-content mismatch, read failure, and actual byte-cap enforcement. `SpreadsheetToolSpec` is the sole spreadsheet unit spec (the `SpreadsheetToolOld`-era `SpreadsheetToolOldSpec` is deleted): it covers first-sheet-only `.xlsx`, legacy `.xls`, valid CSV, and malformed CSV rejection, collecting each method's `ZStream[Scope, ServiceError, CSVRecord]` output for assertion.
- Golden: `ExtractCustomersFromImageGoldenSpec` and `ExtractCustomersFromSpreadsheetGoldenSpec` are both manual inspection suites, not assertion suites. Each wires a real `FileService[ServiceTask]` (`FileService.local`, `FileServiceConfig.live`, `FileScanner.live`, `SpreadsheetTool.live`, `AIClientDataExtraction.live` against the real OpenAI API, with `OrganizationManagementRepository`/`CatalogueRepository`/`S3ClientOrganizationMedia`/`ImageProcessing` as unexpectant ScalaMock stubs since `extractCustomers` never calls them) and calls `FileService.extractCustomers` directly — the same entry point `POST /extract/customers` calls — so the run exercises the real scan/media-type-detection/dispatch/batch-merge pipeline end to end. The image spec sends each of the ten sample images; the spreadsheet spec sends the CSV, XLS, and XLSX fixtures and lets `FileScanner`'s real content-type detection choose the conversion path. Both print the single merged `ExtractCustomersPostResponse` as indented JSON for manual inspection without asserting any field. The checked-in API keys remain empty so every live case is canceled unless a person deliberately supplies a key for a real run.

**Status (image, CSV, Excel extraction):** the unified streaming implementation is complete. `POST /extract/customers` accepts all supported sources with required `X-File-Name`. `SpreadsheetTool`/`AIClientDataExtraction` are the sole spreadsheet/AI clients for this feature; the earlier path-based `SpreadsheetToolOld`/`AIClient` pair, their own specs, and `CsvValidatedPath` are deleted. The image golden suite and spreadsheet inspection suite remain manual and are not treated as automated release proof.

`SupportedMediaType` carries `CSV`/`XLS`/`XLSX` plus the image and spreadsheet grouping lists and the combined `extractData` list. `SpreadsheetTool.convertExcelToCsv`/`convertToCsv` each consume a `FileScannedPath` and return a lazy `ZStream[Scope, ServiceError, CSVRecord]`. `AIClientDataExtraction.extractFromImage` consumes a `FileScannedPath`; `extractFromCsv` consumes that record stream directly and performs internal batching. Apache POI, Apache Commons CSV, and the Log4j-to-SLF4J bridge remain the spreadsheet dependencies.

`FileService.extractCustomers` scans once, dispatches images directly to the AI client, and converts spreadsheets to a record stream before one AI-client call. `AIClientDataExtraction.extractFromCsv` owns bounded-parallel groups and per-group decoding; `FileService.mergeExtractCustomersPostResponses` owns the deterministic response merging, the one conditional `noteCompaction` call (skipped only when there are no raw batch notes to compact), and its logged, local fallback on failure, once the returned chunk comes back. `Main.scala` wires `SpreadsheetTool.live` and `AIClientDataExtraction.live`; the former `ExcelToCsvConverter`/`CsvValidator` components and the later `SpreadsheetToolOld`/`AIClient` pair are all deleted.

`FileScanner.scan` is the sole scanner contract. It validates the declared extension, drains and caps the upload into a scoped path, uses the filename as Tika's hint, and requires the detected MIME to agree. The acceptance-layer plain-ZIP case proves that `SpreadsheetTool`'s POI parse remains the structural backstop for hinted OOXML containers.

Acceptance coverage is consolidated under `/extract/customers`; verification evidence is reported with the implementing change rather than preserved as stale pass counts here.

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
