# AI response performance

> **Status (issue 607, rework agreed 2026-11-22, in progress).** Product truth: [AI Response Performance](../../pages/epics/07-ai-response-performance.md). Everything below describes the agreed design. **Built:** the spec with its ten cases and moved assets (slice 1), the difflicious comparison and `assertNoDiff` output (slice 2), and the spec's own spreadsheet join and note shortening for the notes compaction case, with `CatalogueFileService` unchanged (slice 3), and the [removal](#scope) of the old scored checks, their goldens and the four old Catalogue asset folders (slice 4). **Not built yet:** no golden is committed, so every case fails with "Golden file is missing" when a key is set. Open gaps: see [Open gaps](#open-gaps).

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

- **Assets** were moved, byte for byte, from the four old Catalogue golden asset folders (`assets-test/CatalogueFileAiExtract{FromImage,FromPdf,FromSpreadsheet,UnrelatedContent}GoldenSpec/`, deleted in slice 4) into `assets-test/CatalogueFileAiExtractGoldenSpec/`. The unrelated files were renumbered because their names clash with the price-list files: the old unrelated `image-1.jpeg` became `image-3.jpeg`, `pdf-1.pdf` became `pdf-2.pdf`, and `spreadsheet-1.csv` became `spreadsheet-4.csv`.
- **Removed in slice 4 (user decision):**
  - the scorer `golden/performance/` and its unit specs `unit/golden/performance/`, with nothing replacing them;
  - the four old Catalogue extraction specs;
  - both notes compaction specs (`CatalogueFileNotesCompactionGoldenSpec`, `CustomerBookFileNotesCompactionGoldenSpec`);
  - the four Customer Book golden specs;
  - every golden JSON those specs loaded; `assets-golden/` now holds only `ImageProcessingSpec/`.

  The Customer Book source assets stay, see [Open gaps](#open-gaps).
- **Not built:** a score or percentage, run history, saved reports, multi-model runs, and Customer Book golden checks (to be rebuilt later the same way).

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
3. The AI call runs live (see [What is called](#what-is-called-no-service)), followed, in the notes compaction case only, by the join and the note-shortening call. An AI error or timeout in any of them fails through `.zioValue`.
4. The answer (obtained) is compared with the golden (expected) using the [comparison](#comparison) differ. The case fails through `assertNoDiff` when anything differs ([difference output](#difference-output)). When it is OK, nothing is printed.

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

- **Values (user decision, slice 1):** one spec-private generic given, `differRefinedType[WrappedType](using RefinedType.Mirror[WrappedType], TypeName[WrappedType])`, covers every Iron newtype. It follows the `Mirror`-based pattern of `IronRefinedTypeArbitraries`, but stays inside the spec rather than in test-kit. It is `Differ.useEquals`: the value is shown quoted when its base type is `String`, and with `toString` otherwise. This covers `CatalogueItemName`, `CatalogueItemDescription`, `CatalogueItemVariantSku`, `…Label`, `…Description`, `…AttributeName`, `…AttributeValue`, `PriceAmount`, and `CatalogueItemVariant{Quantity,PerUnit,UnitSize}Value` (all `RefinedType[BigDecimal, Positive & LessEqual[99999]]`). The four Scala enums, `Currency`, `CatalogueItemVariantUnit`, `CatalogueItemVariantUnit.PerUnit` and `…UnitSize`, keep their own private `useEquals(_.toString)` givens. Customer Book's rebuild moves the generic given into a test-kit trait if it needs it. difflicious names every Iron newtype `RefinedType.T` (opaque alias). Value results print only the value, so that name never appears in the output. Text is exact `String` equality. `PriceAmount` is Scala `BigDecimal`, whose equality ignores scale, so `8.40` equals `8.4` (epic scenario 10; probe-verified in slice 1). There is no closeness and no 70% rule.
- **Pairing** (`pairBy`, user decisions Q5/Q3/Q4). difflicious pairs each answer element with the first unpaired golden element that has an equal key. Leftovers are answer-only (extra) or golden-only (missing). Order is never a difference.

  | List | Key |
  |---|---|
  | `candidates` | `Left(set of its variants' SKU strings)` when any variant has a SKU, otherwise `Right(name)`. Items with codes pair only when the SKU sets are identical, in any order. One misread code makes the item missing plus extra. Items with neither code nor name share `Right(None)` and pair with each other in order of appearance. So do items with the same name and no code |
  | `variants` | `sku`, else `label`, in order of appearance on equal keys |
  | `attributes` | `name` |
  | every notes list, `emptyEntryRows`, `unidentifiedEntryRows` | the value itself. A reworded note is one missing plus one extra |

## Difference output

User decision, slice 2, **for the time being**. This reverses the earlier choice of a differences-only walker; the walker `golden/GoldenDiff.scala` was written and then dropped before commit. `compareWithGolden` calls difflicious-scalatest's standard assertion, `catalogueFileAIExtractDiffer.assertNoDiff(catalogueFileAIExtract, catalogueFileAIExtractGolden)`: the AI answer is "obtained" and the golden is "expected". The spec mixes in `difflicious.scalatest.ScalatestDiff`.

- When nothing differs, nothing is printed and the case passes.
- On any difference the case fails with a `ScalatestDifferenceFoundException`. Its message is a `Test id: <ULID>` line followed by `DiffResultPrinter.consoleOutput` of the **whole** answer: every record, list and field, not only the differing parts.
- **Changed value:** `answer value -> golden value`, the answer side in red and the golden side in green.
- **Present on one side only** (an unpaired item, variant or note, or `Some` against `None`): the whole part, in red when only the answer has it and in green when only the golden has it. Missing and extra are told apart **only by colour**. Read the output in a colour terminal (`.sbtopts` sets `-Dsbt.color=always`). When sbt output goes to a file, or is pasted without colour, the codes are lost (observed in slice 2). A missing item or a reworded note then looks exactly like a matching part, and only changed values (`->`) stay visible.
- Optional values print as `Some(value: …)` / `None()`, and a one-sided item takes roughly 40 lines.
- No difflicious JSONL report file is written, because its ScalaTest reporter is not registered.

## Open gaps

- **Customer Book source assets kept without a spec (user decision Q5, deviation).** `assets-test/CustomerBookFileAiExtract{FromImage,FromPdf,FromSpreadsheet,UnrelatedContent}GoldenSpec/` stay in place for the later rebuild, although no spec reads them. This is an accepted exception to [Asset files](acceptance-testing.md#asset-files) ("one folder per spec"). The rebuilt specs must reuse these exact folder names, or move the files.
- **The check's spreadsheet join and shortening are a copy (user decision, slice 3, option B; epic known gap 5, accepted for now).** The check copies only the merge; it has no skip rule, acceptance rule or fallback. A later change to the service's merge or compaction ([Catalogue step 3](../features/catalogue.md#cataloguefileserviceaiextract)) is not followed by the check; update the spec's copy by hand in the same PR. The service's acceptance rule and fallback are proved only by `fun/CatalogueFileServiceSpec`, never by the live check.
- **Comparison rules have no AI-free proof.** The pairing keys and epic scenarios 1–10 are exercised only by live runs. The user removed the scorer's unit specs with nothing replacing them, in line with "no tests for test-only code".
- **Note wording fails the check** (epic known gap 3, accepted).
- **Whole-answer, colour-only output** (epic known gap 4, for the time being). A differences-only output (a walker over `DiffResult`, built and dropped in slice 2) is the open alternative.
- **Dependency compatibility: resolved in slice 1 (2026-11-22).** difflicious 0.7.1 is built on `hearth` 0.4.2, and chimney 2.1.1 brings 0.4.4. `show gateway-core/evicted` lists `hearth_3:0.4.4 is selected over 0.4.2` among the resolved conflicts only, with nothing from difflicious, fansi or hearth among the suspected binary-incompatible ones. `gateway-core/Test/compile` derives `Differ[CatalogueFileAIExtract]` (`derivedDeep`, 15 givens, 8 `configure` paths), and the spec constructs it eagerly without error in the dummy-key run. Recheck `evicted` when chimney or difflicious is upgraded.

## Running

```sh
sbt shutdown   # a running sbt 2 server keeps its own environment, so stop it first
AI_CLIENT_DATA_EXTRACTION_API_KEY=… [AI_CLIENT_DATA_EXTRACTION_IMAGE_MODEL=… AI_CLIENT_DATA_EXTRACTION_IMAGE_REASONING_EFFORT=…] \
  sbt --server "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec"
AI_CLIENT_DATA_EXTRACTION_API_KEY=… sbt --server "gateway-core/testOnly io.mesazon.gateway.golden.CatalogueFileAiExtractGoldenSpec -- -z \"spreadsheet-2.xls\""   # one case by name substring (spreadsheet-1.csv names two cases)
sbt shutdown   # do not leave a server holding a real key
```

Use `gateway-core/testOnly`, not `Test/runMain`, because the forked working directory must be the module base directory for the relative paths. Use the standalone `sbt --server` form after `sbt shutdown`. Through the sbt 2 thin client, the forked test JVM sees the running server's environment, not the key on the command line ([known issue](../known-issues.md#manual-ai-golden-check-ignores-the-api-key-given-on-the-command-line)).

Live runs happen only with the user's explicit go-ahead for each run. Never commit a credential.
