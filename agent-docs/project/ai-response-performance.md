# AI response performance

> **Status (issue 607, rework built 2026-11-23; diffson round built 2026-11-23; Customer Book check built 2026-11-24, see [Customer Book check](#customer-book-check); stored patches built and first written 2026-11-24, see [Stored patches](#stored-patches)).** Product truth: [AI Response Performance](../../pages/epics/07-ai-response-performance.md). **Diffson round:** the comparison moved from difflicious to plain diffson (`lcsdiff`, user decision option 3): position-based, order counts in every list, a differences-only output, goldens unchanged ([Comparison](#comparison), [Difference output](#difference-output)). **Built:** the spec with its ten cases and moved assets (slice 1), the comparison (slice 2, difflicious; replaced by plain diffson in the diffson round), and the spec's own spreadsheet join and note shortening for the notes compaction case, with `CatalogueFileService` unchanged (slice 3), and the [removal](#scope) of the old scored checks, their goldens and the four old Catalogue asset folders (slice 4). **Goldens:** all 10 are hand-corrected and committed: 5a (`image-1.json`, `image-2.json`, `pdf-1.json`), 5b (`spreadsheet-1.json`, `-2.json`, `-3.json`), 5c (`spreadsheet-1-notes-compaction.json`) and 5d (`image-3.json`, `pdf-2.json`, `spreadsheet-4.json`, unchanged from the capture). **First run at defaults (slice 6):** all 10 cases answered and all 10 failed on differences, see [First run at defaults](#first-run-at-defaults-2026-11-23). Open gaps: see [Open gaps](#open-gaps).

How the hand-run AI golden check compares a live model's raw answer with a hand-corrected golden answer. Any difference fails the case.

## Scope

- **Two specs:** `golden/CatalogueFileAiExtractGoldenSpec` (10 cases, below) and `golden/CustomerBookFileAiExtractGoldenSpec` (23 cases, see [Customer Book check](#customer-book-check)). Each source gets one explicit case, with no list or loop. These are the Catalogue cases (the epic fixes the list, requirement 3):

  | Case | Asset | Declared name | Processing message | Model | Golden |
  |---|---|---|---|---|---|
  | Price-list picture 1 | `image-1.jpeg` (JPEG) | — | today's text, verbatim: `ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is` | `imageModel` | `image-1.json` |
  | Price-list picture 2 | `image-2.jpeg` | — | none | `imageModel` | `image-2.json` |
  | PDF price list | `pdf-1.pdf` | `Catalogue-Price-List.PDF` | none | `pdfModel` | `pdf-1.json` |
  | Spreadsheets | `spreadsheet-1.csv`, `-2.xls`, `-3.xlsx` | — | none | `csvModel` | `spreadsheet-<n>.json` |
  | Notes compaction | `spreadsheet-1.csv`, `csvBatchMaxDataRows = 15` | — | none | `csvModel`, then `noteCompactionModel` | `spreadsheet-1-notes-compaction.json` |
  | Unrelated picture, PDF, spreadsheet | `image-3.jpeg`, `pdf-2.pdf`, `spreadsheet-4.csv` | `pdf-2.pdf` | none | the model of its file kind | `image-3.json`, `pdf-2.json`, `spreadsheet-4.json` |

- **Assets** were moved, byte for byte, from the four old Catalogue golden asset folders (`assets-test/CatalogueFileAiExtract{FromImage,FromPdf,FromSpreadsheet,UnrelatedContent}GoldenSpec/`, deleted in slice 4) into `assets-test/CatalogueFileAiExtractGoldenSpec/`. The unrelated files were renumbered because their names clash with the price-list files: the old unrelated `image-1.jpeg` became `image-3.jpeg`, `pdf-1.pdf` became `pdf-2.pdf`, and `spreadsheet-1.csv` became `spreadsheet-4.csv`.
- **Removed in slice 4 (user decision):**
  - the scorer `golden/performance/` and its unit specs `unit/golden/performance/`, with nothing replacing them;
  - the four old Catalogue extraction specs;
  - both notes compaction specs (`CatalogueFileNotesCompactionGoldenSpec`, `CustomerBookFileNotesCompactionGoldenSpec`);
  - the four Customer Book golden specs;
  - every golden JSON those specs loaded; `assets-golden/` now holds only `ImageProcessingSpec/`.

  The Customer Book source assets stay, see [Open gaps](#open-gaps).
- **Not built:** a score or percentage, run history, saved reports and multi-model runs. The Customer Book check is built: see [Customer Book check](#customer-book-check).

## What is called (no service)

The check calls the AI integration point directly, with the production prompts. It does not call `CatalogueFileService.aiExtract`. The service's deterministic steps after the AI answer are already covered by `fun/CatalogueFileServiceSpec` and are not repeated: duplicates, field marks, attention, info lines and the Chimney mapping.

| Source | Call |
|---|---|
| Image | `AIClientDataExtraction.extractFromImage[CatalogueFileAIExtract](FileScannedPath(<asset path>), SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions, <message>)` |
| PDF | `extractFromPdf[CatalogueFileAIExtract](FileScannedPath(<asset path>), <declared name>, AIInstructions.extractCatalogueItemsFromPdfInstructions, None)` |
| Spreadsheet | `SpreadsheetTool.convertToCsv` (CSV) or `convertExcelToCsv` (XLS/XLSX), then `extractFromCsv[CatalogueFileAIExtract](…, AIInstructions.extractCatalogueItemsFromFileInstructions, None)`, then the group's answer as is, or for the notes compaction case the spec's own [join and note shortening](#what-is-called-no-service) |

- The asset path is the source-tree path `src/test/resources/assets-test/CatalogueFileAiExtractGoldenSpec/<asset>`, relative to the forked test working directory, which is the module base directory. No `FileScanner` is used: the media type is given explicitly, and `FileScannedPath(...)` only wraps the asset path in the type the tools take. Each spreadsheet case writes its steps inline (user decision, slice 3): it calls `convertToCsv` (CSV) or `convertExcelToCsv` (XLS/XLSX) directly by its own file type, with no media-type check, then `ZIO.scoped(extractFromCsv(…)).zioValue`.
- **Spec-private join and note shortening (user decision, slice 3, option B; replaces the earlier Q2 choice of sharing the service's step).** `CatalogueFileService` is not changed or called, and the service's skip rule is not copied: each case knows from its own setup whether its spreadsheet is read in groups (user decision, slice 3).
  - `extractFromCsv` returns the groups' answers, `NonEmptyChunk[CatalogueFileAIExtract]`. There is no shared spreadsheet helper.
  - **Single-group cases** (`spreadsheet-1.csv`, `-2.xls`, `-3.xlsx` and the unrelated `spreadsheet-4.csv`, all at `csvBatchMaxDataRows = 100`) take the one group's answer with `.head`: no join and no shortening. If a group were ever dropped this way (an asset grown past 100 data rows), its items and rows would be missing from the answer and the case would fail as differences against the hand-corrected golden.
  - **The notes compaction case** always joins the groups with a spec-private `mergeCatalogueFileAIExtracts`, copied verbatim from `CatalogueFileServiceImpl` (it sums both counts and concatenates candidates, both row lists and top-level notes in group order). It then always calls `noteCompaction(<joined notes>, AIInstructions.compactCatalogueFileNotesInstructions)` and puts the shortening answer in place of the top-level notes **as is**. The service's acceptance rule (non-empty and no more notes than were sent) and its keep-the-raw-notes fallback are **not** copied.

  So a failed or timed-out shortening call fails the case through `.zioValue`, like any AI error. An empty or longer shortening answer is compared as is and shows up as notes differences. The copy does not follow later service changes (see [Open gaps](#open-gaps)).
- **Notes compaction case:** `spreadsheet-1.csv` has a header and 30 data rows (file rows 2–31). With `csvBatchMaxDataRows = 15` it is read in exactly two groups, rows 2–16 and rows 17–31. The split falls on the repeated `Product` header (row 17), so no item straddles groups. Each group produces general notes, so compaction runs.

## Golden files

- **Location:** `backend/gateway/core/src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec/<asset base name>[-<variant>].json`, resolved through `Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec")`.
- **Shape:** the raw `CatalogueFileAIExtract` (the "AI answer", epic requirement 1), with a 2-space indent and no trailing newline. It is decoded with the production `json.ai.catalogueFileAIExtractCodec`. Absent `Option`s and empty lists may be omitted or written as `null`/`[]`; both decode the same. Committed goldens show every field as `null`/`[]`, except a selling unit's absent `perUnit` and `unitSize`: `CatalogueItemVariantSaleUnit` is written by the shared `json.tapir.catalogueItemVariantSaleUnitCodec` (jsoniter defaults), which leaves absent `Option`s out.
- **Never written by the check.** Committed code has no recording path.
- **Bootstrap procedure** (issue 607, user decision):
  1. One live run per source at high reasoning effort, only with the user's go-ahead and key.
  2. Temporary code, reverted afterwards, writes the answer with every field shown (`null`/`[]`, with the selling-unit exception above) to the gitignored `target/golden-capture/`.
  3. Hand-correct the answer against the asset and the [AI extraction](../standards/ai-extraction.md) rules, notes included, changing only what breaks a rule.
  4. Decode-check it with the production codec, and re-encode it in the capture format to prove the file's shape byte for byte (temporary code, reverted).
- **Notes round (user decision 2026-11-23; done in slice N3, 2026-11-24):** only the general `extractionNotes` of the committed Catalogue goldens were hand-edited to the new order and wording, with no new capture; nothing else in them changed. Seven goldens changed (`image-1`, `image-2`, `pdf-1`, `spreadsheet-1`, `-2`, `-3`, `spreadsheet-1-notes-compaction`); `image-3`, `pdf-2` and `spreadsheet-4` hold only a nothing-detected note and needed no change. Each left-out line is quoted whole (column labels: first label only; a spreadsheet row's printed cells and a picture/PDF row's cells joined by ", "), side-by-side tables are separate lines, and a date line below its label gets its own note. Typography follows the source where there is evidence, including the same supplier's PDF text layer (as for the 5a apostrophe), such as the en dash in `image-1`'s dates.
- **Capture done (issue 607, 2026-11-22):** one live session ran all 10 cases at `high` effort for image, PDF, CSV and note compaction (`AI_CLIENT_DATA_EXTRACTION_{IMAGE,PDF,CSV,NOTE_COMPACTION}_REASONING_EFFORT=high`), default models, and `AI_CLIENT_DATA_EXTRACTION_NOTE_COMPACTION_REQUEST_TIMEOUT="600 seconds"` for that run only (user decision; nothing showed it was needed). 10 of 10 answered in about 10 minutes. The raw answers are kept outside the repository and are hand-corrected in batches 5a–5d, three goldens or fewer per batch, each with a correction log (JSON path, before, after, rule broken) for the user's review.
- **Hand-correction rulings (user decisions, 5a; apply to every batch):**
  - A missing Greek accent (`Ακτινιδιο`) or a transliterated spelling (`COSTA RIKA`) does not "look misspelled": no "Name may be misspelled in the source" note. The note is kept for clear spelling mistakes such as `Πάρκαπα`.
  - For characters that look identical (Greek and Latin `X`/`Χ`, `I`/`Ι`, `L`/`Λ`, straight and curly apostrophes), the golden follows the source where there is evidence, such as the PDF text layer, and otherwise keeps the AI's choice.
  - A value cut by a cell border keeps its visible characters and gets "Name partly unclear, please check", including a last letter only partly visible (`PINEAPPLE 8τεμ COSTA RIKA`).
  - (5b) A column mapped to a catalogue field is never also an attribute, even when its value cannot be read: the `Cost €` column is the price, so `TBD` gives `price: null` with one variant note and no `Cost €` attribute.
  - (5b) A sale basis in packaging form keeps its packaging role: `sold per bundle of 10` is `1 Pack` with `perUnit` `10 Piece`, not `10 Piece`.
  - (5b) Goldens of the same rows in different file kinds (`spreadsheet-1/-2/-3`) are aligned only where one breaks a rule; differently worded notes stay as each run wrote them.
  - (5c) A `Size` column value stays an attribute and is never also the selling unit's `unitSize` (same interpretation across the file): `150 cm` canes, `20 m` hoses and `10 l` cans keep `Size` only. `unitSize` comes from the sale basis itself, such as `Olive oil … 500 ml bottle`.
  - (Customer Book G3–G9) Each address value comes only from its own field: a city left empty is not filled from another column, such as State/Region (`spreadsheet-4`, 50 addresses in 33 candidates); addresses that become identical then merge as the prompt's merge rule says, and a merged address whose source rows show it serving both shipping and billing is `ShippingAndBilling`. An individual's tax number is left out with the rule's note `Tax number shown but not kept`, whatever its readability. A screenshot's printed window title is a left-out `title` line. Every partial address carries a note on its missing field. A unit fused into the street number without a unit word (`200-1188 West Pender St`) stays in `addressLine1`. A second street printed in a "Street Line 2" cell joins the first in `addressLine1`. Phones keep the AI's best-effort split when the printed first group is not a real country code, digits unchanged.
  - (Customer Book G2) A detail kind that appears only in an entry listed in the unidentified rows gets no "… were not used" note, because that note would be about the unidentified entry. A tagline on a customer's own business card is neither the uploader's letterhead nor a footer, so it keeps own words (`slogan`).
  - (5c) The notes compaction golden keeps the shortening answer's top-level notes unless one fails the extraction note rules or the shortening rules; its items, counts and rows follow `spreadsheet-1.json`'s rulings, and amounts may differ only in trailing zeros (`189` and `189.00` compare equal).
- `image-2.json`: the row count was settled against the picture in 5a. It has 125 priced rows (29 + 45 + 51) and no repeated cropped strip: the top of the image is blank padding. Epic known gap 1, which described about 124 rows and a cropped strip, was based on a first look and is corrected by PO.

## Run order and failures

**Test style (user decision 2026-11-23, [Scala](../standards/scala.md#for-comprehension-style) direction; Customer Book built in C2, Catalogue in C2b):** both golden specs write every case as `"..." in withContext { context => import context.*; for { ... } yield () }`, with a spec-private `Context` (the AI client and `SpreadsheetTool`) built from layers in `withContext` and run through `.zioValue`. Inside the `for`, `_ = assume(…)` comes first, then the golden load and the AI call (`<-`), then `_ = compareWithGolden(…)`; `assume`, the missing-golden `fail` and the comparison `fail` surface unchanged through `.zioValue` (a no-key run cancels every case). Customer Book's `withContext(f)` always uses the default batch size; Catalogue's is `withContext(csvBatchMaxDataRowsOpt: Option[Int] = None)(f)`: every case calls `withContext()` (the production default batch size) except the notes compaction case, `withContext(csvBatchMaxDataRowsOpt = Some(CsvBatchMaxDataRowsCompaction))` (15).

For each case:

1. `assume(apiKey.nonEmpty, …)`. The key comes from `AI_CLIENT_DATA_EXTRACTION_API_KEY`, and the in-code fallback is `""`. No key cancels the case.
2. The golden is resolved and decoded **before** the AI call. A missing file fails naming its full path. A decoding error fails with the decoder message.
3. The AI call runs live (see [What is called](#what-is-called-no-service)), followed, in the notes compaction case only, by the join and the note-shortening call. An AI error or timeout in any of them fails through `.zioValue`.
4. The golden is compared with the answer ([comparison](#comparison)), and the case prints its Stored and Current blocks ([stored patches](#stored-patches)). A difference never fails the case.

## Model selection and config

The spec loads `AIClientDataExtractionConfig.live`, so production defaults and their `${?ENV}` overrides choose the model and effort per file kind: `AI_CLIENT_DATA_EXTRACTION_{IMAGE,PDF,CSV,NOTE_COMPACTION}_{MODEL,REASONING_EFFORT}`.

**Both specs (user decision 2026-11-23; Customer Book built in C2, Catalogue in C2b):** each overrides only these, every field set explicitly per [Scala](../standards/scala.md):

- `scheme`, `host`, `port`;
- `apiKey`;
- `requestTimeout = 600 s` for every case;
- Catalogue's notes compaction case only: `csvBatchMaxDataRows = 15`.

Retries, retry delay, batch size and batch parallelism come from the production defaults and their environment variables: `send-max-retries = 2` (`AI_CLIENT_DATA_EXTRACTION_SEND_MAX_RETRIES`), `send-retry-delay = 1 second` (`…_SEND_RETRY_DELAY`), `csv-batch-max-data-rows = 100` (`…_CSV_BATCH_MAX_DATA_ROWS`) and `csv-batch-parallelism = 4` (`…_CSV_BATCH_PARALLELISM`). With the defaults, Customer Book's `spreadsheet-4.csv` reads in four groups (100/100/100/47) and every other spreadsheet in one.

Compaction keeps the production `note-compaction-request-timeout`. The model name is not printed.

## Comparison

**Diffson round (user decisions 2026-11-23, built 2026-11-23).** Comparison uses plain diffson (`org.gnieh %% diffson-circe` 4.7.1, `Dependencies.diffsonCirce`, in `test-kit`, with `circe-core` and `circe-parser` pinned there to the repository's `circeV`); difflicious was removed from the build. The user chose the library's own list comparison (option 3) over a keyed rewrite that kept the earlier pairing rules, so **the pairing rules of the difflicious comparison no longer apply** (this reverses the earlier Q3 answer). The goldens are not changed and not re-run (Q4).

Every field of the raw answer is compared:

- the counts `entriesIdentified` and `entriesProcessed`;
- `emptyEntryRows` and `unidentifiedEntryRows`;
- the top-level `extractionNotes`;
- per item: `name`, `description`, `variants` and its own `extractionNotes`;
- per variant: `sku`, `label`, `description`, `attributes`, `price` (`amount`, `currency`, `currencyInferred`), `sellingUnit` (`quantity`, `perUnit`, `unitSize`), `sellingUnitInferred` and its own `extractionNotes`.

Not compared, because they are not part of the AI answer: the service's info-line text, duplicate and missing marks, `needsAttention`, and the merge of variant notes into item notes.

How the spec compares one case (spec-private code in `golden/CatalogueFileAiExtractGoldenSpec`):

1. **Encode both sides the same way.** The golden is decoded with the production `json.ai.catalogueFileAIExtractCodec` before the AI call, as before. The golden and the answer are then encoded with the spec-private `catalogueFileAIExtractCodecFieldComplete` (`JsonCodecMaker.make` with `transientNone`, `transientEmpty` and `transientDefault` all `false`, passed with `using`; the four enum codecs are imported from `json.tapir`, the shared selling-unit codec is not, so `perUnit` and `unitSize` are written too) and parsed with `io.circe.parser.parse`. Absent and `null` count as the same (user decision Q2): both sides are written in full, so a field empty on one side and filled on the other is one `replace` with `"old":null` or `"value":null`.
2. **Diff the golden into the answer** in `compareWithGolden`: `diff(golden JSON, answer JSON)` with `diffson.jsonpatch.lcsdiff.remembering` and a spec member `given Lcs[Json] = new Patience[Json]` (user decision Q1: the list describes what the AI did).

**Rules (user decisions 2026-11-23):**

- **Objects** (an item, a variant, a price, a selling unit) are compared field by field; field order never matters.
- **Lists** (items, variants, attributes, every notes list, both row lists) are compared by position. diffson first matches the longest run of elements that are **entirely** equal, in order; elements left over at the same position are then compared field by field, and the rest are `add` or `remove`. **Order counts in every list** (Q1): reordered notes or rows are differences.
- **There is no pairing by product codes, name, label or attribute name.** A misread code or a near-alike name shows as field changes on the item at that position, not as missing plus extra.
- **Cascade (accepted, epic known gap):** a skipped or extra item is one `remove` or `add` only when its neighbours are entirely equal to the golden. When they also differ (for example only in `sellingUnitInferred`), every later item is compared with the wrong golden item and prints as field changes.
- **Values:** text is exact string equality. Numbers use circe's number equality and hashing, which ignore trailing zeros, so `8.40` equals `8.4` (epic scenario 10). There is no closeness and no 70% rule.

## Difference output

**Built 2026-11-23 (user decisions 2026-11-23; closed epic known gap 4).** It replaced difflicious-scalatest's `assertNoDiff`, which printed a `Test id: <ULID>` line and then the whole answer, with one-sided parts told apart only by colour.

- Every case prints its patch and passes; a difference never fails it ([stored patches](#stored-patches), since 2026-11-24; until then any difference failed the case through `fail`). An empty patch prints as an empty array.
- The patch is **only** `jsonPatch.asJson.spaces2`: diffson-circe's JSON Patch (RFC 6902) as one indented JSON array, exactly the library's plain output, readable without colour. There is no test id line (user decision).
  - **changed value:** `{"op" : "replace", "path" : …, "value" : <AI answer>, "old" : <golden>}`;
  - **missing** (only in the golden, the AI left it out): `{"op" : "remove", "path" : …, "old" : <the whole part>}`;
  - **extra** (only in the AI's answer): `{"op" : "add", "path" : …, "value" : <the whole part>}`.
- **Paths** are JSON Pointers by position, for example `/candidates/16/variants/0/sellingUnit/quantity/value`; an element added at the end of a list is `/…/-`.
- A part on one side only is printed in full, spread over lines. The printed differences name neither the source nor the model; the source file appears only in the case name ScalaTest prints, and the model name is never printed. No report file is written.
- **Proof (user decision Q2, done 2026-11-23):** one temporary test, run once without a key and then reverted, built answers from the committed goldens and checked the printed patch. It failed against a stub without the failure and passed with the comparison; its diff and logs are kept outside the repository. Observed operations:
  - golden against itself, and `8.40` against `8.4`: no difference;
  - price `8.1` for `8.4`: `replace /candidates/0/variants/0/price/amount`, `"value" : 8.1`, `"old" : 8.4`;
  - a description the golden lacks: `replace /candidates/8/description` with `"old" : null`;
  - one reworded note: a single positional `replace /extractionNotes/6`;
  - two notes swapped: `add /extractionNotes/0` plus `remove /extractionNotes/2`;
  - a skipped item between items equal to the golden: `remove /candidates/8` (the whole item);
  - a skipped item whose later items also differ (`sellingUnitInferred`): three item pairs show as `name` and flag `replace`s, then `remove /candidates/11` (the cascade);
  - an extra item at the end: `add /candidates/-`;
  - `perUnit` absent in the answer: `replace …/perUnit` with `"value" : null` and the golden object as `"old"`.

## Customer Book check

**Agreed 2026-11-23, built 2026-11-24** (issue 607, user decisions Q1–Q4 of the Customer Book round; product truth: epic requirements 1 and 3–6). **Built:** the asset move (slice C1) and `golden/CustomerBookFileAiExtractGoldenSpec` with all 23 cases (slice C2; a no-key run cancels 23). **Goldens:** all 23 are hand-corrected from the notes-round capture (N4, 2026-11-24), in batches G1 (`image-1`, `image-1-omit-phone-numbers`, `image-2`), G2 (`image-3`, `image-4`, `image-5`) and G3–G9 (the other 17, one pass at the user's request; `spreadsheet-4.json` checked against the CSV with a throwaway script). Not run yet: the check at default effort against them. **Notes round (user decision 2026-11-23, built):** the reading and shortening prompts changed their general notes (fixed order, one "left out" wording); the capture taken in C3 (before that change) is not used. It was replaced by one new high-effort capture of all 23 cases after the prompt change (N4, 2026-11-24, user's go-ahead: 779 s, 600 s shortening override, run-setting variables unset; `spreadsheet-4` read in 4 groups, shortening 72 notes to 22), and hand-correction starts from that. Customer Book gets its own check built exactly like Catalogue's. Everything in [Golden files](#golden-files), [Run order and failures](#run-order-and-failures), [Model selection and config](#model-selection-and-config), [Comparison](#comparison) and [Difference output](#difference-output) applies unless stated here.

- **Spec (user decision Q1; built in C2):** a separate `golden/CustomerBookFileAiExtractGoldenSpec` with its own copy of the plumbing (AI client built in `withContext` with only the `scheme`, `host`, `port`, `apiKey` and 600-second `requestTimeout` overrides, retries, delay, batch size and parallelism from the production defaults (user decision 2026-11-23); `SpreadsheetTool`, golden loading, field-complete codec, `lcsdiff` comparison). The Catalogue spec is not changed and nothing is shared.
- **Assets (user decision Q2; moved 2026-11-23, slice C1):** the 22 kept files were moved byte for byte (sha256 checked one to one) from `assets-test/CustomerBookFileAiExtract{FromImage,FromPdf,FromSpreadsheet,UnrelatedContent}GoldenSpec/` into `assets-test/CustomerBookFileAiExtractGoldenSpec/`, and the four old folders were removed. Clashing unrelated names were renumbered: `image-1.jpeg` → `image-11.jpeg`, `image-2.webp` → `image-12.webp`, `image-3.png` → `image-13.png`, `image-4.jpeg` → `image-14.jpeg`, `pdf-1.pdf` → `pdf-3.pdf`, `spreadsheet-1.csv` → `spreadsheet-5.csv`. `spreadsheet-4.csv` is cleared for use by the user (Q3 of the assessment).
- **Goldens:** `assets-golden/CustomerBookFileAiExtractGoldenSpec/<asset base name>[-<variant>].json`, in the raw `CustomerBookFileAIExtract` shape. The deleted old goldens (public-response shape) are not reused.
- **Cases (23, one explicit case each, no loop):**

  | Case | Asset | Declared name | Processing message | Golden |
  |---|---|---|---|---|
  | Contact-list pictures | `image-1.png` … `image-10.png` (PNG) | — | none | `image-<n>.json` |
  | Picture 1 again | `image-1.png` | — | `omit phone numbers, keep everything else` (verbatim, the old check's text from b19e476; its last version, 2feb0f3, had dropped the message) | `image-1-omit-phone-numbers.json` |
  | Typed PDF | `pdf-1.pdf` (25 numbered entries over two pages) | `pdf-1.pdf` | none | `pdf-1.json` |
  | Scanned handwritten PDF | `pdf-2.pdf` | `pdf-2.pdf` | none | `pdf-2.json` |
  | Spreadsheets, one group | `spreadsheet-1.csv`, `-2.xls`, `-3.xlsx` | — | none | `spreadsheet-<n>.json` |
  | Shortening case | `spreadsheet-4.csv` (header plus 347 data rows, about 70 columns; four groups of 100/100/100/47 at the normal `csvBatchMaxDataRows = 100`) | — | none | `spreadsheet-4.json` |
  | Unrelated pictures, PDF, spreadsheet | `image-11.jpeg`, `image-12.webp`, `image-13.png`, `image-14.jpeg`, `pdf-3.pdf`, `spreadsheet-5.csv` | `pdf-3.pdf` | none | `image-11.json` … `image-14.json`, `pdf-3.json`, `spreadsheet-5.json` |

- **Calls:** `extractFromImage[CustomerBookFileAIExtract](FileScannedPath(<asset path>), SupportedMediaType.PNG/JPEG/WEBP, AIInstructions.extractCustomersFromImageInstructions, <message>)`; `extractFromPdf[CustomerBookFileAIExtract](…, <declared name>, AIInstructions.extractCustomersFromPdfInstructions, None)`; spreadsheets `convertToCsv`/`convertExcelToCsv` inline per case, then `ZIO.scoped(extractFromCsv[CustomerBookFileAIExtract](…, AIInstructions.extractCustomersFromFileInstructions, None)).zioValue`. Single-group cases take `.head`. `CustomerBookFileService` is not changed or called.
- **Shortening case (user decision Q2 of the epic round):** a spec-private `mergeCustomerBookFileAIExtracts`, copied verbatim from `CustomerBookFileServiceImpl` (sums both counts, concatenates both candidate lists, both row-index lists and the notes in group order), then always `noteCompaction(<joined notes>, AIInstructions.compactCustomerBookFileNotesInstructions)`, whose notes replace the top-level notes as is. No skip rule, acceptance rule or fallback: a failed shortening call fails the case.
- **Encoding:** `customerBookFileAIExtractCodecFieldComplete`, a spec-private `JsonCodecMaker.make[CustomerBookFileAIExtract](CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false))`, passed with `using`. Imports by name: `addressTypeCodec` from `json.tapir` (the only enum) and `iron.jsoniter.given`; never a `json.tapir.*` wildcard (it broke `-Werror` in the Catalogue spec, e144411). A temporary check in C2 confirmed every field is written (`null`/`[]`) and `addressType` as its plain name, such as `"Billing"`. The golden is decoded with the production `json.ai.customerBookFileAIExtractCodec` before the AI call.
- **Compared (every field of the raw answer):** `entriesIdentified`, `entriesProcessed`, `emptyEntryRowIndexes`, `unidentifiedEntryRowIndexes`, top-level `extractionNotes`; per individual `fullName`, `emails`, `phoneNumbers`, `addresses`, `fullNameInferred`, notes; per business `businessName`, `emails`, `taxID`, `phoneNumbers`, `addresses`, `customerBusinessContacts`, `businessNameInferred`, notes; per contact `fullName`, `role`, `email`, `phoneNumber`, `fullNameInferred`, notes; per phone `phoneNationalNumber`, `phoneCountryCode`, `phoneCountryCodeInferred`; per address entry the address (`addressLine1`, `addressLine2`, `city`, `postalCode`, `country`, notes) and `addressType`. Every list is compared by position. **Not compared** (the product's rules after the AI answer, covered by `fun/CustomerBookFileServiceSpec`): duplicate and missing marks, `needsAttention`, default email and phone, the two-address cap with its retyping and left-out notes, and the info lines.
- **Goldens are made like Catalogue's:** one high-effort live capture of all 23 cases (temporary code, reverted, only with the user's go-ahead), then hand-correction in batches of at most three goldens (`spreadsheet-4.json` alone), each with a correction log and a decode and shape check. They are corrected against [Customer Book](../features/customer-book.md) extraction rules, the [AI extraction](../standards/ai-extraction.md) standard and epic 05 step 8, with the three Catalogue rulings (user decision Q3): look-alike characters follow the source where there is evidence, otherwise the AI's choice; a blurred character keeps the human reading; a missing accent or a Greek-style spelling of a foreign word gets no misspelling note. `spreadsheet-4.json` (347 entries) is checked mechanically against the CSV cells with a throwaway script, and only judgement items go to the user.

## Stored patches

**Agreed and built 2026-11-24 (slice P1); first stored patches written 2026-11-24 (slice P2)** (issue 607, user decisions; product truth: epic 07 requirements 7 and 8, non-functional requirement 2, scenarios 16–19, gap 9). Every one of the 33 cases now has a stored patch, so a run prints Stored then Current and writes nothing. EM technical decisions, recorded here:

- **Never fail on a difference.** `compareWithGolden(goldenFileName, answer, golden)` computes the patch as today and prints, instead of failing. The other failures are unchanged: no key cancels, a missing or unreadable golden fails before the AI call, an AI error or timeout, an unreadable answer or a failed shortening call fails through `.zioValue`.
- **Location and name:** `backend/gateway/core/src/test/resources/assets-diffson/<SpecClassName>/<golden base name>.json`, so `image-1.json` has `assets-diffson/CatalogueFileAiExtractGoldenSpec/image-1.json` and `image-1-omit-phone-numbers.json` has `assets-diffson/CustomerBookFileAiExtractGoldenSpec/image-1-omit-phone-numbers.json`. It is resolved through `Paths.get("src/test/resources/assets-diffson/<SpecClassName>")`, like the goldens, so a write lands in the source tree for the user to review and commit. It follows the [one-folder-per-spec](acceptance-testing.md#asset-files) asset rule.
- **Format:** the exact text printed as the Current block, `jsonPatch.asJson.spaces2` (the golden-to-answer patch from diffson-circe), UTF-8, no trailing newline. It is read back as text and printed verbatim, never parsed or re-encoded.
- **Printing** (ScalaTest `info`, one block each): with a stored patch, `Stored:` and the file's text, then `Current:` and the current patch; nothing is written. With no stored patch at default settings, `Current:` and the patch, then `Stored patch written: <path>`. With no stored patch at other settings, `Current:` and the patch, then `Nothing stored: the settings are not the product defaults`. No score or count.
- **Default settings** (decided by EM): the run's environment holds no `AI_CLIENT_DATA_EXTRACTION_*` variable other than `AI_CLIENT_DATA_EXTRACTION_API_KEY`. Any model, reasoning-effort, timeout, retry or batch variable, including the capture-only `…_NOTE_COMPACTION_REQUEST_TIMEOUT`, makes the run non-default. Each spec computes it once as a spec-private `isProductDefaultSettings`. A case's own fixed setting (Catalogue's compaction batch size of 15) is part of the case, not a deviation. System-property config overrides (`-D…`) are not detected; do not use them for these runs.
- **Never overwrite:** the write uses `Files.writeString(path, text, StandardOpenOption.CREATE_NEW)` after `Files.createDirectories`, so an existing file is never replaced, even by two runs at once. To refresh a stored patch, delete the file and run again at default settings.
- **Kept away from goldens:** writes go only to `assets-diffson/`; nothing in either spec writes to `assets-golden/`. A case writes only after its AI call (and shortening) succeeded, so a cancelled or failed case writes nothing.
- **Golden edits:** editing a golden means deleting its stored patch and rewriting it from a default-settings run in the same change.
- **Code shape:** each spec keeps its own copy (user decision Q1 of the Customer Book round): `diffsonDirectory`, `isProductDefaultSettings`, and `compareWithGolden` taking the golden file name, which every case passes.
- **First stored patches (written 2026-11-24, slice P2, user's go-ahead):** one live run of both specs at full default settings (only the key set; note shortening at the production 30-second limit) answered 33 of 33 cases and wrote 33 files under `assets-diffson/`, each byte-equal to its printed Current block; a no-key rerun cancelled all 33 and wrote nothing. Operations per stored patch: Catalogue `image-1` 8, `image-2` 162, `image-3` 1, `pdf-1` 8, `pdf-2` 1, `spreadsheet-1` 9, `-2` 8, `-3` 9, notes compaction 7, `spreadsheet-4` 1; Customer Book 13 cases empty, `image-2` 6, `image-3` 4, `image-4` 4, `image-5` 3, `image-6` 2, `image-7` 2, `image-8` 1, `image-10` 1, `pdf-2` 5, `spreadsheet-4` 356. The two large baselines are model behaviour against agreed golden rulings, not check defects: Catalogue `image-2`'s 162 include 102 `sellingUnitInferred` values (the model still treats the `KG` column header as each row's own unit), plus misread names and codes and note wording; Customer Book `spreadsheet-4`'s 356 are mostly address and candidate note wording (177 + 20 + 3), 56 cities still filled from State/Region (ruling J1), 34 extra addresses from the different merges that follow, and 46 best-effort phone splits. A degradation in those two cases is harder to see by eye.
- **Proof without the AI (done in P1):** a temporary, reverted test drove `compareWithGolden` with a golden and a changed copy, under a temporary golden name: missing at default settings writes the file; present prints Stored and Current and leaves the file byte-identical; missing with a model variable set (`AI_CLIENT_DATA_EXTRACTION_IMAGE_REASONING_EFFORT=high` on the command line) writes nothing; an existing file is never replaced. The temporary files are deleted afterwards.

## Open gaps

- **The check's spreadsheet join and shortening are a copy (user decision, slice 3, option B; epic known gap 5, accepted for now).** The check copies only the merge; it has no skip rule, acceptance rule or fallback. A later change to the service's merge or compaction ([Catalogue step 3](../features/catalogue.md#cataloguefileserviceaiextract)) is not followed by the check; update the spec's copy by hand in the same PR. The service's acceptance rule and fallback are proved only by `fun/CatalogueFileServiceSpec`, never by the live check.
- **Comparison rules have no permanent AI-free proof.** The user removed the scorer's unit specs with nothing replacing them, in line with "no tests for test-only code". The diffson round proved its output once with a temporary, reverted test (user decision Q2, [Difference output](#difference-output)); a later change to the encoding or the diff needs the same one-off proof.
- **Order counts and changes cascade (user decisions, diffson round; epic known gap).** Every list is compared by position, with no pairing by codes or names. A reordered note is a difference, and a skipped or extra item among items that also differ shifts every later item into field changes.
- **Note wording fails the check** (epic known gap 3, accepted).
- **Run settings can be changed from the environment (user decision 2026-11-23, accepted).** Because neither golden spec pins retries, retry delay, batch size or parallelism, an exported `AI_CLIENT_DATA_EXTRACTION_SEND_MAX_RETRIES`, `…_SEND_RETRY_DELAY`, `…_CSV_BATCH_MAX_DATA_ROWS` or `…_CSV_BATCH_PARALLELISM` changes the check. A different batch size changes how spreadsheets are grouped: a single-group case that takes `.head` would then drop later groups and fail on missing entries, and Customer Book's `spreadsheet-4.csv` would be shortened from different groups. Run the check with these variables unset.
- **Look-alike characters fail the check invisibly** (epic known gap 6, accepted). A later run that picks the other of two identical-looking characters fails, and both sides of the difference print the same.
- **Dependency compatibility (checked 2026-11-23).** difflicious and its `hearth` 0.4.2 left the build. `diffson-circe` 4.7.1 (built with Scala 3.3.8 LTS) brings `diffson-core`, circe 0.14.16 (resolved to the pinned 0.14.17), cats-core 2.13.0 (the repository's version), `scalac-compat-annotation` and `scala-collection-compat`, and no `hearth`. `show gateway-core/evicted` and `show gateway-it/evicted` select circe 0.14.17 and cats 2.13.0, with nothing new suspected binary-incompatible, and `Test/compile` passes for every module. Recheck `evicted` when diffson, circe or cats is upgraded.

## First run at defaults (2026-11-23)

One live run of all 10 cases with production models and effort and no overrides (issue 607, slice 6, user go-ahead): 7 min 16 s in total, no AI error or timeout, `image-2.jpeg` well within the 600-second deadline. **All 10 cases failed on differences.** No golden was changed by the run; a golden change needs the user's approval and its own slice. The differences below were reported by the difflicious comparison then in use, which paired items by codes or name and ignored list order; under today's position-based diffson comparison the same answers would print as different operations (for example a misread code as field changes at that position, not as a missing plus an extra item).

| Case | Differences (answer vs golden) |
|---|---|
| `image-1` | 2 names (`Πάραπα` for `Πάρκαπα`, `Ακτινίδιο` with an accent for the printed `Ακτινιδιο`); notes (7 answer, 7 golden, worded differently) |
| `image-2` | 111 changed values: 94 `sellingUnitInferred` false where the golden has true (the `KG` column header read as each row's own unit), 16 names (misreads, `X`/`Χ` look-alikes, `IL` and `8τεμ` dropped, a missing space), 1 row shifted under `17103`; 11 items on each side from misread codes; notes (52 answer, 65 golden) |
| `pdf-1` | notes only, including one missing misspelling note |
| `spreadsheet-1` | counts (identified 24 for 26, processed 18 for 19); Bamboo canes selling unit missing (golden `1 Pack` per `10 Piece`) and its inferred flag |
| `spreadsheet-2`, `-3` | row 28 reported in a left-out note instead of `unidentifiedEntryRows`, and the counts |
| `spreadsheet-1-notes-compaction` | counts |
| `image-3`, `pdf-2`, `spreadsheet-4` | note wording only |

- In every spreadsheet case the answer's own counts do not add up (`entriesIdentified` is not processed + empty + unidentified), a prompt rule the default-effort model broke.
- Three `image-2` codes have one blurred digit, and both the high-effort capture and this run read them differently from the golden: `46103` (COCONUTS) read `46163`, `71416` (SANTA MARIA 70/75 1L) read `71415`, `78208` (MOOLY) read `78200`.
- No case showed a clear "answer right, golden wrong".
- Evidence: the colour log, a plain copy and the parsed differences of the slice-6 run were kept in local temporary folders only, not in the repository.

## Running

```sh
sbt shutdown   # a running sbt 2 server keeps its own environment, so stop it first
AI_CLIENT_DATA_EXTRACTION_API_KEY=… [AI_CLIENT_DATA_EXTRACTION_IMAGE_MODEL=… AI_CLIENT_DATA_EXTRACTION_IMAGE_REASONING_EFFORT=…] \
  sbt --server "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec"
AI_CLIENT_DATA_EXTRACTION_API_KEY=… sbt --server "gateway-core/testOnly io.mesazon.gateway.golden.CustomerBookFileAiExtractGoldenSpec"
AI_CLIENT_DATA_EXTRACTION_API_KEY=… sbt --server "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec -- -z \"spreadsheet-2.xls\""   # one case by name substring (spreadsheet-1.csv names two cases)
sbt shutdown   # do not leave a server holding a real key
```

Use `gateway-core/testOnly`, not `Test/runMain`, because the forked working directory must be the module base directory for the relative paths. Use the standalone `sbt --server` form after `sbt shutdown`. Through the sbt 2 thin client, the forked test JVM sees the running server's environment, not the key on the command line ([known issue](../known-issues.md#manual-ai-golden-check-ignores-the-api-key-given-on-the-command-line)).

Leave `AI_CLIENT_DATA_EXTRACTION_SEND_MAX_RETRIES`, `…_SEND_RETRY_DELAY`, `…_CSV_BATCH_MAX_DATA_ROWS` and `…_CSV_BATCH_PARALLELISM` unset, so the check runs on the production defaults ([Model selection and config](#model-selection-and-config)).

Live runs happen only with the user's explicit go-ahead for each run. Never commit a credential.
