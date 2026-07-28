# sbt Build Definition

Project-agnostic standards for a Scala 3 / sbt 2.x-style multi-module build: centralizing dependency versions, creating modules through one helper, CI/local alias parity, and the build tool's caching/incremental-task/pipelining rules (and where a project must deliberately opt out of them).

Not owned here: general Scala language/naming conventions ([scala-new.md](scala-new.md)); what each test tier proves — unit vs. functional vs. integration vs. acceptance ([functional-tests-new.md](../functional-tests-new.md), [integration-tests-new.md](../integration-tests-new.md), [acceptance-tests-new.md](../acceptance-tests-new.md)) — this doc owns only how those tests are *wired and invoked* from the build (forking, docker-image prerequisites, CI cache-busting), not what they assert.

Dense, LLM-oriented rules only — no narrative, no restating a global convention an agent already knows. Record only standards unique to this build. Concrete values for this codebase: [sbt-project.md](sbt-project.md).

## Metabuild conventions

Pin the build tool version, the language version used by application modules, and the JDK version explicitly, in version-controlled files rather than relying on whatever a developer's machine happens to have installed. Format and lint the build definition itself (build files, plugin configuration, helper sources) with the same tooling and dialect used for application code, so the build is held to the same standard as the code it builds.

## Dependency version centralization

State every third-party library version in exactly one file, never inline in a module's build configuration. A single version file makes it possible to see and bump every dependency at a glance, and prevents two modules silently drifting onto different versions of the same library. Pair this with a thin, named wrapper for "add these library dependencies to this module" so call sites read as intent (`withDependencies(...)`) rather than raw list concatenation.

Also centralize, in a small number of clearly-scoped files, the concerns that would otherwise be copy-pasted per module: compiler flags, packaging/image settings, and command aliases. Each such file should own exactly one concern, so a change to (for example) compiler flags touches one file regardless of how many modules exist.

## Module creation through a single helper

Create every new module through one shared helper rather than hand-writing a project definition per module. The helper is the single place that applies cross-cutting settings (compiler options, standard scopes) to every module, so a module can never accidentally be created without them. Adopt one fixed convention for directory layout, project identifier, and `lazy val` naming, and enforce it through the helper rather than through code review alone. Shared infrastructure modules (pure-domain-types, clock, generators, a shared test-support module, one test-harness module per piece of test infrastructure, a schema/migration module) follow the same helper as application modules.

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

## Known harmless test noise

Some test-runner or infrastructure-library noise is a known, harmless artifact of how a forked test JVM shuts down under the current build-tool version, rather than a real dependency conflict or leak. Document any such noise precisely — what it looks like, why it's benign, what does and does not silence it — so it isn't repeatedly misdiagnosed as a regression, and so a future contributor doesn't waste time trying already-ruled-out fixes.

## Command aliases as CI/local parity

Compose command aliases out of small, single-purpose steps, and make CI invoke the exact same alias a developer runs locally — never a bespoke CI-only sequence of raw commands. This guarantees CI can't drift from what "passing locally" means: if the alias changes, both CI and local behavior change together. Provide a read-only "check" alias (fails on any violation, changes nothing) separate from a "fix"/"run" alias (applies changes), for every gate that supports the distinction (formatting, linting), so CI can enforce without mutating and developers can self-fix with one command.

Give every deployable/service its own single CI-entrypoint alias, and make that alias's shape fixed and predictable: reset build state, scope to the right module subset, run the read-only quality gate, then run the full (non-incremental) test task.

## CI wiring

Have CI invoke the single alias defined for the target service/deployable, on the pinned JDK version, with the build tool's own dependency/artifact caching enabled. Publish whatever build artifact the pipeline produces (e.g. a container image) using the tag/repository values injected as environment variables, so the same alias run locally and in CI produces a comparably-tagged artifact. Any lint-on-compile convenience feature that's useful for local incremental feedback should be disabled in CI's plain compile step and instead enforced explicitly by the shared alias, so CI's pass/fail signal comes from one place. Adding a new service/deployable to CI means: a new pipeline definition, a new CI-entrypoint alias for it, and path filters mirroring an existing pipeline definition.
