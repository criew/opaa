# Issue #65 — 🚨 [CRITICAL] No Observability (Metrics, Tracing, Health Checks)
- Geschlossen: 2026-03-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #85 (2026-03-01)

**Laut Issue:** Keine Metriken, Tracing oder Downstream-Health-Checks. Gefordert: Spring Boot Actuator + Micrometer/Prometheus, Health-Indikatoren für OpenAI/pgvector, Metriken für Query-Latenz, LLM-Token/Kosten, Indexierungsdurchsatz, aktive Konversationen; optional Tracing und Grafana-Dashboard.

**Geliefert:** PR #85 liefert Actuator + Micrometer-Prometheus-Registry, drei generische Health-Indikatoren (`ChatHealthIndicator`, `EmbeddingsHealthIndicator`, `VectorStoreHealthIndicator` — providerunabhängig statt OpenAI-spezifisch) und Custom-Metriken (`opaa.query.duration`, `opaa.query.count`, `opaa.query.tokens`, `opaa.indexing.documents`, `opaa.conversations.active`). Tracing und ein Grafana-Dashboard-Template wurden nicht geliefert — Abweichung vom „Definition of Done", aber im Issue selbst schon als „Optional" bzw. ohne Verpflichtung markiert.

**Verifikation:** `backend/src/main/java/io/opaa/observability/` mit den genannten Health-Indikatoren existiert im Worktree. Kein Grep-Hinweis auf ein Grafana-Dashboard-Template im Repo.

**Themen:** observability, metrics, backend, prometheus
