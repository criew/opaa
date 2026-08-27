# Issue #896 — build: Gradle-Modul opaa-api — Spec, Generator und geteilte Enums herauslösen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L, ci
- PRs: #898 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 4, letzter Großbaustein. Ziel war ein eigenes Gradle-Modul `opaa-api` mit der OpenAPI-Spec, dem Java-Generator und den geteilten Domain-Enums, damit eine Spec-Änderung nur dieses kleine Modul invalidiert statt das gesamte Backend neu zu kompilieren. Modulname per Maintainer-Entscheidung fest vorgegeben.

**Geliefert:** Top-Level-Modul `opaa-api/` (nicht unter `backend/`), eingebunden über `include(":opaa-api")` bei weiterhin `backend/` als Gradle-Root. 22 Enums nach `io.opaa.api.types` verschoben (alle bereits Spring-/JPA-frei), inklusive Paritätstests gegen die YAML-Spec. Inkrementalitäts-Beleg im PR: nach einer reinen Spec-Änderung bleibt `:compileJava` des Backends `UP-TO-DATE`. Docker-Build-Kontext musste dafür vom Backend-Verzeichnis auf den Repo-Root umgestellt werden (docker-compose.yml, alle Workflow-Dateien mit Image-Build). Frontend generiert unverändert per `openapi-typescript`, nur der Pfad hat sich geändert — Leitplanke eingehalten.

**Verifikation:** Verzeichnis `opaa-api/` existiert im Worktree.

**Themen:** projektsetup, ci, build, modulstruktur, api, epic-826
