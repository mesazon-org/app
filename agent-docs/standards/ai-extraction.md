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

Catalogue reuses `CatalogueItemCandidate` inside `CatalogueFileExtract`; no separate AI candidate types are agreed. The agreed attribute-map approach is a non-strict JSON schema (`strict: false`) with typed decoding and service validation, preserving dynamic keys and the existing domain. This is schema guidance without strict provider adherence, and is distinct from JSON-object mode. `OpenAIJsonSchema.strict` defaults to `true`; `AIClientDataExtraction` uses the registered output type's setting. Existing registrations therefore remain strict. Register Catalogue separately with `strict = false`; the feature doc tracks registration and service wiring. Source-grouping evidence verification remains unresolved before orchestration.

## Descriptive booleans

Add a boolean only for a specific condition with a defined client meaning. Document its scope, comparison rule, treatment of missing values, and which layer determines the final value. Prefer deriving observable presence from the value itself over adding another flag. General comments do not imply a readiness state.

Catalogue's agreed flags are:

| Flag | Final meaning |
|---|---|
| `atLeastOneVariant` | `true` is an attention condition under the agreed contract, despite the field name; do not interpret it as a healthy-state flag |
| `missingRequiredSaleUnit` | `true` exactly when the variant has no usable `sellingUnit`; the service must keep it consistent with that optional field |
| `nameIsDuplicate` | The item name matches another candidate's name case-insensitively within the completed upload |
| `skuIsDuplicate` | A supplied SKU matches another variant's SKU case-insensitively across all candidates in the completed upload; absent SKUs are never duplicates |

Recompute deterministic flags after grouping and aggregation, rather than trusting batch-local AI values. Catalogue duplicate flags contribute to `needsAttention`, do not merge candidates, and do not query stored records. Existing insert validation and stored uniqueness rules still apply when a candidate is submitted.

Catalogue explicitly exposes `atLeastOneVariant` on each item candidate as an attention flag. An absent optional price, SKU, label, or description does not need an additional flag. A readable item name is already required by its candidate type; entries without one follow the feature's unidentified-entry rules. `CatalogueFileExtractPostResponse` additionally has a service-computed `needsAttention` boolean. It is absent from the AI result and nested candidates; the AI must not determine it. Compute it as `candidates.exists(item => item.atLeastOneVariant || item.nameIsDuplicate || item.variants.exists(variant => variant.missingRequiredSaleUnit || variant.skuIsDuplicate))`. All four flags use `true` to contribute attention; do not negate `atLeastOneVariant`. An empty candidate list gives `false`. General extraction notes do not affect this value. Do not infer an automatic submission block from general notes.

## Notes presented to users

For extraction contracts with note lists, each string is one concise, plain-language comment. Return plain strings without Markdown bullets or numbering: the UI renders the list as bullet points. Use empty lists when there are no comments. Notes explain source limitations and extraction observations; they are not an encoded validation protocol.

| Location | Meaning and examples |
|---|---|
| Item `extractionNotes` | Whole-item observations, such as `"Item description is partly obscured"` or `"Grouping with the preceding item is unclear"` |
| Variant `extractionNotes` | Variant observations, such as `"Unit is not supported"`, `"SKU is visible but could not be read clearly"`, or `"Price currency could not be identified"` |
| Top-level `extractionNotes` | General document observations from the AI, plus service-generated unidentified-row findings where the feature contract requires them |
| Top-level `infoNotes` | Service-generated factual summaries, such as `"Processed 40/55 items"` and `"Filtered 3 empty items"` |

These examples belong in the default extraction instructions, with item and variant arrays shown separately. Missing-unit and duplicate flags have their own precise semantics; notes remain useful comments rather than a second set of flags. Keep an observation at the appropriate level instead of copying it to every candidate.

For Catalogue, preserve general AI notes in source-batch order and build summary/unidentified-row messages in code. Do not compact its lists into one string. Public note wording must agree with what the counts actually measure; spreadsheet source rows and grouped catalogue items are different quantities.

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
- Reconciliation must use the feature's agreed source evidence. For Catalogue, a name match alone does not justify merging named candidates across batches: require an explicit shared item key or grouping marker. Keep unsupported merges separate with duplicate flags and a grouping comment. A variant-only continuation may attach to the preceding item with a grouping comment under the agreed Catalogue rule. There is no extra AI reconciliation pass.
- Do not present AI-invented grouping or row references as verified source evidence. Resolve how the service obtains and checks that evidence before implementing reconciliation.
- Follow the existing client policy for transient failures. Do not add retries for malformed decoded output to mask schema/prompt defects. A failed primary batch fails the extraction; do not silently return a partial preview. A valid result with no recognizable candidates is distinct from a request/decoding failure.

## Proof and documentation

Keep the feature doc and epic precise about delivered behavior and remaining gaps. A prompt, codec, or service skeleton does not mean extraction is wired or verified.

- Functional tests prove dispatch, dependency forwarding, aggregation, deterministic flags, summaries, and owned failure branches. Reuse shared scanner/spreadsheet coverage for unchanged parsing and byte limits.
- Client integration tests prove actual message roles/content, schema/request shape, decoding, and applicable retry boundaries against controlled responses. They do not prove live-model fidelity to free-text instructions.
- Acceptance tests prove the public HTTP contract, authorization, validation/error shape, and preview side-effect guarantees. Follow the documented upload transport limitations rather than adding known-stalling checks.
- Add dedicated JSON codec tests only when the user explicitly requests them, per the [Scala testing standard](scala.md#tests). Do not add tests that merely assert prompt prose.
- Report the checks actually run and any unrun required checks. Describe live-model observations separately from deterministic automated proof.
