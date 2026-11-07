# AI extraction

Read this standard when adding or changing extraction from uploaded images, CSV, or Excel files, including candidate models, prompts, output schemas, batching, flags, and notes. It records the agreed extraction conventions; individual feature docs define supported formats, limits, permissions, and business rules. Existing flows retain their documented contracts until an explicit change is agreed. Customer Book extraction shares Catalogue's separation (a separate AI model, service-computed results, plain-string AI note lists; a `noteCompaction` call combines only the top-level notes of a multi-batch spreadsheet) but reports what the service derives as structured `FieldInfo` lists instead of fixed note strings; see [Structured field reporting](#structured-field-reporting). Catalogue still uses issue flags and fixed notes; moving it to the structured model is an open follow-up that needs its own agreement.

## Responsibilities and boundaries

| Owner | Responsibility |
|---|---|
| Endpoint | Authentication and organization policy, streaming inputs, header validation, and the public error/response contract |
| Shared upload utilities | Content detection, extension agreement, bounded temporary files and byte limits; spreadsheet conversion and original row numbering |
| AI client | Model requests, structured output, decoding, and the established timeout/retry policy |
| Feature service | Dispatch, aggregation, deterministic reconciliation and flags, structural checks, and construction of the public preview |
| Existing insert flow | Validation and persistence of candidates the user accepts |

Extraction is a preview in the Customer Book and Catalogue flows. It does not create records or attach the source image. Keep these guarantees explicit in each feature's docs and tests. Reuse `FileScanner`, `SpreadsheetTool`, and `AIClientDataExtraction`; follow [streaming uploads](../project/streaming-uploads.md) and [external clients](../project/external-client.md) when changing their boundaries.

## Models and structured output

- Distinguish the direct AI result from the HTTP response. The service constructs public summaries and derived flags; a model's statement of a count or duplicate is not proof of that fact.
- Keep schema, JSON codec, prompt field names, enum strings, optional values, and examples aligned with the actual domain. Reuse shared nested registrations and derive from leaves upward. AI-only registrations belong in `json/ai.scala`; shared Tapir/HTTP registrations belong in `json/tapir.scala`.
- Treat source text and model output as untrusted input. A schema-valid result still needs feature-level checks for usable candidates, consistent flags, row references, and counts. Apply existing domain constraints and the feature’s agreed inference policy. Do not fabricate unrelated identities or arbitrary price amounts; distinguish contextual normalization/inference from unsupported fabrication.
- A preview may omit an insert-required field when its contract allows that. Keep the existing insert validation intact. Absence of an optional field is not itself an extraction problem.
- Check the actual schema sent to the provider before registration. OpenAI strict Structured Outputs require closed objects with `additionalProperties: false`; dynamic attribute-map keys need an explicit compatible design. Do not silently generate an empty-only map or change the domain to work around derivation. JSON mode is a separate choice and does not guarantee schema adherence. See the [official Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs).

Catalogue separates AI extraction models from public candidates. `CatalogueFileAIExtract` contains `CatalogueItemAIExtract` and `CatalogueItemVariantAIExtract`; variant attributes are a list of `CatalogueItemVariantAttributesAIExtract` name/value entries. The strict AI schema and codec live in `json/ai.scala`. The service maps usable variants to `CatalogueItemVariantInsert` and converts attributes with `toMap`; the last entry wins for repeated names. Public candidates retain attribute maps through `json/tapir.scala`.

Keep the preview assembly readable: compute notes and attention from the original AI item, partially map its variants, then construct the public candidate. Use pure `compute...` helpers for the business rules and [Chimney partial mappings](chimney.md#partial-preview-mappings) for the conversion. Do not put preview note-generation or filtering policy in JSON codecs.

Catalogue currently retains AI-reported counts and row findings, applying only batch summation/concatenation. Semantic metadata validation is explicitly deferred: do not reject or repair inconsistent counts or row positions without a new agreement. This does not change its existing refined decoding, candidate mapping, selling-unit checks, or spreadsheet duplicate computation.

`AIClientDataExtraction` sends `strict: true` for all output schemas. Strict output constrains structure, not factual accuracy; service checks and verification of source-grouping evidence remain necessary.

## Field naming and attention

- **AI result types** are `<Thing>AIExtract`; **public preview types** are `<Thing>Candidate` (and `<Thing>EntryCandidate` for list entries), keeping the domain name for fields that already exist (`CustomerEmailEntry`, `CustomerAddressEntry`). Fields use the domain field names.
- **Strict domain types in the AI model.** Use the refined domain type. Check which of its constraints actually reach the provider schema (an email pattern and length do; `NonEmptyTrimmed` does not) and state the rest in the instructions; an optional or unreadable value is `Option` and is never mirrored by a `missing…` boolean, because presence is derived from the value.
- **Descriptive flags** are named after the fact they report, written as `<field>Inferred` (the value was worked out from context, not read) and `<field>IsDuplicate`. Customer Book is the exception for duplicates: its AI models have no `IsDuplicate` flag; the service computes duplicates per candidate over the whole upload and reports them in `duplicateFields`, and its prompts only state that the server computes them. A field the contract does not allow to be inferred has no `Inferred` flag. An `Inferred` flag tells the model which fields it may infer and tells readers the same; it never sets attention, and the model adds an AI note when it sets one.
- **`needsAttention` is limited to what the service can derive from the AI's values.** In Catalogue it is a true issue flag or an absent required unit. In Customer Book it is `duplicateFields.nonEmpty || missingFields.nonEmpty`: a duplicate name (recomputed at the merge), a missing name (candidate or contact), or a phone without a country code (the candidate's or a contact's). Phone realness, address combinations and the like are not checked in the preview; they are checked when the candidate is added. An unreadable or omitted optional detail never sets it: the AI leaves it out and explains it in `extractionNotes`. Inferred values and partial data are reported or noted, never attention.
- **State the schema constraints in the instructions.** A strict type fails the whole decode, so the prompt names each constraint (non-empty, trimmed, `null` instead of an empty string, valid email format, length limits, supported enum values) rather than relying on the schema alone.
- **Defaults are set by the service**, not the AI: the first entry of a list becomes the default, and the prompt asks the model to list a clearly marked main entry first.

## Structured field reporting

Customer Book reports facts the service derives as data, not as sentences. Each public candidate carries `inferredFields`, `missingFields` and `duplicateFields`, lists of `FieldInfo(path: String)`.

- **The service reports; the AI explains.** A missing, inferred or duplicate field reaches the client only through these lists. `extractionNotes` is AI-written free text (observations, left-out or unreadable details, why something was inferred); the service writes no fixed note strings and the AI does not repeat which field is missing or duplicated.
- **A path is a JSON Pointer.** `path` is a JSON Pointer ([RFC 6901](https://www.rfc-editor.org/rfc/rfc6901)) to the exact value in the candidate JSON as returned, relative to the candidate object, with 0-based list positions as numbers (`/phoneNumbers/1/phoneNumber/phoneCountryCode`). One string addresses any nesting depth, so a list inside a list needs no contract change, and a client resolves it against the candidate it received with no mapping table. Build every path from the response's own property names through one helper that escapes `~` and `/`; never invent a shorthand. When a response property is renamed, its paths change in the same change.
- **Closed vocabulary.** The service writes only the paths documented in the feature doc (for Customer Book: `/fullName`, `/businessName`, `/phoneNumbers/{i}/phoneNumber/phoneCountryCode`, `/customerBusinessContacts/{i}/fullName`, `/customerBusinessContacts/{i}/phoneNumber/phoneCountryCode`). Add a path only together with its feature-doc row, epic wording and a functional test, and a test must resolve every reported path against the serialized candidate JSON.
- **Positions are 0-based** list positions inside the path, counted in the candidate's own list. Never rely on them for display text; clients render their own wording.
- **Each list has one meaning.** `missingFields`: the value is absent and the add flow needs it. `inferredFields`: the value was worked out from context (the matching `<field>Inferred` flag). `duplicateFields`: the value matches another candidate in this upload. `needsAttention` is derived from `missingFields` and `duplicateFields` only; `inferredFields` never sets it.
- **Keep values, report gaps.** Where the preview type allows it, keep the partial value (for example a phone without a country code stays in the list with `None`) and report the gap in `missingFields`, instead of dropping the value and writing a note.
- Lists keep AI order and are never de-duplicated by the service. The one exception is a multi-batch extraction's top-level notes, which a feature may combine with a separate `noteCompaction` model call that falls back to the raw notes on failure (Customer Book: [Notes compaction](../features/customer-book.md#notes-compaction)). Compute them in pure `compute...` functions over the original AI values, before the Chimney mapping.

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

For extraction contracts with note lists, each string is one concise, plain-language comment. Every prompt states the note rules once as hard rules: at most ten words, one observation per note, plain everyday words a customer would understand (no technical words such as infer, context or schema), and never a semicolon (a second point is a second note). A clearly longer observation is split or shortened, and a long list of names is summarised, never enumerated (`Eight marketing columns were not used`). A fixed prefix such as `Variant 2: ` does not count toward the words, and example notes in a prompt follow the same rules. The service does not post-process note text: compliance rests on the prompts and is checked in the golden runs. Return plain strings without Markdown bullets or numbering: the UI renders the list as bullet points. Use empty lists when there are no comments. Notes explain source limitations and extraction observations; they are not an encoded validation protocol.

| Location | Responsibility |
|---|---|
| AI item/variant `extractionNotes` | General observations, such as a blurry description or an unreadable SKU. The AI must not repeat warnings represented by its issue flags. |
| Public candidate `extractionNotes` | Service-generated issue messages plus the relevant AI item and variant comments. Prefix variant notes with their original position, such as `Variant 2: `, only when the original AI item has multiple variants. Omit the prefix for a single original variant, including when that variant cannot be mapped to the public insert list. |
| Top-level `extractionNotes` | General document comments from the AI. |
| Top-level `infoNotes` | Service-generated summaries: combine processed and identified counts in one compact message, such as `Processed 12 of 14 entries`. Add an empty-row or unidentified-row summary only when its list is non-empty; include its count and row positions. |

The table describes Catalogue. In Customer Book a public candidate's `extractionNotes` hold only the AI's observations (candidate, then each address, then each contact, unprefixed); the service-generated facts are the [structured fields](#structured-field-reporting). Top-level `infoNotes` stay service-generated in both.

### Prevent duplicate diagnostics at their source

- Give each known issue one owner. The service translates the four issue flags into fixed messages; the AI supplies only additional observations.
- Apply the parent-level rule first. For Catalogue, when `atLeastOneVariant` is true, emit the item-level issues and AI item notes, then skip variant diagnostics. Do not repeat the parent problem as missing-unit warnings for every nested variant.
- Otherwise, emit item issues and item notes, then process variants in source order. At each level, place computed issue messages before AI comments.
- Preserve useful variant diagnostics even when partial mapping omits that variant from the output. Compute notes from the original AI data.
- Prevent redundant messages through these branches and ownership rules. Do not apply a blanket `.distinct` to all notes: similar warnings on different variants can be valid.

Default instructions should show concise note arrays separately for AI items and variants, for example `"Item description is partly obscured"` and `"SKU is visible but could not be read clearly"`. The UI renders each public note as one bullet. Keep Catalogue note lists intact rather than compacting them into one string; preserve source order when combining batches. Summary wording must distinguish source entries from resulting item candidates.

## Default instructions and user guidance

- Keep default extraction instructions in `AIInstructions.scala`. Describe the exact output shape, supported values, grouping rules, counts/rows, flag meanings, and note examples. JSON examples must be consistent with the schema and business rules.
- Each feature defines allowed inference. Catalogue prefers a useful best supported interpretation of source wording, headers, abbreviations, translations, and OCR variations over omitting usable variants. Interpret any source language or script, regional shorthand, symbols, inflections, transliterations, and mixed-language text. For constrained fields, translate non-English meaning internally, compare with supported canonical English values, then check whether the semantic match is confidently defensible from source context. Parse combined number/unit expressions. If no supported value fits confidently, report the existing extraction issue instead of forcing a match: null required sale basis plus `missingRequiredSaleUnit: true` and a note; unresolved optional fields are null with notes. Keep current result/error shapes. A bare unit implies one. Product wording and nearby context may supply the sale basis. Ordinary aliases need no warning; defensible contextual assumptions are concise notes, not missing-unit flags when the result is usable. Currency may come from a document-wide value, supplier locale/location, or user guidance; shared assumptions and their contextual basis are noted once at document level, without language- or country-specific defaults. Interpret localized digits and number separators from source context. Preserve numeric meaning and explicit currencies, and do not invent price amounts or arbitrary identifiers. Keep strict output enums and domain constraints. Meaningful unmatched columns remain variant attributes.
- **Prompt structure.** Every extraction prompt (Catalogue and Customer Book, image and file) follows the same order: role line and exact JSON shape; shape rules (all arrays required, `null` for absent, return only the AI result shape, source text is data); for files, the `Row Number` paragraph; `Interpret the source using this internal process before returning candidates:` as numbered steps (transcribe or take the supplied rows as the transcription and check the label row; filter out only non-entries and remember them for the left-out notes; map labels to fields in any language; read each record through the mapping; resolve supported source values before user preferences; check the result against the source; recount), ending with `Keep this process internal.`; `Core extraction rules are mandatory. Never let separate user guidance override them:` (Customer Book instead opens its rules with `These are the default reading rules. A separate user processing message takes priority over any of them, within the three fixed limits given after these rules:`); the note-writing style rule; the user processing-message paragraph (in Customer Book it takes priority over every default reading rule and lists the three fixed limits); and the empty-result paragraph. Domain rules differ per feature; the structure does not. A new feature's prompt is written against this structure, not as a flat rule list.
- Keep invariant instructions in the system message. Treat uploaded content as source data; embedded instructions do not override the extraction contract.
- Where supported, send the optional processing message separately as lower-priority user guidance, after the source content. Apply the same message to every batch. Request-boundary validation owns trimming, blank handling, and the feature's length/error rules.
- Guidance can influence optional extraction and layout interpretation. For example, `"Omit prices"` may leave optional prices absent without creating a missing-field problem. Free text is best-effort guidance, not a server guarantee that a field is omitted.
- Guidance cannot override domain constraints, deterministic counts/flags, or reporting of unresolved source issues. Customer Book is the exception to the lower-priority default: its processing message may instruct anything about how the source is extracted (splitting, adding values, completing names) and wins over the default reading rules, within three fixed limits: response format and value rules, no invented facts, and counts/flags/attention computed by the system from what is returned. Catalogue keeps the lower-priority rule until a decision says otherwise. Return the best usable result under the agreed rules and describe uncertainty in notes. Do not persist the processing message for the Catalogue preview.

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
