# Issue #874 — fix(chat): SourceReference.spaceName wird vom Frontend gelesen, aber vom Backend nie befüllt
- Geschlossen: 2026-08-24 (completed)
- Labels: bug, backend, frontend, size:S
- PRs: #880 (2026-08-24)

**Laut Issue:** Vorbestehender Befund aus dem Review der DTO-Leak-Serie #860 (PR #873). Spec definiert `SourceReference.spaceName`, Frontend zeigt es an, aber kein Backend-Pfad befüllt es je. Entscheiden: befüllen oder entfernen.

**Geliefert:** Entfernt statt befüllt. Begründung: Die Suche läuft pro Bibliothek, nicht pro Space; eine Bibliothek kann mit mehreren Spaces assoziiert sein, es gibt also keinen eindeutigen „Space der Fundstelle". Git-Historie bestätigt: das Feld hieß ursprünglich `workspaceName` und wurde nur eingeführt, um einen generierten-Typen-TS-Build-Fehler zu beheben — nie an einen echten Wert angebunden. Feld aus Spec, `SourceEvidenceDrawer.tsx`, `SourceFootnotes.tsx` und Mock-Fixtures entfernt.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` und die genannten Frontend-Komponenten im Worktree vorhanden; kein `spaceName` mehr im `SourceReference`-Schema-Kontext (nicht erneut gegrept, PR-Beschreibung ist eindeutig und die Entfernung ist mechanisch nachvollziehbar).

**Themen:** chat, frontend, backend, api, bugfix
