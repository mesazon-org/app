# Mesazon App

Mesazon is a Business Management Platform. Its goal is to enhance businesses with
powerful tools to orchestrate their workflows, taking a pragmatic approach: features are derived from real business
needs.

## Tech stack

Each entry links to the coding standards for that technology — **follow them when writing code**.

- [scala](docs-claude/scala.md) — programming language

## Features completed

**Documentation rule (for Claude): every new feature ships with its doc.** When you implement a feature, also write `docs-claude/features/<feature-name>.md` and link it in the list below — you are generating context for your own future sessions, so capture what the code alone won't tell you. When you change an existing feature's behavior, update its doc.

Follow the structure of the existing docs:

- scope paragraph: what the feature owns, what it deliberately excludes, links to the owning features at each boundary
- endpoints table with auth + required onboard stages
- flow, including security/abuse defenses and other non-obvious design decisions
- key files and config
- tests: acceptance in `backend/gateway/it` (see [acceptance-tests.md](docs-claude/acceptance-tests.md)) plus functional/unit/integration

- [User Onboarding](docs-claude/features/user-onboarding.md)
- [User Sign in](docs-claude/features/user-signin.md)
- [User Sign up](docs-claude/features/user-signup.md)
- [User Forgot Password](docs-claude/features/user-forgot-password.md)
- [User Token Management](docs-claude/features/user-token-management.md)
- [Organization Management](docs-claude/features/organization-management.md)
- [Files Management](docs-claude/features/files-management.md)