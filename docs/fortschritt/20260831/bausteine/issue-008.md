# Issue #8 — feat(api): define API contract with OpenAPI spec and dual mock layer
- Geschlossen: 2026-02-19 (completed)
- Labels: mvp, backend, frontend, size:M
- PRs: #26 (2026-02-19)

**Laut Issue:** Vollständigen API-Vertrag als OpenAPI-3.0-Spezifikation definieren (Query, Indexing-Trigger, Indexing-Status), Request/Response-DTOs in Java und TypeScript, dualen Mock-Layer bauen (Backend-Profil `mock` + MSW im Frontend), Validierung, globaler Exception-Handler mit einheitlichem Fehlerformat, CORS.

**Geliefert:** PR #26 liefert die OpenAPI-Spec, 8 Java-Record-DTOs mit Validierung, `GlobalExceptionHandler`, `CorsConfig`, `MockQueryController`/`MockIndexingController` hinter `@Profile("mock")`, passende TypeScript-Typen sowie MSW-v2-Handler mit gemeinsamen Fixtures. Backend- und Frontend-Unit-Tests wie gefordert. Deckt die Anforderung vollständig ab; ein manueller Testpunkt (`VITE_ENABLE_MOCKS=true npm run dev` Browser-Check) blieb im PR-Testplan unmarkiert.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` existiert weiterhin. Die Mock-Controller `MockQueryController`/`MockIndexingController` existieren dagegen NICHT mehr — laut `git log --follow` wurden sie im Commit „refactor: remove Spring mock profile from codebase" entfernt, nachdem `feat(api): generate backend and frontend DTOs from OpenAPI spec` das DTO-Generierungsverfahren (ADR-0006) eingeführt hatte. Das Mock-Profil-Konzept aus #8 wurde also nach dem MVP bewusst wieder abgeschafft; die eigentliche API-Vertrags-Infrastruktur (OpenAPI-Spec, generierte DTOs, MSW im Frontend) besteht als Nachfolgekonstruktion fort.

**Themen:** backend, frontend, api, openapi, mocking
