# Issue #456 — fix(api): Unbekannte Pfade liefern 500 statt 404 und erzeugen einen ERROR-Stacktrace
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: #802 (2026-08-23)

**Laut Issue:** Eine Anfrage an einen von keinem Controller bedienten Pfad wurde mit 500 statt 404 beantwortet, weil Springs `NoResourceFoundException` unbehandelt in den generischen Auffangzweig `handleGenericException` lief — inklusive vollem Stacktrace auf ERROR-Ebene, auch für automatisierte Scanner-Anfragen wie `/wp-admin` oder `/.env`. Erwartet war 404 im gewohnten `ErrorResponse`-Format, protokolliert höchstens auf DEBUG ohne Stacktrace.

**Geliefert:** Genau wie gefordert. PR #802 fügt `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` in `GlobalExceptionHandler` hinzu, liefert 404 mit deutscher Meldung und protokolliert nur auf DEBUG ohne Stacktrace. Nachbesserung nach Review: Die DEBUG-Zeile läuft jetzt durch `errorSanitizer.sanitize(...)` (Konsistenz mit den AI-Handlern), und der neue Test prüft zusätzlich den deutschen Fehlertext. Der im Issue angerissene Grundsatzumbau des Auffangzweigs (generelle Aufrufer- vs. Serverfehler-Unterscheidung) blieb bewusst außerhalb des Umfangs. Reproduktionsnachweis erbracht: Test schlug vor dem Fix mit `Status expected:<404> but was:<500>` fehl.

**Verifikation:** `backend/src/main/java/io/opaa/api/GlobalExceptionHandler.java` enthält den `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` (Zeile 259) mit begleitendem Javadoc-Kommentar zur Herkunft der Ausnahme.

**Themen:** backend, api, fehlerbehandlung
