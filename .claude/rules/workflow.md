# Git Workflow Rules

- Always create a feature branch; never commit directly to main
- Use Conventional Commits format for all commit messages
- Use the PR template
- Keep PRs focused: one logical change per PR
- When fixing an issue, reference it with "Closes #N" in the PR body

# Pre-Push Checklist

Skip if only updating docs.
Before every push, ALL of the following must pass locally.

- Backend formatting
- Backend build + test
- Frontend formatting
- Frontend lint
- Frontend build + test

If any step fails, fix the issue before pushing.
