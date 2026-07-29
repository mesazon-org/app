# PR 5 — Service implementation

Combine the endpoint, validation, schema, and repository layers into the working service. Add orchestration, endpoint implementation/wiring, functional tests, and black-box acceptance tests. Read [Scala](../../standards/scala.md), the feature doc, and the preceding applicable flow guides.

## Implementation boundary

- `service/<Feature>Service.scala` implements the generated Smithy service; a Tapir feature supplies its handler/security wiring through its endpoint module.
- Normal authenticated handler flow: `AuthState.get → validate → map domain request to repository input → repository/client calls → map Rows/domain values to Smithy response`.
- Auth/role/onboard checks belong to middleware/security declarations and `AuthState`, not hand-written credential checks in handlers. For auth machinery changes read [Authentication](../../project/authentication.md).
- Use `.local` for the raw `ServiceTask` implementation. `.observed` translates/logs `ServiceError` as Smithy errors; `.live` is production wiring.
- Wire the service, validator, repository/queries, clients, and generated routes into the application graph. Preserve exact organization scoping and `OptUpdate` semantics.
- Every declared contract error must map to the intended HTTP status. Missing referenced rows follow existing feature policy; record non-obvious behavior in the feature doc.

## Functional tests: one service, dependencies mocked

File: `gateway/core/.../fun/<Feature>ServiceSpec`. No HTTP, database, or Docker. Drive the generated `ServiceTask` interface and prove orchestration.

- Extend `ZWordSpecBase` plus feature/Row/token arbitraries.
- Its ZIO test helpers are `.zioValue`, `.zioError`, `.zioEither`, `.zioCause`, `Ref#refValue`, and `counterRef`; stubs use `.returningZIO`, `.returnsZIOUnit`, `.failingZIO`, `.dyingZIO`, or plain `.returns(effect)` when the effect itself is under test.
- Structure: `"<Feature>Service" when { "<operation>" should { ... } }`; one block per operation contains success and every owned failure branch.
- Every test uses a fresh `TestContext`; provide `<Feature>Service.local`, real request/domain validators and validator config, and strict mocks for repositories, clients, `AuthState`, clock/generators, etc. Never use `.live`/`.observed`.
- Hardcode config copies from `application.conf`; update tests when config changes. Pin time to millisecond precision and respect strict time-boundary assertions.
- Set expectations before building the service. Use `inSequence` for ordered flows and exact argument values/counts, including default arguments; no wildcards.
- Parameterless effects use eta expansion (`(() => authState.get).expects()`).
- A validation-failure case sets no downstream expectations, proving the repository/client is untouched.
- Dependency failures propagate the same `ServiceError`. For tolerated/retried failures, count invocations and assert both successful response and `maxRetries + 1`.
- Happy requests originate as domain arbitrary values and transform to Smithy; use the original domain value (or its Chimney repository input) as the exact mock expectation.
- Invalid requests originate as Smithy values with only tested fields changed; assert the complete `ValidationError`.
- Assert full Smithy responses, exact missing-entity messages, and all branch state.

Each operation covers: happy path; validation where applicable; missing data; unchanged dependency failures; and every service-owned attempt/lockout/cooldown/retry/negative-side-effect branch.

Run:

```sh
sbt "gateway-core/testOnly io.mesazon.gateway.fun.*"
```

## Acceptance tests: real app over HTTP

File: `backend/gateway/it/.../<Feature>ApiSpec`. Harness types live in `io.mesazon.gateway.it.harness`; feature specs remain in `io.mesazon.gateway.it`. The shared `backend/gateway/it/compose.yaml` stack supplies gateway, PostgreSQL+Flyway, MailHog, Wiremock, and S3; nothing inside the app is mocked.

Harness rules:

- `@DoNotDiscover class <Feature>ApiSpec extends GatewayAcceptanceTest, <FeatureArbitraries>`.
- Add `new <Feature>ApiSpec` to `GatewayAcceptanceSpec`; never give a child spec its own Docker/context/lifecycle. The parent boots one stack and waits for gateway plus every `RepositoryConfig.allTableNames` table.
- The table wait belongs in the parent's `afterContainersStart`; readiness alone may precede Flyway. Reset belongs in each nested spec's shared `GatewayAcceptanceTest.beforeEach`, because a `Suites` parent has no tests on which its own `beforeEach` could run.
- Keep `gateway-it / Test / parallelExecution := false`: nested suites share the database.
- Add required clients/Queries to `GatewayItContext.build`. Use `withContext`; arrange/read through production Queries, call HTTP through `GatewayClient`, and inspect MailHog/S3 where relevant.
- Shared `beforeEach` truncates all tables and resets external services. Tests must remain independent.
- Structure: `"<Feature> API" when { "<METHOD> /path" should { ... } }`.
- Order tests by HTTP status: happy 200/204, then 400, 401, 403, 409, 500, 503. Within one code use a sensible order.

Per endpoint, prove:

1. Happy status + full body + complete DB/external state.
2. Business edges end-to-end and their follow-up state.
3. Every applicable standard error below; route wiring is per endpoint, so no case is “covered elsewhere.”
4. After every rejection, all prohibited DB/email/storage effects are absent.

| Case | Result | Applies |
|---|---|---|
| Invalid field/body | `400 ValidationError` with exact fields | endpoint has fallible input |
| Missing organization header | `400 BadRequest` | organization-role trait |
| Missing credentials/token | `401 Unauthorized` | authenticated endpoint |
| Invalid token | `401 Unauthorized` | bearer endpoint |
| Token row missing | `401 Unauthorized` | refresh/reset token lookup |
| Wrong OTP | `400 BadRequest` | OTP verify |
| Expired OTP | `401 Unauthorized`, OTP deleted | OTP verify |
| Disallowed onboard stage | `403 Forbidden` | stage gate |
| Disallowed organization role | `403 Forbidden` | organization-role trait |
| Conflict | `409 Conflict` | endpoint declares conflict |
| Missing user-details row | `500 InternalServerError` | completed-stage gate |
| Not an organization member | `500 InternalServerError` | organization-role trait |
| Referenced entity missing | `500 InternalServerError` | lookup-by-ID behavior |

For the common bearer + completed-stage + organization-role endpoint, isolate and include all eight gates: validation, missing organization header, missing token, invalid token, disallowed stage, disallowed role, missing user details, and missing membership. Seed every unrelated prerequisite validly. Choose allowed/disallowed stages and roles from their sets/complements; do not hardcode `Owner`. Assert untouched endpoint tables for every rejection.

Acceptance specifics:

- Expected error type is the `GatewayClient` type parameter.
- Specs run only through `sbt "gateway-it/test"` or `testOnly *GatewayAcceptanceSpec`; `testOnly *<Feature>ApiSpec` does not discover them.
- A required-list request codec must use `JsonCodecMaker.make[T](CodecMakerConfig.withTransientEmpty(false))`; otherwise empty lists are omitted and fail Smithy required-field decoding before validation. When diagnosing a 400, assert `.fields`, not `toString`.
- Config limits copied into specs must change with `application.conf`.

Run:

```sh
sbt "gateway-it/test"
```

Finally update the feature doc to completed or list exact remaining slices/tests; never remove or falsely complete it.
