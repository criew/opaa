# ADR-0006: OpenAPI-First-DTO-Generierung

## Status

Akzeptiert

## Kontext

OPAA stellt eine REST-API bereit, die durch eine OpenAPI-Spezifikation definiert ist (heute: Fragmente je Thema unter `opaa-api/src/main/openapi/`, siehe Nachtrag). Anfangs wurden Backend-DTOs als handgeschriebene Java-Records in `io.opaa.api.dto` erstellt. Als die API wuchs (Abfrage-, Indizierungs-, Workspace-Endpunkte), wurde es fehleranfällig und duplizierte Aufwand, handgeschriebene DTOs mit der OpenAPI-Spezifikation synchron zu halten.

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

## Nachtrag (09/2026, #2002): Spec in Dateien je Thema, zur Build-Zeit gebündelt

Die Spec war auf rund 19.600 Zeilen gewachsen und wurde in 145 von 763 Commits aus 60 Tagen geändert. Parallele Stränge kollidierten in dieser einen Datei, und ein Agent musste für jede Änderung eine sehr große Datei lesen (Analyse in Epic #1906).

### Entscheidung

- Die Spec liegt als eine Datei je Thema unter `opaa-api/src/main/openapi/`. Die Themen folgen den logischen Modulen aus `backend/AGENTS.md`, wo nötig feiner, zum Beispiel `libraries.yaml` und `library-metadata.yaml`. `root.yaml` trägt `openapi` und `info`. `common.yaml` trägt die Bausteine ohne fachlichen Besitzer: Fehlerhülle, übergreifende Enums, gemeinsame Parameter und Antworten.
- Ein Fragment enthält nur `tags`, `paths` und `components`. Alle `$ref` zeigen in die gebündelte Spec (`#/components/...`). Es gibt keine Verweise zwischen Dateien und keine Indexdatei, die bei jedem Feature mitgeändert werden müsste.
- Der Gradle-Task `:opaa-api:bundleOpenApi` führt die Fragmente zusammen (`io.opaa.api.bundler.OpenApiBundler`, SnakeYAML auf dem Knotenbaum, damit jeder Skalar seine Quellform behält). Das Ergebnis `build/openapi-bundle/openapi/opaa-api.yaml` ist Eingabe des Generators und liegt für die Tests unverändert als `/openapi/opaa-api.yaml` im Klassenpfad. Der Bundler bricht ab bei doppelten Schlüsseln, bei nicht lokalen oder nicht auflösbaren `$ref` und bei Operationen, die nicht genau ein Tag aus der eigenen Datei tragen.
- Das Frontend bündelt dieselben Fragmente mit `frontend/scripts/bundle-openapi.mjs` (Paket `yaml`) nach denselben Regeln, bevor `openapi-typescript` läuft. Frontend-CI und Frontend-Image haben keine JVM.
- Tags: ein Tag je Thema, deklariert in der Datei des Themas. Das Sammel-Tag `admin` und die Doppelformen `space`/`spaces` und `library`/`libraries` entfallen.

### Verworfene Alternativen

- **Redocly CLI `bundle`:** braucht Node im Backend-Build, über das Gradle-Node-Plugin oder über den Aufruf von pnpm im Frontend. `bundle` löst außerdem nur Verweise zwischen Dateien auf und braucht deshalb eine Wurzeldatei, die jeden Pfad und jedes Schema per `$ref` aufzählt. Diese Wurzeldatei wäre wieder eine Sammeldatei, die jedes Feature berührt.
- **`swagger-parser` oder das Multi-File-Verhalten des OpenAPI-Generators:** Der Generator käme mit `$ref` zwischen Dateien zurecht. Die Tests und das Frontend brauchen aber trotzdem eine einzelne Datei. Das Serialisieren über das Modell von `swagger-parser` normalisiert die Spec außerdem und benennt externe Schemas gegebenenfalls um. Die Gleichheit mit der alten Datei wäre so nicht mehr belegbar.
- **Frontend bündelt über Gradle:** Das Frontend-Image und der Frontend-CI-Job bräuchten dann ein JDK und den Gradle-Build.

### Konsequenzen

- Eine Spec-Änderung berührt in der Regel nur die Datei ihres Themas. Die größte Datei hat rund 2.100 Zeilen.
- Es gibt zwei Bundler mit denselben Regeln. Wer die Regeln ändert, ändert beide. Die Prüfungen laufen nur im Java-Bundler, also in der Backend-CI, und die läuft bei jeder Spec-Änderung.
- Innerhalb eines Fragments zeigt ein Editor Verweise auf Schemas anderer Dateien als nicht auflösbar an. Maßgeblich ist das Bündel.
