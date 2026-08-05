# Agent pipeline setup

`/feature "<description>"` runs Product Owner → Engineering Manager → one complexity-selected Lead Engineer through local [OmniRoute](https://github.com/diegosouzapw/OmniRoute).

Shared sources are `.agents/{agents,contracts,commands,skills}/`; matching `.claude/` files are relative symlinks. Edit `.agents/`. Claude-only config/files remain real files in `.claude/`; diverge one shared file by replacing only its symlink. `.codex/agents/` contains thin Codex profiles that reuse the shared contracts and pin native Codex model/effort tiers.

OmniRoute is per-engineer local infrastructure. Never commit its admin password, API key, or provider keys.

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

## Checked-in roles/routing

| Role | Claude | Codex | Ownership |
|---|---|---|---|
| Product Owner | `haiku` | parent default | complete product requirements/decisions |
| Engineering Manager | `sonnet` | parent default | edge cases, doc topology, outcome chunks, complexity |
| Lead LOW | `haiku` | `gpt-5.6-terra`/low | bounded known-pattern work |
| Lead MEDIUM | `sonnet` | `gpt-5.6-sol`/high | contained new behavior through multi-layer/risky work |
| Lead HIGH | `opus` | `gpt-5.6-sol`/xhigh | highest-risk/system-wide work |

EM classifies the whole request by `.agents/contracts/complexity.md`'s highest material trigger. One matching Lead session plans, implements, and reviews the full request; scope changes force reclassification. Shared execution rules live in `.agents/contracts/lead-engineer.md`.

The Product Owner and Lead LOW roles run on `haiku` (Claude Haiku 4.5) — the cheapest capable Claude tier — so the whole pipeline runs on Anthropic models without depending on external free routes. If you instead route through OmniRoute free IDs, verify each with `omniroute chat` before use, as availability changes and some return 401/anti-abuse failures.

## Restrictive Claude permissions

If “don't ask” mode denies tools, add to gitignored `.claude/settings.local.json` `permissions.allow`:

```json
"AskUserQuestion",
"WebFetch",
"WebSearch"
```

This does not bypass Claude Code's separate, non-configurable auto-mode classifier.

## Run

In a fresh Claude Code session:

```text
/feature "add a health-check endpoint"
```

PO owns the final product specification. EM challenges it, asks PO first, escalates unknown stakeholder decisions to the user, maps only required docs/outcome slices, and assigns complexity. The selected expert Lead owns all coding decisions and follows [Feature flow](features/flow/README.md), including same-PR tests/docs.

## Troubleshooting

- Node warning: `nvm use 22`; verify `node --version`.
- `omniroute` missing after restart: fix the nvm block/default alias.
- Models missing: remove `/v1`; fully restart process.
- `@/shared`/`@/lib` crash: known commands above; use manual environment.
- Ambiguous model: set all `ANTHROPIC_DEFAULT_*_MODEL` to available provider-prefixed IDs; restart.
