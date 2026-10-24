# AI extraction

Read this standard when adding or changing extraction from uploaded images, CSV, or Excel files, including candidate models, prompts, output schemas, batching, flags, and notes. It records the agreed extraction conventions; individual feature docs define supported formats, limits, permissions, and business rules. Existing flows retain their documented contracts until an explicit change is agreed. In particular, Customer Book's existing note-compaction behavior is not changed by Catalogue's note lists.

## Responsibilities and boundaries

| Owner | Responsibility |
|---|---|
| Endpoint | Authentication and organization policy, streaming inputs, header validation, and the public error/response contract |
| Shared upload utilities | Content detection, extension agreement, bounded temporary files and byte limits; spreadsheet conversion and original row numbering |
| AI client | Model requests, structured output, decoding, and the established timeout/retry policy |
| Feature service | Dispatch, aggregation, deterministic reconciliation and flags, structural checks, and construction of the public preview |
| Existing insert flow | Validation and persistence of candidates the user accepts |

Extraction is a preview in the current Customer Book and planned Catalogue flows. It does not create records or attach the source image. Keep these guarantees explicit in each feature's docs and tests. Reuse `FileScanner`, `SpreadsheetTool`, and `AIClientDataExtraction`; follow [streaming uploads](../project/streaming-uploads.md) and [external clients](../project/external-client.md) when changing their boundaries.

## Models and structured output

- Distinguish the direct AI result from the HTTP response. The service constructs public summaries and derived flags; a model's statement of a count or duplicate is not proof of that fact.
- Keep schema, JSON codec, prompt field names, enum strings, optional values, and examples aligned with the actual domain. Reuse shared nested registrations and derive from leaves upward. AI-only registrations belong in `json/ai.scala`; shared Tapir/HTTP registrations belong in `json/tapir.scala`.
- Treat source text and model output as untrusted input. A schema-valid result still needs feature-level checks for usable candidates, consistent flags, row references, and counts. Apply existing domain constraints; do not invent a price, currency, required identity, or unit to make a candidate complete.
- A preview may omit an insert-required field when its contract allows that. Keep the existing insert validation intact. Absence of an optional field is not itself an extraction problem.
- Check the actual schema sent to the provider before registration. OpenAI strict Structured Outputs require closed objects with `additionalProperties: false`; dynamic attribute-map keys need an explicit compatible design. Do not silently generate an empty-only map or change the domain to work around derivation. JSON mode is a separate choice and does not guarantee schema adherence. See the [official Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs).

Catalogue separates AI extraction models from public candidates. `CatalogueFileAIExtract` contains `CatalogueItemAIExtract` and `CatalogueItemVariantAIExtract`; variant attributes are a list of `CatalogueItemVariantAttributesAIExtract` name/value entries. The strict AI schema and codec live in `json/ai.scala`. The service maps usable variants to `CatalogueItemVariantInsert` and converts attributes with `toMap`; the last entry wins for repeated names. Public candidates retain attribute maps through `json/tapir.scala`.

Keep the preview assembly readable: compute notes and attention from the original AI item, partially map its variants, then construct the public candidate. Use pure `compute...` helpers for the business rules and [Chimney partial mappings](chimney.md#partial-preview-mappings) for the conversion. Do not put preview note-generation or filtering policy in JSON codecs.

`AIClientDataExtraction` sends `strict: true` for all output schemas. Strict output constrains structure, not factual accuracy; service checks and verification of source-grouping evidence remain necessary.

## Descriptive booleans

Add a boolean only for a specific condition with a defined client meaning. Document its scope, comparison rule, treatment of missing values, and which layer determines the final value. Prefer deriving observable presence from the value itself over adding another flag. General comments do not imply a readiness state.

Catalogue's AI issue flags are:

| Flag | Final meaning |
|---|---|
| `atLeastOneVariant` | `true` is an attention condition under the agreed contract, despite the field name; do not interpret it as a healthy-state flag |
| `missingRequiredSaleUnit` | `true` exactly when the variant has no usable `sellingUnit`; the service must keep it consistent with that optional field |
| `nameIsDuplicate` | The item name matches another candidate's name case-insensitively within the completed upload |
| `skuIsDuplicate` | A supplied SKU matches another variant's SKU case-insensitively across all candidates in the completed upload; absent SKUs are never duplicates |

Duplicate checks concern this upload only, never stored records, and do not justify merging candidates. Perform any feature-required reconciliation before final preview mapping. Normal insert validation and stored uniqueness rules still apply when a candidate is submitted.

Compute `needsAttention` on each public `CatalogueItemCandidate`, not on the top-level response. Any true AI issue flag makes that item require attention; an absent variant selling unit does too, even if the AI omitted its flag. General AI notes alone do not change attention. Keep the item even when no variants can be mapped: the public variant list may be empty, while `CatalogueItemVariantInsert` still requires a selling unit. Existing add-item validation remains unchanged.

## Notes presented to users

For extraction contracts with note lists, each string is one concise, plain-language comment. Return plain strings without Markdown bullets or numbering: the UI renders the list as bullet points. Use empty lists when there are no comments. Notes explain source limitations and extraction observations; they are not an encoded validation protocol.

| Location | Responsibility |
|---|---|
| AI item/variant `extractionNotes` | General observations, such as a blurry description or an unreadable SKU. The AI must not repeat warnings represented by its issue flags. |
| Public candidate `extractionNotes` | Service-generated issue messages plus the relevant AI item and variant comments. Prefix variant notes with their original position, such as `Variant 2: `. |
| Top-level `extractionNotes` | General document comments from the AI. |
| Top-level `infoNotes` | Service-generated summaries: combine processed and identified counts in one compact message, such as `Processed 12 of 14 entries`. Add an empty-row or unidentified-row summary only when its list is non-empty; include its count and row positions. |

### Prevent duplicate diagnostics at their source

- Give each known issue one owner. The service translates the four issue flags into fixed messages; the AI supplies only additional observations.
- Apply the parent-level rule first. For Catalogue, when `atLeastOneVariant` is true, emit the item-level issues and AI item notes, then skip variant diagnostics. Do not repeat the parent problem as missing-unit warnings for every nested variant.
- Otherwise, emit item issues and item notes, then process variants in source order. At each level, place computed issue messages before AI comments.
- Preserve useful variant diagnostics even when partial mapping omits that variant from the output. Compute notes from the original AI data.
- Prevent redundant messages through these branches and ownership rules. Do not apply a blanket `.distinct` to all notes: similar warnings on different variants can be valid.

Default instructions should show concise note arrays separately for AI items and variants, for example `"Item description is partly obscured"` and `"SKU is visible but could not be read clearly"`. The UI renders each public note as one bullet. Keep Catalogue note lists intact rather than compacting them into one string; preserve source order when combining batches. Summary wording must distinguish source entries from resulting item candidates.

## Default instructions and user guidance

- Keep default extraction instructions in `AIInstructions.scala`. Describe the exact output shape, supported values, grouping rules, counts/rows, flag meanings, and note examples. JSON examples must be consistent with the schema and business rules.
- Extract supported, readable source values. Each feature decides allowed inference: Catalogue accepts selling units only from structured fields, preserves stated supported units, and omits unsupported/unclear-currency prices with variant comments. Meaningful unmatched columns become variant attributes under its agreed contract.
- Keep invariant instructions in the system message. Treat uploaded content as source data; embedded instructions do not override the extraction contract.
- Where supported, send the optional processing message separately as lower-priority user guidance, after the source content. Apply the same message to every batch. Request-boundary validation owns trimming, blank handling, and the feature's length/error rules.
- Guidance can influence optional extraction and layout interpretation. For example, `"Omit prices"` may leave optional prices absent without creating a missing-field problem. Free text is best-effort guidance, not a server guarantee that a field is omitted.
- Guidance cannot override domain constraints, deterministic counts/flags, or reporting of unresolved source issues. Return the best usable result under the agreed rules and describe uncertainty in notes. Do not persist the processing message for the Catalogue preview.

## Batches, reconciliation, and failures

- Keep original spreadsheet row positions through conversion, batching, and aggregation. Batch sizes, concurrency, sheet selection, and count definitions belong in the feature doc/configuration; do not assume one row equals one candidate item.
- Keep results in source order despite concurrent requests. Compute final processed-item counts and duplicate flags over the aggregate, after feature-specific grouping.
- Reconciliation must use the feature's agreed source evidence; a name match alone does not justify merging candidates. Catalogue cross-batch grouping is explicitly deferred: retain separate batch candidates, recompute upload-wide duplicate flags, and do not attach variants across boundaries. AI grouping within a supplied batch remains supported. Revisit source evidence and deterministic boundary reconciliation together under [Catalogue follow-up 13](../features/catalogue.md#known-issues-edge-cases-and-larger-follow-ups); do not introduce AI-only source-reference fields for this deferred work.
- Do not present AI-invented grouping or row references as verified source evidence. Resolve how the service obtains and checks that evidence before implementing reconciliation.
- Follow the existing client policy for transient failures. Do not add retries for malformed decoded output to mask schema/prompt defects. A failed primary batch fails the extraction; do not silently return a partial preview. A valid result with no recognizable candidates is distinct from a request/decoding failure.

## Proof and documentation

Keep the feature doc and epic precise about delivered behavior and remaining gaps. A prompt, codec, or service skeleton does not mean extraction is wired or verified.

- Functional tests prove dispatch, dependency forwarding, aggregation, deterministic flags, summaries, and owned failure branches. Reuse shared scanner/spreadsheet coverage for unchanged parsing and byte limits.
- Client integration tests prove actual message roles/content, schema/request shape, decoding, and applicable retry boundaries against controlled responses. They do not prove live-model fidelity to free-text instructions.
- Acceptance tests prove the public HTTP contract, authorization, validation/error shape, and preview side-effect guarantees. Follow the documented upload transport limitations rather than adding known-stalling checks.
- Add dedicated JSON codec tests only when the user explicitly requests them, per the [Scala testing standard](scala.md#tests). Do not add tests that merely assert prompt prose.
- Report the checks actually run and any unrun required checks. Describe live-model observations separately from deterministic automated proof.
