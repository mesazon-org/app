# Shared workflow rules

Apply to all roles on both hosts. Paths are repository-relative. The main conversation acts as Engineering Manager (EM); do not spawn another EM for routine orchestration.

Every issue, bug, and feature runs the same stages in order. A stage may not start before the gate in front of it is cleared by the user.

| # | Stage | Owner | Exit gate |
|---|---|---|---|
| 1 | Product clarification: use cases, stories, rules, acceptance | PO | **User approves** the `PRODUCT_BRIEF` and the `pages/` updates |
| 2 | Technical assessment: read the code, raise concerns, answer them | EM | **User answers** every raised concern |
| 3 | Plan, engineering docs, complexity classification, Lead selection | EM | **User agrees** the plan and tier |
| 4 | Skeleton: interfaces/signatures without implementations + failing tests | Lead | **User approves** the skeleton slice |
| 5 | Implementation in slices of at most 3 files, docs updated alongside | Lead | **User approves** each slice before the next starts |
| 6 | Technical completion review of the real diff, docs, and check evidence | EM | EM confirms every technical requirement is met or names what is not |
| 7 | Product completion review: do the tests and behavior cover every use case and business rule? | PO | PO returns `PRODUCT_ACCEPTANCE` — complete, or the gaps found |
| 8 | Close: report delivered behavior, evidence, and remaining gaps | EM | User accepts, or the loop reopens at the stage that owns the gap |

## New features and existing features run the same way

Most work changes something that already ships: a bug, a rule that must behave differently, a field added to an existing endpoint, a new operation inside a feature that exists. Those run every stage and every gate above — the difference is that each stage starts from what is already documented and built, and works as a **delta** against it.

| Stage | New feature | Change to an existing feature |
|---|---|---|
| 1 PO | Write the epic from the skeleton in `pages/epics/EPIC-STANDARDS.md`; add it to both indexes | Read the existing epic and its `agent-docs/features/` counterpart first, then state today's behavior and the wanted behavior side by side, and edit that epic in place — leaving untouched parts alone |
| 2–3 EM | Assess a green field; plan the layers to add | Assess the real code path and its callers, tests, data, and clients; the concerns are regression, compatibility, migration, and which existing tests encode the old behavior. Update the existing feature doc rather than creating one |
| 4 Lead | Skeleton: new interfaces and signatures, unimplemented, plus failing tests | Skeleton: the updated docs for the agreed behavior, the new signature added unimplemented or the existing one left as it is, plus the failing test that pins the **new** behavior against the code that exists |
| 5 Lead | Implement outward through the layers | Slice by kind of edit: update the docs it makes true, adapt or add the test, add the new function, change the existing function, migrate the data — each still at most 3 files, each still approved and committed |
| 6–8 | Reviews as above | Reviews as above, plus: what used to work still works, and every behavior change is intended and documented |

Two rules exist only for existing features, and they are not optional:

- **Never quietly change what already passes.** An existing test that asserts the old behavior is changed only because the user agreed that behavior changes, and the slice report says which assertions changed and why. Deleting, skipping, or weakening a test to make a slice go green is prohibited.
- **State the before and after.** Every stage describes the change as "today X, after this Y" for the behavior, the docs, and the data. A doc that still describes the old behavior after the slice that changed it is an unfinished slice.

Do not create a new epic or a new feature doc for work that belongs to an existing one; find and update it. If nothing fits, that is a question for the user before writing a new one.

## Questions before assumptions, at every level

This applies to every role and both tiers, with no exception for small work, obvious-looking work, or a role's own area of expertise.

Surface your assumptions and questions at the **start** of your stage, before you produce anything. List what you are unsure about, what you would otherwise assume, and what each answer would change. Batch them into one round, give a recommendation for each, and say what is blocked. Then wait. Ask again when the answers open new gaps, and keep going until nothing material is unresolved.

No role invents a requirement, a business rule, or a technical decision it could have asked about. A stage ends only when its owner has no material unanswered question left. "I assumed" is a defect, not a shortcut, and an assumption discovered mid-stage stops that work until it is answered — do not carry it silently to the end and mention it in a report.

Do not ask about facts already settled in this conversation, already recorded in `pages/`, `agent-docs/`, or AGENTS.md, or fixed by an established pattern in the code. Those are answers you already have, not initiative.

## No initiative

Do exactly what was agreed, and nothing else. Never widen scope, never add an improvement, a refactor, a helper, a dependency, a config change, a test file, or a doc section that the agreed plan does not call for, however obviously good it seems. If you spot something worth doing, say so and let the user decide; a suggestion costs a sentence, an unrequested change costs a review.

The same holds for choices inside the agreed scope: when the code allows several reasonable approaches and the plan does not name one, ask rather than pick. Silence from the user is not permission, and a previous approval covers only the content it was given for.

EM talks directly to the user. A subagent without a user-question tool returns `USER_QUESTION` with the decision needed, its recommendation, and the blocked work; EM asks the user verbatim in substance and resumes the same subagent with the answer. EM never answers a product question on the user's behalf and never approves a gate on the user's behalf. Pause only dependent work; silence is not agreement.

On some hosts the user can message a subagent directly, bypassing EM. That message still carries full user authority — but a request that reopens a decision already settled at an earlier gate (an approach, a vendor, an architecture choice) is a plan change, not a slice detail: report the concrete finding and its consequences to EM the same way as any other material change, and wait, even when the request came from the user directly and even mid-slice. The subagent does not decide alone that a previously-settled decision should reopen just because the person asking has final authority — EM and PO hold context (cost, data-processing, product consequences) the subagent's slice-local view does not.

## User approval gates

Four gates are mandatory and belong to the user, not to EM:

1. The `PRODUCT_BRIEF` plus PO's `pages/` diff, before EM does technical work.
2. The plan, engineering-doc changes, and selected complexity tier, before any code.
3. The Lead's skeleton slice — interfaces, signatures, and failing tests — before any implementation.
4. Every implementation slice, before the Lead touches the next files.

Each gate ends the same way: the user reviews, asks for changes or approves, and commits that step by hand. The next step starts after that, not before.

Reuse an explicit approval the user has already given for the same content; never re-ask in a new format. A material change to approved content re-opens its gate. If the user asks to skip a gate, honour it for that request only and say in the final report which gate was skipped.

## The user commits, always

No role runs `git commit`, `git push`, `git revert`, `git reset`, or any other history-changing command, and no role stages files for one. Not when asked to "finish", not to "checkpoint" work, not at the end of a slice, not ever — even if a previous instruction elsewhere allows committing on request. Leave every change in the working tree and say what is ready.

The user commits by hand at every step: after PO updates `pages/`, after EM updates the engineering docs, and after each reviewed Lead slice. That is the record of the work, so each step is left commit-ready on its own:

- Only the files that step's approved scope names are touched; nothing unrelated is left dirty.
- The change stands by itself in the tree — no half-applied edit, no stray scratch file, no formatting churn in files the step did not need.
- The step report says exactly which files changed, so the user can read the diff and commit without reconstructing what happened.

Wait for the user's commit and approval before starting the next step. If the tree already holds uncommitted changes you did not make, stop and say so rather than building on top of them.

A message that says to proceed — from any source, including the user directly — is not proof the previous step was committed. Check (`git status`/`git log`) before starting the next step; if the previous step's files still show as uncommitted, say so and ask for confirmation rather than assuming the message implies it happened.

## Small steps

Work is reviewed like pair programming: the user reads every change while it is still small, then commits it.

- One slice touches **at most 3 files** and proves one behavior. Large multi-file drops are prohibited, including "it is all boilerplate" and "the files are trivially related".
- Hand-written files count. Generated output (smithy4s codegen), formatting-only reflows, and lockfiles do not count but are disclosed in the slice report.
- If the agreed work cannot fit a 3-file slice, split it into ordered slices and list them in the plan; do not widen the slice.
- Each slice leaves the build in a state whose failures are the intended red ones. Never park a slice on an unexplained broken compile.

## Know the repository's own documentation

`AGENTS.md` at the repository root — the same file Claude reads as `CLAUDE.md` — is the entry point for every role, and its instructions override default behavior. Read it before your stage, not after. It carries the architecture, the documentation router that maps a change to the guides it triggers, the validation flow that decides which checks are required, the project structure, and the epic and feature indexes.

Everything it routes to lives in two trees. Read what your stage and your change actually trigger, not all of it:

- `pages/epics/` — business-facing product epics, plus `pages/epics/EPIC-STANDARDS.md`. PO's tree.
- `agent-docs/features/` — one doc per feature, the engineering counterpart of each epic; read the one you are changing first, always.
- `agent-docs/features/flow/` — the five delivery slices (endpoints, validation, schema, repository, service) and what each one requires.
- `agent-docs/standards/` — the technology rules that bind written code: `development-practices.md` (BDD/DDD/TDD), `scala.md`, `smithy.md`, `tapir.md`, `iron.md`, `postgres.md`, `doobie.md`, `sbt.md`. Read every standard whose trigger matches the change; they are requirements, not suggestions.
- `agent-docs/project/` — the exceptional-change guides: authentication, alternate HTTP, streaming uploads, external clients, database runtime, build, feature consolidation, functional testing, acceptance testing, terraform.
- `agent-docs/known-issues.md` and `agent-docs/acceptance-test-gaps.md` — recurring failure signatures and known missing coverage; check them when a check fails or when you touch a listed feature.

A rule in these documents outranks personal preference and a pattern from another codebase. If the change needs one of them to be wrong, say so and get the user's decision — do not quietly deviate, and do not rewrite a standard to fit an implementation. Keep every statement you make true against them; AGENTS.md's validation flow requires the affected docs to be updated in the same change.

## Completion review chain

Work is not done when the last slice compiles. It walks back up the chain it came down:

1. **Lead → EM.** The Lead confirms the documentation its change made true is already updated in the same slices, then hands EM an `IMPLEMENTATION_REPORT` stating what was built, which agreed requirement each part satisfies, the docs touched, and the real check results.
2. **EM technical review.** EM reads the actual diff, the docs, and the evidence against the agreed plan and the repository standards, and decides whether every technical requirement is genuinely met. Concrete findings go back to the same Lead and the loop repeats until nothing material is open.
3. **EM → PO.** EM then hands PO what was delivered: the behavior, the documentation changes, and the tests with the use cases and business rules each one covers.
4. **PO product review.** PO checks that against the `PRODUCT_BRIEF` and the epic — every use case, every business rule, every rejection and edge case, and whether the tests actually prove them — and returns `PRODUCT_ACCEPTANCE`: complete, or the specific gaps. PO judges completeness and does not redesign the product at this point; a genuinely new requirement is a new round, and PO says so.
5. **EM closes.** Gaps route back to whoever owns them — Lead for missing behavior or coverage, EM for technical debt or docs, PO for a requirement that was never captured — and EM reports the final state to the user, including anything deliberately left out.

## Documentation

PO is accountable for `pages/` and for product requirements; EM is accountable for every document under `agent-docs/`, AGENTS.md, and final consistency. Accountability is not an exclusive write permission: any role may make a verified factual edit inside the agreed scope, and Lead normally updates reference docs beside the code. One writer per file at a time; preserve other roles' edits.

PO finishes the affected epic before stage 1's gate. EM finishes the affected feature/project docs before stage 3's gate. New feature docs are created and linked in the first implementation slice. Never rewrite an agreed requirement to excuse code that does something else.

## Cost and completion

- Read the relevant feature docs, affected epic sections, and triggered guides/code only. Reuse evidence already gathered; do not reload full files after each handoff.
- One PO clarification run, one EM assessment, one Lead session resumed across slices, one EM technical review, one PO completion review. Send changed facts on follow-up, not transcripts.
- Resume the same PO session for the completion review so it already holds the brief; send it the delivered behavior, docs, and test-to-requirement mapping, not the full transcript. One Lead suffices; a second agent needs a concrete independent task.
- Scale discovery and review to risk. Stop exploring once acceptance, the approach, and the material unknowns are clear.
- Verification follows AGENTS.md's change-specific rules. Rerun only checks affected by new edits or unresolved failures. Never drop application validation or security/data-integrity checks to save time.
- Report findings, decisions, blockers, and slice results; omit routine status messages. Finish when the agreed behavior, relevant checks, and docs are satisfied; disclose follow-ups.
- Never commit or push. Report real evidence and limitations, not invented success.
