# Issue #244 — docs: bestehende öffentliche Instanz opaa.ewerlin.com in der Deployment-Dokumentation beschreiben
- Geschlossen: 2026-08-02 (completed)
- Labels: documentation, size:S, demo
- PRs: #247 (2026-08-02)

**Laut Issue:** Die bereits betriebene öffentliche Instanz `opaa.ewerlin.com` war weder in `docs/deployment.md` noch in `docker-compose.yml` erwähnt. Gefordert: Abschnitt mit URL, Zweck, Betreiber, Zugriff, Abweichungen von der Standardkonfiguration (insbesondere Chat-/Embedding-Anbieter, da Anwendungs-Default `ollama`/`phi3:mini` ist, die Instanz aber vermutlich `openai` nutzt), Aktualisierungsablauf, erlaubte/verbotene Daten, sowie ein Verweis aus `README.md`.

**Geliefert:** Abschnitt in `docs/deployment.md` mit Betreiber (Maintainer), Zweck (öffentliche Test-/Demo-Instanz, kein Produktivbetrieb), Zugriff (`OPAA_AUTH_MODE=oidc` hinter Keycloak, bewusst account-gebunden, kein Gastzugang), Aktualisierungsablauf (Verweis auf `.github/workflows/publish-images.yml`, automatischer Image-Pull bei Push auf `main`), Datenverbot (keine personenbezogenen/vertraulichen/produktiven Daten), Verweis aus `README.md`. Abweichung vom Issue: Die tatsächliche LLM-/Embedding-Konfiguration der Instanz, Rate-Limits, Bind-Adresse und Ports sowie der genaue Update-Mechanismus auf dem VPS blieben unbekannt und wurden explizit als "nicht dokumentiert" ausgewiesen statt vermutet — der PR-Autor holte dazu Rücksprache mit dem Maintainer ein (Ergebnis: Zugang bleibt bewusst hinter Keycloak, kein anonymer Zugang für die spätere Demo-Korpus-Ausrollung #230).

**Verifikation:** `docs/deployment.md` enthält heute (Zeile ~32) den Abschnitt zur Instanz, `README.md` verweist (Zeile ~67) darauf. Der Stand ist seither deutlich ausgebaut worden (u. a. konkrete Modellkonfiguration, Rheinfurt-Demo-Rollout #230/#712) — die im PR offen gelassenen Punkte wurden also in Folge-Issues nachgezogen.

**Themen:** deployment, doku, demo
