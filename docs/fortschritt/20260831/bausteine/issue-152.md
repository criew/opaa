# Issue #152 — refactor: Generate workspace DTOs from OpenAPI spec instead of handwriting them
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement, backend, size:M
- PRs: #154 (2026-03-08), #159 (2026-03-08)

**Laut Issue:** Acht handgeschriebene Workspace-DTOs (u. a. `WorkspaceResponse`, `WorkspaceMemberResponse`) sowie das fehlende `WorkspaceDocumentResponse` sollten aus der OpenAPI-Spec generiert werden, inklusive Enum-Mapping (`WorkspaceRole`, `WorkspaceType`) und Anpassung von `build.gradle.kts`.

**Geliefert:** PR #154 entfernt die 8 handgeschriebenen Workspace-DTOs, generiert sie über den OpenAPI Generator mit Enum-Mapping via `typeMappings`/`importMappings`, und fügt ADR-0006 hinzu, die das OpenAPI-first-DTO-Prinzip dauerhaft festschreibt. PR #159 zieht dasselbe Muster für Auth-DTOs nach (über den ursprünglichen Issue-Scope hinaus, aber sachlich folgerichtig). Deckt den geforderten Umfang vollständig ab.

**Verifikation:** ADR-0006 (`docs/decisions/0006-openapi-dto-generation.md`) und die zugehörige Regel in `AGENTS.md` bestehen im heutigen Projekt fort und sind verbindliche Konvention — auch wenn die konkreten Workspace-DTOs durch die spätere Space-Migration ersetzt wurden, gilt das hier etablierte Prinzip unverändert für alle DTOs.

**Themen:** api, openapi, dto, codegen, refactoring
