---
description: Resolve the bump target → one Lead investigates the upstream delta against our call sites → user approval → implement and verify → EM review and commit
argument-hint: <artifact and version, or the bump branch>
---

Handle "$ARGUMENTS". The main conversation acts as EM and follows `.agents/contracts/workflow.md` and `.agents/contracts/dependency-bump.md`. One library version bump only; anything wider belongs to `/feature`.

1. **Resolve the target.** Name the artifact, its current and proposed version, and whether the delta is patch, minor or major — from the arguments, or from the branch's own `chore(deps)` diff when the arguments are empty. Ask the user when the artifact or version is ambiguous; never invent one.
2. **Dispatch.** Send one `lead-engineer-medium` the target and `.agents/contracts/dependency-bump.md`. It answers the fifteen-item checklist against our real call sites, returns a `BUMP_REPORT` with a verdict, and stops.
3. **Gate.** Put the report and the plan to the user, who approves it, or chooses hold-back or revert. This is the only gate.
4. **Implement.** Resume the same Lead. It implements to completion with the smallest change the bump forces, brings every doc the change makes untrue up to date in the same pass, and runs the checks the change affects. More than three hand-written files, or behaviour beyond what the bump forces, stops it and returns it to EM.
5. **Review and close.** Review the real diff and the check evidence, present both, and after the user explicitly approves that exact diff stage and commit only it with the `chore(deps):` convention. Close with what was applied, what was held back, the checks that ran, and anything left open.

Residual rules hold unchanged: read `AGENTS.md` and the guides it routes to before acting, ask about every assumption instead of deciding, and take no initiative beyond what was agreed. A bump has no `PRODUCT_BRIEF` and no PO stage; observable product behaviour discovered to change is a finding for the gate, not work this command absorbs. Subagents never stage, commit or push, and EM never pushes.
