# Agent pipeline setup

Both hosts use the [shared workflow](../.agents/commands/feature.md). Every issue, bug, and feature runs the same stages in order, each behind a user gate where one is defined, and the finished work walks back up the same chain it came down:

1. PO clarifies the product until nothing material is unanswered and updates `pages/`.
2. **User approves** the `PRODUCT_BRIEF` and the `pages/` diff.
3. EM reads the code, raises technical concerns (new dependency, refactor blast radius, approach, contradictions, risk), gets the user's answers, updates the engineering docs, and proposes a plan with ordered ≤3-file slices and a `MEDIUM`/`HIGH` classification.
4. **User approves** the plan, docs, and tier.
5. The selected Lead delivers the skeleton slice (interfaces and failing tests), then implements slice by slice, keeping docs current inside each slice and stopping for approval each time.
6. EM reviews the real diff, docs, and evidence for technical completeness against the plan.
7. PO reviews the delivered behavior and the tests against the brief and the epic for product completeness, returning `PRODUCT_ACCEPTANCE` or gaps.
8. EM routes any gap to its owner and closes with the user.

The main conversation is EM and never approves a gate on the user's behalf. No separate orchestrator.

New features and changes to features that already ship run the same stages and the same gates. For an existing feature each stage starts from the existing epic, feature doc, and code and works as a delta — stated as "today X, after this Y", with those documents updated in place rather than replaced, and the work sliced by kind of edit: update the docs, adapt or add the test, add the new function, change the existing function, migrate the data. Existing tests are adapted only because the user agreed the behavior changes, never to make a slice pass.

Three rules bind every role at every tier, small work included:

- **Read the repository's own instructions first.** `AGENTS.md` (served to Claude as `CLAUDE.md`) is the entry point; its documentation router names the `agent-docs/` feature docs, flow slices, standards, and project guides a change triggers, and its validation flow names the required checks. Those standards are requirements — deviating needs the user's decision, not a quiet exception.
- **Ask, do not assume, and take no initiative.** Every role opens its stage by listing its open questions and would-be assumptions with recommendations, and waits. Nothing outside the agreed scope gets added, improved, or refactored on an agent's own judgement.
- **The user commits, always.** No agent runs `git commit`, `git push`, or stages files. The user reviews and commits by hand after PO's `pages/` update, after EM's engineering-doc update, and after every Lead slice, so each step is left commit-ready and self-contained.

## Shared sources and hosts

`AGENTS.md` is shared through `CLAUDE.md`'s relative symlink. `.agents/` owns roles, contracts, commands, and skills; matching `.claude/` files are symlinks. `.codex/agents/*.toml` holds native metadata/model settings and references the same role Markdown, excluding Claude YAML. Both hosts read the same product and engineering docs. Keep credentials and local settings gitignored.

Codex uses the [native TOML format](https://learn.chatgpt.com/docs/agent-configuration/subagents); Claude uses [Markdown subagents](https://code.claude.com/docs/en/sub-agents). Open a fresh session after profile changes. Claude: `/feature "<description>"`. Codex: `Use the feature workflow for <description>`; AGENTS.md routes the request without requiring a native slash-menu entry.

EM speaks directly to the user and handles agent dispatch. If a subagent cannot ask the user directly, it returns `USER_QUESTION` to EM. Use the same workflow sequentially if agents are unavailable and disclose self-review. Ordinary ChatGPT without repository/tools access cannot automatically load this setup. Codex uses its configured provider; OmniRoute below is optional Claude infrastructure.

## Roles and effort

| Role | Claude profile | Codex profile | Use |
|---|---|---|---|
| PO | `sonnet` | `gpt-5.6-luna`/high | clarify the product by asking, never assuming; accountable for `pages/`; final product-completeness review |
| EM | main session; optional separate profile `sonnet` | main session; optional separate profile `gpt-5.6-luna`/high | technical concerns, plan, complexity, dispatch, approval relay, technical-completion review, close |
| Lead MEDIUM | `sonnet` | `gpt-5.6-luna`/high | contained feature/integration work |
| Lead HIGH | `opus` | `gpt-5.6-sol`/high | high-risk implementation |

Only two Leads exist — the medium and the strongest model of each provider. The [complexity contract](../.agents/contracts/complexity.md) decides between them; it does not change the process, since both tiers run every gate and keep slices at three hand-written files or fewer. Model availability depends on the account/host. Main EM uses the user's selected session model; the optional EM profile does not change it.

Documentation ownership is accountability, not a write restriction. PO finishes `pages/` before gate 1 and EM finishes `agent-docs/` before gate 2; the Lead updates reference docs beside the code it changes. All roles may make verified factual edits within agreed scope. One writer per file. EM's review covers code and docs together; PO is consulted again for product ambiguity and always for the completion review that closes the loop.

The [shared contract](../.agents/contracts/workflow.md) owns the stages, the user gates, the question protocol, and the slice size limit. AGENTS.md owns change-specific verification: docs/config-only edits use relevant structural checks, while application changes retain applicable tests and lint. A slice is a review increment, not a PR — one PR normally carries several approved, dependency-ordered slices. No unrelated tests or repeated passing checks.

To evaluate changes over several comparable tasks, record available time-to-first-code-edit, total time, token usage, and material rework. Use host-reported metrics when available; do not add monitoring agents or invent missing counters. Compare similar risk levels before changing more settings.

## Optional Claude OmniRoute setup

Never commit OmniRoute's admin password, API key, or provider keys. The following records the tested local setup; verify provider IDs against your installation.

## Requirements

- nvm; Node 22 `>=22.22.2` (tested `22.23.1`); bundled npm 10.x.
- `omniroute` npm package (tested `3.8.48`; ~700 MB unpacked).
- Provider connections/API keys owned by the engineer.

Use nvm: system/Homebrew Node may be too old and global npm may require `sudo`.

## Install

```sh
brew install nvm
mkdir -p ~/.nvm
```

Add to `~/.zshrc`:

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "/opt/homebrew/opt/nvm/nvm.sh" ] && \. "/opt/homebrew/opt/nvm/nvm.sh"
[ -s "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm" ] && \. "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm"
```

Then:

```sh
nvm install 22
nvm alias default 22
npm install -g omniroute
omniroute serve --daemon --no-open
curl http://localhost:20128/v1/models
```

Remove any old custom npm prefix/PATH. If needed: `nvm use --delete-prefix`.

Known OmniRoute 3.8.48 packaging bug: `omniroute config set claude` and `omniroute setup --list` fail on `@/shared`/`@/lib`. Do not use them; `serve`, `status`, `doctor`, `providers`, `chat`, `setup-claude`, and `launch` work. Configure manually below.

## Provider setup

At `http://localhost:20128`:

1. Set a local admin password.
2. Add Claude subscription and optional paid/free providers.
3. Create an OmniRoute API key.
4. Verify:

```sh
OMNIROUTE_API_KEY=<key> omniroute chat "reply with exactly: pong" --model auto
```

## Claude Code environment

Add to `~/.zshrc`:

```sh
export ANTHROPIC_BASE_URL="http://localhost:20128"
export ANTHROPIC_AUTH_TOKEN="<omniroute-api-key>"
export ANTHROPIC_API_KEY=""
export CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1

export ANTHROPIC_DEFAULT_OPUS_MODEL="cc/claude-opus-4-8"
export ANTHROPIC_DEFAULT_SONNET_MODEL="cc/claude-sonnet-5"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="cc/claude-haiku-4-5-20251001"
```

Explicit aliases prevent `400 Ambiguous model` when multiple providers expose the same bare name. IDs may drift; inspect `OMNIROUTE_API_KEY=<key> omniroute models`, update all three, then verify a prefixed model:

```sh
OMNIROUTE_API_KEY=<key> omniroute chat "reply with exactly: pong" --model cc/claude-opus-4-8
```

Restart every Claude Code process; environment is read at startup. `ANTHROPIC_BASE_URL` must not end in `/v1`.

## User questions and permissions

Run the main conversation in a mode that allows user interaction. Claude's `dontAsk` mode denies `AskUserQuestion` even if allowlisted; use a normal interactive mode or relay the question as plain text. A tool permission denial is not a user answer. Do not change local permissions or bypass host approval controls automatically.

## Troubleshooting

- Node warning: `nvm use 22`; verify `node --version`.
- `omniroute` missing after restart: fix the nvm block/default alias.
- Models missing: remove `/v1`; fully restart process.
- `@/shared`/`@/lib` crash: known commands above; use manual environment.
- Ambiguous model: set all `ANTHROPIC_DEFAULT_*_MODEL` to available provider-prefixed IDs; restart.
