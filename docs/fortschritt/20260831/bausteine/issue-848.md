# Issue #848 — docs: Koordinations-Betriebsregeln aus lokalem Memory ins Repo überführen
- Geschlossen: 2026-08-24 (completed)
- Labels: documentation, size:S
- PRs: #850 (2026-08-24)

**Laut Issue:** Mehrere Betriebsregeln der Agenten-Koordination existierten nur im lokalen Memory des Koordinators und fehlten damit in einer zweiten Entwicklungsumgebung (z. B. VPS) — mit realen Folgen am 24.08.2026 (rote CI nach Auto-Merge, Wartefallen, RAM-Thrashing durch parallele Vollbuilds). Vier Regeln nach `docs/AGENT-ORGANIZATION.md` (PR-Wächter, Wartefallen, Vollbuild-Staffelung, Security-Delegation) und eine nach AGENTS.md (Beleg nur auf aktuellem Stand).

**Geliefert:** Wie gefordert. `docs/AGENT-ORGANIZATION.md` erhielt den Abschnitt „Koordinator-Betrieb" mit den vier Punkten; `AGENTS.md`, Abschnitt „Reproduktionsnachweis", um den Punkt „Beleg-Läufe nur auf aktuellem Stand" ergänzt. Bereits vorhandene Regeln wurden geprüft und nicht dupliziert; Maschinenspezifisches blieb bewusst lokal.

**Verifikation:** `docs/AGENT-ORGANIZATION.md` und `AGENTS.md` im Worktree enthalten die beschriebenen Abschnitte (deckt sich mit dem Memory-Eintrag „Build-Cache statt Lock-Wrapper" und „Agent-Wartefalle Hintergrundläufe" des Nutzers).

**Themen:** doku, agenten-organisation, koordination
