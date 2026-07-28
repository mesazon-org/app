# Functional tests

Functional tests are white-box tests of **one service implementation with every effectful dependency mocked** — no HTTP, no database, no containers. They drive the service's effectful interface directly and prove its *orchestration*: that validation is wired in, that dependencies are called with **exactly** the right arguments, that each branch returns or raises the right value, and that retries/counters behave as designed.

## Scope

This document owns the **project-agnostic standard for the mocked-dependency service-test tier**:

- the scope boundary of this tier relative to the others: which behaviours belong here versus in a narrower or wider test layer;
- the harness shape — a service-spec base plus an effect/mocking adapter — and the fresh-mocks-per-test (`TestContext`) pattern;
- the expectation rules: exact-argument matching, ordering, and the "no expectations set" proof that a branch never reaches a dependency;
- how requests and expected values are built so they stay independent of the code under test;
- the checklist every operation's test section must satisfy.

It does **not** own:

- **General test naming, structure, and assertion rules** (test naming, whole-model asserts, one `should`/behaviour block per operation, success-before-failure ordering, distinctness proofs, strict-boundary offsets, independently derived expected values) — see [stack/scala-new.md](stack/scala-new.md#testing-standards).
- **The real-dependency tier** (repository/client tested against real infrastructure, no HTTP) — see [integration-tests-new.md](integration-tests-new.md).
- **The whole-app-over-HTTP tier** (real service + real dependencies, driven through the transport) — see [acceptance-tests-new.md](acceptance-tests-new.md).
- **Validator and pure-helper unit tests** — see [validators-new.md](validators-new.md).

## Table of contents

- [Scope](#scope)
- [Naming & scope](#naming--scope)
- [Harness](#harness)
- [TestContext — fresh mocks per test](#testcontext--fresh-mocks-per-test)
- [Expectations](#expectations)
- [Building requests & expected values](#building-requests--expected-values)
- [What every operation's section must cover](#what-every-operations-section-must-cover)
- [Running](#running)

## Naming & scope

- One spec per service implementation, named after the feature it belongs to (e.g. `<Feature>ServiceSpec`). Non-endpoint machinery that lives in the service layer is also tested here — a background supervisor or an internal authentication service gets its own spec the same way an endpoint-facing service does.
- Structure: `"<Feature>Service" when { "<operation>" should { "…" in new TestContext { … } } }` — one `should` block per operation holding its happy **and** failure paths. (This grouping convention is the general test-structure rule; see [stack/scala-new.md](stack/scala-new.md#test-intent-and-structure).)

## Harness

- Extend the project's service-spec base plus the feature's arbitraries traits. The service-spec base combines: the test framework's spec style, the mocking framework's mock factory, standard matchers/option-value/eventually/lone-element helpers, and a sample-from-arbitrary helper.
- An effect-test adapter runs effects synchronously and bridges the mocking framework to the project's effect type:
  - a "run and expect success" extension, a "run and expect a typed failure" extension, an "run and expect an `Either`" extension, a "run and expect a cause" extension, a mutable-ref "read current value" extension, and a "fresh zero counter" effect for counting invocations (e.g. proving a retry count).
  - Call-handler helpers that lift a plain value/unit/error/defect into the effect type for a mocked call's return, plus a way to hand the mock a plain effect when the effect itself matters (e.g. a counter increment composed with a failure).

## TestContext — fresh mocks per test

Every spec ends with a `trait TestContext`; each test opens with `in new TestContext` so mocks and config are rebuilt per test (test isolation — nothing shared, see [stack/scala-new.md](stack/scala-new.md#test-intent-and-structure)).

```scala
trait TestContext {
  val customerBookRepositoryMock = mock[CustomerBookRepository]

  def buildCustomerBookService: smithy.CustomerBookService[ServiceTask] =
    ZIO
      .service[smithy.CustomerBookService[ServiceTask]]
      .provide(
        CustomerBookService.local,               // the ServiceTask impl — NOT .live
        CustomerBookRequestValidator.live,       // real validator stack
        EmailValidator.live,
        PhoneNumberDomainValidator.live,
        PhoneNumberUtil.live,
        ZLayer.succeed(PhoneNumberValidatorConfig(supportedPhoneRegions = Set("CY", "GB"))),
        ZLayer.succeed(customerBookRepositoryMock),  // every effectful dep is a mock layer
      )
      .zioValue
}
```

- **Build the non-observable wrapper's inner impl, never the production-wired one.** A service is typically wrapped by an outer layer that only translates its internal error type into transport-facing error responses (proved end-to-end by acceptance tests); functional specs must wire the inner implementation directly and assert the raw internal error with the "expect a typed failure" extension — building the production-wired wrapper would hide the very orchestration this tier exists to prove.
- **Real validators, mocked everything else.** The feature's request validator plus the domain validators it depends on (email format, phone-number format, and similar effectful-but-non-trivial validation) are provided live — mocking them would just restate the test instead of proving orchestration through them. Repositories, clients, auth/session state, token services, time providers, ID generators, and one-time-code generators are all mocks provided through the dependency-injection layer.
- **Config is a hardcoded copy of the real configuration values** — the same gotcha as in the whole-app-over-HTTP tier: change the real config and the spec's copy must change too. A `build…Service(isDev = false)`-style parameter plus `config.copy(...)` is the pattern for exercising config-dependent branches.
- **Time is pinned**: a fixed "now" instant is captured once in `TestContext`, truncated to the precision the persistence/serialization layer round-trips at, and returned by the mocked time provider. Mind the strict-boundary rule in [stack/scala-new.md](stack/scala-new.md#test-data-and-assertions) when deriving offsets from that pinned instant.

## Expectations

- Set expectations **before** building the service; wrap ordered flows in the mocking framework's "in sequence" construct — it proves call order, not just call count.
- Expect **exact argument values**, never wildcards: the argument match *is* the assertion that the service transformed/scoped its inputs correctly. Methods with default parameters still need every argument spelled out — do not let a default parameter's value go unstated in the expectation.
- No-arg/parameterless methods use the mocking framework's eta-expanded expectation form so a zero-argument call can still be matched exactly once.
- **A validation-failure test sets no expectations at all** — mocks are strict, so an unexpected dependency call fails the test; that absence of an expectation *is* the "this branch never reaches the dependency" proof.
- Dependency failures: return a failed effect from the mock and assert the same error instance propagates unchanged through the service.
- Tolerated failures with retries (e.g. a side-effect that must not fail the request, such as a best-effort notification): have the mock's return effect increment a counter and then fail, assert the happy response **and** that the counter reached the expected retry count.

## Building requests & expected values

- Happy path: sample the **domain request** and transform it into the transport request using the feature's arbitraries/transformers — the validator round-trips it, so the sampled domain model itself is the expected value for a dependency's expectation (transform it further into whatever input shape that dependency takes, if different).
- Failure path: sample the **transport request** and `.copy` exactly the one bad field to an invalid value; assert the **whole** validation error, including every invalid field it carries.
- Rows or other derived models are sampled and `.copy`-pinned to the identifiers they must share with other sampled values in the test; when a test needs "any allowed value from a set" (e.g. any allowed stage), draw it by shuffling the set and taking the head, so any member of the set proves the gate rather than hardcoding one.
- Assert responses as **whole response models** with every field built out, not a partial/field-by-field assertion.

## What every operation's section must cover

1. **Happy path** — the exact ordered dependency calls and the full response model (or the effect-adapter's success assertion against unit, for no-content-style operations).
2. **Every failure branch the service owns** — validation error (dependency untouched), missing-entity branch with its exact error message, a dependency error propagating unchanged, attempt/lockout/cooldown-style branches, and tolerated-failure branches with their retry counts.

## Running

Run this tier with the project's "functional tests only" test target — no containers, seconds not minutes.
