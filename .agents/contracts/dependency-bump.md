# Dependency bump contract

Applies to one library version bump — the Scala Steward `chore(deps)` case, or the same change made by hand. Scope is a version val in `project/Dependencies.scala`, a plugin version in `project/plugins.sbt`, or the sbt version in `project/build.properties` — plus whatever that version forces in our own code, tests, and docs. Anything wider than that is not a bump: route it through `/feature`.

A bump exists because a version number changed, not because a requirement did. The work is therefore investigation first and code second: read what actually changed upstream, read it against our real call sites, and decide whether to take it. "Nothing to apply" is the normal outcome for most bumps and must stay cheap to reach.

This contract overrides two rules in `.agents/contracts/lead-engineer.md` for a bump: the skeleton slice and the per-slice gates. A version number has no new behaviour to pin, so there is nothing for a skeleton test to fail against, and the single report-and-plan gate replaces the slice-by-slice approvals. Everything else in that contract applies unchanged — no initiative beyond what was agreed, ask before assuming, never stage/commit/push, the forced-companion-change rule, the evidence rule for when a library cannot do what the plan assumes, and documentation kept current in the same pass rather than as a cleanup afterwards.

## Flow

| # | Stage | Owner | Exit |
|---|---|---|---|
| 1 | Resolve the bump target: artifact, current → proposed version, delta kind | EM | Target is unambiguous, or the user has been asked |
| 2 | Investigate the bump against our usage and answer the checklist | one `lead-engineer-medium` | Lead returns a `BUMP_REPORT` with a verdict and stops |
| 3 | The gate: approve the report and the plan, or choose hold-back or revert | user | **User approves** |
| 4 | Implement to completion with the smallest change, docs current, checks run | the same Lead, resumed | Lead returns an `IMPLEMENTATION_REPORT` |
| 5 | Review the real diff and evidence, present it, commit after approval, close | EM | User approves the exact diff; the commit lands |

One Lead handles stages 2 and 4 — the same session resumed, so the investigation context is not rebuilt. There is no second agent, no `PRODUCT_BRIEF`, and no PO stage: there is no product requirement in a version number. If the bump turns out to change observable product behaviour, that is a finding for the gate and routes to `/feature`; this command does not absorb it.

## Gates and commits

`.agents/contracts/workflow.md`'s stage 1, 3, 4 and 5 gates collapse into the single report-and-plan gate at stage 3. The pre-commit approval of the exact diff is not a pipeline gate but the standing rule from that file's §"User-approved commits by the main-conversation EM", which applies unmodified. Two user touchpoints, then; only the first is called "the gate". This collapse is specific to a bump and weakens that section for no other work.

Subagents never run `git add`, `git commit`, `git push`, `git revert`, or `git reset`. The main-conversation EM stages and commits only the exact diff the user has explicitly approved, and never pushes. A local `PreToolUse` hook in `.claude/settings.local.json` additionally denies `git commit` and `git push` outright, so in practice the user runs the commit themselves; that is the standing condition, not a workaround.

Commit messages keep the Scala Steward convention `chore(deps): ...` rather than `/feature`'s `feat: <issue number> <epic title> <slice description>`, because a Steward PR has no issue number and no epic.

## Where the evidence comes from

The Lead has `Read`, `Write`, `Edit`, `Bash`, `Grep` and `Glob`, and no web tools. Every upstream fact below is reachable through `Bash`, and the report says which of these three sources each answer came from:

1. **The project's own changelog and tags**, by `curl` — for example `curl -s https://raw.githubusercontent.com/apache/tika/4.2.0/CHANGES.txt`, with `https://api.github.com/repos/<org>/<repo>/tags` to confirm which versions actually exist between ours and the target. This serves items 2, 3 and 4.
2. **The POM and its parent on Maven Central**, at `https://repo1.maven.org/maven2/<group path>/<artifact>/<version>/<artifact>-<version>.pom` — diff ours against the target's to see new, removed and rescoped dependencies, and read the parent's `dependencyManagement` for the version a newly arrived transitive resolves at. This serves item 7.
3. **The sources and class jars already in the Coursier cache** (`~/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/...`, which usually holds both versions once the bump has resolved) — extract the same type from each version's `-sources.jar` and `diff` them. This settles item 3 and much of item 6 conclusively rather than by inference: on the Tika 4.1.0 → 4.2.0 bump it proved `org/apache/tika/Tika.java` byte-identical between the two, eliminating the whole "the API changed" hypothesis in one step.

Cache evidence outranks a changelog's silence. A changelog that does not mention a type is not proof the type did not change. When a source genuinely cannot be reached, the item is answered "could not verify" with the reason — never with a guess, and never with a claim the changelog implies but does not say.

## Investigation checklist

The Lead works through all fifteen items and answers each one in its `BUMP_REPORT`. This is a required checklist, not a required report length: an item that genuinely does not apply to a given bump is answered "not applicable" with one clause of reason. Padding a report to look thorough is a defect in the same way an unasked assumption is.

Weight follows the kind of bump. A library bump sits on items 2 to 7. An sbt or plugin bump has no library call sites to read, so items 2 to 6 are usually thin and the weight moves to item 8 (plugin cross-version `_sbt2_3`) and item 11 — `agent-docs/known-issues.md` already records two sbt-version-coupled signatures, the cache-linked SemanticDB file that needs a one-time `sbt "clean; runLint"` after the upgrade, and the action cache that replays a stale compile failure.

1. **Target.** The artifact, current → proposed version, and whether the delta is patch, minor or major. Whether a newer release exists than the one Steward proposed. Whether sibling artifacts share the same version val — `poiV` covers `poi` and `poi-ooxml`, and the `http4s`, `tapir` and `jsoniter` families each resolve from one val.
2. **Upstream delta.** The release notes or `CHANGES` for every version between ours and the target, not only the target. Breaking changes, removals, renames, relocations.
3. **Deprecations, both directions.** APIs newly deprecated that we call, and previously deprecated APIs now removed.
4. **Security.** CVEs or hardening the release fixes, and whether our usage sits on the affected path.
5. **Our usage surface.** Every import and call site with `file:line`. The release notes are read against that list, never in the abstract.
6. **Silent behaviour change.** The dangerous class: same signature, different result — changed defaults, limits, detection, formatting, time zones, error shapes. Two standing examples in this repository: Tika's content detection behind `SupportedMediaType`, where a detection change silently moves the accept/reject boundary of every upload, and POI's workbook parse, which is the structural backstop for spreadsheet extraction.
7. **Transitive delta.** New, removed or relocated artifacts; a companion module now required; `show <module>/evicted` for binary-compatibility warnings; version skew with siblings, such as the `log4jToSlf4jV` pin that tracks POI's transitive `log4j-api`. An update that needs a `dependencyOverrides` entry to resolve is **held back** — the rule `agent-docs/project/build.md` already applies to STTP AI 0.12.0.
8. **Toolchain and licence.** A new minimum JDK, Scala or sbt requirement, sbt plugin cross-version (plugins must publish `_sbt2_3`), or a licence change.
9. **Non-compile surfaces.** `ServiceLoader`/SPI registrations, resource and config file formats, logging bridges, reflection, native libraries, Docker packaging.
10. **Test impact.** Which existing specs pin behaviour the release changes. Extend the existing spec first, and name every assertion the change invalidates before touching it.
11. **Known issues.** Check `agent-docs/known-issues.md` for the failure signature before diagnosing it as new. Record a genuinely new reusable signature there, in that file's severity / status / signature / cause / fix / verification shape, once it is resolved.
12. **Adoptable improvements.** New API that would simplify or harden an existing call site — proposed in the report, never taken silently. The no-initiative rule in `.agents/contracts/workflow.md` applies unchanged.
13. **Verdict.** Exactly one of: take as-is; take with the listed code changes; take pinned or held back, with the reason; revert the bump, with the reason. "Nothing to apply" is a valid, reportable outcome — never invent work to justify the run.
14. **Checks**, in the order `agent-docs/project/build.md` actually used them, with the module and service derived from where the dependency is actually wired rather than assumed to be the gateway: `sbt "clean; compile"`, then `sbt "Test/compile; <affected service>-it/Test/compile"`, then `sbt "runLint"`, then `show <affected module>/evicted`, then the affected targeted specs, then the affected service's `<service>-build` alias. For a `backendGatewayCore` dependency — where most of these land — that reads `sbt "Test/compile; gateway-it/Test/compile"`, `show gateway-core/evicted`, `gateway-core/testOnly io.mesazon.gateway.fun.*` and `sbt "gateway-build"`; a bump to `backend/domain`, `backend/test-kit` or `backend/waha` substitutes its own modules and alias — `waha-it/Test/compile` and `waha-build` — and `waha-build` also runs when the bumped version val is shared with a `backend/waha` module. Report what ran, what did not, and why. `build.sbt` defaults `ENABLE_SCALA_LINT_ON_COMPILE` to `true` locally, so a bare `sbt compile` can fail for scalafix/scalafmt reasons that read as compile errors; isolate a genuine compiler error with `ENABLE_SCALA_LINT_ON_COMPILE=false`.
15. **Docs owed.** Refresh the dependency-update table and verification paragraph in `agent-docs/project/build.md`. Update any feature doc that states the library's behaviour — `agent-docs/features/customer-book.md` and `agent-docs/project/streaming-uploads.md` for Tika and POI. Add the known-issues entry if one was earned.

## Standing rules

Every version has exactly one owner file, the ownership split `agent-docs/project/build.md` already documents: library versions live only in `project/Dependencies.scala` as `<lib>V` vals, plugin versions only in `project/plugins.sbt`, the sbt version only in `project/build.properties`. Never inline a version in a module, per `agent-docs/standards/sbt.md`, and never add a second copy of a version a sibling artifact already resolves from.

A revert verdict is carried out as a **forward** change: restore the previous version in whichever of those three owner files the bump changed, and commit that with the `chore(deps):` convention. Never `git revert`, `git reset`, or any other history-changing command, by any role. Abandoning the Steward branch or closing its PR is the user's disposal to make — the Lead reports the verdict and stops, and EM relays it.

The safety valve replaces `/feature`'s per-slice gates. If the adaptation needs more than three hand-written files, or changes behaviour beyond what the bump forces, the Lead stops and returns to EM instead of pressing on. A bump that grows into a feature is reclassified, not completed quietly.
