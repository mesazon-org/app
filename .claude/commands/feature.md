---
description: Run a feature request through the Product Owner → Engineering Manager → Lead Engineer → Senior Engineer pipeline
argument-hint: <feature description>
---

You are the orchestrator for a feature request: **"$ARGUMENTS"**. You do not implement or design anything yourself — your job is to run the pipeline below, relay information between the roles faithfully (don't summarize away detail they need), and keep the user informed at each handoff. Use `TaskCreate`/`TaskUpdate` to track the task list once the Lead Engineer produces it, so progress is visible.

## 1. Product Owner
Spawn the `product-owner` subagent with the raw request above. Get back the brief.

## 2. Engineering Manager
Spawn the `engineering-manager` subagent with the brief. It may call `AskUserQuestion` on the user directly — let it. It returns a requirements doc with a rough plan where each unit is tagged with a suggested owner (`senior-engineer` for chores, `lead-engineer` for new features / mid-high complexity work). Keep this agent's session alive (don't discard it) — you may need to relay Lead Engineer questions back to it in step 3.

## 3. Engineering Manager ↔ Lead Engineer discussion
Spawn the `lead-engineer` subagent with the EM's requirements doc, and tell it explicitly that it can raise technical questions for the EM. If it raises any:
- Use `SendMessage` to send the question to the *same* engineering-manager agent session from step 2 (do not respawn — it needs the prior context to answer well).
- Use `SendMessage` to send the EM's answer back to the *same* lead-engineer session.
- Repeat until the Lead Engineer has no more open questions.

The Lead Engineer's final output is a task list, each task tagged with an **owner** (`senior-engineer` or `lead-engineer` — the Lead Engineer may have revised the EM's suggestion). Register these as tasks via `TaskCreate` so the user can see progress. Keep this `lead-engineer` session alive too — it implements its own tasks and reviews the Senior Engineer's in step 4, so it needs to stay the same session throughout.

## 4. Implementation
For each task, in the order the Lead Engineer specified, branch on its owner:

**Owner `senior-engineer`:**
1. Mark the task `in_progress`.
2. Spawn a `senior-engineer` subagent for it, passing the task's full description + acceptance criteria. No `model` override needed — its frontmatter default (free tier) is always the right choice, since only chore-level tasks reach this role.
3. Once it reports done, get the diff (`git status` / `git diff`) and send it to the *same* `lead-engineer` session from step 3 for review (`SendMessage`, don't respawn — it already has full context on the plan).
4. If the Lead Engineer requests changes: `SendMessage` the specific feedback back to the *same* senior-engineer session to fix, then re-review. Loop until approved.
5. Mark the task `completed`.

**Owner `lead-engineer`:**
1. Mark the task `in_progress`.
2. `SendMessage` the task's full description + acceptance criteria to the *same* `lead-engineer` session from step 3 (don't respawn — it needs the planning context), asking it to implement the task directly.
3. Once it reports done, mark the task `completed` — no separate review step; the Lead Engineer is the review gate, there's no one above it in this pipeline to review its own work.

## 5. Wrap-up
Once every task is approved, report back to the user: what was built, which files changed, and confirm the feature doc under `docs-claude/features/` was written and linked per `CLAUDE.md`'s documentation rule. Do not commit or push anything — leave that decision to the user.
