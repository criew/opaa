# Issue #517 — feat(library): Indizierte Dokumente für alle Quellentypen anzeigen — mit Paging und Stichwortsuche
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #540 (2026-08-19)

**Laut Issue:** Die Dokumentliste war nur für UPLOAD-Bibliotheken sichtbar, obwohl der Endpunkt Daten für alle Typen liefern würde; zudem lieferte er ein ungepagtes Array ohne Suche. Gefordert: Paging (`page`/`size`, Gesamtzahl), Stichwortsuche über den Dateinamen, sichtbar für alle Bibliothekstypen; Löschverhalten bei FILESYSTEM/HTTP_DIRECTORY (Rückkehr mit nächstem Lauf) im PR dokumentieren.

**Geliefert:** Wie gefordert. Endpunkt wechselt auf gepagte Antwort (`{ items, page, size, totalElements }`, Default-Seitengröße 20, max 100). Beim Review zeigte sich, dass RSS_FEED dasselbe Rückkehr-Problem hat wie FILESYSTEM/HTTP_DIRECTORY (`RssFeedIndexingExecutor#isUnchanged` erkennt eine Löschung nicht) — die Löschaktion wurde deshalb einheitlich für alle Konnektortypen ausgeblendet, nicht nur die beiden im Issue genannten.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentRepository.java` und `frontend/src/pages/LibraryDetailPage.tsx` enthalten die entsprechende Paging-/Suchlogik.

**Themen:** backend, frontend, spaces, retrieval, ux
