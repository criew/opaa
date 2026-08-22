# Issue #47 — feat: configurable HTTP/1.1 mode for vLLM and other OpenAI-compatible servers
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp
- PRs: #48 (2026-02-26)

**Laut Issue:** Anfragen an vLLM (Uvicorn/ASGI, nur HTTP/1.1) schlugen mit 400 Bad Request fehl, weil Spring Boots `JdkClientHttpRequestFactory` HTTP/2 (h2c-Upgrade) bevorzugt. Gefordert war eine konfigurierbare Option `opaa.http.force-http1` (Default `false`), die über einen `RestClientCustomizer`-Bean alle Spring-AI-HTTP-Verbindungen auf HTTP/1.1 zwingt.

**Geliefert:** PR #48 setzt genau das um — neue Property `opaa.http.force-http1` (Env: `OPAA_HTTP_FORCE_HTTP1`), Default `false` ohne Verhaltensänderung. Deckt sich vollständig mit dem Issue-Vorschlag, keine Abweichungen.

**Verifikation:** `backend/src/main/java/io/opaa/api/HttpClientConfig.java` existiert weiterhin und enthält `@ConditionalOnProperty(name = "opaa.http.force-http1", havingValue = "true")`.

**Themen:** backend, deployment, vllm, http-konfiguration
