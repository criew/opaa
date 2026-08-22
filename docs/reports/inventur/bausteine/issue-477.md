# Issue #477 — feat(library): Dokumentzahl in der Bibliotheksliste
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #488 (2026-08-18)

**Laut Issue:** `LibraryListResponse` sollte je Bibliothek `documentCount` liefern (ohne N+1), die Übersicht sollte die Zahl anzeigen, ohne jede Karte aufklappen zu müssen.

**Geliefert:** Wie gefordert. `documentCount` in `LibraryListResponse`, gezählt über eine neue gruppierte Query `DocumentRepository#countByLibraryIdIn` für die ganze Seite auf einmal statt `countByLibraryId` je Zeile. `LibraryManagementPage` zeigt die Zahl direkt in der eingeklappten Kopfzeile; der bisherige Zähler in der aufgeklappten Detailansicht wurde als redundant entfernt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentRepository.java` enthält weiterhin Zählabfragen für Bibliotheken; `frontend/src/pages/LibraryManagementPage.tsx` existiert und zeigt Bibliotheksmetadaten in der Kopfzeile. Die Dokumentzahl wurde später um weitere Metadaten ergänzt (Zeitplan, Quellentyp), ohne diese Funktion zu verdrängen.

**Themen:** backend, frontend, spaces, retrieval
