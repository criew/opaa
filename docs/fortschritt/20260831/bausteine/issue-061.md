# Issue #61 — 🚨 [CRITICAL] CORS Wildcard Headers Security Risk
- Geschlossen: 2026-02-28 (completed)
- Labels: backend, size:S, security
- PRs: #80 (2026-02-28)

**Laut Issue:** `CorsConfig.java` erlaubte mit `allowedHeaders("*")` beliebige Header — Risiko für CORS-Bypass und Request Smuggling, besonders vor der geplanten Auth-Einführung. Gefordert: explizite Whitelist (`Content-Type`, `Authorization`, `X-Requested-With`).

**Geliefert:** PR #80 ersetzt den Wildcard exakt wie im Issue vorgeschlagen durch die genannte Whitelist. Keine Abweichung.

**Verifikation:** `backend/src/main/java/io/opaa/api/CorsConfig.java` existiert im heutigen Worktree nicht mehr — die CORS-Konfiguration ist im Zuge der späteren Auth-Einführung (Issue #108, PR #135) nach `backend/src/main/java/io/opaa/auth/SecurityCorsConfig.java` gewandert und dort weiterhin mit expliziter Header-Liste konfiguriert. Die hier gelieferte Whitelist-Logik lebt also fort, nur an anderer Stelle.

**Themen:** security, cors, backend
