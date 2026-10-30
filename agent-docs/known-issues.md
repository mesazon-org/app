# Known issues

Agent diagnostic index. Match the signature before changing code. Record reusable issues as: severity, status, signature, cause, fix, and verification.

## Severity

| Level | Use when |
|---|---|
| Critical | Security compromise, irreversible data loss/corruption, or widespread production outage without a workaround |
| High | Production-critical behavior is unavailable or materially incorrect |
| Medium | CI, delivery, or a non-critical workflow is repeatedly blocked or unreliable |
| Low | Harmless noise or a narrow inconvenience with no behavior or delivery impact |

## Single-file formatting creates empty backend trees inside modules

- **Status:** Resolved 2026-10-25
- **Severity:** Low
- **Signature:** Modules contain empty paths such as `backend/domain/backend/gateway/core/src/main/scala/io/mesazon/gateway/service`. Fourteen original trees were created together on 2026-10-15; they contain no files.
- **Cause:** `scalafmtOnly` aggregates by default and resolves each explicit file path against each project's `baseDirectory`. Running it from the root with a repository-relative `backend/gateway/core/.../SomeFile.scala` path sends that path to every module. The failed reads create parent directories even though the files do not exist there. Reproduced with `scalafmtOnly backend/__directory_probe__/NoSuch.scala`, first in `gateway-core` and then from `app` across the aggregated modules.
- **Fix:** Set `scalafmtOnly / aggregate := false` in the common settings in `build.sbt`. Remove the leftover trees using empty-directory removal only.
- **Prevention:** Select `app` for repository-relative paths: `sbt "project app; scalafmtOnly backend/gateway/core/src/main/scala/io/mesazon/gateway/utils/SpreadsheetTool.scala"`. If selecting a module, use a path relative to that module. Use `runFmt` for all modules.
- **Verify:** Run that root-project single-file command and `sbt "runLint"`; both must succeed without recreating nested `backend` trees.

## Scala 3.9.0 bump makes Smithy4s-generated sources fail the build on a fatal warning

- **Status:** Resolved 2026-09-14
- **Severity:** Medium
- **Signature:** After bumping `scalaVersion` to `3.9.0` (and `smithy4s-sbt-codegen` to `0.19.12`), compilation of `backendGatewayCore` fails on a warning-turned-error inside Smithy4s-generated sources under `src_managed`, diagnostic id `E230`. The same code compiled warning-free on the prior Scala/Smithy4s versions; no hand-written source is implicated.
- **Cause:** `project/Settings.scala`'s `ScalaCompiler` settings apply `-Wunused:all` (via `tpolecatScalacOptions`) to every backend module, including the one with `Smithy4sCodegenPlugin` enabled, so Smithy4s's generated code is compiled under the same strict flags as hand-written code. `sbt-tpolecat` escalates warnings to fatal errors in its CI-detected mode. Scala 3.9.0 newly raises the `E230` diagnostic against Smithy4s's generated output, and being fatal, it breaks the build.
- **Fix:** Added a narrowly-scoped suppression to `project/Settings.scala`'s main `tpolecatScalacOptions`, matching Smithy4s's own guidance for this warning: `-Wconf:id=E230&src=.*/src_managed/.*:s`. Scoped to `src_managed` only — it does not weaken `-Wunused:all` or fatal-warnings for hand-written code.
- **Prevention:** After a Scala or Smithy4s version bump, if a fatal warning appears only in generated (`src_managed`) sources, suppress the specific diagnostic id scoped to `src_managed`, rather than relaxing `-Wunused:all` or tpolecat's fatal-warnings mode globally.
- **Verify:** `sbt "clean; compile"` succeeds with no `E230` failure; `sbt "runLint"` passes; `sbt "gateway-build"` (or `checkLint; testFull` on the affected aggregate) reproduces the CI-equivalent fatal-warnings path and passes.

## Removed Smithy shape keeps compiling from `src_managed`

- **Status:** Workaround 2026-09-28
- **Severity:** Low
- **Signature:** After deleting or renaming a Smithy shape, `sbt compile` still compiles its old generated file under `target/.../src_managed/main/smithy4s/...` (e.g. `OrganizationAddressEntryRequest.scala` after it was replaced by `AddressEntryRequest`), so references to the removed type keep compiling locally or a stale type clashes with the new one.
- **Cause:** smithy4s incremental codegen writes new/changed shapes but does not delete generated files for shapes that no longer exist.
- **Fix:** Run `sbt "gateway-core/clean; smithy4sCodegen; compile"` after removing or renaming a Smithy shape.
- **Verify:** `src_managed/.../smithy/` no longer contains the removed shape's file and the clean compile succeeds (a clean CI build never sees the stale file).

## Acceptance client sends a Smithy enum as an object and the gateway rejects the request

- **Status:** Workaround 2026-09-30
- **Severity:** Medium
- **Signature:** After a Smithy request gains a member whose type is a new Smithy enum (e.g. `AddressType` inside `AddressEntryRequest`), acceptance tests that send it fail intermittently with a 400 / empty stored rows — only when the sampled request actually contains the enum (e.g. a non-empty `addresses` list), so a different set of tests fails on each run.
- **Cause:** `gateway-it`'s `GatewayClient` derives its request codecs with jsoniter `JsonCodecMaker.make`. Without a codec for the enum in scope, jsoniter encodes it as a discriminator object (`{"type": "Shipping"}`) instead of the wire string, and the gateway's decoder rejects it.
- **Fix:** Declare a hand-written `given JsonValueCodec[smithy.<Enum>]` in `GatewayClient`, before the request codecs that use it, like the existing `OnboardStage`, `CustomerType` and `AddressType` codecs.
- **Verify:** Run the affected acceptance spec twice; both runs pass with non-empty generated values.

## Standalone acceptance runner or test compile fails on a stale sbt server

- **Status:** Workaround 2026-09-30
- **Severity:** Low
- **Signature:** Any of: the documented standalone-runner `set backendGatewayIt / Test / run / forkOptions := …` expression fails with `Not found: $Wrap… is not a legal path`; `gateway-core/Test/compile` reports main packages missing (`value clients is not a member of io.mesazon.gateway`) right after a `reload`; or a build that just compiled reports `Ambiguous given instances` in `json/tapir.scala` after a `sbt --no-server "gateway-core/console"` probe.
- **Cause:** Stale state in the long-running sbt server (the same family as the action-cache and Docker-context issues below).
- **Fix:** `sbt shutdown`, then rerun the same command.
- **Verify:** The rerun compiles and the runner reaches the tests.

## Customer batch (businesses/individuals) closes HTTP connection

- **Status:** Resolved 2026-08-24 (recurred on `/insert/customer-individuals` after the 2026-07-29 businesses fix; root cause now fixed at the shared generator)
- **Severity:** Medium
- **Signature:** `POST /insert/customer-businesses` or `POST /insert/customer-individuals` fails before any response with `HTTP/1.1 header parser received no bytes` and `EOF reached while reading`. Expected auth errors include missing-organization `BadRequest` and non-member `InternalServerError`. Ignore the dynamic Testcontainers port.
- **Cause:** Two layers. (1) `arbInsertCustomerBusinessesPostRequest` originally used uncapped `Gen.resultOf` for nested lists — one captured request had 94 businesses, 4,564 contacts, 4,810 emails, 4,645 phones, 2,472,295 JSON bytes, exceeding the former 2 MiB Smithy entity limit. (2) The 2026-07-29 fix capped the top-level batch count and added per-endpoint minimal-data test helpers, but missed that the shared `genEntriesWithSingleDefault` (emails/phones for both customers and organizations) still used unbounded `Gen.listOf` underneath — so `/insert/customer-individuals`' auth-gate tests, which never got a minimal-data helper, kept generating up to 50 individuals × ~100 emails/phones each and reproduced the same signature.
- **Fix:** `genEntriesWithSingleDefault` (`GatewayArbitraries.scala`) now caps its list at 0–5 entries — the actual root cause, shared by every email/phone list in the codebase, not just customer-businesses. This alone bounds `/insert/customer-individuals`' auth-gate tests (which never got a per-endpoint minimal-data helper the way businesses did) without needing one. Businesses fix (10-business cap, `SmithyMaxEntitySize` 5 MiB) stays in place.
- **Prevention:** Bound nested collection arbitraries **at the shared generator**, not just at each call site — a per-endpoint fix can miss siblings using the same underlying generator. Keep encoded requests comfortably below 5 MiB. Engineering Manager analysis must cover body/resource limits and test-data scale.
- **Verify:** Run `sbt "gateway-core/testOnly *CustomerBookRequestValidatorSpec"`, `sbt "gateway-core/testOnly io.mesazon.gateway.fun.CustomerBookServiceSpec"`, `sbt "gateway-it/test"`, and `sbt "runLint"`. On recurrence, capture request bytes and gateway logs; check for `EntityTooLarge`, HTTP 413, or a close at 5 MiB.

## Catalogue batch rejection closes the connection before an HTTP response

- **Status:** Open investigation, observed in CI on 2026-10-21; not reproduced in the dependency-update gateway build.
- **Severity:** Medium
- **Signature:** `CatalogueApiSpec`'s batch non-member case raises `SttpClientException.ReadException`, `HTTP/1.1 header parser received no bytes`, and `EOF reached while reading` instead of receiving the expected 500. The subsequent ScalaTest throwable-serialization warning is secondary.
- **Evidence:** [Failed gateway CI run](https://github.com/mesazon-org/app/actions/runs/39596309311). The supplied trace shows a transport close, not an incorrect membership response. Full CI logs require an authenticated GitHub connection. The updated dependency build passed this unchanged test as part of all 282 HTTP cases; that pass does not establish a cause or prove the intermittent failure fixed.
- **Cause:** Unconfirmed. `ServerMiddleware` can reject auth before Smithy consumes the JSON body. Pinned http4s 0.23.38 sends the response and closes a connection whose remaining body was not consumed, providing a possible timing-dependent close path. Do not conflate this with the older customer-batch 5 MiB overflow: Catalogue generators already bound both item and variant counts to ten, and attribute count to ten. Gateway/container failure or a stale pooled connection also needs excluding from server logs.
- **Fix:** None established. Do not add retries, weaken the expected error, or change auth behavior solely from this client stack trace.
- **Verify:** Repeat the Catalogue HTTP suite with current images. If it recurs, capture encoded request size, gateway logs and container restart/OOM state around the exact request; distinguish unread-body/connection handling from an entity-limit failure before changing shared middleware or fixtures.

## Oversized Tapir upload can hang the request instead of failing fast

- **Status:** Open 2026-08-01
- **Severity:** Medium
- **Signature:** A real HTTP client uploading a file past `HttpApp.TapirMaxEntitySize` (20 MB) to a Tapir streaming upload endpoint (`/upload/organization/logo`, `/catalogue/file/upload/image/item`) never receives a response; the client eventually raises its own read-timeout (`java.net.http.HttpTimeoutException: request timed out`). The gateway logs show no activity for that request at all — not even `FileScanner`/`FileService` log lines — so the request never reaches application code. Leaving such a connection open can also delay unrelated requests on the same gateway instance. User-facing effect: the upload shows no progress and no answer, so the person cannot tell whether it is still working or already lost. The Organization Onboarding (logo) and Catalogue (item image) epics describe this in their oversized-upload scenario as today's behaviour; it is a defect to fix, not an open product decision.
- **Cause:** Not yet root-caused. `HttpApp.scala` wraps the Tapir routes in `org.http4s.server.middleware.EntityLimiter` at 20 MB; the hang appears to originate in that middleware (or its interop with `ZHttp4sServerInterpreter`'s streaming body) before `FileScanner.scan` is ever invoked, independent of `FileScanner`'s own size handling.
- **Fix:** None yet. `FileScanner.scan` fully drains its input stream (instead of stopping the moment the byte cap is hit) so it does not itself abandon a request body mid-read (design: [Streaming uploads](project/streaming-uploads.md)), but this did not change the reproduction above — the hang happens upstream of `FileScanner` entirely.
- **Prevention:** Do not add a real end-to-end acceptance test that uploads a file past the entity limit over live HTTP; it reproduces this hang and can destabilize the rest of the acceptance suite. Cover oversized-file rejection at the unit level (`FileScannerSpec`) and functional level (`FileServiceSpec`, mocked `FileScanner`) instead, as both upload endpoints already do.
- **Verify:** N/A until root-caused. To reproduce: send a real HTTP POST with a body > 20 MB to either upload endpoint with valid auth/headers and observe the hang.

## sbt 2 action cache replays a stale compile failure indefinitely

- **Status:** Resolved 2026-08-02
- **Severity:** Medium
- **Signature:** A compile fails with real-looking Smithy-generated `Not found: type X` errors (e.g. in `WahaWebhookMessageInput.scala`, `UserSignUpService.scala`) followed by `sbt.util.CachedCompileFailure$$anon$1: Compilation failed` at `sbt.util.ActionCache$.cache`. The failure reproduces identically across `sbt clean`, killing and restarting the sbt server, and rerunning `smithy4sCodegen` by hand — none of which should leave a stale failure behind.
- **Cause:** Before sbt 2.1, the action cache persists to disk at `~/Library/Caches/sbt/v2/ac` (content store at `.../cas`), independent of the project's `target/` directory. Once a task run is cached as failed under a given input hash, `sbt clean` (which only clears `target/` in those versions) does not invalidate it, so a later invocation with the same inputs replays the cached failure instead of recompiling — even though the actual current source state compiles fine.
- **Fix:** sbt 2.1 adds cache invalidation to the affected module's `clean` followed by compilation ([upstream change](https://github.com/sbt/sbt/pull/9752)). The current stable pin is 2.0.11, so do not assume it has this 2.1 behavior. If the failure is established as a stale action-cache replay on that version, remove only the affected action-cache directory, leaving `cas` and `proc`.
- **Prevention:** If a `clean` invocation and a killed/restarted sbt server both fail to clear a persistent-looking compile error — especially one citing Smithy-generated sources unrelated to the current change — suspect the action cache before suspecting the code. Do not spend time re-diagnosing the Smithy/TASTy content of the error; check for `CachedCompileFailure` in the stack trace first.
- **Verify:** The same command that previously failed now recompiles (visible `compiling N Scala sources ...` output) and succeeds.

## sbt 2 compile cannot overwrite a cache-linked SemanticDB file

- **Status:** Resolved 2026-10-15 with sbt 2.1.0-RC1 and a one-time clean; the fix is also in the current stable 2.0.11 pin
- **Severity:** Medium
- **Signature:** `gateway-core/compile` or `gateway-core/Test/compile` fails after an ordinary source edit with `(gateway-core / Compile / compileIncremental) java.nio.file.AccessDeniedException: .../target/out/jvm/scala-3.9.0/gateway-core/meta/META-INF/semanticdb/.../<File>.scala.semanticdb` (or `test-meta/...` for `Test / compileIncremental`). The named file can be the edited source or an unrelated one (generated Smithy sources, `HttpApp.scala`). Rerunning without a clean reproduces it.
- **Cause:** sbt 2 restores cached outputs under `target/` as symlinks into its content store (`~/Library/Caches/sbt/v2/cas/sha256-...`). sbt 2.0.10 made those blobs read-only to prevent cache corruption; Scala's SemanticDB writer overwrites files in place through the symlink and is denied. Downgrading to 2.0.8 does not remove the read-only blobs from the shared cache. This matches [upstream issue #9877](https://github.com/sbt/sbt/issues/9877).
- **Fix:** Keep sbt 2.0.11 or a later release containing [#9878](https://github.com/sbt/sbt/pull/9878): restore `.semanticdb` outputs as writable copies. The fix was initially verified here on 2.1.0-RC1 and was subsequently included in [stable 2.0.11](https://github.com/sbt/sbt/releases/tag/v2.0.11). Run `sbt "clean; runLint"` once from the root project after upgrading to remove links already left in `target/` by older versions. Changing the version alone can leave these old links in place and still fail. No shared-cache deletion or permission changes are required.
- **Prevention:** Keep the upstream fix when moving to a stable sbt release. Do not make CAS blobs writable: writing through a restored symlink can corrupt shared cached content. If a clean compile fails with `error while loading <Type>` against generated `.tasty` files, see [Generated Smithy TASTy cannot be loaded after codegen](#generated-smithy-tasty-cannot-be-loaded-after-codegen).
- **Verify:** After the one-time clean, compile a module, remove only its generated output directory without running `clean`, and compile again to force disk-cache restoration. Check that restored `.semanticdb` files are writable regular files, then change a compiler option temporarily and confirm a real recompile succeeds. A clean build alone does not exercise the original failure sequence; sbt 2.1 `clean` invalidates the cache.
- **Evidence:** On 2026-10-15, `domain/compile` restored 61 writable regular SemanticDB files with 100% disk-cache hits and no recompilation. Adding a temporary `-Wconf` option then recompiled all 60 domain sources successfully. Root `sbt "clean; runLint"` also passed, including gateway main/test compilation.

## Stale sbt server produces a Docker context missing a numbered layer directory

- **Status:** Resolved 2026-08-02
- **Severity:** Medium
- **Signature:** `gateway-core / Docker / publishLocal` (or any `gateway-it` acceptance run that packages the image) fails during `docker buildx build` with `ERROR: failed to calculate checksum of ref ...: "/4/opt/docker/lib": not found`, even though the Dockerfile's prior `COPY 2/opt/docker/lib/ jars/` step succeeds (often shown as `CACHED`). Reproduces identically across `sbt clean`, deleting `target/docker`, removing the `local/gateway-core:latest` image, `docker buildx prune -af`, and even a full Docker Desktop restart — none of those clear it.
- **Cause:** The long-running sbt server (thin-client mode keeps one alive across invocations) held a stale in-memory/on-disk staging result for `Docker/stage` from an earlier interrupted or cache-corrupted run (see the action-cache known issue above — the two can compound). sbt-native-packager splits the app's jars into numbered layer directories (`2/...`, `4/...`); the server kept serving a context missing the `4/` group while still emitting a Dockerfile that references it.
- **Fix:** Kill the sbt server process (`ps aux | grep sbt-launch`, then `kill <pid>`) and clear the action cache (`rm -rf ~/Library/Caches/sbt/v2/ac`) together, then rerun. A fresh server re-stages the Docker context correctly. Clearing only one of the two was insufficient in practice.
- **Prevention:** If a Docker packaging failure survives `clean` + image removal + a Docker daemon restart, suspect the sbt server's own stale state next rather than the Docker/BuildKit side. Restarting the sbt server is a normal recovery step after this repo's known sbt 2 action-cache issue, not just a last resort.
- **Verify:** `gateway-core / Docker / publishLocal` completes (`Built image gateway-core with tags [latest, latest]`) and a subsequent `gateway-it/testOnly *GatewayAcceptanceSpec` run reports a non-zero test count with the expected pass/fail split.

## Testcontainers shutdown hook cannot load `PathUtils`

- **Status:** Resolved 2026-07-16
- **Severity:** Low
- **Signature:** A successful container-backed suite prints `NoClassDefFoundError: org/testcontainers/utility/PathUtils` during forked-JVM shutdown.
- **Cause:** Testcontainers 2.0.5 registers a `MountableFile.deleteOnExit` hook; sbt 2 can close the forked classloader before the hook lazily loads `PathUtils`.
- **Fix:** `DockerComposeBase` installs an uncaught-exception handler that suppresses only `NoClassDefFoundError` containing `org/testcontainers/utility/PathUtils`. All other uncaught exceptions retain stack traces.
- **Prevention:** Do not broaden the predicate. Remove the workaround only after a Testcontainers or sbt upgrade proves it obsolete. Do not substitute logging/env settings, `Tests.Setup`, classloader layering, eager loading, or disabled forking.
- **Verify:** Run a container-backed suite. Confirm exit status is unchanged, the `PathUtils` trace is absent, and unrelated uncaught exceptions remain visible.

## Generated Smithy TASTy cannot be loaded after codegen

- **Status:** Mitigated 2026-07-29
- **Severity:** Medium
- **Signature:** Gateway compilation fails in an otherwise unrelated source such as `FileServiceEndpoints.scala` with `Could not read TASTy file` or `cannot be loaded from .../smithy/<Type>.tasty` immediately after Smithy code generation. A clean invocation can pass and a later incremental invocation can reproduce the same failure.
- **Cause:** Incremental build outputs can retain generated Smithy classes that no longer match the classpath used to compile an inlined dependent endpoint. The issue can recur after a successful clean build when a later invocation incrementally recompiles shared test or generated sources.
- **Fix:** Run each affected verification as one clean invocation (`sbt "clean; <target>"`) so Smithy sources and every dependent module are regenerated and compiled together.
- **Prevention:** After switching between branches that change Smithy contracts or generated domain dependencies, or when the signature recurs between verification commands, prefix the affected target with `clean`. Do not edit the endpoint named in the failure unless a clean invocation reproduces it.
- **Verify:** The clean invocation reaches and completes the intended target; a non-clean follow-up may still reproduce the incremental-build issue and does not invalidate the clean result.

## Gateway Docker packaging crashes in Dottydoc

- **Status:** Mitigated 2026-07-30
- **Severity:** Medium
- **Signature:** `gateway-core / Docker / publishLocal` or a dependent `gateway-it` test fails before containers start with `RuntimeException: InvocationTargetException`, caused by a `NullPointerException` in `dotty.tools.scaladoc.translators.SignatureBuilder`.
- **Cause:** Local Scala 3.8.4 documentation generation can crash while Native Packager resolves the gateway documentation artifact. Application/test compilation is already successful; the failure is in an unused Docker documentation artifact.
- **Fix:** Keep the setting local to the verification invocation: `sbt "set backendGatewayCore / Compile / packageDoc / publishArtifact := false; gateway-it/testOnly *GatewayAcceptanceSpec"`. Do not commit a build change solely to hide the compiler-tooling failure. Confirmed 2026-09-09: the same crash can recur on a different module's `Compile / doc` on the next run (seen on `domain`, then `waha-core`) once the first module's artifact is disabled — the failure isn't pinned to one module. Disabling `packageDoc` per-module one at a time is whack-a-mole; use the project-wide form instead: `sbt "set every Compile / packageDoc / publishArtifact := false; gateway-it/testOnly *GatewayAcceptanceSpec"`.
- **Prevention:** Use the repository-pinned Temurin runtime/full JDK rather than an older Homebrew Java patch release when possible. If the pinned runtime still reproduces the signature, retain the invocation-scoped workaround.
- **Verify:** The gateway image builds, the real containers start, and `GatewayAcceptanceSpec` reports a non-zero test count. An sbt success that says `No tests were executed` is not verification.

## Focused nested acceptance selection executes no tests

- **Status:** Mitigated 2026-09-13
- **Severity:** Medium
- **Signature:** `gateway-it/testOnly *GatewayAcceptanceSpec -- -z /extract/customers` builds the images and reports success, but executes zero tests. Running `FileApiSpec` directly instead bypasses the parent context initialization. Running ScalaTest's standalone runner from the repository root can also abort with `Unable to parse YAML file` / missing `compose.yaml` before starting tests.
- **Cause:** sbt's ScalaTest wildcard test selector targets the selected parent's own test names, not its nested specs. The compose harness also depends on the gateway-it working directory. Nested suite selection is supported by the standalone [ScalaTest runner](https://www.scalatest.org/user_guide/using_the_runner), not sbt's suite-argument parser.
- **Fix:** After publishing the current gateway and WireMock images, select the nested file suite through its parent and set the forked runner's working directory for this invocation only:
  ```sh
  sbt "set backendGatewayIt / Test / run / forkOptions := Def.uncached((backendGatewayIt / Test / run / forkOptions).value.withWorkingDirectory(Some((backendGatewayIt / baseDirectory).value))); gateway-it/Test/runMain org.scalatest.tools.Runner -o -s io.mesazon.gateway.it.harness.GatewayAcceptanceSpec -i io.mesazon.gateway.it.FileApiSpec"
  ```
- **Prevention:** `Test/runMain` does not invoke the build's test-entrypoint Docker publish hooks; publish fresh images first. Do not count a successful zero-test command as proof, run a child without its parent, or assume adding `-z` to this nested selection narrows the suite: the verified invocation with `-z` still ran the entire file suite.
- **Verify:** The shared stack starts and the runner reports 34 passing `FileApiSpec` tests, including all twelve unified customer-extraction cases. No shared harness/build changes are required.

## DigitalOcean deploy fails readiness with connection refused despite a healthy app

- **Status:** Resolved 2026-08-24
- **Severity:** Medium
- **Signature:** A DO App Platform deploy fails with `Readiness probe failed: ... connect: connection refused` on the readiness port (8082). Gateway logs show the JVM starting and Hikari connecting fine, but Ember-Server does not bind 8080/8081/8082 until ~40+ seconds after container start — after the probe already gave up.
- **Cause:** `health_check` in `terraform/modules/app-service/main.tf` budgeted `initial_delay_seconds=5` + `period_seconds=10` × `failure_threshold=3` = 35s before DO marks the app unhealthy. JVM cold start (JIT/class-loading after the DB pool is ready) has been observed taking ~44s to bind the readiness port, so the probe expires before the app ever gets to answer.
- **Fix:** Widened the budget to `initial_delay_seconds=20` + `period_seconds=10` × `failure_threshold=5` = 70s.
- **Prevention:** If startup time regresses again (e.g. from a dependency/JDK bump), widen this window rather than assuming the app itself is broken — check whether Ember actually binds in the logs before treating a readiness failure as a real crash.
- **Verify:** Redeploy and confirm the app reaches a healthy state; check deploy logs for the Ember bind timestamp relative to container start and confirm it lands inside the new budget.

## Catalogue image golden exhausts the request timeout

- **Status:** Manual-check timeout adjusted; live reverification pending.
- **Signature:** `CatalogueFileAiExtractFromImageGoldenSpec` fails after about three minutes with `Unable to send message to AI` → `SttpClientException.TimeoutException` → `java.net.http.HttpTimeoutException: request timed out` for `/v1/chat/completions`.
- **Cause:** Each attempt exceeded the golden's 60-second read deadline; the client allowed two retries with exponential delay. The exception does not identify whether provider latency, request complexity, or connectivity caused the delay. No completion reached JSON decoding.
- **Adjustment:** The catalogue image manual golden now uses 180 seconds per attempt, retaining two retries. A timeout-only run can therefore take roughly nine minutes. The shared production default is also 180 seconds per attempt, with the existing environment override and client retry/error behavior retained.
- **Verify:** Compile/lint the spec without sending data. A person can then run it with their configured key and asset to observe whether a complete response arrives within the longer deadline; canceled or timed-out runs are not extraction-quality evidence.
