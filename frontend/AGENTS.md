# Frontend: Themen und Grenzen

Ergänzt die Regeln in der [AGENTS.md im Wurzelverzeichnis](../AGENTS.md). Das Frontend wird nach fachlichen Themen geschnitten (Epic #2057). Die Grenzen zwischen den Themen prüft ESLint schon jetzt, auch für Dateien, die noch am alten Ort liegen.

## Zielbild

```
src/
  app/                 Routenbaum, AppShell, Navigation, Bereichsseiten aus mehreren Themen, MSW-Sammelstelle
  shared/              übergreifende Bausteine: UI, Sitzung, API-Client, Theme, Hilfsfunktionen
  features/<thema>/    pages/ components/ stores/ api/ hooks/ utils/ mocks/
    index.ts           öffentliche Schnittstelle des Themas, ohne Seiten
    routes.ts          Routen des Themas (nur für app)
    AGENTS.md, CLAUDE.md
  test/                Test-Setup und Render-Helfer
```

Der Name eines Themas ist der Tag seiner Operationen in der OpenAPI-Spezifikation (kebab-case), zusammengehörige Tags bilden ein Thema. Verwaltungsthemen liegen flach unter `features/`.

## Wo die Grenzen stehen

[`eslint/themes.js`](eslint/themes.js) ist die einzige Quelle:

- `THEMES` ordnet jedem Thema seine Dateien zu: den Zielort `src/features/<thema>/` und Globs für den alten Ort. `SHARED`, `APP` und `TEST_INFRA` tun dasselbe für `src/shared/`, `src/app/` und `src/test/`.
- `ALLOWED` ist der erlaubte Abhängigkeitsgraph zwischen den Themen, ohne Zyklen.

[`eslint/boundaries.js`](eslint/boundaries.js) erzeugt daraus die Regel `boundaries/dependencies` von [eslint-plugin-boundaries](https://www.jsboundaries.dev/). `eslint/themes.test.mjs` prüft, dass jede Datei unter `src/` genau einem Thema oder Bereich zugeordnet ist und der Graph zyklenfrei bleibt; `eslint/boundaries.test.mjs` prüft die Regel an einem Beispielprojekt.

## Regeln

- Ein Thema hängt nur von sich selbst, von `shared` und von den Themen in `ALLOWED` ab. `shared` hängt von nichts ab. `app` und `src/test/` dürfen alles.
- Ein Thema am Zielort ist von außen nur über seine `index.ts` erreichbar, `app` zusätzlich über `routes.ts`. `index.ts` exportiert keine Seiten.
- Imports über Themengrenzen schreiben den Alias `@/` (`import { SubjectPicker } from '@/features/principal'`), Imports innerhalb eines Themas sind relativ.
- Tests und Mock-Handler dürfen die Fixtures und Handler aller Themen nutzen (`mocks/`), Produktivcode nie.
- Ein Test darf mit `vi.mock()` ein einzelnes Modul hinter der `index.ts` eines erlaubten Themas ersetzen; wer über die `index.ts` importiert, sieht den Mock ebenfalls.
- `vi.mock()`, `vi.doMock()` und `vi.importActual()` zählen wie Imports.
- Eine neue Datei entsteht dort, wo `themes.js` sie einem Thema zuordnet; für ein bereits umgezogenes Thema am Zielort. Eine Datei, die bei einer Entflechtung das Thema wechselt, zieht an den Zielort ihres neuen Themas, statt einen Glob in `themes.js` umzuhängen. Ohne Zuordnung schlägt `eslint/themes.test.mjs` fehl.
- Eine neue Kante im Graphen ist eine Architekturentscheidung: erst abstimmen, dann `ALLOWED` ändern. Die Kanten folgen `ALLOWED_MODULE_EDGES` im Backend, Abweichungen stehen im Planungskommentar des Epics #2057.

## Übergangsliste

Die Verstöße, die es beim Einführen der Regel schon gab, stehen in `eslint-suppressions.json` ([Bulk Suppressions](https://eslint.org/docs/latest/use/suppressions) von ESLint), je Datei mit ihrer Anzahl.

- Die Liste darf nur schrumpfen. Der CI-Schritt „Übergangsliste der Themengrenzen prüfen“ im Job `frontend` vergleicht die Summe mit `main` und scheitert, wenn sie wächst oder eine andere Regel als `boundaries/dependencies` enthält (`node scripts/check-boundary-suppressions.mjs`).
- `pnpm run lint` scheitert bei jedem Verstoß über die Liste hinaus und bei Einträgen, die nicht mehr auftreten. Wer einen Verstoß behebt, verkleinert die Liste mit `pnpm exec eslint . --prune-suppressions`.
- Nie `--suppress-rule` oder `--suppress-all` für neue Verstöße verwenden: die CI lehnt die gewachsene Liste ab.
- Ziel ist eine leere Liste; dann entfällt die Datei samt Prüfschritt.

## Ablauf eines Umzugs-PRs

Ein Umzugs-PR verschiebt die Dateien eines Themas an den Zielort und ändert sonst nur Modulpfade. Entflechtungen, die Code ändern, kommen in eigene PRs mit Test für die neue Naht.

1. Liste der Umzüge schreiben, je Zeile `<alt> <neu>` relativ zu `frontend/`; Ordner ziehen mit allem darunter um, Tests wandern mit ihrer Datei:
   ```
   src/components/notifications src/features/notification/components
   src/services/notificationApi.ts src/features/notification/api/notificationApi.ts
   ```
2. Vor dem Umzug die Testliste festhalten (aus `frontend/`; braucht die generierten API-Typen, `pnpm run generate:api-types`):
   ```bash
   pnpm exec vitest list 2>/dev/null | sed -E 's#^[^ >]*/##' | sort > /tmp/tests-vorher.txt
   ```
3. Trockenlauf, dann Umzug:
   ```bash
   node scripts/move-files.mjs --dry-run umzug.txt
   node scripts/move-files.mjs umzug.txt
   ```
   Das Skript verschiebt per `git mv`, schreibt relative und `@/`-Pfade in Imports, Exporten, `import()`, `vi.mock()`, `vi.doMock()` und `vi.importActual()` aller Dateien unter `src/` um, die verschobenen eingeschlossen, und benennt die Schlüssel in `eslint-suppressions.json` um. Ein Pfad behält seine Form (relativ bleibt relativ, `@/` bleibt `@/`, eine weggelassene Endung oder `index` bleibt weggelassen). Meldet es `check glob`, findet ein `import.meta.glob()` danach andere Dateien als vorher; das Muster von Hand nachziehen.
4. `index.ts` anlegen und alles exportieren, was andere Themen brauchen. `pnpm run lint` zeigt jede Stelle, die noch in das Thema hineingreift; dort auf `@/features/<thema>` umstellen. `themes.js` bleibt unverändert, die alten Globs treffen einfach nichts mehr.
5. `AGENTS.md` und `CLAUDE.md` (Inhalt `@AGENTS.md`) des Themas anlegen.
6. Nachweis, alles aus `frontend/`:
   - `pnpm run format:check`, `pnpm run lint`, `pnpm run test`, `pnpm run build` grün;
   - Testliste unverändert:
     ```bash
     pnpm exec vitest list 2>/dev/null | sed -E 's#^[^ >]*/##' | sort > /tmp/tests-nachher.txt
     diff /tmp/tests-vorher.txt /tmp/tests-nachher.txt
     ```
   - Diff-Filter leer: `scripts/move-diff-filter.sh` gibt jede geänderte Zeile aus, die kein Modulpfad ist (Import- und Exportzeilen, `vi.mock()`/`vi.importActual()`, Pfad auf eigener Zeile, Schlüssel der Übergangsliste);
   - E2E-Suite auf dem Branch: `gh workflow run e2e.yml --ref <branch>`.

   Alle drei Ergebnisse gehören in die PR-Beschreibung.
