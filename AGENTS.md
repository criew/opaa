# OPAA - AI Agent Instructions

## Project Overview

OPAA (Open Project AI Assistant) is an open-source project building an AI-powered project assistant.
Licensed under Apache 2.0. Contributions from humans and AI agents are equally welcome.

## Architecture

> Architecture documentation lives in `docs/decisions/`. Read the ADRs there for context on major decisions.

The tech stack has not been chosen yet. This section will be updated once the first technology decisions are made.

## Build & Test

> Commands will be added once the tech stack is established.

```bash
# Build: TBD
# Test: TBD
# Lint: TBD
```

## Code Conventions

### Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `build`

AI agents must include a `Co-Authored-By` trailer in commits.

### Branch Naming

Format: `feature/<issue-id>_<short-description>`

Examples:
- `feature/42_user-authentication`
- `feature/15_fix-null-pointer`
- `feature/7_add-contributing-guide`

Every branch ties back to a GitHub Issue via its ID.

### Pull Requests

- No direct pushes to `main` — all changes go through PRs
- PRs must be reviewed before merge
- Use the PR template (includes AI agent disclosure)

## Important Paths

- `docs/decisions/` — Architecture Decision Records (ADRs)
- `docs/features/` — Feature specifications
- `.github/ISSUE_TEMPLATE/` — Issue templates
- `.github/PULL_REQUEST_TEMPLATE.md` — PR template
- `CONTRIBUTING.md` — Contributor guide
- `CLAUDE.md` — Claude-specific instructions
- `AGENTS.md` — This file (universal AI agent instructions)

## Security

- Never commit secrets, API keys, or credentials
- Use environment variables for configuration
- Do not commit `.env` files
