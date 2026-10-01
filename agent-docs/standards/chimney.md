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

## Enums

- Mirrored domain and Smithy enums have identical `UpperCamelCase` cases and transform directly with `.transformInto[TargetEnum]`.
- Use the direct target case for a fixed compile-time value, for example `smithy.CustomerType.Individual`. Use `.transformInto[smithy.CustomerType]` for a runtime source value.
- Never convert a mirrored enum through its string value, `fromString`, casting, or pattern matching.
- Never add named domain-to-Smithy or Smithy-to-domain helpers for a one-to-one enum.

## Iron refined products

- Import `io.github.iltotore.iron.chimney.given` for Iron's Chimney support.
- Gateway code that transforms an Iron `RefinedType` whose base is a case class into a different product target also imports `io.mesazon.gateway.utils.ironRefinedTypeToTargetTransformer` (or `io.mesazon.gateway.utils.given` where that package's givens are imported together).
- The bridge's `TargetType <: Product` bound lets Chimney derive nested refined-product → product transformations without competing with Iron's primitive transformers.
- With the bridge in scope, transform the refined value, collection, or complete owning product directly. Do not access `.value`, add a base-type ascription/cast, or define a one-off `Transformer` for the refined product.

## Explicit transformers

- Do not introduce a bespoke `Transformer` given or trait for one missing field at one call site; customize that call inline.
- When a shared test-arbitraries trait feeds many implicit derivations that reach the same genuinely non-derivable element, including nested batches, define one named element transformer in that trait and reuse it.
- Smithy arbitrary givens derive from the matching domain arbitrary with Chimney. Keep the outer ScalaCheck `.map` because it transforms an `Arbitrary` value; transform the sampled domain model inside it.

## Tests and boundaries

- Build expected mappings independently and field-by-field when testing custom mapping logic. Using a transformation to arrange test input is allowed.
- Mirrored enum expectations are the exception: use the direct Smithy case for a fixed expected value and Chimney for a runtime-variable expected value because there is no custom mapping logic to test.
- When a Tapir endpoint genuinely needs a separate endpoint-owned response wrapper, transform it inline in `serverLogic`; do not move the transport wrapper into the service or add a private mapping helper.
