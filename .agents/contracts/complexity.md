# Complexity contract

Two Leads exist: `lead-engineer-medium` and `lead-engineer-high`. EM classifies every issue as `MEDIUM` or `HIGH` from concrete implementation risk — not document length, not diff size, not a hypothetical worst case — and states the classification at stage 3's gate with a one-line reason.

- `MEDIUM`: contained work on established patterns. Bounded behavior corrections, a feature inside known layers, new config or test infrastructure, routine schema work, ordinary auth/transaction/integration edge cases.
- `HIGH`: security architecture, destructive or irreversible data migration, financial/audit correctness, difficult concurrency or distributed consistency, broad breaking changes, or work whose blast radius EM cannot bound from the code it read.

Use the highest material trigger present. When the tier is genuinely borderline, choose `HIGH` and say why in one line; unclear user intent is resolved with the user, never absorbed as risk. Reassess only when new evidence materially changes the risk, and tell the user before switching Lead.

Tier changes the model and the depth of reasoning, never the process. Both tiers run every stage and every user gate in `workflow.md`, keep slices at three files or fewer, and start from the skeleton-and-failing-tests slice. `HIGH` additionally records explicit risk and rollback reasoning in the plan and gets deeper review of the touched boundary.
