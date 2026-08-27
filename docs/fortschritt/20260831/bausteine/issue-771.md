# Issue #771 — fix(models): Fehlender OPAA_SETTINGS_ENCRYPTION_KEY bricht den Anwendungsstart ab statt nur die Seed-Übernahme
- Geschlossen: 2026-08-23 (completed)
- Labels: bug, backend, size:S, security
- PRs: #772 (2026-08-23)

**Laut Issue:** Nach nächtlichem Deploy war die Demo-Instanz down und der Demo-Smoke-CI-Workflow rot, weil ein fehlender `OPAA_SETTINGS_ENCRYPTION_KEY` die `IllegalStateException` aus `SettingsEncryptor` bis in den `ApplicationRunner` durchschlagen ließ und den Start abbrach — im Widerspruch zur eigenen Dokumentation, die nur ein Scheitern der einmaligen Seed-Übernahme zusagt. Gefordert: Seed kontrolliert überspringen mit ERROR-Log, kein Seed-Marker, Anwendung startet normal; alle anderen Fehler weiterhin propagieren; `e2e/demo-smoke.env` korrigieren; Reproduktionsnachweis.

**Geliefert:** `SettingsEncryptor#isKeyConfigured()` neu, `LlmModelSeeder#seedFromOpenAi()` wirft bei fehlendem Schlüssel eine paketprivate `MissingEncryptionKeyException`, die `seedIfNeeded()` fängt, ERROR loggt und ohne Marker überspringt — Neustart mit gesetztem Schlüssel holt die Übernahme nach. `e2e/demo-smoke.env`: der wirkungslose `OPAA_OPENAI_API_KEY`-Platzhalter entfernt statt eines zusätzlichen Test-Encryption-Keys, um kein geheimnisähnliches Artefakt einzuführen — leichte Abweichung vom im Issue vorgeschlagenen „deterministischen Test-Key setzen“, aber im PR begründet. `docs/deployment.md` entsprechend präzisiert.

**Verifikation:** `LlmModelSeeder.java` enthält `isKeyConfigured`/`MissingEncryptionKeyException` (bestätigt per Grep). `LlmModelSeedRunner.java`, `LlmModelSeederTest.java`, `e2e/demo-smoke.env` existieren im Worktree.

**Themen:** modellverwaltung, security, deployment, demo-instanz
