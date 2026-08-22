# Issue #252 — docs: Standardwerte in docs/deployment.md gegen application.yml abgleichen
- Geschlossen: 2026-08-21 (completed)
- Labels: documentation, size:S
- PRs: #715 (2026-08-21)

**Laut Issue:** Die Spalte "Standard" in der Umgebungsvariablen-Tabelle vermischte zwei verschiedene Ebenen: den Anwendungs-Default aus `application.yml` (gilt ohne gesetzte Variable) und die Compose-Belegung aus `.env.example` (gilt real im Compose-Stack) — konkret widersprüchlich bei `OPAA_AI_CHAT_PROVIDER`/`OPAA_AI_EMBEDDING_PROVIDER`. Gefordert: zwei Spalten, alle Zeilen gegen beide Quellen verifiziert, Wirkungsbedingung anbieterspezifischer Variablen kenntlich gemacht, Vorrangregel der Konfigurationsquellen genannt.

**Geliefert:** Zweispaltige Tabelle ("Anwendungs-Default" / "Compose-Belegung"), alle Zeilen verifiziert und mehrere Abweichungen korrigiert (`OPAA_SERVER_ADDRESS`, `OPAA_OIDC_JWK_SET_URI`, `OPAA_OPENAI_API_KEY`-Platzhalter, `OPAA_DB_URL`-Query-Parameter, profilabhängiger `OPAA_OLLAMA_BASE_URL`), fehlende Zeilen ergänzt, reine Compose-/nginx-Variablen ohne Spring-Property als solche gekennzeichnet, Absatz zur Vorrangregel (Umgebungsvariable > `.env.docker` > `application.yml`) ergänzt. Klarstellung im PR: Die im Issue konkret genannte Diskrepanz bei den Provider-Variablen war zum PR-Zeitpunkt bereits anderweitig behoben (beide bereits `ollama`) — die strukturelle Zwei-Spalten-Unterscheidung fehlte aber trotzdem noch und war der eigentliche Gegenstand des PRs.

**Verifikation:** `docs/deployment.md` enthält heute die Spalten "Anwendungs-Default (`application.yml`)" / "Compose-Belegung (`.env.docker.example`)" (Zeile ~402) sowie die Vorrangregel-Erläuterung (Zeile ~359 ff.). Die Referenzdatei heißt inzwischen `.env.docker.example` statt `.env.example` (Folge von #716, split in zwei Vorlagen) — inhaltlich deckt sich das mit dem gelieferten Konzept.

**Themen:** deployment, doku
