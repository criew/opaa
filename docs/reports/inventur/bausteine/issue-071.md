# Issue #71 — 🟡 [MEDIUM] Sensitive Error Information in Logs
- Geschlossen: 2026-03-01 (completed)
- Labels: backend, size:S, security
- PRs: #89 (2026-03-01), #90 (2026-03-01)

**Laut Issue:** AI-Exception-Nachrichten wurden ungefiltert geloggt — Risiko für Leakage von API-Keys, Dateipfaden, internen Konfigurationsdetails. Gefordert: Sanitizer-Komponente, Anwendung auf alle AI-Exception-Handler, Tests, Dokumentation der Logging-Praxis.

**Geliefert:** PR #89 liefert `ErrorSanitizer` (redigiert API-Keys, Unix/Windows-Pfade, URL-Query-Parameter) und bindet ihn im `GlobalExceptionHandler` ein, mit ausführlichen Tests. PR #90 ist ein direktes Follow-up desselben Tages: `ErrorSanitizer` wird von einer Spring-`@Component` zu einer einfachen Utility-Klasse zurückgebaut, um `@WebMvcTest`-Testklassen nicht unnötig mit einem zustandslosen Bean zu belasten — eine Code-Quality-Korrektur, keine fachliche Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/api/ErrorSanitizer.java` existiert im heutigen Worktree weiterhin und ist in `GlobalExceptionHandler.java` eingebunden.

**Themen:** security, logging, backend
