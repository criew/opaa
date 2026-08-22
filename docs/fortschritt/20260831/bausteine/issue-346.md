# Issue #346 — docs(agents): Sub-Issue-Regel für Epics in AGENTS.md aufnehmen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #347 (2026-08-14)

**Laut Issue:** Die mit #335 eingeführte Regel (Epics führen Tickets als native Sub-Issues) war nur in `agents/roles/product-manager.md` und `.github/ISSUE_TEMPLATE/epic.md` dokumentiert. `AGENTS.md`, die zentrale Einstiegsdatei, sollte im Abschnitt „GitHub-Issues" auf die Regel verweisen, ohne die Details zu duplizieren.

**Geliefert:** PR #347 ergänzt genau diesen Abschnitt in `AGENTS.md` mit Verweis auf das Epic-Template und den Befehl zum nachträglichen Verknüpfen. Laut PR-Beschreibung war die Lücke beim Anlegen von #338 selbst aufgefallen — das Epic wurde zunächst ohne Sub-Issues erstellt. Keine Abweichung vom Issue.

**Verifikation:** `AGENTS.md` Zeile 142 enthält „Epics führen ihre Tickets als native Sub-Issues" mit Verweis auf `.github/ISSUE_TEMPLATE/epic.md`; die Regel ist im aktuellen Repository-Stand vorhanden.

**Themen:** doku, agenten-organisation, projektsetup
