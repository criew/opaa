# Issue #133 — [FEAT] Automatically generate frontend/backend DTOs from OpenAPI spec
- Geschlossen: 2026-03-08 (completed)
- Labels: enhancement
- PRs: #134 (2026-03-08)

**Laut Issue:** DTO-Generierung für Backend und Frontend aus der OpenAPI-Spezifikation automatisieren (OpenAPI Generator als Single-Source-of-Truth-Pipeline), in Build/CI integriert und dokumentiert.

**Geliefert:** PR #134 integrierte den OpenAPI Generator in den Backend-Gradle-Build, entfernte handgeschriebene Backend-DTOs zugunsten generierter Klassen, fügte `openapi-typescript`-Generierung im Frontend hinzu und glich das Spec-Schema an den bestehenden API-Vertrag an. Deckt den Kern des Issues für die damals vorhandenen Schemas ab; workspace-spezifische DTOs blieben zunächst handschriftlich und wurden separat in #152 nachgezogen.

**Verifikation:** `build.gradle.kts` enthält den `openApiGenerate`-Task (`org.openapitools.generator.gradle.plugin.tasks.GenerateTask`), bestätigt die dauerhafte Etablierung dieses Musters (heute mit ADR-0006 dokumentiert, siehe AGENTS.md).

**Themen:** api, openapi, dto, codegen, ci
