# Issue #433 — fix(indexing): Gelöschte Zielbibliothek mitten im Lauf sauber behandeln (Warnung statt failed)
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, size:S
- PRs: #602 (2026-08-20)

**Laut Issue:** Wird die Zielbibliothek eines laufenden Indizierungsauftrags gelöscht, sollte der Lauf eine Warnung protokollieren und die betroffenen Dokumente als `skipped` statt `failed` markieren (analog zur Konnektor-Spezifikation).

**Geliefert:** Der Maintainer hat den Umfang im Issue-Kommentar geändert — statt den Fall im laufenden Job abzufangen, wird das Löschen an der Wurzel verhindert: `KnowledgeLibraryService#deleteLibrary` lehnt das Löschen jetzt mit `409 CONFLICT` ab, solange ein `IndexingJob` mit Status `RUNNING` existiert. Der ursprünglich beschriebene Fall (Warnung/skipped mitten im Lauf) kann damit regulär nicht mehr eintreten — es ist eine andere, aber sachlich gleichwertige Lösung des Grundproblems, keine Umsetzung des ursprünglichen Abnahmekriteriums „skipped statt failed". Reproduktionsnachweis über Unit- und Integrationstest erbracht. Verwandter, nicht mitgelöster Punkt: #501 (hängengebliebene RUNNING-Jobs könnten eine Bibliothek dauerhaft blockieren).

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` existiert im heutigen Code.

**Themen:** indexing, spaces, backend, fehlerbehandlung
