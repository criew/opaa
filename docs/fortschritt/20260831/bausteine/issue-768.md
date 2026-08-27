# Issue #768 — fix(api): OpenAI-SDK-Fehler (com.openai.errors.*) im GlobalExceptionHandler nutzerfreundlich mappen
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S
- PRs: #806 (2026-08-23)

**Laut Issue:** Seit der Umstellung auf die OpenAI-kompatible Anbindung (#766) werfen Chat-Aufrufe bei Fehlern `com.openai.errors.*`-Typen, auf die `GlobalExceptionHandler` noch nicht mappt — sie landen im generischen 500-Handler statt in einer deutschen Fehlermeldung. Gefordert: Mapping ergänzen, transient/permanent unterscheiden, Tests für beide Fälle.

**Geliefert:** Drei neue `@ExceptionHandler`: `OpenAIIoException`/`OpenAIRetryableException` → 503 (transient), `OpenAIServiceException` → 429/5xx als 503 (inkl. `Retry-After`-Weiterleitung bei Rate-Limit), sonst 502; `OpenAIException` als Auffangzweig → 502. Abweichung/Präzisierung gegenüber dem Issue: Abnahmekriterium „401/403/404 werden unterscheidbar gemappt“ wurde bewusst nur im Log umgesetzt, nicht in der Client-Antwort — dort bleibt es einheitlich 502, mit der Begründung, der Client könne zwischen den Fehlerursachen ohnehin nichts unternehmen. Titelgenerierung und Verbindungstest waren nicht betroffen (eigenes Catch-all bzw. eigener HTTP-Client) und blieben unverändert.

**Verifikation:** `GlobalExceptionHandler.java` enthält Handler für `OpenAIIoException`/`OpenAIServiceException` (bestätigt per Grep). `GlobalExceptionHandlerTest.java` und `QueryControllerLlmErrorMappingIntegrationTest.java` existieren im Worktree.

**Themen:** modellverwaltung, backend, fehlerbehandlung
