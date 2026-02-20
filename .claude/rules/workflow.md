# Git Workflow Rules

- Always create a feature branch; never commit directly to main
- Use Conventional Commits format for all commit messages
- Keep PRs focused: one logical change per PR
- When fixing an issue, reference it with "Closes #N" in the PR body

# Pre-Push Checklist (MANDATORY)

Before every push, ALL of the following must pass locally. Do NOT push until every step succeeds:

1. **Backend formatting:** `cd backend && ./gradlew spotlessApply`
2. **Backend build + tests:** `cd backend && ./gradlew build`
3. **Frontend formatting:** `cd frontend && npm run format`
4. **Frontend lint:** `cd frontend && npm run lint`
5. **Frontend tests:** `cd frontend && npm run test -- --run`
6. **Frontend build:** `cd frontend && npm run build`

If any step fails, fix the issue before pushing. Never skip formatting or tests.
