# Issue #755 — Epic: feat(models): Verwaltete Chat-Modelle in der Administrationsoberfläche (Stufe 1)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, epic, backend, frontend, size:L
- PRs: keine (Epic ohne eigenen PR)

**Laut Issue:** Das Chat-Modell wird heute über eine Umgebungsvariable beim Aufsetzen entschieden — jede Änderung verlangt Neustart und Zugriff auf die Betriebsebene. Stufe 1 macht das Chat-Modell zu einem verwalteten Objekt: eine Liste hinterlegter Modelle, von denen genau eines aktiv ist, mit verschlüsselten Zugangsdaten, Verbindungstest und Audit. Angebunden wird ausschließlich über die OpenAI-kompatible Schnittstelle (Ollama läuft ebenfalls darüber, unter `/v1` — kein zweiter Anbindungsweg). Fünf Phasen: Persistenz/Zugangsdatenschutz (#756), Admin-API (#757), Laufzeitumbau (#758), Administrationsoberfläche (#759), E2E-Absicherung (#760).

**Geliefert:** Die Arbeit steckt in den Sub-Issues. #756 (PR #763) und #757 (PR #764) sind im vorliegenden Chunk enthalten und einzeln geprüft (siehe issue-756.md, issue-757.md) — beide vollständig geliefert. #758, #759, #760 liegen außerhalb dieses Delta-Chunks und wurden hier nicht einzeln nachgeprüft; der Abhängigkeitsgraph im Issue (#756→#757→#758, #757→#759→#760) legt nahe, dass sie auf den beiden geprüften Phasen aufbauen.

**Verifikation:** Über #756/#757 bestätigt (Migration 058 `llm_models`, `LlmModelController`/`LlmModelService` existieren im Worktree, siehe deren Bausteine).

**Themen:** modellverwaltung, backend, frontend, security, epic
