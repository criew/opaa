# AI Agent Instructions

## Project Overview

OPAA (Open Project AI Assistant) is an open-source project building an AI-powered project assistant.
Contributions from humans and AI agents are equally welcome.

## Architecture

- **Backend:** Java 21 + Spring Boot 3.5.10 + Spring AI 1.1.2 (Gradle 9.3.1, Kotlin DSL)
- **Database:** PostgreSQL 18 + pgvector, Liquibase
- **Frontend:** React 19 + TypeScript + Material UI 7 + React Router 7 + Zustand + Vitest + MSW
- **CI:** GitHub Actions
- **Deployment:** Docker Compose

> See [ADR-0002](docs/decisions/0002-mvp-technology-stack.md) for full rationale.

## Build & Test

```bash
# Backend (from backend/)
./gradlew build
./gradlew test
./gradlew bootRun --args='--spring.profiles.active=mock'
./gradlew bootRun
./gradlew spotlessCheck
./gradlew spotlessApply

# Frontend (from frontend/)
npm ci                                  # Install dependencies
VITE_ENABLE_MOCKS=true npm run dev      # Dev server with MSW mocks
npm run dev                             # Dev server (needs backend on :8080)
npm run build                           # Production build
npm run lint                            # Lint (ESLint)
npm run test                            # Tests (Vitest)
npm run format:check                    # Check Prettier formatting
npm run format                          # Auto-format with Prettier
```

## Dependency Management

- Dependency versions MUST be declared in `backend/gradle/libs.versions.toml` — never inline a version in `build.gradle.kts`
- Use version catalogs (`libs.versions.*`, `libs.*`) for referencing versions and libraries
- This applies to both `[libraries]` and `[plugins]` sections

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

Every branch ties back to a GitHub Issue via its ID.

### Pull Requests

- No direct pushes to `main` — all changes go through PRs
- PRs must be reviewed before merge
- Use the PR template

## Important Paths

- `docs/decisions/` — Architecture Decision Records (ADRs)
- `docs/features/` — Feature specifications
- `.github/ISSUE_TEMPLATE/` — Issue templates
- `.github/PULL_REQUEST_TEMPLATE.md` — PR template
- `CONTRIBUTING.md` — Contributor guide
- `AGENTS.md` — AI agent instructions
- `backend/` — Spring Boot backend (Gradle project)
- `frontend/` — React frontend (Vite project)
- `frontend/src/test/test-utils.tsx` — Shared test render helpers

## Agent Behavior

- Respond in the language the user writes in
- Do not refactor code unless explicitly asked
- Before creating new files, check if similar patterns or utilities already exist
- Prefer small, focused commits over large ones
- When fixing a bug, write a test that reproduces it first
- Read `docs/decisions/` for Architecture Decision Records before making major structural changes

## Security

- Never commit secrets, API keys, or credentials
- Use environment variables for configuration
- Do not commit `.env` files
