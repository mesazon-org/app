# sbt build — Mesazon specifics

Read with the [agnostic sbt rules](../agnostic/sbt.md).

- sbt **2.0.2**; Scala 3 metabuild (`build.sbt`, `project/`, `scala3` scalafmt dialect); application Scala **3.8.4**; Temurin JDK 25 (`.tool-versions`).

## File map

| File | Owns |
|---|---|
| `build.sbt` | Common (bare) settings, module graph |
| `project/build.properties` | sbt version |
| `project/plugins.sbt` | Plugins (all must publish `_sbt2_3` artifacts) |
| `project/Dependencies.scala` | All versions (`<lib>V`) + `ModuleID`; never inline dependencies in `build.sbt` |
| `project/Settings.scala` | `ScalaCompiler` (`-no-indent`, `-old-syntax`, `-experimental`, `--preview`, `-Wunused:all`, test suppressions); `testAfterDockerPublish` |
| `project/Projects.scala` | `ProjectOps.withDependencies(deps*)` extension used instead of raw `libraryDependencies ++=` |
| `project/Aliases.scala` | Lint/fix + service CI aliases |
| `project/DockerSettings.scala` | `DockerSettings.compileScope`: gateway distroless `java25-debian13`; stage `2/` dependencies, `4/` app. Bare `java`: `terraform/dev/gateway/app.tf` sets `JAVA_TOOL_OPTIONS`, never ignored `JAVA_OPTS` |
| `project/DockerWiremockSettings.scala` | Wiremock image with `backend/wiremock/mappings/` baked in |
| `.sbtopts` | sbt JVM memory/GC + `-Dsbt.color=always` |

## Module creation

- `backend/<module>` or `backend/<root>/<sub>`; ID `<root>-<sub>`. Always use `createBackendModule(root)(subModuleOpt)`; it applies `Settings.ScalaCompiler`.
- Service roots follow the pattern `<name>` aggregate → `<name>-core` (implementation, dockerized) + `<name>-it` (acceptance tests only). Existing: `gateway`, `waha`.
- `lazy val` names in `build.sbt`: `backend<Name>Module` / `backend<Service>Module{Root,Core,It}`.
- Infrastructure: `domain` (pure Iron/Cats types), `clock`, `generator`, `test-kit` (all test libs), `postgresql-test`, `s3-test`, `wiremock`, `schemas` (Flyway only).
- Dependency style: `.dependsOn(x)` for module wiring (one per line), `% Test` for test-only wiring, `.withDependencies(Dependencies.foo, ...)` for libraries. Version vals in `Dependencies.scala` are named `<lib>V` and grouped by ecosystem with a comment header.
- Test forking is on (`Test / fork := true`); forked suites run in parallel.

## sbt 2 specifics baked into this build (do not regress)

- **Bare settings replace `ThisBuild`**: top-of-`build.sbt` bare settings are injected into *all* subprojects. Never reintroduce `ThisBuild /`.
- `test: InputKey[TestResult]` is incremental; `testFull` ignores the cache (stored outside `target/`, surviving `clean`). Never assign `test := Def.sequential(...).value`.
- Docker-dependent modules use `Settings.testAfterDockerPublish(...)`, wrapping `test`, `testQuick`, `testOnly`, `testFull` via `.dependsOn`; sbt executes shared prerequisites once. Wrap only as `key := (Test / key).dependsOn(...).evaluated`/`.value`; `Def.sequential`/`Def.task` self-reference causes `RuntimeUndefined`. `InputTask.mapTask` preserves completion. `testFull` stays `Def.uncached`.
- **`Classpath`/`mappings` carry `xsbti.HashedVirtualFileRef`, not `File`** — convert with `fileConverter.value.toVirtualFile(path)` (see `DockerWiremockSettings`).
- **All tasks are cached by default**; side-effecting custom tasks must be wrapped in `Def.uncached(...)`.
- **Unified target**: outputs live in `target/out/jvm/scala-<ver>/<module>/`, not `<module>/target/`.
- Settings intentionally *not* set because they are sbt 2 defaults: auto-reload on build change, `Test / parallelExecution`, `Test / testForkedParallel`.
- `usePipelining := true` overlaps this deep graph: `domain → generator → waha-core → gateway-core → gateway-it`. No module defines macros. If one does, set `<module> / exportPipelining := false` or disable globally. Chimney/Iron/jsoniter/smithy4s inline/derivation/codegen are not module-defined macros.
- Plugins require `_sbt2_3`. Keep sbt-twirl `2.1.0-M9` (only compatible line); upgrade to stable 2.1.0 when released. smithy4s plugin version = `smithy4sV`.
- CI multi-command invocations must be a single quoted string: `sbt "a; b"` (old `sbt a b` form fails).
- Test-name completion needs scope (sbt#8539): `gateway-it / Test / testOnly <TAB>` or `project gateway-it`, then `Test / testOnly <TAB>` after test compilation. `testAfterDockerPublish` preserves completion.

## Known harmless test noise

Benign post-suite stderr: `NoClassDefFoundError: org/testcontainers/utility/PathUtils` from Testcontainers `2.0.5`; tests exit 0 and the sole jar contains the class. Cause: `MountableFile.deleteOnExit` loads through sbt 2's closed forked-JVM classloader. `DockerComposeBase` installs `Thread.setDefaultUncaughtExceptionHandler`, dropping only this exception; repository specs extend it and acceptance specs nest under `GatewayAcceptanceSpec`, which extends it. Ineffective: sbt/env/logging options, `Tests.Setup`, `classLoaderLayeringStrategy`, preloading, or disabling fork.

## Command aliases (`project/Aliases.scala`)

Root registers all via `Aliases.all`.

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

`<service>-build`: `clean` → `project <aggregate>` → `checkLint` → `testFull`. CI must use `testFull`: incremental caches survive `clean`; `Settings.testAfterDockerPublish` still publishes required images.

## CI wiring

`.github/workflows/job-scala-build.yml`: `sbt <module>-build`, JDK 25, setup-java/sbt cache, upload `Docker / publishLocal` image using `DOCKER_IMAGE_TAG`/`DOCKER_REPOSITORY`. CI disables `ENABLE_SCALA_LINT_ON_COMPILE`; alias runs `checkLint`. New service: `pipeline-<name>-ci.yml` + `<name>-build` alias + `pipeline-waha-ci.yml`-style path filters.

## Common commands

- `sbt "gateway-build"` / `sbt "waha-build"` — what CI runs
- `sbt "checkLint"` / `sbt "runLint"` — scalafix + scalafmt check/apply (includes `*.sbt` via `scalafmtSbt`)
- `sbt "gateway-it/test"` — acceptance tests (auto-publishes wiremock + gateway-core docker images first; needs Docker running)
- `sbt "testFull"` — force-run tests skipped by incremental `test`
