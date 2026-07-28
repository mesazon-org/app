# Agent pipeline setup (OmniRoute + Claude Code roles)

This repo has a 4-role Claude Code subagent pipeline — Product Owner → Engineering Manager → Lead Engineer → Senior Engineer — invoked via `/feature "<description>"`. The roles themselves are checked into git (`.claude/agents/*.md`, `.claude/commands/feature.md`) — cloning the repo is enough to get them. What's **not** checked in, and what every engineer must set up once on their own machine, is [OmniRoute](https://github.com/diegosouzapw/OmniRoute), the local AI gateway that routes each role's Claude Code calls across model tiers (your Claude subscription, paid API keys, free providers) instead of hard-coding one model for everything.

OmniRoute runs **locally per engineer** — it is not shared infrastructure. Each person configures their own provider connections (their own Claude subscription login, their own API keys) in their own local instance. Never commit an OmniRoute API key, provider API key, or the admin password to this repo.

## Prerequisites

| Tool | Version | Installed via | Purpose |
|---|---|---|---|
| Homebrew | any current | [brew.sh](https://brew.sh) | installs `nvm` |
| nvm | any current (`0.40.6` at time of writing) | `brew install nvm` | manages Node versions without needing `sudo` for global npm installs |
| Node.js | `>= 22.22.2` on the 22.x LTS line (tested on `22.23.1`) | `nvm install 22` | OmniRoute requires this minimum — older 22.x builds trigger a native-module (`better-sqlite3`) warning |
| npm | bundled with Node (`10.x`) | comes with Node via nvm | installs OmniRoute globally |
| OmniRoute | `omniroute` npm package, tested at `3.8.48` | `npm install -g omniroute` | the local AI gateway itself |

Don't install Node/npm via the system installer or plain `brew install node` — see step 1 for why nvm specifically is needed.

## 1. Install Node 22 via nvm

The system/Homebrew Node on a fresh Mac is usually not the version OmniRoute wants (it warns below `22.22.2`), and installing npm globals against system Node needs `sudo`. Use nvm instead:

```sh
brew install nvm
mkdir -p ~/.nvm
```

Add to `~/.zshrc` (or `~/.bash_profile` if you're on bash):

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "/opt/homebrew/opt/nvm/nvm.sh" ] && \. "/opt/homebrew/opt/nvm/nvm.sh"
[ -s "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm" ] && \. "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm"
```

Open a new terminal, then:

```sh
nvm install 22
nvm alias default 22
node --version   # should print v22.22.2 or newer
```

If you'd previously worked around npm's global-install permissions with a custom prefix (e.g. `~/.npm-global` + a `PATH` export), remove both now — nvm's Node has its own user-writable global directory, so that workaround is unnecessary and will conflict (`nvm use` will refuse to run until you `nvm use --delete-prefix` to clear it).

## 2. Install OmniRoute

```sh
npm install -g omniroute
```

This is a large package (~700MB unpacked, several minutes). Start it:

```sh
omniroute serve --daemon --no-open
```

Confirm it's up: open http://localhost:20128 or `curl http://localhost:20128/v1/models`.

**Known bug (v3.8.48, current at time of writing):** `omniroute config set claude` and `omniroute setup --list` crash with `Cannot find package '@/shared'` (or `'@/lib'`) — this is an upstream packaging/source bug (some `@/...` path-aliased imports point at directories with no `index.ts`, even in the upstream GitHub repo, not something fixable from our end). **Don't use those two commands.** Everything else (`serve`, `status`, `doctor`, `providers`, `chat`, `setup-claude`, `launch`) works fine — wire Claude Code manually instead (step 4 below). If this gets fixed upstream, `omniroute config set claude --api-key <key> --yes` would be the shorter path.

## 3. Configure your provider tiers (manual, one-time, per engineer)

Open the OmniRoute dashboard (http://localhost:20128) — first run prompts you to set an admin password (pick your own; it's local-only). Then, in the dashboard:

1. Add a **Claude** provider connection and sign in with your Claude subscription — this is what the Lead Engineer / Engineering Manager roles will use for their heavier reasoning.
2. Optionally add paid API keys and/or free providers for cheaper tiers (used for the Product Owner's low-stakes brief-writing and routine Senior Engineer implementation work).
3. Grab an OmniRoute **API key** from the dashboard (Settings → API Keys) — this authenticates *your* Claude Code to *your* local gateway, separate from any upstream provider keys.

Sanity-check the key works before moving on:

```sh
OMNIROUTE_API_KEY=<your-key> omniroute chat "reply with exactly: pong" --model auto
```

## 4. Point Claude Code at the gateway

Add to `~/.zshrc`:

```sh
export ANTHROPIC_BASE_URL="http://localhost:20128"
export ANTHROPIC_AUTH_TOKEN="<your-omniroute-api-key>"
export ANTHROPIC_API_KEY="" # must be explicitly empty, or Claude Code may prefer it over ANTHROPIC_AUTH_TOKEN
export CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1
```

**Also required**, not optional: once OmniRoute has more than one provider capable of serving the same bare model name (e.g. its built-in `cc` — Claude Code subscription — tier alongside a `claude` provider connection you added), Claude Code's default alias resolution (`opus` → `claude-opus-4-8`) becomes ambiguous and every request fails with `API Error: 400 Ambiguous model 'claude-opus-4-8'. Use provider/model prefix`. Pin the aliases explicitly:

```sh
export ANTHROPIC_DEFAULT_OPUS_MODEL="cc/claude-opus-4-8"
export ANTHROPIC_DEFAULT_SONNET_MODEL="cc/claude-sonnet-5"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="cc/claude-haiku-4-5-20251001"
```

Run `OMNIROUTE_API_KEY=<your-key> omniroute models` to see the exact provider-prefixed IDs available for *your* configured providers — the ones above are the `cc` tier's current names and may drift as new Claude models ship. Sanity-check before moving on:

```sh
OMNIROUTE_API_KEY=<your-key> omniroute chat "reply with exactly: pong" --model cc/claude-opus-4-8
```

Claude Code reads all of this once at startup, so **restart every open Claude Code session** (including the one you used to edit `.zshrc`) after this step. Once restarted, `/model` should list gateway-routed models alongside the native Claude ones.

## Cost: who actually uses your paid subscription

A naive setup routes every one of the four roles through your paid Claude subscription — for a small ask, that's a lot of subscription usage for a lot of low-stakes work (asking clarifying questions, writing a brief). The defaults checked into `.claude/agents/*.md` instead only spend paid tokens where it actually matters:

| Role | Default model | Why |
|---|---|---|
| `product-owner` | `oc/deepseek-v4-flash-free` — a free tier OmniRoute ships with no extra setup | restating a request doesn't need a frontier model |
| `engineering-manager` | `oc/deepseek-v4-flash-free` | same reasoning — clarifying questions and a requirements doc don't need the paid tier |
| `senior-engineer` | `oc/deepseek-v4-flash-free` — always | this role only ever gets chore-level tasks (renamings, doc-only changes, small isolated fixes) by design, see below — nothing it does needs the paid tier |
| `lead-engineer` | `opus` (→ `cc/claude-opus-4-8`, paid) — always | this role implements new features and mid/high-complexity work itself *and* is the quality gate reviewing the Senior Engineer's chores — don't cheapen it |

`oc/deepseek-v4-flash-free` is the one free-tier model I confirmed actually works reliably — several other `-free`-suffixed options in `omniroute models` (`minimax-m3-free`, `qwen3.6-plus-free`) returned `401 Unauthorized`, and the `ddgw/*` free gateway hit DuckDuckGo's anti-abuse challenge. Re-run `OMNIROUTE_API_KEY=<your-key> omniroute chat "reply with exactly: pong" --model <id>` before trusting any other free model, and don't assume availability is stable — OmniRoute's free catalog can change.

**How work is split between Senior and Lead**: the Engineering Manager tags each unit of work in its rough plan with a suggested owner — `senior-engineer` for chores, `lead-engineer` for anything that's a new feature or non-trivial (the Lead Engineer can override this during planning if its technical read disagrees). The orchestrator then routes each task accordingly: `senior-engineer` tasks get spawned fresh and reviewed by the Lead Engineer before being marked done; `lead-engineer` tasks are implemented by the Lead Engineer directly, in the same session that did the planning, with no separate review step (there's no one above it in the pipeline to review its own work). This means the cost split is really an ownership split, not a per-task model override — no dynamic model selection needed at spawn time.

If you want a different split (e.g. also give the Engineering Manager a paid tier for tricky requirements), edit the `model:` line directly in the relevant `.claude/agents/*.md` file.

## If you run Claude Code in an autonomous/"don't ask" permission mode

The Engineering Manager needs `AskUserQuestion` to ask you clarifying questions directly, and any role may need `WebFetch`/`WebSearch`. If your permission mode auto-denies anything not explicitly allow-listed (you'll see errors like `Permission to use AskUserQuestion has been denied because Claude Code is running in don't ask mode`), add these to `.claude/settings.local.json`'s `permissions.allow` list:

```json
"AskUserQuestion",
"WebFetch",
"WebSearch"
```

`.claude/settings.local.json` is gitignored (personal, per-machine) — this isn't something that ships with the repo, every engineer running the pipeline under a restrictive/autonomous permission mode needs to add it themselves. Note this is a different mechanism from Claude Code's separate heuristic "auto mode classifier" safety layer (the one that produces `Permission for this action was denied by the Claude Code auto mode classifier` errors) — that layer isn't configurable via `settings.json` and may still occasionally block an action regardless of your allow list.

## 5. Use it

From inside this repo, in a fresh Claude Code session:

```
/feature "add a health-check endpoint"
```

This runs the request through Product Owner → Engineering Manager (may ask you clarifying questions) → Engineering Manager/Lead Engineer technical discussion → Senior Engineer implementation → Lead Engineer review, following `docs-claude/adding-a-feature.md`'s order of work throughout.

## Troubleshooting

- **Node version warning on `omniroute serve`**: you're not on nvm's Node 22 — check `node --version`, re-run `nvm use 22`.
- **`omniroute: command not found` in a new terminal**: nvm block missing/misplaced in `~/.zshrc`, or you never ran `nvm alias default 22`.
- **Claude Code not picking up gateway models after restart**: confirm `ANTHROPIC_BASE_URL` has no trailing `/v1` and that you fully quit and reopened the terminal/session (env is read once at process start).
- **`omniroute config set claude` / `omniroute setup --list` crash**: expected, see the known-bug note in step 2 — use step 4's manual env vars instead.
- **`API Error: 400 Ambiguous model 'claude-opus-4-8'. Use provider/model prefix`**: you're missing the `ANTHROPIC_DEFAULT_*_MODEL` env vars from step 4, or added a provider connection that now duplicates a model the built-in `cc` tier already serves. Add/re-check the three `ANTHROPIC_DEFAULT_*_MODEL` exports and restart Claude Code.
