# sbt Build Definition

The build runs on **sbt 2.0.2** (Scala 3 metabuild — everything under `project/` and `build.sbt` is Scala 3 syntax, formatted with the `scala3` scalafmt dialect). Scala version for all modules: **3.8.4**, JDK 21 (temurin, see `.tool-versions`).

## File map

| File | Owns |
|---|---|
| `build.sbt` | Common (bare) settings, module graph |
| `project/build.properties` | sbt version |
| `project/plugins.sbt` | Plugins (all must publish `_sbt2_3` artifacts) |
| `project/Dependencies.scala` | Every library version (`lazy val fooV`) and `ModuleID` — **all dependency changes happen here, never inline in build.sbt** |
| `project/Settings.scala` | `Settings.ScalaCompiler` (tpolecat scalac options `-no-indent`, `-old-syntax`, `-experimental`, `--preview`, `-Wunused:all` + test-only warning suppressions) and `Settings.testAfterDockerPublish` (publish docker images before tests) |
| `project/Projects.scala` | `ProjectOps.withDependencies(deps*)` extension used instead of raw `libraryDependencies ++=` |
| `project/Aliases.scala` | Command aliases: `checkLint`/`runLint` (fix+fmt), `gateway-build`, `waha-build` (CI entrypoints: `clean; project <x>; checkLint; test`) |
| `project/DockerSettings.scala` | `DockerSettings.compileScope`: distroless java21 image for gateway-core (native-packager stage layers `2/` = dependency jars, `4/` = app jars) |
| `project/DockerWiremockSettings.scala` | Wiremock image with `backend/wiremock/mappings/` baked in |
| `.sbtopts` | sbt server JVM memory/GC + `-Dsbt.color=always` (sbt 2's thin client otherwise drops colour in some terminals) |

## sbt 2 specifics baked into this build (do not regress)

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

## Known harmless noise

- Forked test JVMs would otherwise print `NoClassDefFoundError: org/testcontainers/utility/PathUtils` from a shutdown thread *after* the suite finishes. It is benign (tests still pass, exit code 0) and **not** a dependency conflict — only one testcontainers jar (`2.0.5`) is on the classpath and it contains `PathUtils`. Cause: testcontainers' `MountableFile.deleteOnExit` shutdown hook lazily loads a class through sbt's `ForkMain` `URLClassLoader`, which sbt 2 closes by JVM-exit time (sbt 1 left it open — hence "only in sbt 2"). It cannot be silenced with sbt options / env vars / logging config (it is raw JVM stderr from the *forked* JVM, and `Tests.Setup` runs in the sbt JVM, not the forked one). It **is** silenced by a `Thread.setDefaultUncaughtExceptionHandler` installed in `DockerComposeBase` (test-kit), which runs in the forked JVM and drops only this one exception, printing everything else. Every testcontainers spec extends `DockerComposeBase`, so that one handler covers all of them. What does *not* work: `classLoaderLayeringStrategy` (only affects in-process, non-forked runs — verified `Flat`/`AllLibraryJars` ineffective), preloading the class (unreachable from those loaders), and disabling fork (breaks the testcontainers/SLF4J setup).

## Module structure & naming conventions

- Directory layout: `backend/<module>` or `backend/<root>/<sub>`; project ID = `<root>-<sub>` (e.g. dir `backend/gateway/core` → id `gateway-core`). Enforced by `createBackendModule(root)(subModuleOpt)` in `build.sbt` — always create new modules through it (it also applies `Settings.ScalaCompiler`).
- Service roots follow the pattern `<name>` aggregate → `<name>-core` (implementation, dockerized) + `<name>-it` (acceptance tests only). Existing: `gateway`, `waha`.
- `lazy val` names in build.sbt: `backend<Name>Module` / `backend<Service>Module{Root,Core,It}`.
- Shared infrastructure modules: `domain` (pure types, iron+cats only), `clock`, `generator`, `test-kit` (all test libs; other test modules depend on it), `postgresql-test`, `s3-test`, `wiremock` (dockerized stub server + mappings), `schemas` (flyway SQL, no Scala).
- Dependency style: `.dependsOn(x)` for module wiring (one per line), `% Test` for test-only wiring, `.withDependencies(Dependencies.foo, ...)` for libraries. Version vals in `Dependencies.scala` are named `<lib>V` and grouped by ecosystem with a comment header.
- Test forking is on (`Test / fork := true`); forked suites run in parallel.

## Command aliases (`project/Aliases.scala`)

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

## CI

`.github/workflows/job-scala-build.yml` runs `sbt <module>-build` (the alias above) on JDK 21 with `setup-java` sbt caching (covers coursier) and uploads the docker image built by `Docker / publishLocal` (tag from `DOCKER_IMAGE_TAG` env, repo from `DOCKER_REPOSITORY`). Lint-on-compile is env-gated via `ENABLE_SCALA_LINT_ON_COMPILE` (off in CI compile; `checkLint` runs explicitly in the alias). New services need: a `pipeline-<name>-ci.yml`, a `<name>-build` alias in `Aliases.scala`, and path filters mirroring `pipeline-waha-ci.yml`.

## Common commands

- `sbt "gateway-build"` / `sbt "waha-build"` — what CI runs
- `sbt "checkLint"` / `sbt "runLint"` — scalafix + scalafmt check/apply (includes `*.sbt` via `scalafmtSbt`)
- `sbt "gateway-it/test"` — acceptance tests (auto-publishes wiremock + gateway-core docker images first; needs Docker running)
- `sbt "testFull"` — force-run tests skipped by incremental `test`
