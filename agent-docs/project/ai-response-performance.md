# AI response performance

> **Status (issue 607, rework agreed 2026-11-22, not built yet).** Product truth: [AI Response Performance](../../pages/epics/07-ai-response-performance.md). Everything below describes the agreed design. Until the rework slices land, the repository still holds the old scored checks: the `golden/performance/` scorer, its `unit/golden/performance/` specs, four Catalogue extraction specs, two notes compaction specs and four Customer Book specs. Open gaps: see [Open gaps](#open-gaps).

How the hand-run AI golden check compares a live model's raw answer with a hand-corrected golden answer. Any difference fails the case.

## Scope

- **One spec, Catalogue only:** `golden/CatalogueFileAiExtractGoldenSpec`. Each source gets one explicit case, with no list or loop. These are the cases (the epic fixes the list, requirement 3):

  | Case | Asset | Declared name | Processing message | Model | Golden |
  |---|---|---|---|---|---|
  | Price-list picture 1 | `image-1.jpeg` (JPEG) | — | today's text, verbatim: `ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is` | `imageModel` | `image-1.json` |
  | Price-list picture 2 | `image-2.jpeg` | — | none | `imageModel` | `image-2.json` |
  | PDF price list | `pdf-1.pdf` | `Catalogue-Price-List.PDF` | none | `pdfModel` | `pdf-1.json` |
  | Spreadsheets | `spreadsheet-1.csv`, `-2.xls`, `-3.xlsx` | — | none | `csvModel` | `spreadsheet-<n>.json` |
  | Notes compaction | `spreadsheet-1.csv`, `csvBatchMaxDataRows = 15` | — | none | `csvModel`, then `noteCompactionModel` | `spreadsheet-1-notes-compaction.json` |
  | Unrelated picture, PDF, spreadsheet | `image-3.jpeg`, `pdf-2.pdf`, `spreadsheet-4.csv` | `pdf-2.pdf` | none | the model of its file kind | `image-3.json`, `pdf-2.json`, `spreadsheet-4.json` |

- **Assets** move, byte for byte, from today's four Catalogue golden asset folders into `assets-test/CatalogueFileAiExtractGoldenSpec/`. The unrelated files are renumbered because their names clash with the price-list files: today's unrelated `image-1.jpeg` becomes `image-3.jpeg`, `pdf-1.pdf` becomes `pdf-2.pdf`, and `spreadsheet-1.csv` becomes `spreadsheet-4.csv`.
- **Removed (user decision):**
  - the scorer `golden/performance/` and its unit specs `unit/golden/performance/`, with nothing replacing them;
  - the four old Catalogue extraction specs;
  - both notes compaction specs (`CatalogueFileNotesCompactionGoldenSpec`, `CustomerBookFileNotesCompactionGoldenSpec`);
  - the four Customer Book golden specs;
  - every golden JSON those specs loaded.

  The Customer Book source assets stay, see [Open gaps](#open-gaps).
- **Not built:** a score or percentage, run history, saved reports, multi-model runs, and Customer Book golden checks (to be rebuilt later the same way).

## What is called (no service)

The check calls the AI integration point directly, with the production prompts. It does not call `CatalogueFileService.aiExtract`. The service's deterministic steps after the AI answer are already covered by `fun/CatalogueFileServiceSpec` and are not repeated: duplicates, field marks, attention, info lines and the Chimney mapping.

| Source | Call |
|---|---|
| Image | `AIClientDataExtraction.extractFromImage[CatalogueFileAIExtract](FileScannedPath(<asset path>), SupportedMediaType.JPEG, AIInstructions.extractCatalogueItemsFromImageInstructions, <message>)` |
| PDF | `extractFromPdf[CatalogueFileAIExtract](FileScannedPath(<asset path>), <declared name>, AIInstructions.extractCatalogueItemsFromPdfInstructions, None)` |
| Spreadsheet | `SpreadsheetTool.convertToCsv` (CSV) or `convertExcelToCsv` (XLS/XLSX), then `extractFromCsv[CatalogueFileAIExtract](…, AIInstructions.extractCatalogueItemsFromFileInstructions, None)`, then the shared `CatalogueFileService.mergeAndCompactCatalogueFileAIExtracts` |

- The asset path is the source-tree path `src/test/resources/assets-test/CatalogueFileAiExtractGoldenSpec/<asset>`, relative to the forked test working directory, which is the module base directory. No `FileScanner` is used: the media type is given explicitly.
- **Shared merge and compaction (user decision, Q2):** the spreadsheet step after `extractFromCsv` moves out of `CatalogueFileServiceImpl` into the companion as `private[gateway] def mergeAndCompactCatalogueFileAIExtracts(aiClientDataExtraction, catalogueFileAIExtracts): UIO[CatalogueFileAIExtract]`. The service and the check both call it, and its behaviour is unchanged:
  - the pure merge;
  - compaction only with at least two groups and non-empty merged notes;
  - compacted notes accepted only when they are non-empty and no more than were sent;
  - otherwise, or on any error or defect, a warning and the raw notes.

  So a failed compaction does not fail the case as an AI error; it shows up as notes differences. With the default batch size each of the three spreadsheets is one group, so the merge is a no-op and no compaction runs.
- **Notes compaction case:** `spreadsheet-1.csv` has a header and 30 data rows (file rows 2–31). With `csvBatchMaxDataRows = 15` it is read in exactly two groups, rows 2–16 and rows 17–31. The split falls on the repeated `Product` header (row 17), so no item straddles groups. Each group produces general notes, so compaction runs.

## Golden files

- **Location:** `backend/gateway/core/src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec/<asset base name>[-<variant>].json`, resolved through `Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec")`.
- **Shape:** the raw `CatalogueFileAIExtract` (the "AI answer", epic requirement 1), with a 2-space indent and no trailing newline. It is decoded with the production `json.ai.catalogueFileAIExtractCodec`. Absent `Option`s and empty lists may be omitted or written as `null`/`[]`; both decode the same.
- **Never written by the check.** Committed code has no recording path.
- **Bootstrap procedure** (issue 607, user decision):
  1. One live run per source at high reasoning effort, only with the user's go-ahead and key.
  2. Temporary code, reverted afterwards, writes the answer with every field shown (`null`/`[]`).
  3. Hand-correct the answer against the asset and the [AI extraction](../standards/ai-extraction.md) rules, notes included, changing only what breaks a rule.
  4. Decode-check it with the production codec.
- `image-2.json`: when it is hand-corrected, settle the row count against the picture (epic known gap 1).

## Run order and failures

For each case:

1. `assume(apiKey.nonEmpty, …)`. The key comes from `AI_CLIENT_DATA_EXTRACTION_API_KEY`, and the in-code fallback is `""`. No key cancels the case.
2. The golden is resolved and decoded **before** the AI call. A missing file fails naming its full path. A decoding error fails with the decoder message.
3. The AI call runs live (see [What is called](#what-is-called-no-service)). An AI error or timeout fails through `.zioValue`.
4. The answer (obtained) is compared with the golden (expected) using the [comparison](#comparison) differ. If the result is not OK, the case fails with only the [difference output](#difference-output). When it is OK, nothing is printed.

## Model selection and config

The spec loads `AIClientDataExtractionConfig.live`, so production defaults and their `${?ENV}` overrides choose the model and effort per file kind: `AI_CLIENT_DATA_EXTRACTION_{IMAGE,PDF,CSV,NOTE_COMPACTION}_{MODEL,REASONING_EFFORT}`. The spec overrides only these, every field set explicitly per [Scala](../standards/scala.md):

- `scheme`, `host`, `port`;
- `apiKey`;
- `requestTimeout = 600 s` for every case;
- `sendMaxRetries = 2`;
- `sendRetryDelay = 1 s`;
- `csvBatchMaxDataRows`: 100, or 15 for the compaction case;
- `csvBatchParallelism = 4`.

Compaction keeps the production `note-compaction-request-timeout`. The model name is not printed.

## Comparison

Comparison uses difflicious (`com.github.jatcwang %% difflicious-scalatest`, in `test-kit`). One `Differ[CatalogueFileAIExtract]` is derived in the spec, and no field is ignored. Every field of the raw answer is compared:

- the counts `entriesIdentified` and `entriesProcessed`;
- `emptyEntryRows` and `unidentifiedEntryRows`;
- the top-level `extractionNotes`;
- per item: `name`, `description`, `variants` and its own `extractionNotes`;
- per variant: `sku`, `label`, `description`, `attributes`, `price` (`amount`, `currency`, `currencyInferred`), `sellingUnit`, `sellingUnitInferred` and its own `extractionNotes`.

Not compared, because they are not part of the AI answer: the service's info-line text, duplicate and missing marks, `needsAttention`, and the merge of variant notes into item notes.

- **Values:** each Iron newtype and enum uses a `Differ.useEquals` given: `CatalogueItemName`, `CatalogueItemDescription`, `CatalogueItemVariantSku`, `…Label`, `…Description`, `…AttributeName`, `…AttributeValue`, `PriceAmount`, `Currency`, and the selling-unit value and unit types. Text is exact `String` equality. `PriceAmount` is Scala `BigDecimal`, whose equality ignores scale, so `8.40` equals `8.4` (epic scenario 10). There is no closeness and no 70% rule.
- **Pairing** (`pairBy`, user decisions Q5/Q3/Q4). difflicious pairs each answer element with the first unpaired golden element that has an equal key. Leftovers are answer-only (extra) or golden-only (missing). Order is never a difference.

  | List | Key |
  |---|---|
  | `candidates` | `Left(set of its variants' SKU strings)` when any variant has a SKU, otherwise `Right(name)`. Items with codes pair only when the SKU sets are identical, in any order. One misread code makes the item missing plus extra. Items with neither code nor name share `Right(None)` and pair with each other in order of appearance. So do items with the same name and no code |
  | `variants` | `sku`, else `label`, in order of appearance on equal keys |
  | `attributes` | `name` |
  | every notes list, `emptyEntryRows`, `unidentifiedEntryRows` | the value itself. A reworded note is one missing plus one extra |

## Difference output

User decision Q1. difflicious's own printer renders the whole structure and marks differences only by colour, and its `assertNoDiff` adds a `Test id` header, so neither is used. A test-only walker, `golden/GoldenDiff.scala` (generic over difflicious `DiffResult`, about 50 lines, no unit spec per [Scala](../standards/scala.md) "no tests for test-only code"), turns a non-OK result into one plain-text line per difference, and the case calls `fail` with those lines joined by newlines:

- **Location:** field names joined by `.`. A list element is labelled by its pairing identity: `[sku "GH-30"]`, `[name "Garden hose"]`, `[label "500 g"]`, or `[(no code, no name)]`. For example, `candidates[sku "GH-20", "GH-30"].variants[sku "GH-30"].price.amount`.
- **Changed value:** `<location>: answer <x>, golden <y>`.
- **Present on one side only** (an `Option` that is `Some` on one side, or an unpaired element): `<location>: missing from the answer`, or `<location>: extra in the answer`, followed by that part rendered in plain text (no colour codes), indented two spaces.
- Only non-OK nodes are printed. There is no header, source or model name, count or percentage. Missing and extra are stated in words, so the output reads the same without colour.

## Open gaps

- **Customer Book source assets kept without a spec (user decision Q5, deviation).** `assets-test/CustomerBookFileAiExtract{FromImage,FromPdf,FromSpreadsheet,UnrelatedContent}GoldenSpec/` stay in place for the later rebuild, although no spec reads them. This is an accepted exception to [Asset files](acceptance-testing.md#asset-files) ("one folder per spec"). The rebuilt specs must reuse these exact folder names, or move the files.
- **Comparison rules have no AI-free proof.** The pairing keys, the walker and epic scenarios 1–10 are exercised only by live runs. The user removed the scorer's unit specs with nothing replacing them, in line with "no tests for test-only code".
- **Note wording fails the check** (epic known gap 3, accepted).
- **Dependency compatibility, pending first-slice proof.** difflicious 0.7.1 depends on `hearth` 0.4.2, and chimney 2.1.1 brings `hearth` 0.4.4. If derivation fails to compile or `evicted` flags a conflict, the fallback (difflicious 0.5.1, which uses magnolia) needs the user's approval.

## Running

```sh
AI_CLIENT_DATA_EXTRACTION_API_KEY=… [AI_CLIENT_DATA_EXTRACTION_IMAGE_MODEL=… AI_CLIENT_DATA_EXTRACTION_IMAGE_REASONING_EFFORT=…] \
  sbt "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec"
sbt "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec -- -z \"spreadsheet-1.csv\""   # one case by name substring
```

Use `gateway-core/testOnly`, not `Test/runMain`, because the forked working directory must be the module base directory for the relative paths. Live runs happen only with the user's explicit go-ahead for each run. Never commit a credential.
