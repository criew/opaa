# Issue #518 — fix(indexing): RSS-Läufe zählen Feed-Einträge statt indizierter Dokumente — Anhänge fehlen in der Anzeige
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #534 (2026-08-19)

**Laut Issue:** `RssFeedIndexingExecutor` zählte Feed-Einträge statt tatsächlich indizierter Dokumente — Anhänge (bis zu 10 je Eintrag) tauchten in keinem Zähler auf. Die Anzeige „10 Dokumente verarbeitet" konnte damit den tatsächlichen Indexbestand systematisch unterschätzen. Gefordert: getrennte Zählung von Feed-Einträgen und Dokumenten, Anhänge auch beim Nachholen für unveränderte Einträge mitgezählt.

**Geliefert:** Wie gefordert. Neuer Zähler `documentsIndexedTotal` (Migration `030`), zählt Eintrag und jeden erfolgreich indizierten Anhang, auch beim Nachholen. Fehlgeschlagene/deduplizierte Anhänge erhöhen den Zähler nicht. Für FILESYSTEM/HTTP_DIRECTORY bleibt `documentsIndexedTotal` immer gleich `documentCount` (1 Datei = 1 Dokument), die dortige Anzeige bleibt unverändert korrekt. Frontend zeigt für RSS-Läufe „X Feed-Einträge, Y übersprungen, Z indiziert (N Dokumente insgesamt)". Reproduktionsnachweis mit drei roten/grünen Testfällen im PR dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingRunProgress.java` und `IndexingJob.java` existieren mit dem entsprechenden Zähler; `frontend/src/pages/LibraryDetailPage.tsx` enthält die getrennte Anzeige.

**Themen:** backend, frontend, bugfix, feeds, retrieval
