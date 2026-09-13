# Functional testing

Mocked-dependency proof for one gateway service. Use with the current feature doc, [Service flow](../features/flow/05-service.md), and [Scala](../standards/scala.md).

## Boundary

Functional tests drive the generated `ServiceTask` interface directly, with every dependency (repositories, clients, `AuthState`, clock, ID/OTP generators) replaced by a strict mock. No HTTP, no database, no Docker. They prove orchestration — the service's own validation, decisions, mappings, and call sequencing — not what a real dependency does when called.

Feature specs live in `backend/gateway/core/src/test/scala/io/mesazon/gateway/fun/<Feature>ServiceSpec.scala`.

## Harness and setup

- Extend `ZWordSpecBase` plus the feature's/Row's/token arbitraries.
- Every test uses a fresh `TestContext`; provide `<Feature>Service.local`, real request/domain validators and validator config, and strict mocks for every other dependency. Never use `.live`/`.observed` — those wire production error translation/logging, which acceptance tests already exercise over real HTTP.
- ZIO test helpers: `.zioValue`, `.zioError`, `.zioEither`, `.zioCause`, `Ref#refValue`, `counterRef`. Stub helpers: `.returningZIO`, `.returnsZIOUnit`, `.failingZIO`, `.dyingZIO`, or plain `.returns(effect)` when the effect itself is under test.
- Parameterless effects use eta expansion: `(() => authState.get).expects()`.

## Structure and naming

```scala
"<Feature>Service" when {
  "<operation>" should {
    "successfully <observable outcome>" in new TestContext { ... }
    "fail with a[n] <ConcreteError> when <condition>" in new TestContext { ... }
  }
}
```

One block per operation; successes (including no-ops) first, then every owned failure branch. Naming otherwise follows [Scala](../standards/scala.md)'s general test structure/naming rules (exact model names, qualifiers last, `fail with a[n] <concrete error>` phrasing).

## Expectations and sequencing

- Set expectations before building the service, with exact argument values/counts, including default arguments — no wildcards.
- Whenever a test sets expectations on two or more mocks (the same dependency called twice, or different dependencies), wrap them in `inSequence`. A functional test must prove call *order*, not just that each mock was eventually called and the final output happened to be correct.
- Happy requests originate as domain arbitrary values and transform to Smithy; use the original domain value (or its Chimney repository input) as the exact mock expectation.
- Invalid requests originate as Smithy values with only the tested field(s) changed; assert the complete `ValidationError`.
- A validation-failure case sets no downstream expectations, proving the repository/client is untouched.
- Dependency failures propagate the same `ServiceError` unchanged — test one generic instance propagating, not every subtype; test a specific subtype only when the service handles it differently (retries it, translates it, counts it, etc.). For tolerated/retried failures, count invocations and assert both the eventual successful response and `maxRetries + 1` calls.

## Known limitation: generic methods with typeclass `using` bounds can't be mocked

ScalaMock's `mock[T]` macro cannot correctly mock a method shaped like `def m[A](...)(using OpenAIJsonSchema[A], JsonValueCodec[A]): F[A]` (a type parameter plus typeclass `using` parameters depending on it) — e.g. `AIClient.extractFromImage`; the unchanged `OpenAIClient.sendMessage` has the same generic-method shape with ordinary `Schema[A]`. Verified against ScalaMock 7.5.5's own `MockMaker.scala` source, not a syntax mistake: the macro's generated override erases `A` to `Any`, so the `using` clause becomes `OpenAIJsonSchema[Any]`/`JsonValueCodec[Any]` in the generated class, which then fails to resolve (or resolves ambiguously against unrelated concrete givens) rather than picking up the real `OpenAIJsonSchema[ExtractCustomersResponse]`/`JsonValueCodec[ExtractCustomersResponse]` the call site actually needs. This is the same reason this codebase's one prior attempt to test `OpenAIClient.sendMessage[A]` (in `ReplyingToMessagesCronJobStreamSpec`) is left fully commented out rather than using `mock[OpenAIClient]`.

Resolution: for a dependency with this method shape, use a small hand-written test double implementing the trait directly instead of `mock[T]`, backed by `Ref`s to capture the exact arguments received per call and to hold the canned response/failure, then assert on the captured `Ref` value the same way a `.expects(...)` call would, keeping the "no wildcards" spirit without ScalaMock's DSL. Every other dependency without this method shape stays a normal ScalaMock mock in the same spec.

These hand-written doubles live in their own `mock/Mocks.scala` file (package `io.mesazon.gateway.mock`, `object Mocks`), one member per dependency that needs this treatment — e.g. `Mocks.AIClientMock` for `AIClient.extractFromImage` — kept separate from the feature spec that uses them so the workaround doesn't clutter the spec itself.

## Config and time

Hardcode config copies from `application.conf` directly in the spec; update the test when the config changes — do not load the real config file. Pin time to millisecond precision and respect strict time-boundary assertions (see [Scala](../standards/scala.md)'s guidance on excluding equality-producing random offsets near a branch boundary).

## Coverage expectations

Each operation covers: happy path; validation where applicable; missing data; unchanged dependency failures; and every *service-owned* attempt/lockout/cooldown/retry/negative-side-effect branch. "Service-owned" means the service's own code branches on it — a `case`/`if`/retry count/counter comparison that the service itself evaluates.

Assert full Smithy responses, exact missing-entity messages, and all branch state (mocks called or not, counters incremented or not).

### Anti-pattern: behavior owned by a mocked dependency

Do not add a case whose only distinguishing behavior lives inside a mocked dependency rather than in the service's own branching. A mock returns exactly what the test tells it to return, regardless of how the scenario is named or arranged — so a case like this cannot exercise the real differentiating behavior; it only re-proves a generic success/propagation case already covered elsewhere in the same block.

Worked example: a phone-number uniqueness rule was added to `POST /onboard/details`, enforced entirely by a named Postgres unique constraint — the service has no branch that distinguishes "the caller's own number" from "any other successful update"; it just calls `updateUserDetails` and propagates whatever comes back. A functional test named `"successfully onboard details when the phone number given is already the caller's own"` mocked `updateUserDetails` to return success either way, so it was byte-for-byte identical to the suite's existing generic success test — same mock, same expectation shape, same assertions. It was removed. The behavior it was trying to name is real, but it only exists at the layer where the constraint actually runs:

- a repository/integration test proves the constraint itself (conflict on a different account's number, success updating a row to the value it already holds);
- an acceptance test proves the end-to-end scenario over real HTTP and real Postgres (the exact case this functional test was redundantly attempting).

Before adding a functional-test case, ask: if I told the mock to return success/failure regardless of which value I pass it, would this test still pass and still look different from an existing one? If not, the case belongs at the repository/integration or acceptance layer instead, not here.

## Review checklist

Before completion, inspect the spec from top to bottom and confirm:

- every operation appears exactly once as a `should` section, successes before failures;
- every test builds its own `TestContext` and mocks — no shared fixtures;
- every multi-mock test uses `inSequence` with exact argument values;
- every validation-failure case proves no downstream mock was touched;
- every dependency-failure case propagates the correct `ServiceError`, and retried/tolerated failures assert exact invocation counts;
- no case's only distinguishing behavior lives inside a mocked dependency's configured return value (see the anti-pattern above);
- config values are hardcoded copies of `application.conf`, kept in sync;
- time/random/IDs are pinned wherever a boundary or ordering assertion depends on them;
- the feature doc's functional-test coverage statement is current.

## Verification

```sh
sbt "gateway-core/testOnly io.mesazon.gateway.fun.*"
sbt "runLint"
```
