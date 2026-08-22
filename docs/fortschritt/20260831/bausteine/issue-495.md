# Issue #495 — docs(agents): Pre-Push-Verifikation für Nachbesserungsrunden verschlanken
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:S
- PRs: #496 (2026-08-19)

**Laut Issue:** Drei vom Maintainer freigegebene Beschleunigungen des Entwickler-Arbeitsablaufs sollten dokumentiert werden: verkürzte lokale Verifikation für Nachbesserungsrunden (nur Formatierung/Kompilieren/berührte Tests, Volllauf übernimmt die CI), `npm ci --prefer-offline` in frischen Worktrees, und Builds im Vordergrund abwarten statt auf Hintergrundläufe zu schlafen.

**Geliefert:** Wie gefordert, alle drei Punkte in `AGENTS.md` und `agents/roles/developer.md` dokumentiert, mit der Begründung aus der Auswertung von Epic #463 (lokaler Volllauf hat in Nachbesserungsrunden nichts gefangen, was die CI nicht auch gefangen hätte; einmal hat er sogar einen von der CI gefangenen Fehler verfehlt, PR #474).

**Verifikation:** Die heutige `AGENTS.md` (in diesem Worktree) enthält im Abschnitt „Pre-Push-Checkliste" exakt diese Unterscheidung zwischen Erstumsetzung und Nachbesserungsrunde sowie die Regel „Builds und Tests im Vordergrund ausführen" — deckungsgleich mit dem PR-Inhalt.

**Themen:** doku, agenten-organisation, ci, prozess
