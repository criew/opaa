# Issue #62 — 🚨 [CRITICAL] Missing Rate Limiting on API Endpoints
- Geschlossen: 2026-03-01 (completed)
- Labels: backend, size:M, security
- PRs: #84 (2026-03-01)

**Laut Issue:** Keine Rate Limits auf `/api/v1/query` und `/api/v1/indexing/trigger` — Risiko für LLM-Kosten-Explosion und DoS. Gefordert: konfigurierbares Rate Limiting (Query 10/min, Indexing 1/min), 429-Antworten, externe Konfiguration.

**Geliefert:** PR #84 implementiert Per-IP-Rate-Limiting mit Caffeine-basiertem Sliding Window, konfigurierbar über Umgebungsvariablen und global abschaltbar (`OPAA_RATE_LIMIT_ENABLED=false`), inkl. 429-Antworten und Frontend-Fehleranzeige. Deckt die Anforderungen vollständig ab.

**Verifikation:** `RateLimitConfiguration.java`, `RateLimitFilter.java`, `RateLimitProperties.java`, `RateLimitService.java` existieren unverändert im heutigen Worktree unter `backend/src/main/java/io/opaa/api/`.

**Themen:** security, rate-limiting, backend
