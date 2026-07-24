# Functional tests (`backend/gateway/core/src/test/scala/io/mesazon/gateway/fun`)

White-box tests of **one service implementation with every effectful dependency mocked** — no HTTP, no DB, no docker. They drive the service's `ServiceTask` interface directly and prove its *orchestration*: that validation is wired in, that the repository/clients are called with **exactly** the right arguments (org scoping, Chimney-mapped inputs, `Opt`/`Some` update semantics), that each branch returns/raises the right value, and that retries/counters behave. Business-logic branches belong here; acceptance specs (see [acceptance-tests.md](acceptance-tests.md)) prove the integrated wiring, and `unit/` specs cover validators and pure helpers. The general test-writing standards (naming, whole-model asserts, one `should` per operation, distinctness proofs) are in [scala.md § Testing](scala.md#testing) and apply here unchanged.

## Naming & scope

- One spec per service: `fun/<Feature>ServiceSpec` (`CustomerBookServiceSpec`, `UserSignInServiceSpec`, …). Non-endpoint machinery that lives in the service layer is also tested here (`AuthenticationServiceSpec` for the auth middleware service, `StreamAppSpec` for the cron-stream supervisor).
- Structure: `"<Feature>Service" when { "<operation>" should { "…" in new TestContext { … } } }` — one `should` block per operation holding its happy **and** failure paths.

## Harness

- Extend `ZWordSpecBase` plus the feature's arbitraries traits (`<Feature>SmithyArbitraries`, `RepositoryArbitraries`, `TokenArbitraries`, …). `ZWordSpecBase` = `WordSpecBase` (`AnyWordSpec` + scalamock `MockFactory` + ScalaTest matchers/`OptionValues`/`Eventually`/`LoneElement` + `arbitrarySample`) + scalamock `ZIOStubs` + `ZIOTestOps`.
- `ZIOTestOps` runs effects synchronously and adapts scalamock to ZIO:
  - `.zioValue` (run, expect success), `.zioError` (run, expect typed failure), `.zioEither`, `.zioCause`, `Ref#refValue`, and `counterRef` (a fresh `Ref.make(0)` effect for counting invocations, e.g. proving a retry count).
  - Call-handler helpers: `.returningZIO(value)` / `.returnsZIOUnit` / `.failingZIO(error)` / `.dyingZIO(throwable)`; use plain `.returns(effect)` when the effect itself matters (a counter increment `*>` a failure).

## TestContext — fresh mocks per test

Every spec ends with a `trait TestContext`; each test opens with `in new TestContext` so mocks and config are rebuilt per test (test isolation — nothing shared, see scala.md).

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

- **Build `.local`, never `.live`.** The `observed` wrapper only translates `ServiceError` → smithy error responses (proved end-to-end by acceptance tests); fun specs assert the raw `ServiceError` with `.zioError shouldBe …`.
- **Real validators, mocked everything else.** The `<Feature>RequestValidator` + domain validators (`EmailValidator`, `PhoneNumberDomainValidator` with `PhoneNumberUtil` and a hardcoded `PhoneNumberValidatorConfig(Set("CY", "GB"))`) are provided live — mocking them would just restate the test. Repositories, clients, `AuthState`, `JwtService`, `TimeProvider`, `IDGenerator`, `OtpGenerator` are scalamock mocks provided via `ZLayer.succeed(mock)`.
- **Config is a hardcoded copy of `application.conf` values** (`UserForgotPasswordConfig(otpExpiresAtOffset = 60.seconds, …)`) — same gotcha as acceptance tests: change the conf and the spec's copy must change too. A `build…Service(isDev = false)` parameter + `config.copy(...)` is the pattern for config-dependent branches.
- **Time is pinned**: `val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)` in `TestContext`, returned by the `TimeProvider` mock (truncated because Postgres/JSON round-trips are millis; irrelevant here but kept consistent). Mind the strict-boundary rule in scala.md when deriving offsets from `instantNow`.

## Expectations

- Set expectations **before** building the service; wrap ordered flows in `inSequence(...)` — it proves call order, not just call count.
- `.expects(<exact argument values>).returningZIO(result).once()` — always exact values, never wildcards: the argument match *is* the assertion that the service transformed/scoped its inputs correctly. Methods with default parameters still need every argument spelled out (see `updateOrganization`'s ten `None`s in `FileServiceSpec`).
- No-arg/parameterless methods use the eta-expanded form: `(() => authStateMock.get).expects().returningZIO(authedUser).once()`.
- **A validation-failure test sets no expectations at all** — scalamock mocks are strict, so an unexpected repository call fails the test; that's the "never reaches the repository" proof.
- Dependency failures: `.returns(ZIO.fail(serviceError))` (or `.failingZIO`) and assert the same instance propagates: `….zioError shouldBe serviceError`.
- Tolerated failures with retries (e.g. a confirmation email that must not fail the request): `.returns(counter.incrementAndGet *> ZIO.fail(...))`, assert the happy response **and** `counter.get.zioValue shouldBe maxRetries + 1`.

## Building requests & expected values

- Happy path: sample the **domain request** (`arbitrarySample[InsertCustomerIndividualPostRequest]`) and send `request.transformInto[smithy.…]` using the transformers in the feature's `…SmithyArbitraries` — the validator round-trips it, so the sampled model itself is the expected value for the repository `.expects(...)` (Chimney-map it to the `…Input` where the repo takes inputs).
- Failure path: sample the **smithy request** (`arbitrarySample[smithy.…Request]`) and `.copy` exactly the one bad field (`fullName = ""`); assert the **whole** `ValidationError(invalidFields = List(InvalidFieldError(...)))`.
- Rows/derived models are sampled and `.copy`-pinned to the ids they must share (`userDetailsRow.copy(userID = authedUser.userID)`); allowed-stage sets are drawn with `Random.shuffle(OnboardStage.completedStages).zioValue.head` so any allowed stage proves the gate.
- Assert responses as **whole smithy models** (`shouldBe smithy.SignInPostResponse(...)` with every field built out).

## What every operation's section must cover

1. **Happy path** — the exact ordered dependency calls and the full response model (or `.zioValue shouldBe ()` for 204-style `Unit` operations).
2. **Every failure branch the service owns** — validation error (repo untouched), missing entity → the exact `UnexpectedError` message, dependency error propagating unchanged, attempt/lockout/cooldown branches, tolerated-failure branches with their retry counts.

Run them with `sbt "gateway-core/testOnly io.mesazon.gateway.fun.*"` — no containers, seconds not minutes.
