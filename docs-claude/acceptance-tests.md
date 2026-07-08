# Acceptance tests (`backend/gateway/it`)

Black-box tests of the **real running gateway**: the app and all its dependencies run in docker compose (`compose/compose.yaml` — gateway, postgres + flyway migrations, mailhog, wiremock, s3, waha), and tests drive it purely over HTTP. Nothing is mocked inside the application; if a test passes here, the wiring, smithy/Tapir serialization, middleware, config, and SQL all work together. Every completed feature has one spec here (linked from its doc in `docs-claude/features/`).

## Harness

- Specs are named `<Feature>ApiSpec` (e.g. `UserSignInApiSpec`, `FileApiSpec`), extend `ZWordSpecBase, DockerComposeBase, SmithyArbitraries, RepositoryArbitraries, IronRefinedTypeTransformer`, and use the default `compose.yaml`.
- HTTP calls go through `client/GatewayClient.scala` (sttp + jsoniter codecs for the smithy request/response types). The expected *error* type is passed as a type parameter: `gatewayClient.signInPost[smithy.Unauthorized](...)`.
- **Arrange and assert directly against Postgres**: each spec builds a per-test `Context` (`withContext`) with `PostgreSQLTestClient` plus the production `*Queries` classes — rows are seeded with `arbitrarySample[XxxRow].copy(...)` and inserted via the real insert queries; side effects are read back with the `getAll*Testing` queries.
- Emails are asserted through `MailHogClient`; S3 objects through the `s3-test` module.
- `beforeAll` waits for gateway readiness with `eventually`; `beforeEach` truncates **all** tables (`repositoryConfig.allTableNames`) — tests are independent and order-insensitive.
- Structure: `"<Feature> API" when { "<METHOD> /path" should { "..." in withContext { ... } } }`.
- **Order tests by ascending response status code.** Within each `should` block, place the happy path (`200`) first, then the failure cases in increasing status-code order (`400` → `401` → `403` → `500` …); keep any sensible sub-order among cases that share a status. Execution stays order-insensitive (`beforeEach` truncates everything) — this is purely a source-ordering convention that makes coverage gaps obvious at a glance. When you add or change a test, re-check that the block is still in incremental status order.

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
| Missing `X-Organization-ID` header | `400 BadRequest` | all `@organizationRolesAllowed` endpoints |
| Organization member with a disallowed role | `403 Forbidden` | all `@organizationRolesAllowed` endpoints |
| Not an organization member (no membership row) | `500 InternalServerError` | all `@organizationRolesAllowed` endpoints |
| Wrong OTP | `400 BadRequest` | OTP-verify endpoints |
| Expired OTP | `401 Unauthorized` (+ OTP row deleted) | OTP-verify endpoints |
| Referenced entity missing (OTP ID/user/token not found) | `500 InternalServerError` | lookup-by-id endpoints |

## Gotchas

- Some limits are asserted against hardcoded copies of `application.conf` values (e.g. `val maxAttempts = 10 // application.conf sign-in-attempts-max`) — change the config and these tests must change too.
- These specs are slower than `fun`/`unit` tests (full compose stack). Business-logic branches belong in `fun` specs against mocks; acceptance specs prove the integrated behavior and the error matrix.
