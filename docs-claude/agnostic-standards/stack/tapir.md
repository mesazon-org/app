# Alternate transport — the escape hatch beside the primary API contract

Project-agnostic standards for the narrow, deliberate exception to a contract-first API: the handful of routes the primary contract mechanism can't express (a streaming binary body with its own entity-size limit is the recurring example), implemented directly against a lower-level HTTP library instead of being generated. Written around [Tapir](https://tapir.softwaremill.com/) (`sttp.tapir`) but applies to any framework used the same way.

Not owned here: the primary API-contract mechanism and how it declares auth/role requirements on a normal operation ([smithy-new.md](smithy-new.md)); the central middleware whose checks this transport must replicate by hand, since the middleware does not run in front of it ([middleware-new.md](../middleware-new.md)); general Scala/type naming ([scala-new.md](scala-new.md)) — this document only adds the alternate-transport-specific naming on top.

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to this escape hatch. Concrete values for this codebase: [tapir-project.md](tapir-project.md).

## File layout

Split the alternate transport's code into two kinds of files, and give every new endpoint the same split: a **shared helpers file** (the security base, error-rendering/decode-failure handling, a swagger role-description helper, shared type aliases/server options every endpoint reuses) and a **per-feature endpoint file** (the endpoint definitions plus the wiring function that resolves services and builds the runnable server endpoints).

## Error model — parallel to the primary contract's, not shared with it

The alternate transport defines **its own error enum**, mirroring the primary contract's error taxonomy one case at a time rather than reusing its generated error types, mapped from the domain service error by a dedicated function that mirrors the primary contract's own error-mapping function case-by-case.

**Whenever a domain error subtype is added or re-homed, update both error handlers together.** The primary contract's handler gets a compile-time nudge — a generated operation's declared error list won't compile if a case is missed — but the alternate transport's handler is a plain `match` with no such check. A missed case there is a silent runtime gap, not a build failure, so it needs to be caught by discipline (and ideally a test), not the compiler.

## Security is hand-wired to mirror the middleware

Routes on this transport are **not** covered by the central middleware: each endpoint wires its own auth directly, using the shared security base's typed security inputs (bearer token, tenant-id header) — replicating exactly what a middleware-gated operation with the equivalent onboard-stage and role annotations gets automatically.

**When a middleware rule changes, update every alternate-transport endpoint's security logic by hand to match.** This is the transport's single biggest correctness trap: nothing enforces that the hand-wired checks stay in sync with the middleware's rules, so a middleware change silently stops applying to these routes unless someone remembers to mirror it.

## Swagger/OpenAPI integration

The primary contract's codegen does not produce an OpenAPI spec for routes on this alternate transport, so the transport must generate its own, served from a plain docs endpoint that mimics the primary contract's generated doc path. The alternate-transport service is still listed in the shared Swagger UI alongside the primary contract's services, by passing its doc ID into the same route-mounting call the primary contract's docs use.

**This alternate-transport docs route must be mounted before the primary contract's swagger routes** in the app's route wiring — otherwise the primary contract's routing intercepts the path first and fails trying to load a classpath spec that doesn't exist there.

Since the role check for one of these endpoints runs inside its hand-wired security logic — invisible to OpenAPI generation — add an explicit description to the endpoint listing the same roles the security logic enforces, using a shared helper. The helper renders the same required-roles marker used in the primary contract's operation doc comments, so the two sets of docs can't drift apart in how they describe a role requirement.

## Schema/codec quirk — one named schema per error variant

The alternate transport's error enum needs **one explicitly named schema per case**, so each status code renders as a distinct OpenAPI component instead of collapsing into one generic error shape. Name each case's schema after the equivalent generated response-content name from the primary contract, so both sets of swagger docs expose consistent component names for the same logical error. A separate fallback schema covers paths — like the decode-failure handler — where the specific variant isn't known ahead of time. The codec should round-trip through the stable code field, not the display name, and should fail loudly on an unrecognized code rather than silently defaulting to some case.

## Entity size limits are transport-specific

Configure a distinct maximum entity size for the alternate transport's routes, separate from the primary contract's limit, when the transport carries larger payloads than the contract's default.

**Keep the transport's max-entity-size constant in sync with its corresponding config value** — the two are not derived from one another, so a config change that isn't mirrored in the transport's limit leaves the two silently disagreeing.

## Naming

Name the alternate transport's error cases and codes the same way the primary contract's naming already establishes a pattern for: `PascalCase` with an `Error` suffix for the case, `SCREAMING_SNAKE_CASE` for the wire code.
