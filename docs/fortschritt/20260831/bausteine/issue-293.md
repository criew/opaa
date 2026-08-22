# Issue #293 — fix(auth): Race bei paralleler Erstanmeldung erzeugt 500er auf uq_users_subject_issuer
- Geschlossen: 2026-08-03 (completed)
- Labels: bug, backend, size:S, auth
- PRs: #299 (2026-08-03)

**Laut Issue:** `UserService.findOrCreateUser` prüfte auf einen vorhandenen Nutzer und legte ihn sonst an, ohne die Unique-Constraint `uq_users_subject_issuer` zu behandeln. Bei paralleler Erstanmeldung (Provisioning-Filter bei jedem Request plus mehrere SPA-Aufrufe direkt nach Login) scheiterten empirisch 3 von 4 parallelen Aufrufen mit einem Duplicate-Key-Fehler bis zum Aufrufer durch. Gefordert: Race-Behandlung analog `SpaceService.ensurePersonalSpace`, Mehrthread-Test gegen echtes Liquibase-Schema, Reproduktionsnachweis.

**Geliefert:** `findOrCreateUser` in `updateExistingUser` und `createOrFetchUser` aufgeteilt; der Insert läuft in einer eigenen `REQUIRES_NEW`-Transaktion, bei `DataIntegrityViolationException` wird der inzwischen committete Gewinner-Datensatz neu gelesen statt der Fehler durchgereicht. Neuer Test `UserServiceCreationRaceIntegrationTest` mit vier echten parallelen Threads gegen Postgres/Liquibase. Reproduktionsnachweis erbracht (Fix zurückgenommen → `DataIntegrityViolationException` sichtbar). Im PR zusätzlich vermerkt, aber nicht behoben: dasselbe Check-then-Create-Muster besteht auch in `DirectorySyncStatusRecorder.record`.

**Verifikation:** `UserServiceCreationRaceIntegrationTest.java` existiert im Worktree unter `backend/src/test/java/io/opaa/auth/`.

**Themen:** auth, backend, concurrency, testing
