# Issue #756 — feat(models): Datenmodell für verwaltete Chat-Modelle, verschlüsselte Zugangsdaten und Seed-Migration
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #763 (2026-08-22)

**Laut Issue:** Phase 1 des Epics #755. Gefordert: Tabelle `llm_models` (Liquibase), kein Provider-Typ-Feld (ausschließlich OpenAI-kompatible Schnittstelle, Ollama über eigenen `/v1`-Endpunkt), optionaler AES-GCM-verschlüsselter API-Schlüssel gegen einen Master-Key aus der Umgebung, datenbankseitige Sicherung "höchstens ein aktiver Eintrag", lautes Scheitern beim Start ohne gültigen Master-Key, Übernahme der bestehenden Umgebungskonfiguration als initiales aktives Modell beim ersten Start (idempotent), Audit-Ereignisse für Anlegen/Ändern/Löschen/Aktivieren.

**Geliefert:** Vollständig wie gefordert. Migration 058 (`llm_models`, partieller eindeutiger Index `ux_llm_models_single_active`). `SettingsEncryptor` (AES-256-GCM, zufälliger IV je Wert) mit eigenem Master-Key `OPAA_SETTINGS_ENCRYPTION_KEY` — bewusst getrennt vom bestehenden `OPAA_CREDENTIALS_ENCRYPTION_KEY`. `SettingsEncryptionKeyGuard` lässt den Start sofort mit deutscher Meldung abbrechen (anders als die bestehende Zugangsdaten-Verschlüsselung, die erst beim ersten Schreibvorgang scheitert) — Begründung: Die Seed-Migration kann selbst schon einen Schlüssel brauchen. `LlmModelSeedRunner` übernimmt `spring.ai.model.chat`-Konfiguration einmalig, mit `/v1`-Suffix-Logik ohne Verdopplung. Bewusste Annahme, außerhalb des Issue-Umfangs vorgezogen: `LlmModelService` implementiert bereits Anlegen/Ändern/Löschen/Aktivieren mit Audit, obwohl die REST-Schicht erst im Folge-Issue (#757) entsteht; die Geschäftsregel "aktives Modell kann nicht gelöscht werden" ist bewusst nicht in dieser Schicht verankert, sondern der Admin-API vorbehalten.

**Verifikation:** Die ursprüngliche Migrationsdatei `058-create-llm-models.yaml` existiert nicht mehr einzeln im Worktree — sie wurde mit PR #906 (Liquibase-Baseline-Konsolidierung, dokumentiert in AGENTS.md) in `backend/src/main/resources/db/changelog/changes/001-baseline.yaml` zusammengefasst (`grep -l llm_models` findet sie dort). `backend/src/main/java/io/opaa/llm/LlmModelConnectionTester.java` und weitere `io.opaa.llm`-Klassen existieren im Worktree.

**Themen:** modellverwaltung, backend, security, migration
