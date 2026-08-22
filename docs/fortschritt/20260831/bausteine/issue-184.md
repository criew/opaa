# Issue #184 — feat(agents): support shared roles across Claude, Codex, and OpenCode
- Geschlossen: 2026-07-18 (completed)
- Labels: documentation, enhancement, setup, size:M
- PRs: #185 (2026-07-18)

**Laut Issue:** Die fünf bestehenden Claude-Code-Agentendefinitionen (`product-manager`, `developer`, `code-reviewer`, `qa-engineer`, `marketing`) sollten provider-neutral nutzbar werden, damit Claude Code, Codex und OpenCode dieselbe Rollenlogik ohne dreifache Pflege verwenden können. Verlangt: gemeinsame Rollen-Contracts, dünne Plattform-Adapter je Client, Read-only-Konfiguration für den Code-Reviewer wo möglich, erhaltene Worktree-Isolation für Developer/QA Engineer, aktualisierte Dokumentation.

**Geliefert:** PR #185 verschiebt die fünf Rollenprompts nach `agents/roles/*.md` als Quelle der Wahrheit und ergänzt Adapter für Claude Code (`.claude/agents/*.md`), Codex (`.codex/agents/*.toml`) und OpenCode (`.opencode/agents/*.md`) — für alle fünf Rollen. `docs/AGENT-ORGANIZATION.md` wurde entsprechend aktualisiert. Deckt sich mit der Forderung; ob Read-only-Konfiguration für Code-Reviewer und Worktree-Zwang für Developer/QA in den Adapter-Dateien konkret umgesetzt sind, wurde nicht im Detail geprüft (nur Dateiexistenz, kein Codereview der Adapterinhalte).

**Verifikation:** Alle im PR genannten Pfade existieren im heutigen Worktree: `agents/roles/` (11 Dateien, darunter die 5 ursprünglichen plus seither ergänzte Stakeholder-Rollen und `ux-designer.md`), `.codex/agents/` (11 `.toml`-Dateien) und `.opencode/agents/` (11 `.md`-Dateien) sind vollständig parallel zu `.claude/agents/` gepflegt. Die Struktur wurde seit dem PR sichtbar weitergepflegt (neue Rollen wie Stakeholder-Agenten und UX-Designer kamen über alle drei Plattformen hinweg konsistent hinzu) — das Cross-Platform-Muster hat sich also gehalten.

**Themen:** agenten-organisation, tooling, cross-platform, doku
