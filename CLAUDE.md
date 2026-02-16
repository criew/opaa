# OPAA - Claude Code Instructions

## Claude-Specific Behavior

- Do not refactor code unless explicitly asked
- Before creating new files, check if similar patterns or utilities already exist
- Prefer small, focused commits over large ones
- Use `git diff` to verify changes before committing
- When fixing a bug, write a test that reproduces it first (when test framework is available)
- Respond in the language the user writes in

## Project Context

@AGENTS.md

## Workflow Commands

```bash
# Build: TBD
# Test: TBD
# Lint: TBD
```

## Key Decisions

Read `docs/decisions/` for Architecture Decision Records before making structural changes.
