# ADR-0006: OpenAPI-First-DTO-Generierung

## Status

Akzeptiert

## Kontext

OPAA stellt eine REST-API bereit, die durch eine OpenAPI-Spezifikation definiert ist (`opaa-api/src/main/resources/openapi/opaa-api.yaml`). Anfangs wurden Backend-DTOs als handgeschriebene Java-Records in `io.opaa.api.dto` erstellt. Als die API wuchs (Abfrage-, Indizierungs-, Workspace-Endpunkte), wurde es fehleranfällig und duplizierte Aufwand, handgeschriebene DTOs mit der OpenAPI-Spezifikation synchron zu halten.

PR #134 führte OpenAPI-Code-Generierung für Nicht-Workspace-DTOs ein. Workspace-DTOs blieben handgeschrieben aufgrund ihrer Abhängigkeit von Domain-Enums (`WorkspaceRole`, `WorkspaceType`). Diese Inkonsistenz — einige DTOs generiert, einige handgeschrieben — schuf Verwirrung über die Quelle der Wahrheit.

Das Frontend generiert bereits alle TypeScript-Typen aus derselben OpenAPI-Spezifikation über `openapi-typescript`, wodurch die Spezifikation der de-facto-Vertrag ist.

## Entscheidung

**Alle API-DTOs MÜSSEN aus der OpenAPI-Spezifikation generiert werden.** Keine handgeschriebenen DTO-Klassen sind in `io.opaa.api.dto` erlaubt.

Konkret:

1. **Die OpenAPI-Spezifikation ist die einzige Quelle der Wahrheit** für alle Request-/Response-Schemas. Änderungen am API-Vertrag beginnen mit einer Spec-Änderung.
2. **Backend-DTOs werden generiert** durch das OpenAPI-Generator-Gradle-Plugin (`spring`-Generator) in `build/generated/openapi/`. Generierter Code wird nicht in die Versionskontrolle eingecheckt.
3. **Frontend-Typen werden generiert** durch `openapi-typescript` in `frontend/src/types/generated/`. Generierter Code wird nicht in die Versionskontrolle eingecheckt.
4. **Domain-Enums, die von DTOs referenziert werden** (z. B. `WorkspaceRole`, `WorkspaceType`), werden über `typeMappings`/`importMappings` in `build.gradle.kts` gemappt, sodass generierte DTOs die vorhandenen Domain-Typen direkt verwenden — keine Konvertierungsschicht benötigt.
5. **Neue API-Endpunkte** müssen zunächst ihre Schemas in der OpenAPI-Spezifikation definieren, dann die generierten DTOs in Controllern und Services verwenden.

### Konfiguration

Spec, OpenAPI-Generator und die von `typeMappings` referenzierten Domain-Enums leben im eigenen Gradle-Modul `opaa-api` (issue #896) — eine Spec-Änderung invalidiert dadurch nur dieses Modul, nicht das gesamte Backend-Sourceset. Der Generator ist in `opaa-api/build.gradle.kts` konfiguriert:

- `models` auf `""` gesetzt (alle Schemas generieren)
- `typeMappings` mappt Domain-Enums und benutzerdefinierte Typen zu vorhandenen Klassen in `io.opaa.api.types`
- `importMappings` liefert die vollqualifizierten Klassennamen für gemappte Typen
- Ein `doLast`-Block entfernt generierte Enum-Dateien, die auf Domain-Enums gemappt sind (der Generator erstellt sie trotz `typeMappings`)

Das Backend konsumiert die generierten DTOs transitiv über `implementation(project(":opaa-api"))`, ohne selbst einen OpenAPI-Generator-Task zu besitzen.

## Konsequenzen

### Einfacher

- **Konsistenz garantiert:** DTOs stimmen immer mit der Spezifikation überein — kein Drift möglich
- **Weniger Boilerplate:** Keine Notwendigkeit, Records, Getter, equals/hashCode oder Jackson-Annotationen zu schreiben
- **Einziger Workflow:** Spezifikation ändern → neu generieren → Service-Code anpassen
- **Frontend-Backend-Abgleich:** Beide Seiten generieren aus derselben Spezifikation

### Schwieriger

- **Generierter Code-Stil:** Generierte DTOs sind veränderliche POJOs mit Gettern/Settern anstatt prägnanter Java-Records. Service-Code verwendet `request.getName()` anstatt `request.name()`.
- **Build-Abhängigkeit:** `compileJava` hängt von `openApiGenerate` ab; die Spezifikation muss gültig sein, damit der Build erfolgreich ist
- **Enum-Mapping-Wartung:** Wenn neue Domain-Enums hinzugefügt werden, die in der API verwendet werden, müssen `typeMappings` und `importMappings` in `build.gradle.kts` aktualisiert werden; der `doLast`-Cleanup-Block leitet die zu entfernenden generierten Dateien mechanisch aus `typeMappings` ab und muss dafür nicht mehr separat gepflegt werden
