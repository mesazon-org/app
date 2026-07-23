# Acceptance tests (`backend/gateway/it`)

Black-box tests of the **real running gateway**: the app and all its dependencies run in docker compose (`compose/compose.yaml` — gateway, postgres + flyway migrations, mailhog, wiremock, s3, waha), and tests drive it purely over HTTP. Nothing is mocked inside the application; if a test passes here, the wiring, smithy/Tapir serialization, middleware, config, and SQL all work together. Every completed feature has one spec here (linked from its doc in `docs-claude/features/`).

## Harness

- Specs are named `<Feature>ApiSpec` (e.g. `UserSignInApiSpec`, `FileApiSpec`), extend `ZWordSpecBase, DockerComposeBase, SmithyArbitraries, RepositoryArbitraries, IronRefinedTypeTransformer`, and use the default `compose.yaml`.
- HTTP calls go through `client/GatewayClient.scala` (sttp + jsoniter codecs for the smithy request/response types). The expected *error* type is passed as a type parameter: `gatewayClient.signInPost[smithy.Unauthorized](...)`.
- **Arrange and assert directly against Postgres**: each spec builds a per-test `Context` (`withContext`) with `PostgreSQLTestClient` plus the production `*Queries` classes — rows are seeded with `arbitrarySample[XxxRow].copy(...)` and inserted via the real insert queries; side effects are read back with the `getAll*Testing` queries.
- Emails are asserted through `MailHogClient`; S3 objects through the `s3-test` module.
- `beforeAll` waits with `eventually` for **both** gateway readiness **and** every table in `repositoryConfig.allTableNames` to exist (`postgresClient.checkIfTableExists`) — the gateway can report ready before Flyway has finished migrating, and without the table wait the first `beforeEach` truncate aborts the whole suite on a slow CI machine (`relation "local_schema.user_credentials" does not exist`). `beforeEach` truncates **all** tables (`repositoryConfig.allTableNames`) — tests are independent and order-insensitive.
- Structure: `"<Feature> API" when { "<METHOD> /path" should { "..." in withContext { ... } } }`.
- **Order tests within a `should` block by the response's HTTP status code, ascending — sort by status code, not by the order you happened to write them.** The smithy error *name* fixes its code, so map name → code first, then sort by the code:

  | Smithy error (the `[smithy.Xxx]` type param) | Status code |
  |---|---|
  | *(happy path — no error type)* | `200`/`204` |
  | `ValidationError`, `BadRequest` | `400` |
  | `Unauthorized` | `401` |
  | `Forbidden` | `403` |
  | `InternalServerError` | `500` |

  So a full block reads: happy path first, then `400` → `401` → `403` → `500`. **`Forbidden` (403) always comes *before* `InternalServerError` (500)** — do not append a new test to the bottom of the block; insert it at the position its status code demands. When several cases share a code (e.g. multiple `400`s), keep a sensible sub-order among them. This is a source-ordering convention only — execution is order-insensitive (`beforeEach` truncates every table) — but a correctly sorted block makes a missing error case obvious at a glance, so re-check the whole block every time you add or move a test.

## What a feature's acceptance spec must cover

1. **Happy path per endpoint** — status code, full response body, *and* the resulting DB state (rows created/updated/deleted, e.g. "sign in leaves exactly one `RefreshToken` row and zero attempt rows").
2. **Business edge cases end-to-end** — resend cooldowns, attempt limits/lockouts, anti-enumeration responses, OTP expiry. Follow-up state matters: e.g. after N failed sign-ins even the *correct* password must fail.
3. **The standard error matrix** (see below).
4. **Negative side effects** — after a failed call, assert what must *not* have happened (no token rows, no attempt rows, no emails).

## Standard error matrix — repeated on (almost) every endpoint

These cases are deliberately duplicated per endpoint; the middleware is shared, but each route's wiring is not, so each endpoint proves its own gate. When adding an endpoint, copy this checklist:

| Case | Expected | Applies to |
|---|---|---|
| Invalid request body / field | `400` `smithy.ValidationError(fields = List(...))` | all endpoints with input |
| Missing credentials/token (`addBasicAuth = false` / no bearer header) | `401 Unauthorized` | all authed endpoints |
| Invalid/garbage token | `401` `smithy.Unauthorized()` | all bearer endpoints |
| Valid token but token row not in DB | `401 Unauthorized` | refresh/reset token endpoints |
| User in a disallowed `OnboardStage` | `403 Forbidden` | all stage-gated endpoints |
| Missing `X-Organization-ID` header | `400 BadRequest` | all `@organizationUserRolesAllowed` endpoints |
| Organization member with a disallowed role | `403 Forbidden` | all `@organizationUserRolesAllowed` endpoints |
| Not an organization member (no membership row) | `500 InternalServerError` | all `@organizationUserRolesAllowed` endpoints |
| Valid token but no user-details row | `500 InternalServerError` | all `@completedOnboardStage` endpoints |
| Wrong OTP | `400 BadRequest` | OTP-verify endpoints |
| Expired OTP | `401 Unauthorized` (+ OTP row deleted) | OTP-verify endpoints |
| Referenced entity missing (OTP ID/user/token not found) | `500 InternalServerError` | lookup-by-id endpoints |

### The middleware gates are MANDATORY on every endpoint — never skip them

**Rule (for every feature we test, now and in future): each authenticated endpoint's `should` block must include the full set of middleware-gate cases, not just the happy path and the endpoint's own business errors.** The auth/onboarding/organization middleware wraps every route but is *wired per route*, so a missing gate on one endpoint is a real, untested hole — copy every applicable row below into each endpoint you add. Do not treat these as optional or "already covered elsewhere". Order them by status code ascending (happy → `400` → `401` → `403` → `409` → `500`), same as any other case.

For an endpoint that is `@httpBearerAuth` + `@completedOnboardStage` + `@organizationUserRolesAllowed(...)` (the common case — every Customer Book write endpoint, see [CustomerBookApiSpec](../backend/gateway/it/src/test/scala/io/mesazon/gateway/it/CustomerBookApiSpec.scala) for the reference implementation), that means **all** of:

1. **Validation** (`400 ValidationError`) — one bad field, fully authed otherwise; assert the exact `fields` and that nothing was written.
2. **Missing `X-Organization-ID`** (`400 BadRequest`) — valid token + completed stage, `organizationIDOpt = None`.
3. **Missing token** (`401 Unauthorized`) — `accessTokenOpt = None`.
4. **Invalid token** (`401 Unauthorized`) — `Some(AccessToken("invalidtoken"))`.
5. **Disallowed onboard stage** (`403 Forbidden`) — user seeded in a non-completed stage (`OnboardStage.values.toList diff OnboardStage.completedStages`); seed a valid membership so the stage is the *only* fault.
6. **Disallowed organization role** (`403 Forbidden`) — membership seeded with a role drawn from the disallowed complement (`OrganizationUserRole.values.toList diff <allowedRoles>`); completed stage so the role is the *only* fault. Draw the allowed role for every other test from the allowed list too (`Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head`) — never hardcode `Owner`.
7. **No user-details row** (`500 InternalServerError`) — token minted for a random `UserID` with no seeded `UserDetailsRow` (fails the completed-stage gate's `getUserDetails.someOrFail`).
8. **Not an organization member** (`500 InternalServerError`) — user seeded with a completed stage but no membership row (fails the role gate's `getOrganizationUser.someOrFail`).

Each middleware-gate test also asserts the **negative side effect** — after the rejected call, the endpoint's tables are untouched (`getAll…Testing … should have size 0`). Isolate each gate's cause: make everything else valid so a failure can only be that gate. Drop a row only when the endpoint genuinely lacks that gate (e.g. a public or non-org-scoped route has no `X-Organization-ID`/role cases); add the endpoint's own business errors (e.g. `409 Conflict`) on top.

## Gotchas

- Some limits are asserted against hardcoded copies of `application.conf` values (e.g. `val maxAttempts = 10 // application.conf sign-in-attempts-max`) — change the config and these tests must change too.
- These specs are slower than `fun`/`unit` tests (full compose stack). Business-logic branches belong in `fun` specs against mocks (see [functional-tests.md](functional-tests.md)); acceptance specs prove the integrated behavior and the error matrix.
- **`GatewayClient` request codecs for smithy structures with `@required` lists must be built with `JsonCodecMaker.make[T](CodecMakerConfig.withTransientEmpty(false))`.** jsoniter-scala defaults to `transientEmpty = true`, which **omits an empty collection from the JSON entirely** — so a request with `emails = List()` is serialized without the `emails` key, the server sees a missing `@required` field, and returns a `400` before your handler runs. This is a silent, *data-dependent* flake: any test that sends `arbitrarySample[…Request]` (happy paths included) whose generator occasionally produces an empty list will intermittently get a wrong `400` (a `ValidationError` with **empty `fields`**, since the failure is the smithy required-field check, not your validator). Diagnosing it is nasty because `smithy.ValidationError.toString` prints only `"Validation error"` — assert on `.fields` to see the real content. The `withTransientEmpty(false)` config makes the client a compliant caller that sends `"emails":[]`, which the server accepts. Copy this config for every new request codec that carries a required list.
