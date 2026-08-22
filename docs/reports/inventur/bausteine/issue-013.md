# Issue #13 — feat(api): replace mock endpoints with real implementation
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, backend, size:M
- PRs: #38 (2026-02-26)

**Laut Issue:** Mock-Controller aus #8 durch echte Implementierungen ersetzen, die `QueryService`/`IndexingJobService` aufrufen; API-Vertrag (OpenAPI-Spec, DTOs) unverändert lassen; Mock-Profil weiterhin funktionsfähig halten; Request-Logging (Methode, Pfad, Antwortzeit) ergänzen.

**Geliefert:** PR #38 liefert nur den Request-Logging-Teil der Anforderung — einen `RequestLoggingFilter`, der Methode, Pfad, Statuscode und Antwortzeit für `/api/`-Requests protokolliert, plus Aufräumen einer ungenutzten Konfigurationseigenschaft. Die eigentliche Kernanforderung („Mock-Controller durch echte Implementierung ersetzen") ist im PR-Body nicht beschrieben und auch nicht in der Dateiliste erkennbar — sie wurde vermutlich in #34 (Indexing, `IndexingController`) und #36 (`QueryController`) bereits miterledigt, die beide reale, nicht-Mock-Controller einführten. #38 schließt das Issue formal über „Closes #13", deckt inhaltlich aber nur den Logging-Teilaspekt ab; der Rest war zum Zeitpunkt des Schließens bereits durch vorangegangene PRs erfüllt.

**Auffälligkeit — Fehlzuordnung in den Daten:** Wie bei #12 sind zusätzlich #286 und #291 verknüpft. Beide betreffen das Tagesreport-CI-Skript, nicht diese Issue. Ursache laut PR-Body von #291: Testbeispieltexte in #286 („`fixes #12 und Closes #13`") wurden fälschlich als reale `Closes #N`-Referenzen ausgewertet. Für die Inventur zählt daher **nur #38** als Liefer-PR von Issue #13.

**Verifikation:** `backend/src/main/java/io/opaa/api/RequestLoggingFilter.java` existiert weiterhin im Worktree. `IndexingController`/`QueryController` (real, nicht Mock) existieren ebenfalls und wurden laut Historie in #34/#36 eingeführt.

**Themen:** backend, api, logging, dokumentationslücke, ci
