---
name: project-setup
description: Bootstrap a fresh macOS machine to build/run Mesazon and use its /feature agent pipeline — installs and configures asdf, the asdf Java plugin, the Java version pinned in .tool-versions, sbt, nvm + Node + npm, OmniRoute (pausing to ask the user for their OmniRoute API key), and Docker. Always checks what's already installed/configured for each tool before touching anything, and only fixes what's missing, so it's safe to re-run. Use this when a new engineer needs their laptop set up for this repo, when repository-setup.md or agent-pipeline-setup.md steps need to be (re)applied on this machine, or when the user asks to "set up my machine" / "bootstrap the environment" / "install everything needed to run this repo".
---

# Project setup

Idempotent machine bootstrap for building/running Mesazon and using the `/feature` agent pipeline. This is the "just do it" version of [`docs/repository-setup.md`](../../docs/repository-setup.md) and [`docs-claude/agent-pipeline-setup.md`](../../docs-claude/agent-pipeline-setup.md) — read those for the *why* behind each step; keep all three in sync if one changes.

Work through this in two passes: **audit everything first, then apply fixes.** Don't skip straight to installing — the whole point is to only touch what's actually missing or wrong, and to show the engineer running this a clear picture before making changes.

## Phase 1 — Audit

For each tool below, check its current state and collect the result into a table (Tool | Required | Found | Action needed) before changing anything. Run these checks from the repo root (`cd` there first if not already).

1. **asdf**: `command -v asdf`
2. **asdf Java plugin**: `asdf plugin list 2>/dev/null | grep -qx java`
3. **asdf-managed Java version**: read the pinned version from `.tool-versions` (`awk '/^java/{print $2}' .tool-versions`), compare against `asdf list java 2>/dev/null`
4. **sbt**: `command -v sbt` (the exact version is pinned in `project/build.properties` and sbt self-bootstraps to it — just check the launcher exists)
5. **nvm**: `[ -s "$(brew --prefix nvm 2>/dev/null)/nvm.sh" ]` or `~/.nvm` present
6. **Node ≥ 22.22.2**: source nvm, then `node --version` (compare — anything below `22.22.2` on the 22.x line triggers an OmniRoute native-module warning)
7. **npm**: bundled with Node — just confirm `command -v npm` after sourcing nvm
8. **OmniRoute**: `command -v omniroute` after sourcing nvm (it must resolve to the nvm-managed Node's global bin, not any stray system install)
9. **Claude Code ↔ OmniRoute wiring**: `grep -q 'ANTHROPIC_BASE_URL="http://localhost:20128"' ~/.zshrc`
10. **Interactive-tool permissions** (only matters if running an autonomous/"don't ask" permission mode): check `.claude/settings.local.json`'s `permissions.allow` list for `AskUserQuestion`, `WebFetch`, and `WebSearch`
11. **Docker**: `command -v docker` **and** `docker info` succeeds (CLI present isn't enough — the daemon/Docker Desktop must actually be running)

Print the audit table to the user before Phase 2.

## Phase 2 — Apply, in order

Only act on rows Phase 1 flagged. The order matters — later tools depend on earlier ones (Java before sbt, nvm before OmniRoute).

### 1. asdf
```sh
brew install asdf
```

### 2. asdf Java plugin
```sh
asdf plugin add java https://github.com/halcyon/asdf-java.git
```
Ensure `~/.zshrc` sources JAVA_HOME setup — check for `. ~/.asdf/plugins/java/set-java-home.zsh` (or equivalent) and append it under an "ASDF Java" comment if missing.

### 3. asdf Java version
```sh
asdf install          # installs whatever .tool-versions pins, from the repo root
asdf global java "$(awk '/^java/{print $2}' .tool-versions)"   # so `java` also resolves correctly outside this repo
```
`.tool-versions` currently pins a **JRE** build. sbt/CI actually use a full JDK — if sbt fails to fork the compiler, that's likely why; flag this to the user rather than silently swapping versions.

### 4. sbt
```sh
brew install sbt
```

### 5. nvm + Node 22 + npm
```sh
brew install nvm
mkdir -p ~/.nvm
```
Add to `~/.zshrc` if not already present:
```sh
export NVM_DIR="$HOME/.nvm"
[ -s "/opt/homebrew/opt/nvm/nvm.sh" ] && \. "/opt/homebrew/opt/nvm/nvm.sh"
[ -s "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm" ] && \. "/opt/homebrew/opt/nvm/etc/bash_completion.d/nvm"
```
Then:
```sh
nvm install 22
nvm alias default 22
```
Node is deliberately installed via nvm rather than the system/Homebrew Node or an asdf node plugin — global npm installs against system Node need `sudo`, which this skill should not invoke. If `~/.npmrc` has a leftover `prefix`/`globalconfig` setting or `~/.zshrc` has an old `~/.npm-global` PATH workaround, remove both (`nvm use --delete-prefix` first if `nvm use` complains) — they conflict with nvm's own per-version global directory.

### 6. OmniRoute
```sh
npm install -g omniroute   # under nvm's Node — large package (~700MB), several minutes
omniroute serve --daemon --no-open   # only if not already running (check `curl -sf http://localhost:20128/v1/models`)
```
Known upstream bug in the published package: `omniroute config set claude` and `omniroute setup --list` crash (`Cannot find package '@/shared'` / `'@/lib'` — missing files even in the upstream repo, not fixable here). Don't use those two commands. Optionally patch it anyway — fetch `https://raw.githubusercontent.com/diegosouzapw/OmniRoute/main/tsconfig.json` and write it to the installed package root (`$(dirname $(dirname $(readlink -f $(command -v omniroute))))/tsconfig.json` or equivalent) — this doesn't fully fix the two broken commands (their root cause is missing source files, not just the config) but does no harm and may help other code paths. Treat this as best-effort, not required.

**Then stop and ask the user directly** (don't fabricate or skip this — it needs their input):

> Open http://localhost:20128 in a browser. First run prompts you to set an admin password (your choice, local-only). In the dashboard, add a Claude provider connection (sign in with your subscription) plus any other tiers you want, then grab an API key from Settings → API Keys. Paste that key here.

Once they reply with a key, sanity-check it:
```sh
OMNIROUTE_API_KEY=<their-key> omniroute chat "reply with exactly: pong" --model auto
```
Then write (or replace, if a previous block exists) this in `~/.zshrc`:
```sh
export ANTHROPIC_BASE_URL="http://localhost:20128"
export ANTHROPIC_AUTH_TOKEN="<their-key>"
export ANTHROPIC_API_KEY="" # must be explicitly empty, or Claude Code may prefer it over ANTHROPIC_AUTH_TOKEN
export CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1
```
**Also required**: once OmniRoute has more than one provider serving the same bare model name (its built-in `cc` tier alongside the `claude` connection just added), Claude Code's alias resolution (`opus` → `claude-opus-4-8`) becomes ambiguous and fails with `API Error: 400 Ambiguous model... Use provider/model prefix`. Look up the exact `cc/`-prefixed IDs (`OMNIROUTE_API_KEY=<their-key> omniroute models`, they drift as new Claude models ship) and pin them:
```sh
export ANTHROPIC_DEFAULT_OPUS_MODEL="cc/claude-opus-4-8"     # match to whatever `omniroute models` actually lists
export ANTHROPIC_DEFAULT_SONNET_MODEL="cc/claude-sonnet-5"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="cc/claude-haiku-4-5-20251001"
```
Remind them these env vars only take effect after restarting Claude Code (env is read once at startup).

### 7. Docker
```sh
docker info   # already satisfied if this succeeds
brew install --cask docker   # only if the CLI itself is missing
```
Docker Desktop needs a one-time manual launch (license/permissions) that Homebrew can't automate — if `docker info` still fails after installing the cask, tell the user to open Docker Desktop from Applications once.

## Phase 3 — Summary

Print a final table: tool → outcome (already satisfied / installed just now / needs manual follow-up, e.g. "open Docker Desktop once" or "restart Claude Code"). Make the remaining manual steps impossible to miss — those are the only things left for the engineer to do by hand.

## Notes

- Never write secrets (the OmniRoute API key, admin password, any provider key) anywhere except the user's own `~/.zshrc`. Never into a file inside this repo.
- If you change a step here, check whether `docs/repository-setup.md` or `docs-claude/agent-pipeline-setup.md` also needs updating — the three are meant to stay consistent.
