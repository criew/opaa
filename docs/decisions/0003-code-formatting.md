# ADR-0003: Code-Formatierung

## Status

Akzeptiert

## Kontext

Das Projekt benötigt einen konsistenten Code-Formatierungsstandard. Ohne automatisierte Durchsetzung schleichen sich Formatierungsinkonsistenzen (Tabs vs. Leerzeichen, Import-Reihenfolge, Zeilenlänge) in Beiträgen sowohl von menschlichen Entwicklern als auch von KI-Agenten ein.

Wesentliche Überlegungen:

- Das Projekt verwendet Java (Backend) und wird später TypeScript (Frontend) hinzufügen
- Sowohl menschliche als auch KI-Beitragende arbeiten an der Codebasis
- Formatierung sollte automatisch durchgesetzt werden, nicht durch manuelles Review

## Entscheidung

### Standard: Google Java Format über Spotless

- **Spotless** (Gradle-Plugin) setzt Formatierung als Teil des Builds durch.
- **Google Java Format** ist der Formatierer für Java-Quelldateien. Er verwendet 2-Leerzeichen-Einrückung, was der Google-Java-Style-Standard ist.
- **Gradle-Kotlin-DSL**-Dateien (`.gradle.kts`) verwenden 4-Leerzeichen-Einrückung, durchgesetzt durch Spotless.
- **Leerzeichen statt Tabs** — alle Quelldateien verwenden Leerzeichen für Einrückung, niemals Tabs.

### Durchsetzung

- `./gradlew spotlessCheck` verifiziert Formatierung (kann in CI verwendet werden).
- `./gradlew spotlessApply` formatiert alle Dateien automatisch.
- Spotless läuft als Teil des Standard-Gradle-Build-Lebenszyklus.

### Umfang

- Java: Google Java Format (2 Leerzeichen, sortierte Imports, keine ungenutzten Imports)
- Kotlin DSL: 4 Leerzeichen, getrimmt, kein Trailing Whitespace
- Frontend (TypeScript/TSX): Prettier (keine Semikolons, einfache Anführungszeichen, Trailing Commas, 100-Zeichen-Zeilenbreite). Durchgesetzt über `npm run format:check` / `npm run format`.

## Konsequenzen

### Was einfacher wird

- **Konsistenter Code-Stil** über alle Beiträge ohne manuellen Review-Aufwand.
- **Keine Formatierungsdebatten** — das Tool entscheidet, Beitragende befolgen es.
- **KI-Agenten** produzieren konsistent formatierten Code durch Ausführen von `spotlessApply` nach Änderungen.

### Was schwieriger wird

- **Google Java Format ist meinungsstark** — seine 2-Leerzeichen-Einrückung und Zeilenumbruch-Stil kann Entwicklern ungewohnt erscheinen, die an 4-Leerzeichen-Java-Konventionen gewöhnt sind. Dies ist beabsichtigt: ein strenger, nicht konfigurierbarer Formatierer eliminiert Bikeshedding.
- **Initiale Reibung** — bestehender Code muss bei der Einführung neu formatiert werden (einmalige Kosten, bereits erledigt).

## Nachträge

### 22.08.2026 — Frontend-Aufrufe laufen über pnpm

Mit der Migration des Frontend-Builds von npm auf pnpm (#653, PR #752) lauten die im Abschnitt
„Umfang" genannten Befehle jetzt `pnpm run format:check` / `pnpm run format`. An der Entscheidung
selbst (Prettier mit den genannten Einstellungen) ändert das nichts.
