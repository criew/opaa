# Issue #268 — docs: PR-Regeln an Merge ohne Approval anpassen
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S
- PRs: #270 (2026-08-02)

**Laut Issue:** Der Branch-Schutz auf `main` verlangte kein formales Approval mehr, nur noch grüne CI plus Merge durch einen Maintainer. Die Dokumentation (`AGENTS.md`, `CONTRIBUTING.md`, `docs/AGENT-ORGANIZATION.md`) beschrieb noch den alten Zustand mit Approval-Pflicht und musste nachgezogen werden. Der Code Reviewer sollte als verpflichtender Schritt erhalten bleiben, CI weiter als Merge-Gate erkennbar sein, `criew` und `bigpuritz` als merge-berechtigte Maintainer benannt werden.

**Geliefert:** PR #270 zieht `AGENTS.md`, `CONTRIBUTING.md` und `docs/AGENT-ORGANIZATION.md` nach: kein Approval mehr gefordert, Maintainer namentlich benannt, Code Reviewer und CI-Status-Checks bleiben verpflichtend beschrieben. Keine Abweichung vom Issue erkennbar.

**Verifikation:** `CONTRIBUTING.md` enthält heute weiterhin den Satz „Merge-Recht haben ausschließlich die Maintainer des Projekts“ und „Ein formales Approval in GitHub ist dafür nicht erforderlich“ — die Regelung ist im aktuellen Code-Stand vorhanden.

**Themen:** doku, agenten-organisation, projektsetup
