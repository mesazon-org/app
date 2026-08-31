---
description: Product Owner → Engineering Manager → complexity-selected Lead Engineer
argument-hint: <feature description>
---

Orchestrate **"$ARGUMENTS"**. Do not design/code. Preserve full role outputs; keep sessions alive; show handoffs/progress. Never commit/push.

## 1 Product

Spawn `product-owner` with the raw request. Keep session. Require `PRODUCT_SPEC`.

## 2 Engineering

Spawn `engineering-manager` with `PRODUCT_SPEC`. Keep session.

If EM returns `PRODUCT_QUESTIONS`:

1. Send them to the same PO.
2. PO answers from context, asking the user directly via `AskUserQuestion` if it can't; require an updated `PRODUCT_SPEC`.
3. Send the updated `PRODUCT_SPEC` to EM.
4. Repeat until EM returns `ENGINEERING_PACKAGE` with no open product questions.

Do not let EM bypass PO for product decisions unless PO explicitly escalates.

## 3 Complexity route

Validate package level/profile:

| Level | Agent |
|---|---|
| LOW | `lead-engineer-low` |
| MEDIUM | `lead-engineer-medium` |
| HIGH | `lead-engineer-high` |

Spawn exactly that Lead with the full package. Keep session for planning, implementation, and final review.

If Lead returns `REQUIREMENT_QUESTIONS`, send to same EM. EM answers from package or routes back to PO per the loop above (PO asks the user if needed). Return the resolved answer and updated package/spec to the same Lead. Lead owns coding decisions; never route coding questions to EM/PO/user.

## 4 Plan/execute

Require `IMPLEMENTATION_PLAN`. Register tasks with `TaskCreate`.

For each task in order:

1. `TaskUpdate` → `in_progress`.
2. Send full task + package to same Lead and request implementation/verification.
3. If requirement uncertainty appears, use step 3 escalation; resume same Lead.
4. Require command evidence; `TaskUpdate` → `completed`.

After tasks, ask same Lead for full-diff review and `IMPLEMENTATION_REPORT`. If it finds issues, track/fix/recheck before completion.

## 5 Wrap

Report requirements delivered, docs/status, verification, remaining/N/A work, and complexity/profile used. Confirm feature doc lifecycle and docs currency from `AGENTS.md`.
