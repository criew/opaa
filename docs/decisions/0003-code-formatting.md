# ADR-0003: Code-Formatierung

## Status

Akzeptiert · zuletzt geändert 2026-08-22

## Kontext

Ohne automatisierte Durchsetzung schleichen sich Formatierungsinkonsistenzen (Tabs, Import-Reihenfolge,
Zeilenlänge) in Beiträge von Menschen und KI-Agenten ein. Formatierung soll das Werkzeug entscheiden,
nicht das Review.

## Entscheidung

### 1. Java: Google Java Format über Spotless

Spotless (Gradle-Plugin, `backend/build.gradle.kts` und `opaa-api/build.gradle.kts`) formatiert alle
Java-Quellen mit Google Java Format: 2 Leerzeichen, sortierte Imports, keine ungenutzten Imports, kein
Trailing Whitespace, Zeilenumbruch am Dateiende. Ein strenger, nicht konfigurierbarer Formatierer
beendet jede Stildebatte.

### 2. Gradle-Kotlin-DSL: 4 Leerzeichen

`*.gradle.kts` nutzt 4 Leerzeichen, getrimmt, mit Zeilenumbruch am Ende; ebenfalls über Spotless.
Leerzeichen statt Tabs gelten für alle Quelldateien (`.editorconfig`).

### 3. Durchsetzung im Build

`./gradlew spotlessCheck` prüft, `./gradlew spotlessApply` formatiert. `spotlessCheck` ist Teil des
Standard-Builds und läuft in der CI.

### 4. Frontend: Prettier

TypeScript/TSX wird mit Prettier formatiert (`frontend/.prettierrc`: keine Semikolons, einfache
Anführungszeichen, Trailing Commas, 100 Zeichen Zeilenbreite). Durchgesetzt über
`pnpm run format:check` in der CI, formatiert mit `pnpm run format`.

## Konsequenzen

Einfacher: konsistenter Stil ohne Review-Aufwand; Agenten liefern formatierten Code, indem sie
`spotlessApply` beziehungsweise `pnpm run format` nach Änderungen ausführen.

Schwieriger: Google Java Format ist meinungsstark; wer 4-Leerzeichen-Java gewohnt ist, muss sich
umstellen.

## Verworfene Alternativen

- Checkstyle oder Konfiguration des Formatierers — jede Einstellung ist eine neue Debatte.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-02-18 | — | Erstfassung | 1–3 |
| 2026-02-20 | — | Prettier für das Frontend | 4 |
| 2026-08-22 | #653 | Frontend-Aufrufe über pnpm statt npm | 4 |
| 2026-10-14 | #2697 | Neufassung nach ADR-Vorlage | — |
