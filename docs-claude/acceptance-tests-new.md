# Acceptance tests

Acceptance tests are black-box tests of the **real running application and all its real dependencies**, brought up in containers, driven purely over HTTP. Nothing is mocked inside the application; if a test passes here, the wiring, the transport's request/response serialization, the middleware, the config, and the SQL all work together. This document defines the project-agnostic standard for that tier: the one shared stack the whole run boots, the source-ordering convention for test cases, the mandatory middleware-gate matrix, and what a feature's spec must cover. It applies regardless of the HTTP framework, container-orchestration tool, or transport-serialization library a given project uses.

## Scope

This document owns the **project-agnostic whole-app-over-HTTP test tier**:

- one shared container stack for the entire acceptance run, booted once and reused by every nested spec;
- the source-ordering convention for test cases within a block — by response status code, ascending, with the exact `403`-before-`500` rule;
- the standard error matrix every endpoint proves, and the full middleware-gate checklist that is **mandatory** on every authenticated endpoint;
- asserting negative side effects (what must **not** have happened after a rejected/failed call);
- what a feature's acceptance spec must cover end to end.

It does **not** own:

- **General test naming, structure, and assertion rules** (test naming, whole-model asserts, one behaviour block per operation, success-before-failure ordering, distinctness proofs, determinism) — see [stack/scala-new.md](stack/scala-new.md#testing-standards).
- **How the auth/onboarding/organization-role gates are actually enforced** — the mechanism this tier's error matrix proves end-to-end — see [middleware-new.md](middleware-new.md).
- **The mocked-dependency service tier** (one service, every dependency mocked, no containers) — see [functional-tests-new.md](functional-tests-new.md).
- **The real-single-dependency tier** (one repository or client against its real dependency, no HTTP, no full app) — see [integration-tests-new.md](integration-tests-new.md).

## Table of contents

- [Scope](#scope)
- [Harness](#harness)
- [What a feature's acceptance spec must cover](#what-a-features-acceptance-spec-must-cover)
- [Standard error matrix — repeated on (almost) every endpoint](#standard-error-matrix--repeated-on-almost-every-endpoint)
- [The middleware gates are MANDATORY on every endpoint — never skip them](#the-middleware-gates-are-mandatory-on-every-endpoint--never-skip-them)
- [Gotchas](#gotchas)

## Harness

**One shared stack for the whole acceptance run.** A single aggregator suite — a "suite of suites" that boots the container stack (the app itself, plus its database, mail catcher, mock HTTP server, object storage, and any other real dependency the app talks to) — is the **only discoverable suite**. It boots the stack **once**, nests every per-feature API spec inside itself, waits once for the app to report ready **and** for every expected table to exist in the database, builds a shared test context (clients + query objects wired against the running stack), and injects that context into each nested spec. Booting one stack instead of one-per-spec is what keeps the run stable — a per-spec container-boot pattern multiplies the number of full stacks started and starves the runner (readiness timeouts, dropped connections).

- Per-feature specs are named `<Feature>ApiSpec`, are marked **not independently discoverable** by the test framework (so the framework's own discovery mechanism never tries to run one standalone outside the aggregator), and mix in the shared acceptance-test trait plus their own arbitrary/sample-generation traits. They do **not** own their own container lifecycle, context-building, or per-suite setup hooks — all of that is global, owned by the aggregator.
- **Running them:** because per-feature specs opt out of standalone discovery, they run only through the aggregator — the project's "acceptance tests" test target (or targeting the aggregator suite by name). Targeting a per-feature spec directly will **not** run it in isolation.
- **Sequential execution is mandatory** for the whole acceptance run: all specs share one database, so the test runner must be denied any parallel-suite distributor — otherwise nested suites would run concurrently against the same shared state.
- **Adding a spec:** write the new per-feature spec extending the shared acceptance-test trait plus its arbitraries, then add it to the aggregator's nested-suite list. If it needs a client or query object not already exposed, add the field to the shared test context and wire it where that context is built.
- HTTP calls go through the project's generated/shared API client for this app (whatever wraps the HTTP call + request/response codec for the contract's request/response types). The expected *error* type is typically passed as a type parameter or discriminator alongside the call, e.g. `apiClient.signInPost[Unauthorized](...)`.
- **Arrange and assert directly against the database**: a `withContext { context => ... }`-style helper hands back the shared test context with a real database test client plus the app's production query classes — rows are seeded with sampled-and-customized row models inserted via the real insert queries; side effects are read back with the project's read-all-for-testing queries.
- Emails are asserted through the mail-catcher's test client; object-storage writes through the storage test client.
- The readiness/table-existence wait lives in the aggregator's own start-up hook (the app can report ready before its schema migrations have finished; without the table wait, the first per-test truncation aborts on a slow machine with a "relation does not exist" error). The shared acceptance-test trait's per-test setup then gives **every test full isolation across the shared stack**: using the injected context, it truncates **all** tables **and** resets each external test double (clears the mail inbox, empties all storage buckets) — tests stay independent and order-insensitive. (This per-test reset is a per-suite hook, so it lives in the trait each per-feature spec mixes in — the aggregator itself has no tests of its own, so a reset hook placed on the aggregator would never fire for the nested specs.)
- Structure: `"<Feature> API" when { "<METHOD> /path" should { "..." in withContext { ... } } }`.
- **Order tests within a `should` block by the response's HTTP status code, ascending — sort by status code, not by the order you happened to write them.** The contract's error type name fixes its code, so map name → code first, then sort by the code:

  | Contract error type (the type param / discriminator passed to the client) | Status code |
  |---|---|
  | *(happy path — no error type)* | `200`/`204` |
  | `ValidationError`, `BadRequest` | `400` |
  | `Unauthorized` | `401` |
  | `Forbidden` | `403` |
  | `InternalServerError` | `500` |

  So a full block reads: happy path first, then `400` → `401` → `403` → `500`. **`Forbidden` (403) always comes *before* `InternalServerError` (500)** — do not append a new test to the bottom of the block; insert it at the position its status code demands. When several cases share a code (e.g. multiple `400`s), keep a sensible sub-order among them. This is a source-ordering convention only — execution is order-insensitive (per-test truncation resets every table) — but a correctly sorted block makes a missing error case obvious at a glance, so re-check the whole block every time you add or move a test.

*Concretely in this codebase:* the harness classes live in `io.mesazon.gateway.it.harness` (`GatewayAcceptanceSpec`, `GatewayAcceptanceTest`, `GatewayItContext`, and the `appNameLive` helper); the `<Feature>ApiSpec`s stay in `io.mesazon.gateway.it`. The aggregator is `GatewayAcceptanceSpec`, a `Suites(...)` extending `DockerComposeBase`; per-feature specs are annotated `@DoNotDiscover` and mix in `GatewayAcceptanceTest`. HTTP calls go through `client/GatewayClient.scala` (sttp + jsoniter codecs). `withContext { context => import context.* ... }` exposes `PostgreSQLTestClient` plus the production `*Queries` classes; emails go through `MailHogClient`, S3 objects through the `s3-test` module. See [CustomerBookApiSpec](../backend/gateway/it/src/test/scala/io/mesazon/gateway/it/CustomerBookApiSpec.scala) for the reference implementation.

## What a feature's acceptance spec must cover

1. **Happy path per endpoint** — status code, full response body, *and* the resulting database state (rows created/updated/deleted, e.g. "sign in leaves exactly one refresh-token row and zero attempt rows").
2. **Business edge cases end-to-end** — resend cooldowns, attempt limits/lockouts, anti-enumeration responses, OTP expiry. Follow-up state matters: e.g. after N failed sign-ins even the *correct* password must fail.
3. **The standard error matrix** (see below).
4. **Negative side effects** — after a failed call, assert what must *not* have happened (no token rows, no attempt rows, no emails).

## Standard error matrix — repeated on (almost) every endpoint

These cases are deliberately duplicated per endpoint; the middleware is shared, but each route's wiring is not, so each endpoint proves its own gate. When adding an endpoint, copy this checklist:

| Case | Expected | Applies to |
|---|---|---|
| Invalid request body / field | `400` `ValidationError(fields = List(...))` | all endpoints with input |
| Missing credentials/token (no basic auth / no bearer header) | `401 Unauthorized` | all authed endpoints |
| Invalid/garbage token | `401` `Unauthorized()` | all bearer endpoints |
| Valid token but token row not in DB | `401 Unauthorized` | refresh/reset token endpoints |
| User in a disallowed onboard stage | `403 Forbidden` | all stage-gated endpoints |
| Missing tenant/organization-scope header | `400 BadRequest` | all endpoints restricted by allowed organization roles |
| Organization member with a disallowed role | `403 Forbidden` | all endpoints restricted by allowed organization roles |
| Not an organization member (no membership row) | `500 InternalServerError` | all endpoints restricted by allowed organization roles |
| Valid token but no user-details row | `500 InternalServerError` | all endpoints gated on completed onboard stage |
| Wrong OTP | `400 BadRequest` | OTP-verify endpoints |
| Expired OTP | `401 Unauthorized` (+ OTP row deleted) | OTP-verify endpoints |
| Referenced entity missing (OTP ID/user/token not found) | `500 InternalServerError` | lookup-by-id endpoints |

### The middleware gates are MANDATORY on every endpoint — never skip them

**Rule (for every feature we test, now and in future): each authenticated endpoint's `should` block must include the full set of middleware-gate cases, not just the happy path and the endpoint's own business errors.** The auth/onboarding/organization middleware wraps every route but is *wired per route*, so a missing gate on one endpoint is a real, untested hole — copy every applicable row below into each endpoint you add. Do not treat these as optional or "already covered elsewhere". Order them by status code ascending (happy → `400` → `401` → `403` → `409` → `500`), same as any other case.

For an endpoint that requires bearer auth + a completed onboard stage + a set of allowed organization roles (the common case — every mutating endpoint on a tenant-scoped feature; see [CustomerBookApiSpec](../backend/gateway/it/src/test/scala/io/mesazon/gateway/it/CustomerBookApiSpec.scala) for the reference implementation), that means **all** of:

1. **Validation** (`400 ValidationError`) — one bad field, fully authed otherwise; assert the exact `fields` and that nothing was written.
2. **Missing tenant/organization-scope header** (`400 BadRequest`) — valid token + completed stage, scope-header value absent.
3. **Missing token** (`401 Unauthorized`) — no access token supplied.
4. **Invalid token** (`401 Unauthorized`) — a garbage/malformed access token value.
5. **Disallowed onboard stage** (`403 Forbidden`) — user seeded in a non-completed stage (drawn from every stage minus the completed stages); seed a valid membership so the stage is the *only* fault.
6. **Disallowed organization role** (`403 Forbidden`) — membership seeded with a role drawn from the disallowed complement of the endpoint's allowed roles; completed stage so the role is the *only* fault. Draw the allowed role for every other test from the allowed list too (shuffle the allowed roles and take the head) — never hardcode a single "owner"-style role.
7. **No user-details row** (`500 InternalServerError`) — token minted for a random user id with no seeded user-details row (fails the completed-stage gate's lookup).
8. **Not an organization member** (`500 InternalServerError`) — user seeded with a completed stage but no membership row (fails the role gate's membership lookup).

Each middleware-gate test also asserts the **negative side effect** — after the rejected call, the endpoint's tables are untouched (the read-all-for-testing query returns an empty result). Isolate each gate's cause: make everything else valid so a failure can only be that gate. Drop a row only when the endpoint genuinely lacks that gate (e.g. a public or non-org-scoped route has no scope-header/role cases); add the endpoint's own business errors (e.g. `409 Conflict`) on top.

## Gotchas

- Some limits are asserted against hardcoded copies of the app's configuration values (e.g. a hardcoded max-attempts value mirroring the config's sign-in-attempts-max) — change the config and these tests must change too.
- These specs are slower than the mocked-dependency or single-real-dependency tiers (full container stack). Business-logic branches belong in the mocked-dependency tier's specs against mocks (see [functional-tests-new.md](functional-tests-new.md)); acceptance specs prove the integrated behavior and the error matrix.
- **CRITICAL — the acceptance HTTP client's request codecs for contract structures with a required list field must be built with the transport-serialization library's "don't omit empty collections" configuration.** Some JSON codec generators default to omitting an empty collection from the serialized JSON entirely — so a request with an empty required list is serialized *without that key at all*, the server sees a missing required field, and returns a `400` before the handler under test ever runs. This is a silent, *data-dependent* flake: any test that sends a sampled request (happy paths included) whose generator occasionally produces an empty list for that field will intermittently get a wrong `400` (a validation error with **empty `fields`**, since the failure is the transport's required-field check, not the app's own validator). Diagnosing it is nasty because the validation error's default string form often prints only a generic "Validation error" message — assert on the error's `fields`/details to see the real content. The fix is to configure the client's codec to be a compliant caller that sends the empty-list key explicitly (e.g. `"emails":[]`), which the server accepts. Copy this configuration for every new request codec that carries a required list. See the project's `[[jsoniter-transient-empty-required-lists]]` note for the concrete jsoniter-scala incantation (`JsonCodecMaker.make[T](CodecMakerConfig.withTransientEmpty(false))`) used in this codebase.
