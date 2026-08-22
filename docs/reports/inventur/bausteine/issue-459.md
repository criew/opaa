# Issue #459 — docs(agents): UX-Designer-Rolle in der Agenten-Organisation einführen
- Geschlossen: 2026-08-17 (completed)
- Labels: documentation, enhancement
- PRs: #460 (2026-08-17)

**Laut Issue:** Mit der Library-/Upload-Serie war viel nutzerseitige Oberfläche entstanden, ohne dass Dialogaufbau, Fehlertexte und Begriffe von einer eigenen Rolle verantwortet wurden. Gefordert war ein Rollenvertrag `agents/roles/ux-designer.md` (Interaktionskonzepte vor Implementierung, Begriffs-/Textkonventionen, UX-Review nach Merge, ohne Produktivcode) sowie Aufnahme der Rolle in `docs/AGENT-ORGANIZATION.md`. Provider-Adapter (`.claude/agents/` etc.) und der Glossaraufbau waren ausdrücklich außerhalb des Umfangs.

**Geliefert:** Genau wie gefordert — `agents/roles/ux-designer.md` neu angelegt, `docs/AGENT-ORGANIZATION.md` um Rollentabelle und Agenten-Definitionen ergänzt. Keine Abweichung; PR merkt an, dass die Annahme der Rolle eine offene Organisationsentscheidung des Maintainers ist.

**Verifikation:** `agents/roles/ux-designer.md` existiert im heutigen Stand des Worktrees. Ob ein Provider-Adapter (`.claude/agents/ux-designer.md` o. ä.) inzwischen ergänzt wurde, wurde nicht geprüft — das wäre ohnehin ein separater Schritt gewesen.

**Themen:** agenten-organisation, doku, ux, rollenvertrag
