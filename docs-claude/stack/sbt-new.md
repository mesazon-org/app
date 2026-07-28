# sbt Build Definition

This document defines **project-agnostic standards** for an sbt build definition — the rules a Scala 3 / sbt 2.x multi-module build follows so that dependency versions, module wiring, CI, and local development stay consistent and cannot silently drift apart.

## Scope

This document owns:

- centralizing dependency versions in one place instead of inlining them across build files;
- creating modules through a single helper so naming and settings conventions apply uniformly;
- command aliases that give CI and local development exact parity;
- the build tool's caching, incremental-task, and compilation-pipelining rules, and where a project must deliberately opt out of them;
- constraints on plugin/version compatibility across a metabuild upgrade;
- CI wiring for a build tool invocation.

This document excludes, with pointers to where each belongs instead:

- general Scala language and naming conventions, independent of the build tool → [scala-new.md](scala-new.md);
- what each test tier is responsible for proving (unit vs. functional vs. integration vs. acceptance) → [functional-tests-new.md](../functional-tests-new.md), [integration-tests-new.md](../integration-tests-new.md), [acceptance-tests-new.md](../acceptance-tests-new.md). This document owns only how those tests are *wired and invoked* from the build (forking, docker-image prerequisites, cache-busting for CI) — not what they assert.

## Table of contents

- [Scope](#scope)
- [Table of contents](#table-of-contents)
- [Metabuild conventions](#metabuild-conventions)
- [Dependency version centralization](#dependency-version-centralization)
- [Module creation through a single helper](#module-creation-through-a-single-helper)
- [Build-tool-version-specific rules](#build-tool-version-specific-rules)
- [Known harmless test noise](#known-harmless-test-noise)
- [Command aliases as CI/local parity](#command-aliases-as-cilocal-parity)
- [CI wiring](#ci-wiring)
- [Common commands](#common-commands)

## Metabuild conventions

Pin the build tool version, the language version used by application modules, and the JDK version explicitly, in version-controlled files rather than relying on whatever a developer's machine happens to have installed. Format and lint the build definition itself (build files, plugin configuration, helper sources) with the same tooling and dialect used for application code, so the build is held to the same standard as the code it builds.

In this build: the build runs on **sbt 2.0.2** (a Scala 3 metabuild — everything under `project/` and `build.sbt` is Scala 3 syntax, formatted with the `scala3` scalafmt dialect). Scala version for all modules: **3.8.4**, JDK 25 (temurin, see `.tool-versions`).

## Dependency version centralization

State every third-party library version in exactly one file, never inline in a module's build configuration. A single version file makes it possible to see and bump every dependency at a glance, and prevents two modules silently drifting onto different versions of the same library. Pair this with a thin, named wrapper for "add these library dependencies to this module" so call sites read as intent (`withDependencies(...)`) rather than raw list concatenation.

Also centralize, in a small number of clearly-scoped files, the concerns that would otherwise be copy-pasted per module: compiler flags, packaging/image settings, and command aliases. Each such file should own exactly one concern, so a change to (for example) compiler flags touches one file regardless of how many modules exist.

In this build, the File map:

| File | Owns |
|---|---|
| `build.sbt` | Common (bare) settings, module graph |
| `project/build.properties` | build tool version |
| `project/plugins.sbt` | Plugins (all must publish artifacts compatible with the pinned build-tool major version) |
| `project/Dependencies.scala` | Every library version (`lazy val fooV`) and its dependency descriptor — **all dependency changes happen here, never inline in build.sbt** |
| `project/Settings.scala` | Shared compiler-option settings (strict/experimental flags + test-only warning suppressions) and shared task-ordering settings (e.g. publish docker images before tests) |
| `project/Projects.scala` | A `withDependencies(deps*)` extension used instead of raw dependency-list concatenation |
| `project/Aliases.scala` | Command aliases: lint check/fix, and one CI-entrypoint alias per service/deployable (`clean; project <x>; checkLint; test`) |
| `project/DockerSettings.scala` | Docker/native-packager image settings for the primary service — base-image constraints, layering, and the reminder that runtime JVM flags belong in the deploy/infra layer's env-var mechanism, not a packaging-time one that the chosen entrypoint ignores |
| `project/DockerWiremockSettings.scala` (or equivalent) | Packaging settings for any stub/mock-server image used by tests, with its fixture mappings baked in |
| `.sbtopts` (or the build tool's equivalent local-options file) | Build-server JVM memory/GC and any terminal-compatibility flags the build server needs (e.g. forcing colour output) |

## Module creation through a single helper

Create every new module through one shared helper rather than hand-writing a project definition per module. The helper is the single place that applies cross-cutting settings (compiler options, standard scopes) to every module, so a module can never accidentally be created without them. Adopt one fixed convention for directory layout, project identifier, and `lazy val` naming, and enforce it through the helper rather than through code review alone.

In this build:

- Directory layout: `backend/<module>` or `backend/<root>/<sub>`; project ID = `<root>-<sub>` (e.g. dir `backend/gateway/core` → id `gateway-core`). Enforced by `createBackendModule(root)(subModuleOpt)` in `build.sbt` — always create new modules through it (it also applies the shared compiler settings).
- Service roots follow the pattern `<name>` aggregate → `<name>-core` (implementation, dockerized) + `<name>-it` (acceptance tests only). Existing: `gateway`, `waha`.
- `lazy val` names in `build.sbt`: `backend<Name>Module` / `backend<Service>Module{Root,Core,It}`.
- Shared infrastructure modules follow the same helper: pure-domain-types module, clock, id/data generators, a shared test-support module (all test libraries; every test module depends on it), one test-harness module per piece of test infrastructure (Postgres, S3, a stub-server), and a schema/migration module with no application code.
- Dependency style: module-to-module wiring one per line, scoped to `Test` for test-only wiring, library wiring through the shared `withDependencies(...)` extension. Version vals in the dependency file are named `<lib>V` and grouped by ecosystem with a comment header.
- Test forking is on; forked suites run in parallel.

## Build-tool-version-specific rules

A build tool upgrade — especially a major version — can silently change defaults: what is cached, what is incremental, what settings scope automatically, and what file-reference types cross task boundaries. Treat every such behavior change as a rule to document and design around, not a one-off gotcha to rediscover. In particular:

- Prefer whatever mechanism the current build-tool version uses to apply settings to *all* subprojects uniformly, and do not reintroduce an older/deprecated mechanism for the same purpose alongside it.
- If the test-running task becomes incremental (skips suites whose results are cached), treat that cache as a correctness hazard for CI: a warm or restored cache could make CI pass without a suite actually running. CI must invoke a "force all" variant of the test task that ignores the cache, not the plain incremental one — even though the incremental one is more convenient for local development.
- If a module's tests need external resources published or started first (e.g. docker images), wrap the task at every entrypoint a developer might invoke it through, not just the most common one — otherwise the dependency is invisible from the entrypoints you didn't wrap. Confirm the underlying task-dependency mechanism runs the shared prerequisite once even when wrapped from multiple entrypoints, and confirm wrapping doesn't break shell tab-completion for that task's arguments.
- Assume all tasks are cached by default in the current build-tool version; any custom task with a side effect (network call, file write outside the tracked output, docker publish) must be explicitly marked as uncached, or it will silently not re-run when it should.
- Know where build outputs live under the current version (a single unified output tree vs. per-module output directories) so tooling and `.gitignore` entries target the right path.
- Do not re-set behaviors that are now defaults in the current build-tool version (e.g. auto-reload on build-file change, parallel test execution/forking) — leaving them unset when they're already the default keeps the build file legible about what's actually being overridden.
- Compilation pipelining (letting a downstream module start compiling against an upstream module's not-yet-finished output) is a build-time optimization that is only sound when the upstream module's public signatures don't require *running* upstream code to determine — i.e., the upstream module must not define macros. A macro must execute the upstream class at the downstream module's compile time, which an early, signature-only output cannot provide, so pipelining a macro-defining module produces wrong or failing builds. If a module ever starts defining a macro, disable pipelining for that module specifically (or globally, if unsure which module is affected).
- Plugins must publish artifacts compatible with the pinned build-tool major version; when a plugin has no stable release for that major version yet, pin the newest compatible pre-release and record the reason plus the follow-up (bump once stable ships) as a comment near the pin.
- Multi-command invocations to the build tool from CI must be passed as they were designed to be parsed (e.g. one quoted string for a `;`-separated sequence) — the un-quoted, space-separated form for multiple commands may silently stop working across major versions.
- If the build tool's shell disables scoped-task delegation for tab-completion, use the fully-scoped key for interactive completion (project-scope into the module, then scope into the task) rather than relying on a bare, unscoped completion that used to work.

In this build (sbt 2 specifics baked into this build — do not regress):

- **Bare settings replace `ThisBuild`**: top-of-`build.sbt` bare settings are injected into *all* subprojects. Never reintroduce `ThisBuild /`.
- **`test` is an `InputKey[TestResult]`** and incremental (skips unchanged passing suites; `testFull` forces all; the incremental result cache lives outside `target/`, so it survives `clean`). You cannot `test := Def.sequential(...).value`. Modules needing docker images before tests use `Settings.testAfterDockerPublish(<publishLocal tasks>*)` (in `project/Settings.scala`), which wraps all four entrypoints — `test`, `testQuick`, `testOnly`, `testFull` — via `.dependsOn` so the images publish before tests run through any of them. Wrapping all four does **not** double-publish: sbt evaluates each task once per command, so the shared publish tasks run once even though multiple keys (and `test`'s delegation to `testQuick`) depend on them. `testFull` is wrapped in `Def.uncached` (mirroring sbt's default) because it is a plain, non-cached task. Two gotchas: (1) augment these keys only via the canonical `key := (Test / key).dependsOn(...).evaluated` / `.value` form, which resolves to the key's previous value; wrapping the self-reference inside `Def.sequential`/`Def.task` instead is treated as a real cycle and fails at runtime with `RuntimeUndefined`. (2) `.dependsOn` on an input task goes through `InputTask.mapTask`, preserving the completion parser, so this wrapping does not affect `testOnly <TAB>` completion.
- **`Classpath`/`mappings` carry `xsbti.HashedVirtualFileRef`, not `File`** — convert with `fileConverter.value.toVirtualFile(path)` (see `DockerWiremockSettings`).
- **All tasks are cached by default**; side-effecting custom tasks must be wrapped in `Def.uncached(...)`.
- **Unified target**: outputs live in `target/out/jvm/scala-<ver>/<module>/`, not `<module>/target/`.
- Settings intentionally *not* set because they are sbt 2 defaults: auto-reload on build change, `Test / parallelExecution`, `Test / testForkedParallel`.
- `usePipelining := true` enables **compilation pipelining** to speed up multi-module builds. Normally a downstream module can't start compiling until every upstream module it depends on has finished producing `.class`/`.tasty` files. With pipelining, the Scala compiler emits an **early output** — a lightweight JAR of just the type signatures (TASTy/pickles), produced partway through compilation, before full codegen/optimization — and sbt lets downstream modules start typechecking against that early output while the upstream module is still finishing its own bytecode. Because our module graph is deep and mostly sequential (`domain → generator → waha-core → gateway-core → gateway-it`), this overlaps work that used to run strictly one-after-another and measurably cuts total compile time. The trade-off/caveat: it's only sound when upstream signatures don't require running upstream code to compute — i.e. **no macros**. A macro must execute the upstream class at the downstream module's compile time, which the not-yet-finished early output can't provide, so pipelining a macro-defining module produces wrong/failed builds. No module here defines a macro today, so it's safe; if one ever does, either disable pipelining for that module (`<module> / exportPipelining := false`) or turn it off globally. (Chimney/iron/jsoniter/smithy4s are compile-time via inline/derivation and codegen, not sbt-visible macro *definitions* in our modules, so they're unaffected.)
- Plugins must have sbt 2 (`_sbt2_3`) artifacts. sbt-twirl must stay on the 2.1.x line (2.0.x has no sbt 2 build) — currently the `2.1.0-M9` **milestone**, the only sbt 2 twirl release so far; bump to the stable 2.1.0 once it ships. smithy4s plugin version must equal `smithy4sV` in `Dependencies.scala`.
- CI multi-command invocations must be a single quoted string: `sbt "a; b"` (old `sbt a b` form fails).
- **Shell tab-completion of test names needs an explicit scope.** sbt 2 disables scoped-task delegation in the shell (sbt/sbt#8539), so a bare `testOnly <TAB>` no longer delegates to `Test / testOnly` and shows nothing. Use the fully-scoped key — `gateway-it / Test / testOnly <TAB>` — or `project gateway-it` then `Test / testOnly <TAB>` (test sources must be compiled first, since the names come from discovered test classes). This is unrelated to `testAfterDockerPublish`: sbt's `dependsOn` on an input task goes through `InputTask.mapTask`, which preserves the completion parser.

## Known harmless test noise

Some test-runner or infrastructure-library noise is a known, harmless artifact of how a forked test JVM shuts down under the current build-tool version, rather than a real dependency conflict or leak. Document any such noise precisely — what it looks like, why it's benign, what does and does not silence it — so it isn't repeatedly misdiagnosed as a regression, and so a future contributor doesn't waste time trying already-ruled-out fixes.

In this build: forked test JVMs would otherwise print `NoClassDefFoundError: org/testcontainers/utility/PathUtils` from a shutdown thread *after* the suite finishes. It is benign (tests still pass, exit code 0) and **not** a dependency conflict — only one testcontainers jar (`2.0.5`) is on the classpath and it contains `PathUtils`. Cause: testcontainers' `MountableFile.deleteOnExit` shutdown hook lazily loads a class through sbt's `ForkMain` `URLClassLoader`, which sbt 2 closes by JVM-exit time (sbt 1 left it open — hence "only in sbt 2"). It cannot be silenced with sbt options / env vars / logging config (it is raw JVM stderr from the *forked* JVM, and `Tests.Setup` runs in the sbt JVM, not the forked one). It **is** silenced by a `Thread.setDefaultUncaughtExceptionHandler` installed in `DockerComposeBase` (test-kit), which runs in the forked JVM and drops only this one exception, printing everything else. The handler is a JVM-wide default, and every forked test JVM that runs testcontainers has a `DockerComposeBase` suite in it — repository specs extend it directly (see [repository-new.md](../repository-new.md)), and the gateway acceptance specs are nested under `GatewayAcceptanceSpec`, which extends it (see [acceptance-tests-new.md](../acceptance-tests-new.md)) — so that one handler covers all of them. What does *not* work: `classLoaderLayeringStrategy` (only affects in-process, non-forked runs — verified `Flat`/`AllLibraryJars` ineffective), preloading the class (unreachable from those loaders), and disabling fork (breaks the testcontainers/SLF4J setup).

## Command aliases as CI/local parity

Compose command aliases out of small, single-purpose steps, and make CI invoke the exact same alias a developer runs locally — never a bespoke CI-only sequence of raw commands. This guarantees CI can't drift from what "passing locally" means: if the alias changes, both CI and local behavior change together. Provide a read-only "check" alias (fails on any violation, changes nothing) separate from a "fix"/"run" alias (applies changes), for every gate that supports the distinction (formatting, linting), so CI can enforce without mutating and developers can self-fix with one command.

Give every deployable/service its own single CI-entrypoint alias, and make that alias's shape fixed and predictable: reset build state, scope to the right module subset, run the read-only quality gate, then run the full (non-incremental) test task.

In this build (`project/Aliases.scala`):

All aliases are registered on the root project via `Aliases.all`. They compose small steps so CI and local dev run the exact same commands.

| Alias | Expands to | Purpose |
|---|---|---|
| `checkFmt` | `scalafmtCheckAll; scalafmtSbtCheck` | fail if any `.scala` **or** `.sbt`/`project` file is misformatted (read-only) |
| `runFmt` | `scalafmtAll; scalafmtSbt` | apply formatting to everything incl. build files |
| `checkFix` | `scalafixAll --check` | fail if scalafix rules (unused imports, `OrganizeImports`, `DisableSyntax`, …) would change anything |
| `runFix` | `scalafixAll` | apply scalafix rewrites |
| `checkLint` | `checkFix; checkFmt` | the read-only lint gate used by CI (scalafix first, then scalafmt) |
| `runLint` | `runFix; runFmt` | fix everything locally before committing |
| `gateway-build` | `clean; project backend; checkLint; testFull` | full gateway CI build |
| `waha-build` | `clean; project waha; checkLint; testFull` | full waha CI build |

The `<service>-build` aliases are the CI entrypoints and follow a fixed shape: **`clean`** (fresh compile) → **`project <aggregate>`** (scope to the service's aggregate module) → **`checkLint`** (fail the build on any format/scalafix violation) → **`testFull`**.

`testFull` (not `test`) is deliberate for CI: `test`/`testQuick` are incremental and skip suites whose results are cached, and that cache lives outside `target/` so `clean` doesn't reset it — a warm/restored cache could make CI go green without running the suites. `testFull` ignores the cache and runs **every** suite every time. It's also wrapped by `Settings.testAfterDockerPublish`, so the wiremock/gateway-core images are still published before the acceptance tests run.

## CI wiring

Have CI invoke the single alias defined for the target service/deployable, on the pinned JDK version, with the build tool's own dependency/artifact caching enabled. Publish whatever build artifact the pipeline produces (e.g. a container image) using the tag/repository values injected as environment variables, so the same alias run locally and in CI produces a comparably-tagged artifact. Any lint-on-compile convenience feature that's useful for local incremental feedback should be disabled in CI's plain compile step and instead enforced explicitly by the shared alias, so CI's pass/fail signal comes from one place. Adding a new service/deployable to CI means: a new pipeline definition, a new CI-entrypoint alias for it, and path filters mirroring an existing pipeline definition.

In this build: `.github/workflows/job-scala-build.yml` runs `sbt <module>-build` (the alias above) on JDK 25 with `setup-java` sbt caching (covers coursier) and uploads the docker image built by `Docker / publishLocal` (tag from `DOCKER_IMAGE_TAG` env, repo from `DOCKER_REPOSITORY`). Lint-on-compile is env-gated via `ENABLE_SCALA_LINT_ON_COMPILE` (off in CI compile; `checkLint` runs explicitly in the alias). New services need: a `pipeline-<name>-ci.yml`, a `<name>-build` alias in `Aliases.scala`, and path filters mirroring `pipeline-waha-ci.yml`.

## Common commands

- `sbt "gateway-build"` / `sbt "waha-build"` — what CI runs
- `sbt "checkLint"` / `sbt "runLint"` — scalafix + scalafmt check/apply (includes `*.sbt` via `scalafmtSbt`)
- `sbt "gateway-it/test"` — acceptance tests (auto-publishes wiremock + gateway-core docker images first; needs Docker running)
- `sbt "testFull"` — force-run tests skipped by incremental `test`
</content>
</invoke>
