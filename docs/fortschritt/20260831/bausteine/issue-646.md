# Issue #646 — fix(indexing): Feed-Zustand pro Bibliothek führen bzw. beim Löschen zurücksetzen
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend
- PRs: #665 (2026-08-20)

**Laut Issue:** `rss_feed_state` war über die Feed-URL eindeutig geschlüsselt (`unique` auf `feed_url`), nicht über die Bibliothek. `KnowledgeLibraryService.deleteLibrary` räumte diese Tabelle beim Löschen nicht auf. Eine neue Bibliothek mit derselben (zuvor verwendeten) Feed-Adresse fand über `findByFeedUrl` den alten ETag/Last-Modified-Eintrag, erhielt sofort `304 Not Modified` und brach den ersten Lauf mit „0 Dokumente" ab — als Erfolg gemeldet, nicht als Fehler. Gefordert: Entscheiden zwischen pro-Bibliothek-Schlüssel oder Zustandsbereinigung beim Löschen, plus Klärung zum Fall der reinen Adressänderung ohne Löschung, plus Regressionstest.

**Geliefert:** Entscheidung für die pro-Bibliothek-Schlüsselung (Option „Feed-Zustand pro Bibliothek führen" aus dem Issue). Migration 045 fügt `library_id` hinzu, backfillt bestehende Zeilen, löscht verwaiste Zeilen und ersetzt die `feed_url`-only-Unique-Constraint durch `(library_id, feed_url)`. `fk_rss_feed_state_library` mit `ON DELETE CASCADE` löst die im Issue offen gelassene `deleteLibrary`-Frage auf Datenbankebene, nicht nur im Anwendungscode. Die zweite offene Frage (reine Adressänderung ohne Löschung) beantwortet sich von selbst: eine neue Adresse findet keinen Eintrag und startet wie eine neue Bibliothek. `RssFeedIndexingExecutor` nutzt jetzt `findByLibraryIdAndFeedUrl`. Reproduktionsnachweis über `Migration045KeyRssFeedStateByLibraryTest` (7 Tests), inklusive eines Tests, der den Defekt unabhängig von der Migration am alten Schema mit echter `SQLException` (`duplicate key value violates unique constraint`) belegt.

**Verifikation:** `RssFeedStateRepository.java` enthält `findByLibraryIdAndFeedUrl(UUID libraryId, String feedUrl)` — Umsetzung entspricht der PR-Beschreibung.

**Themen:** backend, indexing, rss, knowledge-sources, datenbank, migration
