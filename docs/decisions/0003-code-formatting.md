# ADR-0003: Code Formatting

## Status

Accepted

## Context

The project needs a consistent code formatting standard. Without automated enforcement, formatting inconsistencies (tabs vs. spaces, import ordering, line length) creep in across contributions from both human developers and AI agents.

Key considerations:

- The project uses Java (backend) and will add TypeScript (frontend) later
- Both human and AI contributors work on the codebase
- Formatting should be enforced automatically, not through manual review

## Decision

### Standard: Google Java Format via Spotless

- **Spotless** (Gradle plugin) enforces formatting as part of the build.
- **Google Java Format** is the formatter for Java source files. It uses 2-space indentation, which is the Google Java Style standard.
- **Gradle Kotlin DSL** files (`.gradle.kts`) use 4-space indentation, enforced by Spotless.
- **Spaces over tabs** — all source files use spaces for indentation, never tabs.

### Enforcement

- `./gradlew spotlessCheck` verifies formatting (can be used in CI).
- `./gradlew spotlessApply` auto-formats all files.
- Spotless runs as part of the standard Gradle build lifecycle.

### Scope

- Java: Google Java Format (2 spaces, sorted imports, no unused imports)
- Kotlin DSL: 4 spaces, trimmed trailing whitespace
- Frontend formatting will be added when the frontend is scaffolded (likely Prettier)

## Consequences

### What becomes easier

- **Consistent code style** across all contributions without manual review effort.
- **No formatting debates** — the tool decides, contributors comply.
- **AI agents** produce consistently formatted code by running `spotlessApply` after changes.

### What becomes more difficult

- **Google Java Format is opinionated** — its 2-space indentation and line-breaking style may feel unfamiliar to developers used to 4-space Java conventions. This is intentional: a strict, non-configurable formatter eliminates bikeshedding.
- **Initial friction** — existing code must be reformatted on adoption (one-time cost, already done).
