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
- Set expectations before building the service, with exact argument values/counts, including default arguments; no wildcards. Whenever a test sets expectations on 2+ mocks (same or different dependency), wrap them in `inSequence` — functional tests must prove call order, not just that each mock was called and the final output is correct.
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

Follow [Acceptance testing](../../project/acceptance-testing.md), the single source of truth for acceptance structure, endpoint matrices, middleware cases, naming/layout, harness wiring, clients/codecs, assertions, review, and verification.

The service slice is complete only when every endpoint's applicable acceptance matrix passes against the real gateway and dependencies. Update the feature doc with exact completed and remaining cases; never remove or falsely complete its status.

If the feature added an external dependency, credential, or required env var (a new bucket, third-party API key, etc.), also update terraform in this PR — see [Terraform](../../project/terraform.md).
