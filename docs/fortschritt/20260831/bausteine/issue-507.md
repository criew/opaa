# Issue #507 — feat(library): Quellkonfiguration nur für Bearbeitende sichtbar machen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, frontend, size:S, security
- PRs: #657 (2026-08-20)

**Laut Issue:** `LibraryResponse` lieferte `sourcePath`/`sourceUrl`/`sourceProxy` an jeden Lesenden — ein VIEWER einer organisationsweiten Konnektorbibliothek sah damit interne Serverpfade, Quell-URLs und Proxy-Hosts. Gefordert: Entscheidung, ob die Konfiguration nur für canEdit befüllt wird, und Nachzug der Detailseite.

**Geliefert:** Wie gefordert. Die Felder werden nur noch befüllt, wenn `myRole` mindestens MANAGER ist — derselbe Schwellwert wie für Änderungen. `sourceType` bleibt für jede Rolle sichtbar. Die Detailseite blendet den Konfigurationsbereich für Lesende aus und zeigt stattdessen einen Hinweis. Zusätzlich zum Issue-Umfang, auf Maintainer-Wunsch in denselben PR gezogen: `GET .../indexing/status` gab bei `FAILED` bislang die rohe Exception-Meldung zurück (u. a. interne Serverpfade bei `NoSuchFileException`) — dieselbe Information blieb über einen zweiten Endpunkt für jeden VIEWER sichtbar. Diese Meldung wird jetzt ebenfalls rollenabhängig gekürzt (eigenes Issue #659, im selben PR mitgeschlossen).

**Verifikation:** `backend/src/main/java/io/opaa/library/AssetRole.java` und `KnowledgeLibraryService.java` existieren mit rollenabhängiger Sichtbarkeitslogik; `docs/features/spaces-and-assets.md` enthält die entsprechende Ausnahme.

**Themen:** backend, frontend, sicherheit, spaces, berechtigungen
