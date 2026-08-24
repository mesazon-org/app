# Known issues

Agent diagnostic index. Match the signature before changing code. Record reusable issues as: severity, status, signature, cause, fix, and verification.

## Severity

| Level | Use when |
|---|---|
| Critical | Security compromise, irreversible data loss/corruption, or widespread production outage without a workaround |
| High | Production-critical behavior is unavailable or materially incorrect |
| Medium | CI, delivery, or a non-critical workflow is repeatedly blocked or unreliable |
| Low | Harmless noise or a narrow inconvenience with no behavior or delivery impact |

## Customer-business batch closes HTTP connection

- **Status:** Resolved 2026-07-29
- **Severity:** Medium
- **Signature:** `POST /insert/customer-businesses` fails before any response with `HTTP/1.1 header parser received no bytes` and `EOF reached while reading`. Expected auth errors include missing-organization `BadRequest` and non-member `InternalServerError`. Ignore the dynamic Testcontainers port.
- **Cause:** `arbInsertCustomerBusinessesPostRequest` used uncapped `Gen.resultOf` for nested lists. A captured request had 94 businesses, 4,564 contacts, 4,810 emails, 4,645 phones, and 2,472,295 JSON bytes, exceeding the former 2 MiB Smithy entity limit.
- **Fix:** Cap generated batches at 10 businesses; use one minimal valid business for auth-gate tests; increase `HttpApp.SmithyMaxEntitySize` to 5 MiB.
- **Prevention:** Bound nested collection arbitraries. For middleware tests, generate only fields relevant to the branch. Keep encoded requests comfortably below 5 MiB. Engineering Manager analysis must cover body/resource limits and test-data scale.
- **Verify:** Run `sbt "gateway-core/testOnly *CustomerBookRequestValidatorSpec"`, `sbt "gateway-core/testOnly io.mesazon.gateway.fun.CustomerBookServiceSpec"`, `sbt "gateway-it/test"`, and `sbt "runLint"`. On recurrence, capture request bytes and gateway logs; check for `EntityTooLarge`, HTTP 413, or a close at 5 MiB.

## Oversized Tapir upload can hang the request instead of failing fast

- **Status:** Open 2026-08-01
- **Severity:** Medium
- **Signature:** A real HTTP client uploading a file past `HttpApp.TapirMaxEntitySize` (20 MB) to a Tapir streaming upload endpoint (`/upload/organization/logo`, `/upload/catalogue-item/image`) never receives a response; the client eventually raises its own read-timeout (`java.net.http.HttpTimeoutException: request timed out`). The gateway logs show no activity for that request at all — not even `FileScanner`/`FileService` log lines — so the request never reaches application code. Leaving such a connection open can also delay unrelated requests on the same gateway instance.
- **Cause:** Not yet root-caused. `HttpApp.scala` wraps the Tapir routes in `org.http4s.server.middleware.EntityLimiter` at 20 MB; the hang appears to originate in that middleware (or its interop with `ZHttp4sServerInterpreter`'s streaming body) before `FileScanner.scan` is ever invoked, independent of `FileScanner`'s own size handling.
- **Fix:** None yet. `FileScanner.scan` was hardened to fully drain its input stream (instead of stopping the moment the byte cap is hit) so it does not itself abandon a request body mid-read (design: [Streaming uploads](project/streaming-uploads.md)), but this did not change the reproduction above — the hang happens upstream of `FileScanner` entirely.
- **Prevention:** Do not add a real end-to-end acceptance test that uploads a file past the entity limit over live HTTP; it reproduces this hang and can destabilize the rest of the acceptance suite. Cover oversized-file rejection at the unit level (`FileScannerSpec`) and functional level (`FileServiceSpec`, mocked `FileScanner`) instead, as both upload endpoints already do.
- **Verify:** N/A until root-caused. To reproduce: send a real HTTP POST with a body > 20 MB to either upload endpoint with valid auth/headers and observe the hang.

## sbt 2 action cache replays a stale compile failure indefinitely

- **Status:** Resolved 2026-08-02
- **Severity:** Medium
- **Signature:** A compile fails with real-looking Smithy-generated `Not found: type X` errors (e.g. in `WahaWebhookMessageInput.scala`, `UserSignUpService.scala`) followed by `sbt.util.CachedCompileFailure$$anon$1: Compilation failed` at `sbt.util.ActionCache$.cache`. The failure reproduces identically across `sbt clean`, killing and restarting the sbt server, and rerunning `smithy4sCodegen` by hand — none of which should leave a stale failure behind.
- **Cause:** sbt 2's action cache persists to disk at `~/Library/Caches/sbt/v2/ac` (content store at `.../cas`), independent of the project's `target/` directory. Once a task run is cached as failed under a given input hash, `sbt clean` (which only clears `target/`) does not invalidate it, so a later invocation with the same inputs replays the cached failure instead of recompiling — even though the actual current source state compiles fine.
- **Fix:** `rm -rf ~/Library/Caches/sbt/v2/ac` (the action-cache directory only; leave `cas` and `proc`), then rerun the target. It recompiles for real and the cache repopulates.
- **Prevention:** If a `clean` invocation and a killed/restarted sbt server both fail to clear a persistent-looking compile error — especially one citing Smithy-generated sources unrelated to the current change — suspect the action cache before suspecting the code. Do not spend time re-diagnosing the Smithy/TASTy content of the error; check for `CachedCompileFailure` in the stack trace first.
- **Verify:** The same command that previously failed now recompiles (visible `compiling N Scala sources ...` output) and succeeds.

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
- **Signature:** `gatewayCore / Docker / publishLocal` or a dependent `gateway-it` test fails before containers start with `RuntimeException: InvocationTargetException`, caused by a `NullPointerException` in `dotty.tools.scaladoc.translators.SignatureBuilder`.
- **Cause:** Local Scala 3.8.4 documentation generation can crash while Native Packager resolves the gateway documentation artifact. Application/test compilation is already successful; the failure is in an unused Docker documentation artifact.
- **Fix:** Keep the setting local to the verification invocation: `sbt "set backendGatewayCore / Compile / packageDoc / publishArtifact := false; gateway-it/testOnly *GatewayAcceptanceSpec"`. Do not commit a build change solely to hide the compiler-tooling failure.
- **Prevention:** Use the repository-pinned Temurin runtime/full JDK rather than an older Homebrew Java patch release when possible. If the pinned runtime still reproduces the signature, retain the invocation-scoped workaround.
- **Verify:** The gateway image builds, the real containers start, and `GatewayAcceptanceSpec` reports a non-zero test count. An sbt success that says `No tests were executed` is not verification.

## DigitalOcean deploy fails readiness with connection refused despite a healthy app

- **Status:** Resolved 2026-08-24
- **Severity:** Medium
- **Signature:** A DO App Platform deploy fails with `Readiness probe failed: ... connect: connection refused` on the readiness port (8082). Gateway logs show the JVM starting and Hikari connecting fine, but Ember-Server does not bind 8080/8081/8082 until ~40+ seconds after container start — after the probe already gave up.
- **Cause:** `health_check` in `terraform/modules/app-service/main.tf` budgeted `initial_delay_seconds=5` + `period_seconds=10` × `failure_threshold=3` = 35s before DO marks the app unhealthy. JVM cold start (JIT/class-loading after the DB pool is ready) has been observed taking ~44s to bind the readiness port, so the probe expires before the app ever gets to answer.
- **Fix:** Widened the budget to `initial_delay_seconds=20` + `period_seconds=10` × `failure_threshold=5` = 70s.
- **Prevention:** If startup time regresses again (e.g. from a dependency/JDK bump), widen this window rather than assuming the app itself is broken — check whether Ember actually binds in the logs before treating a readiness failure as a real crash.
- **Verify:** Redeploy and confirm the app reaches a healthy state; check deploy logs for the Ember bind timestamp relative to container start and confirm it lands inside the new budget.
