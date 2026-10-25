# Chimney

Reusable model-transformation rules. Related: [Scala](scala.md), [Smithy](smithy.md), [Iron](iron.md), [Tapir](tapir.md), [validation](../features/flow/02-validation.md), [repository](../features/flow/04-repository.md), and [service](../features/flow/05-service.md).

## Default

- When Chimney is on the module's classpath, use it for structurally similar models in main and test code: domain ↔ Smithy/transport, validated request ↔ repository input, and repository Row ↔ response.
- Import `io.scalaland.chimney.dsl.*` at the call site.
- Use `.transformInto[Target]` whenever the transformation is fully automatic. Let Chimney derive matching fields, nested products, options, collections, and mirrored enums together; do not hand-build derivable fields.
- Use `.into[Target]...transform` only when the call needs a real customization such as `.withFieldComputed(...)`, `.withFieldConst(...)`, or `.withFieldRenamed(...)`.
- Do not introduce a named mapping helper for an inline transformation. In particular, do not hide a one-to-one conversion behind a private method.

## Collections

- Transform a Scala collection directly when every element uses automatic derivation: `values.transformInto[List[Target]]`, never `values.map(_.transformInto[Target])`.
- Keep `.map` when each element needs its own Chimney customization or when the method belongs to a different container operation, such as ScalaCheck `Arbitrary.map`.
- Prefer transforming the complete owning product when Chimney can derive it, rather than transforming one of its collection fields separately.
- For gateway `NonEmptyChunk[Source]` → `List[Target]` conversions, import `io.mesazon.gateway.transform.given`. The shared transformer in `transform/transform.scala` preserves element order and uses the element transformer; complete owning products derive without per-field `.toList` customizations or request-specific transformers.

## Partial preview mappings

- Use `.intoPartial[Target]` when an incomplete source may not satisfy the target, such as an optional AI selling unit becoming a required insert field. Let Chimney handle that conversion; do not force it with `.get` or invent a default.
- Keep representation changes in `.withFieldComputed`, such as converting attribute name/value entries to a map. Let Chimney derive the unchanged fields.
- If the feature explicitly allows unusable preview entries to be omitted, use `.transform.asOption` and collect the successful results. This is a preview policy, not a general way to discard validation or persistence errors.
- Compute diagnostics from the original source before collecting successful mappings. A retained item must still explain omitted variants and expose the required attention state.
- Keep pure business computations in named helpers, then bind their results with `.withFieldConst`. Do not bury note-generation or attention rules inside transformer overrides.

## Enums

- Mirrored domain and Smithy enums have identical `UpperCamelCase` cases and transform directly with `.transformInto[TargetEnum]`.
- Use the direct target case for a fixed compile-time value, for example `smithy.CustomerType.Individual`. Use `.transformInto[smithy.CustomerType]` for a runtime source value.
- Never convert a mirrored enum through its string value, `fromString`, casting, or pattern matching.
- Never add named domain-to-Smithy or Smithy-to-domain helpers for a one-to-one enum.
- JSON wire parsing is not a model transformation: a codec may use the generated Smithy enum's `fromString` and `stringValue`. Reuse those APIs instead of duplicating every enum case with pattern matching.

## Iron refined products

- Import `io.github.iltotore.iron.chimney.given` for Iron's Chimney support, including transforming an Iron `RefinedType` whose base is a case class into a different product target (Iron 3.4.1 + Chimney 2.1.0). Import it in every file that performs such a transformation, test code included; the gateway has no own refined-product bridge.
- With Iron's givens in scope, transform the refined value, collection, or complete owning product directly. Do not access `.value`, add a base-type ascription/cast, or define a one-off `Transformer` for the refined product.

## Explicit transformers

- Do not introduce a bespoke `Transformer` given or trait for one missing field at one call site; customize that call inline.
- When a shared test-arbitraries trait feeds many implicit derivations that reach the same genuinely non-derivable element, including nested batches, define one named element transformer in that trait and reuse it.
- Smithy arbitrary givens derive from the matching domain arbitrary with Chimney. Keep the outer ScalaCheck `.map` because it transforms an `Arbitrary` value; transform the sampled domain model inside it.

## Tests and boundaries

- Build expected mappings independently and field-by-field when testing custom mapping logic. Using a transformation to arrange test input is allowed.
- Mirrored enum expectations are the exception: use the direct Smithy case for a fixed expected value and Chimney for a runtime-variable expected value because there is no custom mapping logic to test.
- When a Tapir endpoint genuinely needs a separate endpoint-owned response wrapper, transform it inline in `serverLogic`; do not move the transport wrapper into the service or add a private mapping helper.
