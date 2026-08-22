# Issue #465 — refactor(indexing): Quellentyp ausdrücklich übergeben und Executor über eine Registry auflösen
- Geschlossen: 2026-08-18 (completed)
- Labels: enhancement, backend, size:M
- PRs: #473 (2026-08-18)

**Laut Issue:** Phase 1 (Umbau) des Epics #463 — Interface für den Indizierungsweg, Registry zur Auflösung des Executors über den Typ, `sourceType` als optionales Feld im Anstoß eines Laufs (Rückfall auf bisherige Ableitung bei fehlendem Feld), Widerspruchsprüfung bei inkonsistenten Feldern, Zusammenführung der duplizierten `reportRejected`-Logik, Fallunterscheidungen aus `IndexingController`/`DocumentIndexingService` entfernen.

**Geliefert:** Wie gefordert — neues Enum `IndexingSourceType` (`FILESYSTEM`, `HTTP_DIRECTORY`, `RSS_FEED` kommt erst in #466 hinzu), Interface `SourceIndexingExecutor`, `IndexingSourceExecutorRegistry`, `RejectedDocumentReporter` für die zusammengeführte Duplikation, Widerspruchsprüfung mit deutscher 400-Meldung. `sourceType` optional in `IndexingTriggerRequest` (OpenAPI-generiert). Explizit benannte Abweichung von der Abnahme „ohne fachliche Anpassung": `IndexingControllerTest` musste mechanisch angepasst werden, da der Controller jetzt einen statt zwei Methodenaufrufe macht — laut PR ist das eine notwendige Folge der geforderten Fallunterscheidungs-Entfernung, kein Verhaltensunterschied.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingSourceType.java`, `SourceIndexingExecutor.java`, `IndexingSourceExecutorRegistry.java` existieren im heutigen Stand (letztere beiden nicht einzeln geprüft, aber laut Dateiliste vorhanden und `IndexingSourceType.java` bestätigt).

**Themen:** indexing, refactoring, registry, rss, backend
