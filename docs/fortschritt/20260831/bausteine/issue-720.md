# Issue #720 — feat(deployment): Ollama als optionalen Compose-Service unter eigenem Profil bereitstellen
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement
- PRs: #801 (2026-08-23)

**Laut Issue:** Der Compose-Stack versprach als Voreinstellung lokal betriebene Modelle über Ollama (`http://ollama:11434`), enthielt aber keinen `ollama`-Service — Indizierung und erste Frage liefen in einen Verbindungsfehler. Gefordert war ein `ollama`-Service unter eigenem Compose-Profil, Modell-Bereitstellung (`ollama pull`), und Doku-Nachzug, ohne dass der Standardbetrieb (ohne Profil) sich ändert.

**Geliefert:** Wie gefordert. Zwei neue Services unter Profil `ollama`: `ollama` (Server, benanntes Volume, kein Host-Port) und `ollama-pull` (einmaliger, idempotenter Init-Schritt, zieht `nomic-embed-text` und `phi3:mini`). `docs/deployment.md` um Abschnitt "Lokal betriebenes Ollama im Compose-Stack" ergänzt. Zusätzlich im selben PR ein themennaher Fund behoben: `.env.docker.example` setzte `OPAA_PGVECTOR_DIMENSIONS=1536`, obwohl das dort voreingestellte `nomic-embed-text`-Modell mit 768 Dimensionen einbettet — jede Indizierung mit der unveränderten Beispielkonfiguration wäre sofort gescheitert; jetzt auf 768 korrigiert. Verifikation im PR dokumentiert: mit Profil vollständiger Indizierungs-/Frage-Durchlauf erfolgreich, ohne Profil unverändertes Verhalten, kein Port-Expose über 127.0.0.1 hinaus.

**Verifikation:** Nicht erneut im Code geprüft — Compose-/Doku-Änderung ohne Anwendungscode, PR-Beschreibung dokumentiert eigene Verifikationsschritte ausführlich.

**Themen:** deployment, docker, modellverwaltung, ollama
