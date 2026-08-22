# Issue #194 — docs: document git worktree usage for parallel agent sessions
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation
- PRs: #195 (2026-08-02)

**Laut Issue:** In `AGENTS.md` und `.claude/rules/workflow.md` dokumentieren, dass parallele Agent-Sessions im selben Checkout je Aufgabe einen eigenen Git-Worktree statt eines Branch-Wechsels im gemeinsamen Arbeitsverzeichnis nutzen sollen, um gegenseitiges Blockieren zu vermeiden.

**Geliefert:** PR #195 ergänzt genau die geforderte Passage in `.claude/rules/workflow.md` und `AGENTS.md`. Keine Abweichung vom Issue.

**Verifikation:** `.claude/rules/workflow.md` existiert im heutigen Worktree nicht mehr (spätere Commits, u. a. "docs: doppelt gepflegte Workflow-Regeln auf AGENTS.md zusammenführen", haben die Datei entfernt und ihren Inhalt in `AGENTS.md` konsolidiert). `AGENTS.md` enthält den Abschnitt "Git Worktrees für parallele Sessions" weiterhin mit dem Kerninhalt aus dem Issue. Inhaltlich also weiterhin gültig, nur an anderer Stelle.

**Themen:** doku, agenten-organisation, projektsetup
