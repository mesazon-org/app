# Acceptance testing

Black-box proof for gateway features. Use with the current feature doc, [Service flow](../features/flow/05-service.md), and [Scala](../standards/scala.md).

Known-missing coverage is tracked in [Acceptance test gaps](../acceptance-test-gaps.md). Check it before adding specs for a feature listed there, and delete the entry in the PR that closes it.

## Boundary

Acceptance tests exercise the real gateway over HTTP with real PostgreSQL, Flyway, and configured infrastructure containers. Nothing inside the application is mocked. They prove route registration, transport codecs, middleware, validation, orchestration, persistence, and observable external effects together.

Feature specs live in `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/<Feature>ApiSpec.scala`. Harness-only types live under `io.mesazon.gateway.it.harness`.

## Harness

- Annotate child specs with `@DoNotDiscover` and extend `GatewayAcceptanceTest` plus the exact feature/row arbitraries they need.
- Register each child once in `GatewayAcceptanceSpec`. Run through `gateway-it/test` or `testOnly *GatewayAcceptanceSpec`; a child spec cannot boot the shared stack itself.
- Add production Queries/clients needed for arrangement and inspection to `GatewayItContext.build`.
- Shared `beforeEach` truncates every repository table and resets external services. Each test still owns all of its scenario data.
- Keep `gateway-it / Test / parallelExecution := false`; nested suites share infrastructure.

## Structure, descriptions, and order

Use one explicit section per endpoint:

```scala
"<Feature> Service API" when {
  "POST /insert/entity" should {
    "successfully insert an entity" in withContext { context => ... }
    "fail with a ValidationError when the name is invalid" in withContext { context => ... }
    "fail with a BadRequest when the organization id header is missing" in withContext { context => ... }
  }
}
```

- Never parameterize endpoint cases or a shared middleware matrix. Route wiring can differ even when policy is identical; every result must name the endpoint and failing condition in test output.
- Put happy `200`/`204` and successful no-op/business-edge cases first. Order failures by HTTP status: `400`, `401`, `403`, `409`, `500`, `503`.
- Success descriptions state the observable outcome. Failure descriptions start `fail with a[n] <ConcreteError>` and state the cause; include the rejected-side-effect proof when it is the scenario's important outcome.
- One test invokes one endpoint. Arrange state directly through production Queries; never call another public endpoint as setup.
- `CustomerBookApiSpec` is the canonical in-repository example. Match its explicit arrangement and assertion style; do not invent a feature-local abstraction for authentication, organization setup, endpoint iteration, or rejected-state checks.

### Error shape changes: titles and order

When a PR changes an operation's declared error shapes (adds, removes, or renames an error shape, or changes which concrete error-type a scenario now returns), the corresponding acceptance test titles **and their physical ordering must be updated in the same PR**. A test's title must always name the concrete error type it currently asserts; test ordering must reflect the Smithy operation's own `errors: [...]` list order (ascending HTTP status, alphabetically within ties — mirroring the Smithy standard).

This rule applies to **all test suites** that name error types in their descriptions: unit specs (per `error tests named by ServiceError subtype`), functional specs, and acceptance specs. A failing title is a clear signal that the test and the contract have drifted. A title like `"fail with Unauthorized"` on a test asserting `smithy.UnauthorizedOtp()` is a source of future confusion and must be corrected when the error type changes. When a feature PR introduces a new error shape, the lead engineer reading this guide (via the [documentation router](../../CLAUDE.md#documentation-router)'s "Adding/reviewing real-gateway HTTP acceptance specs" trigger) must verify that test titles and order track error-shape changes and leave no title mismatching its assertion.

### Exact endpoint checklist

Build the cases for each endpoint before writing code. Cross out only cases that are structurally impossible or not declared by the contract, and record non-obvious omissions in the feature doc.

| Endpoint kind | Required cases, in order |
|---|---|
| Validated organization write | happy/no-op edges; `400 ValidationError`; `400 BadRequest` missing organization; `401` missing token; `401` invalid token; `403` incomplete stage; `403` disallowed role; every `409 Conflict`; `500` missing user details; `500` missing membership; dependency `503` if declared |
| UUID-only organization write | happy/no-op edges; `400 BadRequest` missing organization; `401` missing token; `401` invalid token; `403` incomplete stage; `403` disallowed role; `500` missing user details; `500` missing membership; dependency `503` if declared |
| Organization read by ID | happy for every visible lifecycle state; `400 BadRequest` missing organization; `401` missing token; `401` invalid token; `403` incomplete stage; `403` disallowed role only when constructible; `500` referenced entity missing; `500` missing user details; `500` missing membership; dependency `503` if declared |
| Organization list read | happy empty/non-empty/filter/order behavior; `400 BadRequest` missing organization; `401` missing token; `401` invalid token; `403` incomplete stage; `403` disallowed role only when constructible; `500` missing user details; `500` missing membership; dependency `503` if declared |

Batch writes additionally prove empty-batch behavior when valid, stable validation indexes, atomic conflict rollback, complete output order when contractual, and no partial rows after any rejection.

Updates additionally prove absent optional members retain stored values, required members overwrite as documented, missing/archived target behavior, tenant isolation, uniqueness conflicts, and complete final rows.

Archive/delete operations prove the documented missing/already-completed behavior and retained/deleted dependent rows.

## Test layout

Keep the phases visually separate:

1. values and models;
2. database/external arrangement;
3. token or final request values;
4. HTTP call and response binding;
5. response assertions;
6. database/external assertions.

Leave a blank line between phases. In particular, a completed `val` binding is followed by a blank line before its assertions, and a database effect is separated from the next `val` or assertion.

Name values after exact models with qualifiers last (`catalogueItemRowArchived`, `organizationUserRoleInvalid`). Avoid generic `request`, `response`, `row`, or `result` names when an exact operation/model name exists.

### Canonical arrangement

Write authentication and organization prerequisites directly in every applicable test:

```scala
val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
val userDetailsRow = arbitrarySample[UserDetailsRow]
  .copy(onboardStage = onboardStage)
val organizationUserRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
val organizationUserRow  = arbitrarySample[OrganizationUserRow]
  .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

val insertEntityPostRequest = arbitrarySample[InsertEntityPostRequest]

val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

val insertEntityPostResponse =
  gatewayClient
    .insertEntityPost[smithy.InternalServerError](
      insertEntityPostRequest.transformInto[smithy.InsertEntityPostRequest],
      Some(organizationUserRow.organizationID),
      Some(accessJwt.accessToken),
    )
    .zioValue

insertEntityPostResponse.code shouldBe StatusCode.NoContent

val entityRowsAll = postgresClient.executeQuery(entityQueries.getAllEntityRowsTesting).zioValue

entityRowsAll shouldBe List(...)
```

Do not hide this arrangement behind `authenticatedOrganization`, `withOrganization`, fixture builders, endpoint descriptors, loops, or parameterized matrices. Repetition is intentional: each route case must expose exactly which prerequisite is valid, absent, or invalid.

Use the exact operation/model in every binding:

- `insertCatalogueItemPostRequest`, not `request`;
- `insertCatalogueItemPostRequestSmithy` when the binding is generated transport input;
- `insertCatalogueItemPostResponse`, not `response`;
- `catalogueItemRowsAll`, not `rows` or `result`.

Keep qualifiers last: `onboardStageInvalid`, `organizationUserRoleInvalid`, `catalogueItemRowArchived`, `organizationIDForeign`.

### Whitespace

Blank lines are semantic separators, not decoration:

- related model `val`s may stay together;
- insert/update Query effects are separated from preceding models and following values;
- request and token bindings are separate phases when that improves scanning;
- the HTTP response binding is separated from setup;
- response assertions are separated from subsequent DB/external reads;
- a DB/external read bound to a `val` is followed by a blank line before its assertions.

Never place a Query effect immediately beside an assertion, or a completed multi-line `val` immediately beside assertions.

## Organization-scoped middleware matrix

Every organization-scoped endpoint explicitly proves:

1. missing organization ID header → `400 BadRequest`;
2. missing access token → `401 Unauthorized`;
3. invalid access token → `401 Unauthorized`;
4. incomplete onboard stage → `403 Forbidden`;
5. disallowed organization role → `403 Forbidden`, when the endpoint's allowed-role set has a complement;
6. missing user-details row → `500 InternalServerError`;
7. user not in the organization → `500 InternalServerError`.

For each rejection, seed unrelated prerequisites validly and assert the endpoint's tables and external systems are untouched. Generate allowed/disallowed stages and roles from their sets/complements; do not hardcode `Owner`.

If the endpoint deliberately permits every defined organization role, a disallowed role cannot be constructed. Record this in the feature doc and omit only that impossible case. Add it if a future role expands the enum without joining the endpoint allow-list.

Validation is a separate `400 ValidationError` case for fallible input. Assert its exact `.fields`. Also add declared conflicts, missing referenced entities, dependency outages, and feature-owned business branches where applicable.

### Exact middleware arrangement

- Missing organization header: insert valid completed user details, generate a valid token, omit only the organization header. Membership is unnecessary because header parsing must fail first.
- Missing token: supply a syntactically valid organization ID, omit only the token.
- Invalid token: supply a syntactically valid organization ID and `AccessToken("invalidtoken")`.
- Incomplete onboarding: insert user details with a stage from `OnboardStage.values diff completedStages`, insert valid membership/role, and use a valid token.
- Disallowed role: insert completed user details and membership with a role from `OrganizationUserRole.values diff <allowedRoles>`.
- Missing user details: generate a valid token for an arbitrary `UserID` without inserting its details row.
- Missing membership: insert completed user details and use a valid token, but do not insert an organization-user row for the supplied organization.

Assert the exact empty/unchanged feature state after each rejection. Do not rely only on the HTTP status.

## HTTP client codecs

`GatewayClient` owns the typed method and JSON codecs for every tested endpoint. Its error type parameter is the expected error shape for the current case.

Required-list request codecs must use:

```scala
JsonCodecMaker.make[T](CodecMakerConfig.withTransientEmpty(false))
```

Without it, an empty list can be omitted and rejected by Smithy decoding before feature validation.

Client method requirements:

- path segments exactly match the Smithy `@http` URI;
- path labels use refined domain IDs;
- organization header and access token are both optional parameters so rejection cases can omit them;
- unit responses use `asJsonErrorUnit`;
- value responses use `asJsonEitherOrFail`;
- request and response codecs live beside the other `GatewayClient` codecs;
- every success and error body used by the spec has a codec.

## Assertions

- Assert the exact HTTP status and complete success/error body.
- Extract `Option`/`Either` values with `OptionValues`/`EitherValues` (`.value`, `.left.value`) instead of a separate `isDefined`/`isRight` check followed by `.get` — one call, and scalatest fails with a readable message instead of a bare `NoSuchElementException`. A bare `isDefined`/`isRight`/`isLeft` boolean assertion is fine on its own when nothing inside is ever unwrapped afterward (e.g. proving an object is absent from S3).
- Inspect complete database/external state, not only the changed field.
- After rejection, assert every prohibited DB/email/storage effect is absent.
- Assert order only when contractual; otherwise compare order-insensitively.
- Control IDs/times when their values or ordering matter.
- Missing referenced rows follow the feature's documented policy; assert the exact status and resulting state.
- Do not construct the expected DB row by transforming with production mapping code. Build it field-by-field, preserving generated IDs/timestamps from the observed row only when the API does not return/control them.
- For successful writes, assert tenant ID, every request-derived field, lifecycle status, optional composites, audit-field relationships, and total row count.
- For list reads, seed active, archived, and foreign-tenant rows as applicable; prove filtering and contractual ordering separately.
- For by-ID reads, assert the complete Smithy response including optional values and archived visibility policy.
- For conflicts and validation failures, assert exact error bodies plus complete unchanged/empty state.

## Review checklist

Before completion, inspect the spec from top to bottom and confirm:

- every endpoint appears exactly once as a `should` section;
- every case invokes only that endpoint;
- happy/no-op cases precede `400`, `401`, `403`, `409`, `500`, `503`;
- every organization-scoped endpoint contains the applicable middleware matrix explicitly;
- every fallible body has exact validation-field coverage;
- every declared conflict has a real constraint-backed case;
- every documented missing-target/no-op policy is covered;
- every rejection asserts prohibited state is absent;
- every success asserts complete response and persistence/external state;
- bindings use exact model/operation names and concept-first qualifiers;
- there are no generic `request`, `response`, `row`, `result`, endpoint descriptors, endpoint loops, shared authentication fixtures, or cross-endpoint helpers;
- blank lines separate setup values, effects, HTTP calls, reads, and assertions;
- the feature spec is registered in `GatewayAcceptanceSpec`;
- required Queries/clients are built in `GatewayItContext`;
- `GatewayClient` methods and codecs cover every endpoint;
- the feature doc lists exact completed and remaining acceptance cases.

## Verification

```sh
sbt "gateway-it/Test/compile"
sbt "gateway-it/testOnly *GatewayAcceptanceSpec"
sbt "runLint"
```

When build tooling fails before the tests start, match the signature and workaround in [Known issues](../known-issues.md). A packaging failure is not an acceptance-test result; rerun until the intended suite reports a non-zero test count.
