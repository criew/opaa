# OPAA - AI Agent Instructions

## Project Overview

OPAA (Open Project AI Assistant) is an open-source project building an AI-powered project assistant.
Licensed under Apache 2.0. Contributions from humans and AI agents are equally welcome.

## Architecture

> Architecture documentation lives in `docs/decisions/`. Read the ADRs there for context on major decisions.

- **Backend:** Java 21 + Spring Boot 3.5.10 + Spring AI 1.1.2 (Gradle 9.3.1, Kotlin DSL)
- **Database:** PostgreSQL 18 + pgvector
- **Frontend:** React + TypeScript + Material UI (TBD)

> See [ADR-0002](docs/decisions/0002-mvp-technology-stack.md) for full rationale.

## Build & Test

```bash
# Build
cd backend && ./gradlew build

# Test
cd backend && ./gradlew test

# Run (mock profile, no DB/LLM required)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=mock'

# Run (local profile, requires PostgreSQL)
cd backend && ./gradlew bootRun

# Format check
cd backend && ./gradlew spotlessCheck

# Auto-format
cd backend && ./gradlew spotlessApply
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
- `backend/` — Spring Boot backend (Gradle project)

## Agent Behavior

- Do not refactor code unless explicitly asked
- Before creating new files, check if similar patterns or utilities already exist
- Prefer small, focused commits over large ones
- When fixing a bug, write a test that reproduces it first (when test framework is available)
- Read `docs/decisions/` for Architecture Decision Records before making structural changes

## Security

- Never commit secrets, API keys, or credentials
- Use environment variables for configuration
- Do not commit `.env` files
