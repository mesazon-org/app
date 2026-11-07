# Acceptance testing

Black-box proof for gateway features. Use with the current feature doc, [Service flow](../features/flow/05-service.md), [Scala](../standards/scala.md), and [Chimney](../standards/chimney.md).

Known-missing coverage is tracked in [Acceptance test gaps](../acceptance-test-gaps.md). Check it before adding specs for a feature listed there, and delete the entry in the PR that closes it.

## Boundary

Acceptance tests exercise the real gateway over HTTP with real PostgreSQL, Flyway, and configured infrastructure containers. Nothing inside the application is mocked. They prove route registration, transport codecs, middleware, validation, orchestration, persistence, and observable external effects together.

Feature specs live in `backend/gateway/it/src/test/scala/io/mesazon/gateway/it/<Feature>ApiSpec.scala`. Harness-only types live under `io.mesazon.gateway.it.harness`.

## Harness

- `GatewayItContext.live: ZLayer[DockerComposeContainer, Throwable, GatewayItContext]` is assembled with `ZLayer.makeSome` from the production `.live` layers (Queries, `JwtService`, `PasswordService`, `RepositoryConfig`, ...), the test clients' `.live` layers (except `PostgreSQLTestClient.liveScoped`, a Hikari pool closed on release; [Database runtime](database-runtime.md)), config layers derived from the container, and `SttpTestBackend.liveScoped`, which acquires one JDK `HttpClient`, closes it with `HttpClient.close()` on release (sttp never closes a client it was handed), and backs both `GatewayClient` and `MailHogClient` ([External client](external-client.md#sttp-backend-in-a-spec)). Add production Queries/clients needed for arrangement and inspection as a `GatewayItContext` field and their layer to `live`.
- Annotate child specs with `@DoNotDiscover`, take the container by-name as the constructor argument (`dockerComposeContainer: => DockerComposeContainer`), and extend `GatewayAcceptanceTest(dockerComposeContainer)` plus the exact feature/row arbitraries they need. The parameter is by-name because nested specs are constructed before the containers start; it is read only when `withContext` runs. `GatewayAcceptanceTest` takes no ZIO types, holds no context or mutable state, and only exposes `protected def withContext[A](f: GatewayItContext => Task[A]): A`, which provides `GatewayItContext.live` with `ZLayer.succeed(dockerComposeContainer)` to `f` and runs it synchronously. Every call builds its own context from the container (with its own `HttpClient` and Hikari pool) and releases it when `f` ends. Through `.zioValue`, an assertion that fails inside `f` surfaces as the original ScalaTest failure with its spec file/line, or a typed `Throwable` failure, instead of a `FiberFailure`.
- Register each child once in `GatewayAcceptanceSpec.nestedSuites` as `new <Feature>ApiSpec(withContainers(identity))`. `GatewayAcceptanceSpec` is a plain container `Suite` with `DockerComposeBase`; the by-name argument defers `withContainers` until a test's `withContext` call, after the containers have started. `TestContainersForAll.run` starts the containers, runs the nested suites, then stops the containers. Readiness needs no polling in the spec: `backend/gateway/it/compose.yaml` starts `gateway` only after `flyway` completes successfully (`depends_on` with `service_completed_successfully`), so the migrations are applied first, and the gateway's health-port `ExposedService` in `GatewayClient.ExposedServices` has a testcontainers wait strategy (`GET /readiness` returning 204, 1-minute startup timeout), so the containers count as started, and tests begin, only once the gateway reports ready. Run through `gateway-it/test` or `testOnly *GatewayAcceptanceSpec`; a child spec cannot boot the shared stack itself.
- Every test is `"..." in withContext { context => import context.*; for { ... } yield () }`: every binding, including values that need no effect (`userID = arbitrarySample[UserID]` as the first line), arrangement, the HTTP call, and assertions (`_ = actual shouldBe expected`), is a `<-`/`=` binding in one `for`-comprehension, with no plain `val`s before the `for` ([Scala tests](../standards/scala.md#structure-and-names)). The context resets nothing. Each spec overrides `beforeEach` (calling `super.beforeEach()` first) and, through `withContext`, clears only the tables (and MailHog/S3 state) its tests and endpoints touch, taking table names from `repositoryConfig`; a spec must clear everything its assertions read, since other specs no longer reset shared state for it. Each test still owns all of its scenario data. `UserTokenRefreshApiSpec` is the reference.
- Registered today: `UserTokenRefreshApiSpec`, `OrganizationManagementApiSpec`, `UserSignInApiSpec`, `FileApiSpec`, `UserSignUpApiSpec`, `UserForgotPasswordApiSpec`, `UserOnboardApiSpec`, `CustomerBookFileApiSpec`, `CatalogueFileApiSpec`, `CatalogueApiSpec`, and `CustomerBookApiSpec`.
- Every spec migration also (1) checks each test name and inline comment against what the test actually seeds and asserts, fixing any that claim more, less, or the opposite; and (2) compares the feature doc's flow with the spec, then either closes documented-but-untested behaviour with a test or reports it.
- Keep `gateway-it / Test / parallelExecution := false`; nested suites share infrastructure.

## Test assets

Every test asset (image, spreadsheet, compressed archive, or malformed/invalid file) is named `<feature>-<type>-<scope>-<number>.<ext>`:

- `<feature>` — the epic/feature it's for, matching this repo's existing feature names (`contact-book`, `organization`, `catalogue`, ...). When more than one feature's tests need identical file content, duplicate the physical file once per feature rather than sharing one file across features — each feature's tests reference their own copy, never another feature's.
- `<type>` — what kind of file it is: `image`, `spreadsheet`, `compressed`, `malformed`.
- `<scope>` — which module owns it: `test` (`gateway-core`) or `it` (`gateway-it`). `gateway-it`'s test scope depends on `gateway-core`'s test scope (`build.sbt`: `backendGatewayIt.dependsOn(backendGatewayCore % "test->test")`), which puts `gateway-core`'s test resources on `gateway-it`'s test classpath too — a project's own resources take precedence over its dependencies' for that project's own code, so this never causes wrong behavior, but the explicit `<scope>` marker keeps ownership unambiguous at a glance regardless of which module's copy a classpath lookup would actually resolve to.
- `<number>` — a sequence number, unique within that `<feature>-<type>-<scope>` combination.

Examples: `contact-book-image-test-1.png` (an image-extraction golden fixture), `organization-image-test-1.jpeg` (a logo-upload test image), `contact-book-spreadsheet-it-1.csv` (an acceptance-test CSV fixture).

## Structure, descriptions, and order

Use one explicit section per endpoint, then one `"middleware"` section for the shared gates ([Organization-scoped middleware gates](#organization-scoped-middleware-gates)):

```scala
"<Feature> Service API" when {
  "POST /insert/entity" should {
    "successfully insert an entity" in withContext { context => import context.*; for { ... } yield () }
    "fail with a ValidationError when the name is invalid" in withContext { context => import context.*; for { ... } yield () }
  }

  "middleware" should {
    "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context => import context.*; for { ... } yield () }
  }
}
```

- Order endpoint sections exactly as the operations appear in the owning Smithy service’s `operations` list. For Catalogue, single insert comes first, batch insert second, then update, archive operations, reads, and uniqueness. Insert new endpoint sections at their contract position.
- Never parameterize endpoint cases. Every result must name the endpoint and failing condition in test output; a middleware gate test does this through its list position.
- Put happy `200`/`204` and successful no-op/business-edge cases first. Order failures by HTTP status: `400`, `401`, `403`, `409`, `500`, `503`.
- Success descriptions state the observable outcome. Failure descriptions start `fail with a[n] <ConcreteError>` and state the cause; include the rejected-side-effect proof when it is the scenario's important outcome.
- Each endpoint case invokes one endpoint; the gate tests in the middleware section are the only exception. Arrange state directly through production Queries; never call another public endpoint as setup.
- `CustomerBookApiSpec` is the canonical in-repository example for endpoint sections, and `CatalogueApiSpec` for the middleware section. Match their explicit arrangement and assertion style; do not invent a feature-local abstraction for authentication, organization setup, endpoint iteration, or rejected-state checks.

### Error shape changes: titles and order

When a PR changes an operation's declared error shapes (adds, removes, or renames an error shape, or changes which concrete error-type a scenario now returns), the corresponding acceptance test titles **and their physical ordering must be updated in the same PR**. A test's title must always name the concrete error type it currently asserts; test ordering must reflect the Smithy operation's own `errors: [...]` list order (ascending HTTP status, alphabetically within ties — mirroring the Smithy standard).

This rule applies to **all test suites** that name error types in their descriptions: unit specs (per `error tests named by ServiceError subtype`), functional specs, and acceptance specs. A failing title is a clear signal that the test and the contract have drifted. A title like `"fail with Unauthorized"` on a test asserting `smithy.UnauthorizedOtp()` is a source of future confusion and must be corrected when the error type changes. When a feature PR introduces a new error shape, the lead engineer reading this guide (via the [documentation router](../../CLAUDE.md#documentation-router)'s "Adding/reviewing real-gateway HTTP acceptance specs" trigger) must verify that test titles and order track error-shape changes and leave no title mismatching its assertion.

### Exact endpoint checklist

Build the cases for each endpoint before writing code. Cross out only cases that are structurally impossible or not declared by the contract, and record non-obvious omissions in the feature doc. The middleware gates are not endpoint cases: every organization-scoped endpoint joins each applicable gate test in the middleware section instead.

| Endpoint kind | Required endpoint-section cases, in order |
|---|---|
| Validated organization write | happy/no-op edges; `400 ValidationError`; every `409 Conflict`; dependency `503` if declared |
| UUID-only organization write | happy/no-op edges; dependency `503` if declared |
| Organization read by ID | happy for each distinct visibility rule; `500` referenced entity missing; dependency `503` if declared |
| Organization list read | happy empty/non-empty/filter/order behavior; dependency `503` if declared |

Business statuses that share the same visibility/editability rules use arbitrary fixture statuses in the ordinary happy path. Do not duplicate an endpoint success for `Active`, `Inactive`, or another equivalent business status. Keep separate cases for real policy boundaries such as archived versus non-archived, and for status transitions when changing status is itself the requirement.

Batch writes additionally prove empty-batch behavior when valid, stable validation indexes, atomic conflict rollback, complete output order when contractual, and no partial rows after any rejection.

Updates additionally prove absent optional members retain stored values, required members overwrite as documented, missing/archived target behavior, tenant isolation, uniqueness conflicts, and complete final rows.

Archive/delete operations prove the documented missing/already-completed behavior and retained/deleted dependent rows.

## String fixtures

Use simple, distinct hardcoded strings when acceptance setup needs different valid names, SKUs, or similar text values. Sample the surrounding models through their existing arbitraries and override only those fixture fields. For optional strings, preserve generated presence with `Gen.option(Gen.const(...))`, for example `arbitrarySample(Gen.option(Gen.const(CatalogueItemVariantSku.assume("sku1"))))` and a second fixture using `"sku2"`.

Do not build random prefixes/suffixes, repeat length/trim rules, or assert that these fixture strings differ. Their constants establish the setup; string validation and generator correctness belong to lower-level tests. This is an acceptance-specific exception to the general arbitrary-data and fixture-distinctness assertion rules. Keep arbitrary business statuses and other unrelated fields. Endpoint conflict scenarios still assert the actual uniqueness rejection and unchanged state required by their contract.

## Test layout

Blank lines and statement grouping follow [Scala formatting](../standards/scala.md#formatting).

Name values after exact models with qualifiers last (`catalogueItemRowArchived`, `organizationUserRoleInvalid`). Avoid generic `request`, `response`, `row`, or `result` names when an exact operation/model name exists.

### Canonical arrangement

Write authentication and organization prerequisites directly in every applicable test, as bindings inside the test's `withContext` `for`-comprehension:

```scala
"successfully insert an entity" in withContext { context =>
  import context.*

  for {
    onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
    userDetailsRow = arbitrarySample[UserDetailsRow]
      .copy(onboardStage = onboardStage)
    organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
    organizationUserRow = arbitrarySample[OrganizationUserRow]
      .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

    _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
    _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

    insertEntityPostRequest = arbitrarySample[InsertEntityPostRequest]

    accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

    insertEntityPostResponse <- gatewayClient.insertEntityPost[smithy.InternalServerError](
      insertEntityPostRequest.transformInto[smithy.InsertEntityPostRequest],
      Some(organizationUserRow.organizationID),
      Some(accessJwt.accessToken),
    )

    _ = insertEntityPostResponse.code shouldBe StatusCode.NoContent

    entityRowsAll <- postgresClient.executeQuery(entityQueries.getAllEntityRowsTesting)

    _ = entityRowsAll shouldBe List(...)
  } yield ()
}
```

In endpoint cases, do not hide this arrangement behind `authenticatedOrganization`, `withOrganization`, fixture builders, endpoint descriptors, loops, or parameterized matrices. Repetition is intentional: each route case must expose exactly which prerequisite is valid, absent, or invalid. The middleware section's call list is the only multi-endpoint shape, and it still writes its arrangement inline.

Use the exact operation/model in every binding:

- `insertCatalogueItemPostRequest`, not `request`;
- `insertCatalogueItemPostRequestSmithy` when the binding is generated transport input;
- `insertCatalogueItemPostResponse`, not `response`;
- `catalogueItemRowsAll`, not `rows` or `result`.

Keep qualifiers last: `onboardStageInvalid`, `organizationUserRoleInvalid`, `catalogueItemRowArchived`, `organizationIDForeign`.

## Organization-scoped middleware gates

Every organization-scoped endpoint is proven against each gate:

1. missing organization ID header → `400 BadRequest`;
2. missing access token → `401 Unauthorized`;
3. invalid access token → `401 Unauthorized`;
4. incomplete onboard stage → `403 Forbidden`;
5. disallowed organization role → `403 Forbidden`, when the endpoint's allowed-role set has a complement;
6. missing user-details row → `500 InternalServerError`;
7. user not in the organization → `500 InternalServerError`.

For each rejection, seed unrelated prerequisites validly and assert the endpoint's tables and external systems are untouched. Generate allowed/disallowed stages and roles from their sets/complements; do not hardcode `Owner`.

If the endpoint deliberately permits every defined organization role, a disallowed role cannot be constructed. Record this in the feature doc and leave only that endpoint out of the role gate test. Add it if a future role expands the enum without joining the endpoint allow-list.

Validation is a separate `400 ValidationError` case in the endpoint's own section for fallible input. Assert its exact `.fields`. Also add declared conflicts, missing referenced entities, dependency outages, and feature-owned business branches where applicable.

### Exact middleware arrangement

- Missing organization header: insert valid completed user details, generate a valid token, omit only the organization header. Membership is unnecessary because header parsing must fail first.
- Missing token: supply a syntactically valid organization ID, omit only the token.
- Invalid token: supply a syntactically valid organization ID and `AccessToken("invalidtoken")`.
- Incomplete onboarding: insert user details with a stage from `OnboardStage.values diff completedStages`, insert valid membership/role, and use a valid token.
- Disallowed role: insert completed user details and membership with a role from `OrganizationUserRole.values diff <allowedRoles>`.
- Missing user details: generate a valid token for an arbitrary `UserID` without inserting its details row.
- Missing membership: insert completed user details and use a valid token, but do not insert an organization-user row for the supplied organization.

Assert the exact empty/unchanged feature state after each rejection. Do not rely only on the HTTP status.

### Middleware section shape

- Every acceptance spec ends with one `"middleware" should { ... }` section after the endpoint sections, even when it has a single endpoint. Exceptions by the user's choice: `UserSignInApiSpec` and `UserSignUpApiSpec` keep their gate-like checks in their endpoint sections. It holds one test per gate the spec's endpoints have, in the gate order above. Name each `"fail with a[n] <ConcreteError> when <condition> on every endpoint"`. A gate that only some endpoints have covers just those and names them, e.g. `"... on every write endpoint"` when reads allow every role, or `"... on every endpoint that loads the user"`.
- Gate-like checks move here even when a handler, not middleware code, enforces them: missing/invalid access token, incomplete or disallowed onboard stage (each endpoint called with its own user seeded in a stage that endpoint rejects), missing user details, organization header, organization role, and membership. Business rules stay in the endpoint sections: token refresh validation, reset-token checks, wrong password, attempt limits, unknown email, OTP expired/wrong/used up, and a membership's missing organization at sign-in.
- Each test seeds once, with the gate's arrangement and one non-archived target (for Catalogue: an item, its variant and a sibling variant, so the last-variant rule cannot mask a write) in the supplied organization. It binds every request as an `=` binding, with no `arbitrarySample` inside the call list.
- It runs `responses <- ZIO.collectAllPar(List(...))` with one typed `GatewayClient` call per covered endpoint, in Smithy operation order, all typed with the gate's error shape. A single-endpoint spec still uses a one-element list, so a later endpoint only extends it.
- It asserts both lists whole: `responses.map(_.code) shouldBe List.fill(n)(StatusCode.<Status>)` and `responses.map(_.body.left.toOption) shouldBe List.fill(n)(Some(smithy.<Error>()))`. A failure prints both lists, so the differing position names the endpoint.
- Then it binds the grouped verification reads, a blank line, and the unchanged-row assertions ([Scala formatting](../standards/scala.md#formatting)).
- Tradeoff: all covered endpoints run against one seeded DB state. A wrong status names its endpoint by list position, but an unexpected write fails only the shared unchanged-row assertions, which cannot say which endpoint wrote. To pin it, narrow the list locally while debugging.
- Parallel calls are safe because `ServerMiddleware` drains a rejected request body before answering ([Authentication](authentication.md#rejected-request-bodies)). Tapir `securedEndpoint` uploads do not drain; the user put that fix on hold. By the user's choice, `CatalogueFileApiSpec` still uses parallel middleware tests, so a rejected upload there can surface as EOF (`HTTP/1.1 header parser received no bytes`) instead of the status. Report such a failure; do not switch to sequential calls or add retries ([Known issues](../known-issues.md#catalogue-batch-rejection-closes-the-connection-before-an-http-response)).
- `CatalogueApiSpec` is the reference.

## HTTP client codecs

`GatewayClient` owns the typed method and JSON codecs for every tested endpoint. Its error type parameter is the expected error shape for the current case.

### Typed requests only

- Acceptance tests call the endpoint's typed `GatewayClient` method with its generated Smithy request model.
- Do not add raw-body methods such as `catalogueItemPostRaw`, construct JSON strings, or mutate serialized JSON to manufacture invalid payloads.
- Unknown closed-enum values and omitted required members cannot be represented by the typed request. Record that decoding coverage limitation in the feature doc rather than bypassing the model with raw JSON, `null`, or casts.
- Keep validation cases that the typed model can express, such as empty required lists, invalid text, and out-of-range numbers. Preserve the normal codecs so those inputs reach the intended validation layer.

Required-list request codecs must use:

```scala
JsonCodecMaker.make[T](CodecMakerConfig.withTransientEmpty(false))
```

Without it, an empty list can be omitted and rejected by Smithy decoding before feature validation.

Client method requirements:

- Typed `GatewayClient` request parameters use the plain full request name without a `Smithy` suffix, such as `customerBookIndividualPostRequest`; the qualified type already identifies the transport model.

- path segments exactly match the Smithy `@http` URI;
- path labels use refined domain IDs;
- organization header and access token are both optional parameters so rejection cases can omit them;
- unit responses use `asJsonErrorUnit`;
- value responses use `asJsonEitherOrFail`;
- request and response codecs live beside the other `GatewayClient` codecs;
- every success and error body used by the spec has a codec;
- a response type that is the same domain type the production server encodes (not a separate generated Smithy transport type) reuses the production `given` codec directly instead of a duplicate hand-rolled one.

## Acceptance-level stubs for an external dependency the test can't fully control

An endpoint that calls out to an external/AI client with server-owned request content (a fixed prompt, for example) can't be driven the same way its own client-level integration spec (see [External client](external-client.md)) is: the acceptance test has no parameter to plant a per-scenario marker in that content. When the dependency's stub mechanism has no dynamic per-test configuration (e.g. WireMock's static mapping files), add one additional, marker-independent stub matching only on the substrings every real request of that shape carries, at a lower priority (WireMock: a higher priority number) than the client spec's marker-specific stubs, so it only ever matches the real, server-generated request and never competes with the client spec's own scenarios sharing the same backing container/image.

## Assertions

- Assert the exact HTTP status and complete success/error body.
- Extract `Option`/`Either` values with `OptionValues`/`EitherValues` (`.value`, `.left.value`) instead of a separate `isDefined`/`isRight` check followed by `.get` — one call, and scalatest fails with a readable message instead of a bare `NoSuchElementException`. A bare `isDefined`/`isRight`/`isLeft` boolean assertion is fine on its own when nothing inside is ever unwrapped afterward (e.g. proving an object is absent from S3).
- Inspect complete database/external state, not only the changed field. Scope readbacks to the scenario’s behavior and possible side effects. Do not add blanket empty-table checks for every related table to every case; retain targeted persistence, unchanged-row and rollback assertions.
- Cost: whole-model asserts and seeded unchanged-row checks make each test stricter but longer. When they were adopted (November 2026, together with the merged middleware section), `*ApiSpec` lines rose from 18,090 to 20,750 (+15%) while test cases fell from 372 to 299.
- After rejection, assert every prohibited DB/email/storage effect is absent.
- For an endpoint whose entire contract is "nothing is stored" (a read-only extraction/preview step, for example), assert the storage state explicitly in the happy-path test too, not only in the rejection cases — the success path is the one that actually exercises real returned data flowing through the stack, so it is the most meaningful place to prove none of it leaked into persistence.
- Assert order only when contractual; otherwise compare order-insensitively.
- Control IDs/times when their values or ordering matter; take the test's "now" from `Instant.now` ([Scala tests](../standards/scala.md#tests)).
- Missing referenced rows follow the feature's documented policy; assert the exact status and resulting state.
- Do not construct the expected DB row by transforming with production mapping code. Build it field-by-field, preserving generated IDs/timestamps from the observed row only when the API does not return/control them.
- For successful writes, assert tenant ID, every request-derived field, lifecycle status, optional composites, audit-field relationships, and total row count.
- For list reads, seed active, archived, and foreign-tenant rows as applicable; prove filtering and contractual ordering separately.
- For by-ID reads, assert the complete Smithy response including optional values and archived visibility policy.
- For conflicts and validation failures, assert exact error bodies plus complete unchanged/empty state.

## Review checklist

Before completion, inspect the spec from top to bottom and confirm:

- every endpoint appears exactly once as a `should` section;
- endpoint sections follow the owning Smithy service’s operation order;
- every endpoint case invokes only that endpoint;
- happy/no-op cases precede `400`, `401`, `403`, `409`, `500`, `503`;
- the `"middleware"` section ends the spec with one test per applicable gate, each calling every covered endpoint in operation order, and no endpoint section repeats a gate;
- every fallible body has exact validation-field coverage;
- every declared conflict has a real constraint-backed case;
- every documented missing-target/no-op policy is covered;
- every rejection asserts prohibited state is absent;
- every success asserts complete response and persistence/external state;
- bindings use exact model/operation names and concept-first qualifiers;
- there are no generic `request`, `response`, `row`, `result`, endpoint descriptors, shared authentication fixtures, or cross-endpoint helpers, and no endpoint loops other than the middleware section's call list;
- blank lines follow [Scala formatting](../standards/scala.md#formatting);
- the feature spec is registered in `GatewayAcceptanceSpec`;
- required Queries/clients are built in `GatewayItContext`;
- `GatewayClient` methods and codecs cover every endpoint;
- requests use typed client methods, with no raw-body methods or serialized-JSON mutation;
- the feature doc lists exact completed and remaining acceptance cases.
- every test name and inline comment matches what the test seeds and asserts;
- every behaviour in the feature doc's flow is covered by a test or reported as untested.

## Verification

```sh
sbt "gateway-it/Test/compile"
sbt "gateway-it/testOnly *GatewayAcceptanceSpec"
sbt "runLint"
```

When build tooling fails before the tests start, match the signature and workaround in [Known issues](../known-issues.md). A packaging failure is not an acceptance-test result; rerun until the intended suite reports a non-zero test count.

For a focused nested spec, use the standalone-runner selection and invocation-only working-directory setting in [Known issues](../known-issues.md#focused-nested-acceptance-selection-executes-no-tests). A `testOnly` parent with `-z` can select zero tests; the verified nested file-suite selection runs all 30 file API cases, including image extraction.
