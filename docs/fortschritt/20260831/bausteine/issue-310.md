# Issue #310 — fix(api): GlobalExceptionHandler mappt ResponseStatusException und DataIntegrityViolationException nicht
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #314 (2026-08-14)

**Laut Issue:** `GlobalExceptionHandler` fing `ResponseStatusException` und `DataIntegrityViolationException` nicht gesondert ab, beide landeten auf dem Catch-all als HTTP 500 „Interner Serverfehler" — etwa wenn eine Gruppe mit fremdem Grant nicht gelöscht werden konnte. Gefordert: `ResponseStatusException` mit Status/Meldung durchreichen, `DataIntegrityViolationException` auf 409 mit verständlicher Meldung abbilden, Constraint-Name nur ins Log, ggf. nach Constraint-Art unterscheiden.

**Geliefert:** PR #314 setzt beides um und geht über die Mindestforderung hinaus: Statt pauschal 409 für jede `DataIntegrityViolationException` wird nach SQLSTATE unterschieden — `23505`/`23503` (Unique/FK) → 409 Conflict, `23502`/`23514` (Not-Null/Check) → 400 Bad Request, da fehlerhafte Eingabedaten kein Bestandskonflikt sind. Bestehende gezielte Guards bleiben unverändert als vorrangige Fehlerbehandlung.

**Verifikation:** `GlobalExceptionHandler.java` im Worktree enthält `DataIntegrityViolationException`.

**Themen:** backend, api, error-handling
