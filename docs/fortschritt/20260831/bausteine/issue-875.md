# Issue #875 — refactor(backend): Domain-Exceptions statt ResponseStatusException in Services
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:M
- PRs: #881 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B8), baut auf #860 auf. Domain-Services werfen `ResponseStatusException` mit HTTP-Status und deutschen Texten — koppelt Domänenschicht an die Web-Schicht, für Nicht-HTTP-Aufrufer (Scheduler, Sync) nicht nutzbar. Vier Domain-Exceptions (`NotFoundException`, `AccessDeniedException`, `ConflictException`, `ValidationException`), zentrales Mapping im `GlobalExceptionHandler`.

**Geliefert:** Wie gefordert, plus drei zusätzliche Exception-Typen, weil der Bestand tatsächlich Sonderstatus jenseits der vier geforderten brauchte: `UnauthorizedException` (401), `PayloadTooLargeException` (413), `ServiceUnavailableException` (503) — ohne sie wäre das Abnahmekriterium „kein `ResponseStatusException` mehr außerhalb `io.opaa.api`" nicht erfüllbar gewesen. Alle sieben Typen mappen auf denselben Response-Body wie zuvor; Statuscodes/Texte byte-identisch, bis auf eine bewusste Ausnahme: `AuditQueryService#requireAuditor` liefert jetzt den spezifischen Text statt des generischen „Zugriff verweigert" (kein bestehender Test prüfte den generischen Text an dieser Stelle).

**Verifikation:** `backend/src/main/java/io/opaa/common/` enthält `NotFoundException.java`, `AccessDeniedException.java`, `ConflictException.java`, `ValidationException.java`, `PayloadTooLargeException.java`, `ServiceUnavailableException.java`, `UnauthorizedException.java` im Worktree.

**Themen:** backend, refactoring, exceptions, architektur
