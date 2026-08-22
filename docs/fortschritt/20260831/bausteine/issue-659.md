# Issue #659 — fix(indexing): Indizierungsfehlermeldung leakt internen Pfad/Host an VIEWER
- Geschlossen: 2026-08-20 (completed)
- Labels: bug, backend, security
- PRs: #657 (2026-08-20)

**Laut Issue:** Review-Befund zu PR #657 (Umsetzung von #507): `GET /api/v1/libraries/{id}/indexing/status` gab bei `FAILED`-Status die rohe `Exception#getMessage()` weiter (interner Dateisystempfad bei `NoSuchFileException`, Host:Port bei `UnknownHostException`/`ConnectException`) — für jeden VIEWER sichtbar, obwohl #507 dieselben Informationen für `LibraryResponse` bereits vor VIEWER verbirgt. Gefordert: generische oder kategorisierte deutsche Meldung unterhalb MANAGER, Detailmeldung ab MANAGER, Test dafür.

**Geliefert:** Der referenzierte PR #657 liefert beide Themen zugleich (#507 und #659) — die Fehlermeldung des Indizierungsstatus wird jetzt rollenabhängig gekürzt (generischer deutscher Text unterhalb MANAGER, vollständige Executor-Meldung ab MANAGER), mit Reproduktionsnachweis über `LibraryIndexingControllerTest`. Deckt die Abnahmekriterien vollständig ab; die Randnotiz zu `IndexingRunEvent.reference`/RSS-Host blieb wie im Issue vorgesehen unangetastet.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/IndexingStatusView.java` und `backend/src/main/java/io/opaa/library/KnowledgeLibraryService.java` existieren im Worktree.

**Themen:** security, indexing, api, information-disclosure, backend
