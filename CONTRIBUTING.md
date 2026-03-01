# Contributing to OPAA

Thank you for your interest in contributing to OPAA! This project welcomes contributions from both humans and AI coding agents.

## Getting Started

1. Fork the repository
2. Clone your fork
3. Create a feature branch: `git checkout -b feature/42_my-feature`
4. Make your changes
5. Push and open a Pull Request

## Branch Naming

Use the format `feature/<issue-id>_<short-description>`:

```
feature/42_user-authentication
feature/15_fix-null-pointer
feature/7_add-contributing-guide
```

Every branch ties back to a GitHub Issue via its ID.

## Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
feat: add user authentication
fix(api): handle null response from service
docs: update architecture decision records
```

## Pull Requests

- All changes go through PRs — no direct pushes to `main`
- Fill out the PR template completely
- Link related GitHub Issues with `Closes #N`
- Ensure tests pass before requesting review

## Issues

- **Issues must be written in English**
- Use the provided issue templates for bug reports and feature requests
- For larger features, create a feature spec in `docs/features/` and link it from the issue

## AI Agent Contributors

This project explicitly welcomes contributions from AI coding agents (Claude Code, GitHub Copilot, Cursor, Codex, etc.).

### Expectations for AI-generated code

- All AI contributions go through the same PR review process as human code
- Use Conventional Commits format
- Include a `Co-Authored-By` trailer in commits (e.g., `Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>`)
- Mark AI involvement in the PR template's AI Agent Disclosure section
- Read `AGENTS.md` (or `CLAUDE.md` for Claude) before starting work

### For humans reviewing AI-generated code

- Review AI code with the same rigor as human code
- Watch for hallucinated imports, non-existent APIs, and subtle logic errors
- Verify that AI agents followed the project conventions documented here

## Code of Conduct

Be respectful and constructive. We're building something together.
