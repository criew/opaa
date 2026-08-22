# Issue #515 — feat(frontend): Quellentyp „Verzeichnisliste" in „Webverzeichnis" umbenennen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #530 (2026-08-19)

**Laut Issue:** Der Quellentyp `HTTP_DIRECTORY` hieß in der Oberfläche „Verzeichnisliste" und provozierte Verwechslung mit dem Typ „Dateisystem". Umbenennung auf „Webverzeichnis" in allen sichtbaren Texten, der technische Enum-Wert bleibt unverändert.

**Geliefert:** Wie gefordert. Label- und Beschreibungstext in `frontend/src/utils/labels.ts` umbenannt, projektweite Suche fand keine weiteren sichtbaren UI- oder E2E-Texte mit dem alten Begriff.

**Verifikation:** `frontend/src/utils/labels.ts` existiert; eine Suche nach „Webverzeichnis" trifft konsistent auf die neue Bezeichnung in der Dokumentation (`docs/features/knowledge-sources.md`, u. a. in #515 selbst und in #482 nachgeführt).

**Themen:** frontend, ux, doku, spaces
