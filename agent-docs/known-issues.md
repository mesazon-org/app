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
