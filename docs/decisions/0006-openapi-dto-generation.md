# ADR-0006: OpenAPI-First-DTO-Generierung

## Status

Akzeptiert · zuletzt geändert 2026-10-14

## Kontext

Die REST-API ist durch eine OpenAPI-Spezifikation definiert. Anfangs waren Backend-DTOs
handgeschriebene Records in `io.opaa.api.dto`; mit wachsender API wurde das Synchronhalten mit der
Spezifikation fehleranfällig, und ein Mischzustand aus generierten und handgeschriebenen DTOs ließ
offen, was die Quelle der Wahrheit ist. Das Frontend generiert seine Typen bereits aus derselben
Spezifikation.

## Entscheidung

### 1. Die Spezifikation ist die einzige Quelle der Wahrheit

Jede Änderung am API-Vertrag beginnt in der Spezifikation. Alle API-DTOs werden generiert; in
`io.opaa.api.dto` gibt es keine handgeschriebenen Klassen.

### 2. Backend-DTOs werden generiert

Das OpenAPI-Generator-Gradle-Plugin (`spring`-Generator, `models` auf `""`) erzeugt die DTOs nach
`opaa-api/build/generated/openapi/`; generierter Code wird nicht committet.

### 3. Frontend-Typen werden generiert

`openapi-typescript` erzeugt `frontend/src/shared/api/generated/api.ts`
(`pnpm run generate:api-types`, Teil von Build und Test); nicht committet (`.gitignore`).

### 4. Domain-Enums werden gemappt

Von DTOs referenzierte Domain-Enums liegen in `io.opaa.api.types` und werden über
`typeMappings`/`importMappings` in `opaa-api/build.gradle.kts` eingesetzt; eine
Konvertierungsschicht entfällt. `SpecEnumParityTest` prüft die Liste gegen die Spezifikation, ein
`doLast`-Block entfernt die trotz Mapping erzeugten Enum-Dateien und leitet die Kandidaten aus
`typeMappings` ab.

### 5. Reihenfolge für neue Endpunkte

Erst Schemas in der Spezifikation, dann generierte DTOs in Controllern und Mappern. Domain-Services
kennen keine DTO-Typen; die Abbildung lebt in handgeschriebenen `*ResponseMapper` im `web`-Paket des
Moduls (`backend/AGENTS.md`, „Web-Schicht je Modul“).

### 6. Eigenes Gradle-Modul `opaa-api`

Spezifikation, Generator und die geteilten Enums liegen im Modul `opaa-api`. Eine
Spec-Änderung invalidiert nur dieses Modul; das Backend konsumiert die DTOs über
`implementation(project(":opaa-api"))` und hat keinen eigenen Generator-Task.

### 7. Spezifikation als Datei je Thema, zur Build-Zeit gebündelt

Die Spezifikation liegt als Fragmente unter `opaa-api/src/main/openapi/` (ein Thema je
Datei, `root.yaml` für `openapi` und `info`, `common.yaml` für Bausteine ohne fachlichen Besitzer).
Die Themen folgen den logischen Modulen aus `backend/AGENTS.md`, wo nötig feiner; ein Tag je Thema,
deklariert in seiner Datei. Ein Fragment enthält nur `tags`, `paths` und `components`; alle `$ref`
zeigen in das Bündel (`#/components/...`), es gibt keine Indexdatei. `:opaa-api:bundleOpenApi`
(`io.opaa.api.bundler.OpenApiBundler`) erzeugt `build/openapi-bundle/openapi/opaa-api.yaml`,
Eingabe des Generators und im Klassenpfad `/openapi/opaa-api.yaml`; es bricht ab bei doppelten
Schlüsseln, nicht lokalen oder nicht auflösbaren `$ref`, YAML-Ankern und Operationen ohne genau ein
Tag der eigenen Datei. Das Frontend bündelt dieselben Fragmente mit
`frontend/scripts/bundle-openapi.mjs` nach denselben Regeln, weil Frontend-CI und -Image keine JVM
haben.

## Konsequenzen

Einfacher: kein Drift zwischen DTOs und Spezifikation; kein Boilerplate; ein Workflow für Backend
und Frontend; eine Spec-Änderung berührt in der Regel nur die Datei ihres Themas.

Schwieriger: generierte DTOs sind veränderliche POJOs (`getName()` statt `name()`); der Build braucht
eine gültige Spezifikation; neue Domain-Enums müssen in `typeMappings`/`importMappings` eingetragen
werden; zwei Bundler mit denselben Regeln, die inhaltlichen Prüfungen laufen nur im Java-Bundler,
der CI-Job `openapi-bundle-parity` vergleicht beide Bündel bei jeder Änderung an Fragmenten oder
Bundlern als Wertebäume (YAML 1.1) und fließt in den Pflicht-Check `backend` ein.

Restrisiken: Innerhalb eines Fragments zeigt ein Editor Verweise auf Schemas anderer Dateien als
nicht auflösbar an; maßgeblich ist das Bündel.

## Verworfene Alternativen

- Handgeschriebene DTOs neben generierten — Mischzustand ohne klare Quelle der Wahrheit.
- Redocly CLI `bundle` — braucht Node im Backend-Build und eine Wurzeldatei, die jeden Pfad aufzählt.
- `swagger-parser` oder Multi-File-Modus des Generators — Tests und Frontend brauchen trotzdem eine
  Datei, und die Normalisierung macht die Gleichheit mit der alten Spec unbelegbar.
- Frontend bündelt über Gradle — Frontend-Image und -CI bräuchten ein JDK.

## Offen

Nichts.
