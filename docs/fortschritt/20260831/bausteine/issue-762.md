# Issue #762 — refactor(ai): Nativen Ollama-Starter entfernen — Embedding über OpenAI-kompatible Schicht
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:S
- PRs: #766 (2026-08-22)

**Laut Issue:** `spring-ai-starter-model-ollama` entfernen, weil Ollama auch `/v1/embeddings` OpenAI-kompatibel bedient. Embedding-Konfiguration auf die OpenAI-kompatible Schicht vereinheitlichen, Provider-Umschalter `OPAA_AI_EMBEDDING_PROVIDER`/`OPAA_OLLAMA_*` entfallen lassen, Doku und Compose-Umgebung anpassen inkl. Migrationshinweis für Bestandsdeployments (insbesondere die Demo-Instanz).

**Geliefert:** Starter aus `libs.versions.toml`/`build.gradle.kts` entfernt (musste `spring-ai-retry` explizit nachziehen, da bisher transitiv über den Ollama-Starter kam). `spring.ai.model.chat`/`embedding` fest auf `openai`, Base-URL-Defaults zeigen weiterhin auf lokalen Ollama-Server. `LlmModelSeeder` behält für Bestandsinstallationen einen Legacy-Lesepfad für `OPAA_OLLAMA_BASE_URL`/`OPAA_OLLAMA_CHAT_MODEL`. `docs/deployment.md` um vollständige Variablen-Migrationstabelle ergänzt. `.env.example`-Dateien korrigiert (Chat-/Embedding-Defaults spiegelten vorher fälschlich `gpt-4o`/`text-embedding-3-small` statt der tatsächlichen Ollama-Modelle). Deckt sich mit dem Issue-Umfang.

**Verifikation:** `backend/gradle/libs.versions.toml` enthält keinen `spring-ai-starter-model-ollama`-Eintrag mehr, nur noch `testcontainers-ollama` (Test-Infrastruktur) und einen Kommentar, der die Historie erklärt. `EmbeddingInfoService.java` und `LlmModelSeeder.java` existieren im Worktree.

**Themen:** modellverwaltung, embedding, deployment, ollama
