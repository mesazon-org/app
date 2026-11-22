# AI response performance

> **Status (issue 607, 2026-11-21): built and run live.** Product truth: [AI Response Performance](../../pages/epics/07-ai-response-performance.md).
> - **Built:** the shared engine `GoldenPerformance` with the round-2 report, the Catalogue, Customer Book and notes compaction scorers, their AI-free unit specs, and every Catalogue and Customer Book golden spec listed in [Scope](#scope), the two notes compaction specs included.
> - **Run live:** every adopted spec. The Customer Book PDF and spreadsheet goldens were bootstrapped on 2026-11-21 from one `gpt-6.1-sol`/high run and hand-checked; each spec then had one default run. The ten notes compaction goldens were bootstrapped the same day from one `gpt-6.1-sol`/high run of both notes specs, hand-checked against the compaction rules with no edit needed, and each spec then had one default (`gpt-6-luna`/low) run.
> - Open gaps: see [Open gaps](#open-gaps).

How a hand-run AI golden check measures how close a live model's answer comes to a hand-corrected golden answer. The check prints a score; it never fails on the score.

## Scope

- **Generic by design:** the engine (pairing, comparison, counts, percentages, item summary, note counts, report layout) is shared by every AI extraction: Catalogue and Customer Book, image, PDF and spreadsheet. An extraction plugs in through the [extraction seam](#extraction-seam), supplying only its own comparison.
- **Adopted by** (one explicit case per source file, no list or loop):
  - `golden/CatalogueFileAiExtractFromImageGoldenSpec`: `image-1.jpeg` (with its processing message) and `-2.jpeg` (without one); model `imageModel`.
  - `golden/CatalogueFileAiExtractFromPdfGoldenSpec`: `pdf-1.pdf`, declared as `Catalogue-Price-List.PDF`; model `pdfModel`.
  - `golden/CatalogueFileAiExtractFromSpreadsheetGoldenSpec`: `spreadsheet-1.csv`, `-2.xls`, `-3.xlsx`; model `csvModel`.
  - `golden/CatalogueFileAiExtractUnrelatedContentGoldenSpec`: `image-1.jpeg`, `spreadsheet-1.csv`, `pdf-1.pdf`, each with the model of its file kind.
  - `golden/CustomerBookFileAiExtractFromImageGoldenSpec`: `image-1.png` to `-10.png` with no processing message, plus a second `image-1.png` case that asks to omit phone numbers and uses the golden variant `omit-phone-numbers`; model `imageModel`.
  - `golden/CustomerBookFileAiExtractFromPdfGoldenSpec`: `pdf-1.pdf`, `-2.pdf` (declared under the same names, which reach the live AI); model `pdfModel`. No processing message.
  - `golden/CustomerBookFileAiExtractFromSpreadsheetGoldenSpec`: `spreadsheet-1.csv`, `-2.xls`, `-3.xlsx`, `-4.csv`; model `csvModel`. No processing message (the earlier surname-from-email message was dropped in the migration, user decision).
  - `golden/CustomerBookFileAiExtractUnrelatedContentGoldenSpec`: `image-1.jpeg`, `-2.webp`, `-3.png`, `-4.jpeg`, `pdf-1.pdf`, `spreadsheet-1.csv`, each with the model of its file kind.
  - `golden/CatalogueFileNotesCompactionGoldenSpec` and `golden/CustomerBookFileNotesCompactionGoldenSpec`: five explicit cases each, scored with [notes compaction scoring](#notes-compaction-scoring) against `notes-compaction-<1..5>.json` in each spec's own golden folder, numbered by case order; model `noteCompactionModel`. They call `AIClientDataExtraction.noteCompaction` directly (no service), still print the input and output notes, and keep their live `should` assertions: a non-empty result, no more notes than sent, and no bullet, number or `Batch` prefix.
- **Not built:** run history, saved reports, multi-model runs, score thresholds.
- **No production change:** the scorer and specs are test code only.

## Files

All scorer code is gateway-core test code, so it sits beside every golden spec and the response codecs it reads with.

| Path (under `backend/gateway/core/src/test/scala/io/mesazon/gateway/`) | Holds |
|---|---|
| `golden/performance/GoldenPerformance.scala` | Generic, response-agnostic engine: the extraction seam (`GoldenPerformanceExtraction`), comparison and result types, text/number/presence comparison, text closeness, best-match and in-order pairing, `measure` (counts, percentages, item tally, note counts, problems grouped by item) and `renderReport` |
| `golden/performance/CatalogueFileAiExtractPostResponsePerformance.scala` | The Catalogue implementation of the seam for `CatalogueFileAiExtractPostResponse`: detail kinds, candidate and variant pairing, flag path remapping, item and variant comparisons, summary-line pairing, and where the notes are |
| `unit/golden/performance/CatalogueFileAiExtractPostResponsePerformanceSpec.scala` | AI-free unit spec, runs in CI. It covers epic scenarios 1–8 (exact answer, wrong price, skipped item, extra item, invented description, dropped accent, 8.40 = 8.4, notes-only difference) on small hand-built responses and asserts the whole `GoldenPerformanceResult`, tallies, item tally, note counts and grouped problems included. This is an approved exception to the no-tests-for-test-only-code rule, for this scorer only |
| `unit/golden/performance/GoldenPerformanceSpec.scala` | One AI-free case (round 2, user decision) that builds a small `GoldenPerformanceResult` by hand and asserts the whole `renderReport` text line by line. It covers every line type: summary, a table with an empty kind hidden, the note line, a text mismatch with its closeness line, a number mismatch, a missing value, an extra value, a missing item, an extra item, a missing and an extra variant, and a summary-line problem. A second assertion on an empty result covers `Problems: none`. Same approved exception |
| `golden/performance/CustomerBookFileAiExtractPostResponsePerformance.scala` | The Customer Book implementation of the seam for `CustomerBookFileAiExtractPostResponse`: individuals and businesses paired separately, email/phone/address/contact sub-items, assumed/missing/duplicate marks paired by remapped path, needs attention, summary lines |
| `unit/golden/performance/CustomerBookFileAiExtractPostResponsePerformanceSpec.scala` | AI-free unit spec for the Customer Book scorer (user-approved exception): exact answer, a misread name with closeness, a missing and an extra candidate, an extra assumed mark, reordered contacts |
| `golden/performance/NoteCompactionOutputPerformance.scala` | The notes compaction implementation of the seam for `NoteCompactionOutput`: detail kinds `Note` and `NoteCount`, note pairing, the answer-level note count, no notes of its own |
| `unit/golden/performance/NoteCompactionOutputPerformanceSpec.scala` | AI-free unit spec for the notes compaction scorer (user-approved exception): identical answer, a reworded note with its closeness, a missing and an extra note, a kept repeat giving an extra note and a note count mismatch, a different order still 100% |
| `golden/CustomerBookFileAiExtractUnrelatedContentGoldenSpec.scala` | Customer Book unrelated content: loads `{image,pdf,spreadsheet}-*.json` from its golden folder, calls the live AI with no processing message, prints the report. Key only from `AI_CLIENT_DATA_EXTRACTION_API_KEY`, fallback `""` |
| `golden/Catalogue*GoldenSpec.scala`, `golden/CustomerBook*GoldenSpec.scala` | The adopted specs in [Scope](#scope): each loads its golden, calls the live AI and prints the report only |

Golden files: `backend/gateway/core/src/test/resources/assets-golden/<SpecClassName>/<asset base name>[-<variant>].json`, in the folder of the spec that loads it, for example `assets-golden/CatalogueFileAiExtractFromSpreadsheetGoldenSpec/spreadsheet-2.json` or `assets-golden/CustomerBookFileAiExtractFromImageGoldenSpec/image-1-omit-phone-numbers.json`. The variant names a second case on the same asset. The notes compaction specs have no asset: their goldens are `<case id>.json` in their own spec folder, where the case id (`notes-compaction-<n>`) is also the report's source name. Each spec resolves its goldens through its own source-tree path `src/test/resources/assets-golden/<SpecClassName>`, relative to the forked test working directory (the module base directory), and a missing golden fails naming that full path. The assets each golden spec sends are in `backend/gateway/core/src/test/resources/assets-test/<SpecClassName>/`, named `<type>-<n>.<ext>`; the spec declares each asset under that same name (the Catalogue PDF spec excepted, which declares `Catalogue-Price-List.PDF`), so a declared PDF name reaching the live AI is now `pdf-<n>.pdf`. No golden is shared between specs. Folder and naming rules: [Acceptance testing: Asset files](acceptance-testing.md#asset-files).

## Golden files

- A golden is the hand-corrected expected response JSON (`CatalogueFileAiExtractPostResponse`, `CustomerBookFileAiExtractPostResponse` or `NoteCompactionOutput`), written with a 2-space indent and no trailing newline, and decoded with `catalogueFileAiExtractPostResponseCodec`, `customerBookFileAiExtractPostResponseCodec` or the production `json.ai.noteCompactionOutputCodec`.
- The check never writes, creates or edits a golden. There is no auto-recording in the specs.
- **Procedure for a new golden** (issue 607, user decision): bootstrap it from one high-effort live run (the response is captured with temporary code that is reverted afterwards), then hand-correct it against the asset to the extraction rules (for example the free-text punctuation and wiped-out rules in [AI extraction](../standards/ai-extraction.md)), changing only what breaks a rule, and decode-check it with its codec. Later default-effort runs are scored against it.
- `CatalogueFileAiExtractFromImageGoldenSpec/image-1.json`: 54 candidates, one variant each, no SKUs. `image-2.json`: 125 candidates with 125 unique SKUs. Epic known gap 1 records the doubt about golden 2's row count.

## Run order and failures

For each case:

1. `assume(apiKey.nonEmpty, …)`. The key comes from `AI_CLIENT_DATA_EXTRACTION_API_KEY`, and the in-code fallback must be `""` in committed code. No key cancels the case.
2. The golden is resolved and decoded **before** the AI call. If the file is missing, `fail` names its full path, so the long AI call is never made. A decoding error fails with the decoder message.
3. `CatalogueFileService.aiExtract` or `CustomerBookFileService.aiExtract` runs live. An AI error or a timeout fails through `.zioValue`, unchanged. The specs set a 180 s request timeout with `sendMaxRetries = 2`. The notes compaction specs call `AIClientDataExtraction.noteCompaction` instead, with the production note compaction timeout and retries.
4. The scorer measures the answer, and `info(renderReport(...))` prints the report. The full response JSON is not printed (round 2, user decision), and nothing replaces it. The notes compaction specs still print their input and output notes before the report.
5. The extraction cases pass whatever the score. The notes compaction cases also keep their three `should` assertions, which can fail. Nothing is written to disk.

## Model selection

Each spec loads `AIClientDataExtractionConfig.live`, so the production defaults and their `${?ENV}` overrides pick the model and effort per file kind: `AI_CLIENT_DATA_EXTRACTION_{IMAGE,PDF,CSV,NOTE_COMPACTION}_{MODEL,REASONING_EFFORT}`. The specs override only scheme, host, port, key, timeout, retries and CSV batching. The report prints the model of the case's file kind (`imageModel`, `pdfModel` or `csvModel`). A model that rejects the effort fails as an AI error. In the extraction specs, note compaction, if triggered, only affects notes, which are not scored. The notes compaction specs print `noteCompactionModel`, and keep the production `note-compaction-request-timeout` (no timeout or retry override).

## What is compared

Excluded from the score: the top-level `extractionNotes` and every candidate's `extractionNotes`. In round 2 they are only counted, for information ([Score and report](#score-and-report)). Everything else is a detail, and every detail weighs the same.

| Detail kind (table name) | Problem-line detail | Per | Value |
|---|---|---|---|
| Name | `name` | candidate | text |
| Description | `description` | candidate | text |
| Variant SKU | `sku` | variant | text |
| Variant label | `label` | variant | text |
| Variant description | `description` | variant | text |
| Price | `price` | variant | number (price amount) |
| Currency | `currency` | variant | enum case name |
| Selling unit | `selling unit` | variant | one whole value: `quantity`, `perUnit` and `unitSize`, each a number plus a unit. Equal only when every present part has the same unit and the same number value, and the same parts are present |
| Attribute | `attribute <name>` | each attribute name | text value |
| Assumed mark, Missing mark, Duplicate mark | `assumed mark <path>`, `missing mark <path>`, `duplicate mark <path>` | each `inferredFields`/`missingFields`/`duplicateFields` path, listed under the candidate | presence |
| Needs attention | `needs attention` | candidate | `true`/`false`; always present on both sides |
| Summary line | the line's label, for example `Processed` | each `infoNotes` line | text |

Table names are the plain names the user chose (round 2). The table lists kinds in this order.

Outcome of one detail:

- Both sides present and equal → **matched**. Both present and different → **mismatched**.
- Expected present, actual absent → **missing**. Expected absent, actual present → **extra**.
- Both absent (`None`, an absent attribute, an absent flag path) → **not counted**.
- For flag paths only presence matters: present in both is matched, golden only is missing, model only is extra.
- A missing candidate or variant contributes each of its present details as missing, including `needsAttention` and its flag paths. An extra one contributes each of its present details as extra.

Comparison rules:

- **Text:** exact `String` equality. No trim, case-folding or Unicode normalisation.
- **Numbers:** `BigDecimal` `compareTo == 0`, so `8.40` equals `8.4`. Displayed with `stripTrailingZeros.toPlainString`.
- **Closeness** (text mismatches only): Levenshtein edit distance over Unicode code points, not UTF-16 chars. Similarity = `1 − distance / max(expected length, actual length)`, and two empty strings are 100%. Displayed as `<n> character(s) off, <similarity rounded HALF_UP to a whole percent>% alike`, for example `1 character off, 83% alike` for `Κραμπί`→`Κραμπι`. The edit distance is hand-written, with no library. Thresholds compare the unrounded similarity.

## Notes compaction scoring

`NoteCompactionOutputPerformance` scores a `NoteCompactionOutput` (issue 607, user decision):

- **Items:** each golden note is one item, headed `#<1-based golden position> <the note's first line>`. An extra note is headed by its first line, quoted.
- **Pairing:** `pairBestMatch` with exact text first, then text similarity ≥ 70% (higher similarity first, then the position tie-breaks). Unpaired golden notes are missing, unpaired answer notes are extra. Order is not scored.
- **Details:** a paired note is one `Note` text detail (`note`), with closeness on a mismatch; a missing or extra note carries that one detail as missing or extra. One answer-level `Note count` number detail (`note count`) compares the two note counts. The engine prints it under `Summary lines` in Problems.
- **Notes:** `generalNotes` and `itemNotes` are `Nil`, so the `Notes (not scored)` line always shows zeros. It is not hidden: hiding it for one extraction would need a new field on `GoldenPerformanceResult`, which every existing scorer spec asserts whole.
- A note's full text, line breaks included, is printed in its problem line; only the heading is cut to the first line.

## Pairing

Pairing never scores order. One generic best-match routine serves candidates and variants. It repeatedly takes the best remaining expected/actual pair under the current step's rule, then moves to the next step.

**Candidates:**

1. **Shared SKUs.** Pairs whose variant SKU sets intersect, with exact text. The most shared SKUs wins. A tie goes to the higher name similarity, then the smaller position distance `|expectedIndex − actualIndex|`, then the smaller expected index.
2. **Exact name.** Remaining pairs with identical `Some` names. A tie goes to the smaller position distance, then the smaller expected index.
3. **Similar name.** Remaining pairs with name similarity ≥ 70%. Higher similarity first, then the same position tie-breaks.
4. Unpaired expected candidates are **missing** and unpaired actual candidates are **extra**. Nameless candidates pair only through SKUs.

**Variants** (inside a paired candidate):

1. Exact SKU.
2. Exact label, then label similarity ≥ 70%, with the same tie-breaks.
3. The rest in order: the i-th remaining expected variant with the i-th remaining actual variant.
4. Leftovers are missing or extra.

**Flag path remapping.** Before flag paths are compared, each actual path `/variants/{actualIndex}/…` is rewritten to the paired expected variant's index. An unpaired actual variant's paths are rewritten to indices after the last expected variant (`expectedVariantCount + k`). Otherwise an extra variant's `/variants/2/sku` could collide with a paired variant's real `/variants/2/sku` and count as matched. Candidate-level paths such as `/name` are unchanged.

**Info lines.** Lines are paired by label, which is the text before the first digit, trimmed (for example `Processed`, `Catalogue items created`, `Item variants created`, `Unidentified rows (`). Lines that share a label pair in order. Unpaired lines are missing or extra. A paired line is compared as whole text.

## Score and report

Built in round 2. Layout chosen by the user.

**Figures** (all computed by the engine, the same for every extraction):

- **Counted** = right + wrong + missing + extra, where right is matched and wrong is mismatched. Overall % right = right ÷ counted. Each kind's % right uses the same rule over that kind's details, so made-up details lower it. Both are `BigDecimal` at scale 1, HALF_UP, and 100.0 when nothing is counted.
- **Item tally:** top-level items paired, missing and extra. Variants are not items.
- **Note counts** (not scored), taken from both the model's answer and the golden: general (top-level) notes; items with at least one note; total item notes. The golden's counts are a rough reference only, because the golden notes were never checked by hand. Note text is never printed.

**Report text** (`renderReport`; sections separated by one blank line):

```
image-1.jpeg — model gpt-6.1-sol
Overall: 92.4% right (512 of 554 details)
Items: 52 paired, 2 missing, 1 extra

Detail          Right Wrong Missing Extra  % right
Name               50     2       2     0    92.6%
Selling unit       48     4       2     0    88.9%

Notes (not scored): 6 general (golden 8), 12 items with notes (golden 10), 15 item notes (golden 13)

Problems
#12 Κραμπί
  name          expected "Κραμπί", got "Κραμπι"
                (1 character off, 83% alike)
  variant #2 SKU "AB-1"
    price         expected 8.4, got 8.1
    label         missing from the answer (expected "500 g")
  variant #3 SKU "AB-3" — extra in the answer (4 details)
#30 Celery — missing from the answer (5 details)
Extra item "Σέλινο" (5 details)
Summary lines
  Processed     expected "Processed 54 of 54 entries", got "Processed 53 of 54 entries"
                (1 character off, 96% alike)
```

- **Summary:** `<source file> — model <model>`, then `Overall: <x>% right (<right> of <counted> details)`, then `Items: <p> paired, <m> missing, <e> extra`.
- **Table:**
  - It has a `Detail` column, then `Right`, `Wrong`, `Missing`, `Extra` and `% right`.
  - The name column is padded to the longest shown name (at least `Detail`) plus two spaces. Each count is right-aligned to its header, with one space between columns. `% right` is preceded by two spaces and right-aligned to its header.
  - Rows follow the kind order, and a kind with nothing counted is hidden. The result still holds every kind; only the printed table hides the empty ones.
- **Notes line:** `Notes (not scored): <a> general (golden <b>), <c> items with notes (golden <d>), <e> item notes (golden <f>)`.
- **Problems:** `Problems`, then every problem, with no cap.
  - **Order:** golden items in golden order (paired items that have problems, and missing items), then extra items in model order, then `Summary lines` if any summary line has a problem. With no problem at all, the section is the single line `Problems: none`.
  - **Item heading:** `#<1-based golden position> <name>`, or `(no name)` in place of the name.
  - **Missing item:** one line, `<heading> — missing from the answer (<n> details)`.
  - **Extra item:** one line, `Extra item "<name>" (<n> details)`, or `Extra item (no name) (<n> details)`. Here `<n>` counts every detail of the item, its variants included. Use `detail` when n is 1.
  - **Paired item:**
    - Its own problem lines come first, indented two spaces, in kind order. Marks are sorted by path and listed with the item's own details, not under a variant.
    - Then each variant that has problems, in golden-index order with extra variants last. The variant heading `variant #<1-based position> SKU "<sku>"` (SKU left out when absent) is indented two spaces, and its problem lines four. Attributes are sorted by name.
    - A missing or extra variant takes one line: `<variant heading> — missing from the answer (<n> details)` or `— extra in the answer (<n> details)`.
    - Variants without problems are not shown.
  - **Problem line:** `<detail>` padded to the longest problem-line detail in the whole Problems section plus two spaces, then the value text:
    - wrong → `expected <x>, got <y>`;
    - missing value → `missing from the answer (expected <x>)`;
    - extra value → `extra in the answer (got <y>)`.
  - **Closeness:** for a text mismatch, closeness goes on the next line, `(<closeness>)`, aligned under the value column. This is the only wrapping; values are never cut or wrapped by width.
  - **Values:**
    - Text and attribute values are quoted: `"Κραμπί"`. Numbers, currency, selling unit, `true`/`false` and marks (`present`) are not.
    - Numbers use `stripTrailingZeros.toPlainString`. Selling units read as parts in words, for example `12 Box per 1 Bottle size 500 Millilitre`.
    - Equality is always decided on the typed values before rendering.
- **Output:** the report alone, through `info`. No response JSON.

## Open gaps

- **Live keys in working-tree fallbacks.** The Customer Book image, PDF and spreadsheet specs carry a key as their fallback in the working tree only, for user-run measurement. Each must be `""` before it is committed.
- **Rules implemented but not unit-tested.** The Catalogue unit spec covers epic scenarios 1–8 only. These have no case: reordered items or variants still pair; SKU pairing wins over name pairing; names below 70% alike become missing plus extra; remapping of an extra variant's flag paths; info lines paired by label. Adding them needs the user's approval.

## Extraction seam

Built in round 2. An extraction plugs in by implementing `GoldenPerformanceExtraction[Response, DetailKind <: GoldenPerformanceDetailKind]`, which supplies only:

- `detailKinds`: its kinds in table order, each with its plain `label`.
- `itemComparisons(expected, actual)`: one `ItemComparison` per golden item (paired or missing) in golden order, then one per extra item in model order. Each one carries:
  - its heading text (`#12 Κραμπί`, or the bare name for an extra);
  - its pairing outcome (`Paired`, `Missing` or `Extra`);
  - its own detail comparisons;
  - its sub-item comparisons (Catalogue: variants), each with a heading text, a pairing outcome and detail comparisons.

  The extraction does its own pairing with the engine's `pairBestMatch`/`pairInOrder` and builds every detail with the engine's `compare*` functions, so outcomes, rendering and closeness stay uniform.
- `answerDetailComparisons(expected, actual)`: details that belong to the whole answer, not an item (Catalogue: summary lines).
- `generalNotes(response)` and `itemNotes(response)`: where its notes are. The engine does the counting.

`GoldenPerformance.measure(extraction)(expected, actual)` returns the `GoldenPerformanceResult`. That result holds:

- the right and counted totals, and the overall %;
- the item tally;
- every kind's tally with its % right;
- the note counts for both the answer and the golden;
- the problems grouped by item, then the problem summary-line details.

A missing or extra item or variant keeps only its detail count, because it prints as one line. `GoldenPerformance.renderReport(sourceFileName, modelName, result)` renders it.

Customer Book's implementation puts its two item lists into one item list, and each heading names its list (for example `Business #3 Acme Ltd`).

## Running

```sh
AI_CLIENT_DATA_EXTRACTION_API_KEY=… [AI_CLIENT_DATA_EXTRACTION_IMAGE_MODEL=… AI_CLIENT_DATA_EXTRACTION_IMAGE_REASONING_EFFORT=…] \
  sbt "gateway-core/testOnly io.mesazon.gateway.golden.CustomerBookFileAiExtractFromImageGoldenSpec"
sbt "gateway-core/testOnly io.mesazon.gateway.unit.golden.performance.*"   # AI-free scorer specs
```

Use `gateway-core/testOnly`, not `Test/runMain`: the forked test working directory must be the module base directory for the relative golden path. The key comes from `AI_CLIENT_DATA_EXTRACTION_API_KEY`.

Live runs happen only with the user's explicit go-ahead for each run. Never commit a credential.
