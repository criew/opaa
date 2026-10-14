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

[`eslint/boundaries.js`](eslint/boundaries.js) erzeugt daraus die Regel `boundaries/dependencies` von [eslint-plugin-boundaries](https://www.jsboundaries.dev/). Dass jede Datei unter `src/` genau einem Thema oder Bereich zugeordnet ist, prüfen `eslint/themes.test.mjs` (dazu: Graph zyklenfrei) und der CI-Schritt „Themengrenzen prüfen“; `eslint/boundaries.test.mjs` prüft die Regel an einem Beispielprojekt.

## Regeln

- Ein Thema hängt nur von sich selbst, von `shared` und von den Themen in `ALLOWED` ab. `shared` hängt von nichts ab. `app` und `src/test/` dürfen alles.
- Ein Thema am Zielort ist von außen nur über seine `index.ts` erreichbar, `app` zusätzlich über `routes.ts`. `index.ts` exportiert keine Seiten.
- Imports über Themengrenzen schreiben den Alias `@/` (`import { SubjectPicker } from '@/features/principal'`), Imports innerhalb eines Themas sind relativ.
- Tests und Mock-Handler dürfen die Fixtures und Handler aller Themen nutzen (`mocks/`), Produktivcode nie, auch nicht die des eigenen Themas; nur `app` bindet die MSW-Sammelstelle ein.
- Ein Test darf mit `vi.mock()` ein einzelnes Modul hinter der `index.ts` eines erlaubten Themas ersetzen; wer über die `index.ts` importiert, sieht den Mock ebenfalls.
- `vi.mock()`, `vi.doMock()` und `vi.importActual()` zählen wie Imports.
- Eine neue Datei entsteht dort, wo `themes.js` sie einem Thema zuordnet; für ein bereits umgezogenes Thema am Zielort. Eine Datei, die bei einer Entflechtung das Thema wechselt, zieht an den Zielort ihres neuen Themas, statt einen Glob in `themes.js` umzuhängen. Ohne Zuordnung schlagen `eslint/themes.test.mjs` und der CI-Schritt „Themengrenzen prüfen“ fehl.
- Eine neue Kante im Graphen ist eine Architekturentscheidung: erst abstimmen, dann `ALLOWED` ändern. Die Kanten folgen `ALLOWED_MODULE_EDGES` im Backend, Abweichungen stehen im Planungskommentar des Epics #2057.

## Übergangsliste

Die Verstöße, die es beim Einführen der Regel schon gab, stehen in `eslint-suppressions.json` ([Bulk Suppressions](https://eslint.org/docs/latest/use/suppressions) von ESLint), je Datei mit ihrer Anzahl.

- Die Liste darf nur schrumpfen. Der CI-Schritt „Themengrenzen prüfen“ im Job `frontend` (`node scripts/check-theme-boundaries.mjs`) vergleicht die Summe mit `main` und scheitert, wenn sie wächst oder eine andere Regel als `boundaries/dependencies` enthält.
- Die Grenzregel wird nie per Kommentar abgeschaltet: Derselbe Schritt scheitert an `eslint-disable`-Kommentaren, die `boundaries/…` nennen oder ohne Regelliste alles abschalten, und an Inline-Konfiguration für `boundaries/…`.
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
   Das Skript verschiebt per `git mv`, schreibt relative und `@/`-Pfade in Imports, Exporten, `import()`, `vi.mock()`, `vi.doMock()` und `vi.importActual()` aller Dateien unter `src/` um, die verschobenen eingeschlossen, und benennt die Schlüssel in `eslint-suppressions.json` um. Ein Pfad behält seine Form (relativ bleibt relativ, `@/` bleibt `@/`, eine weggelassene Endung oder `index` bleibt weggelassen). Meldet es `check glob`, findet ein `import.meta.glob()` danach andere Dateien als vorher; meldet es `check reference`, nennt eine Datei außerhalb von `src/` (`index.html`, `vite.config.ts`, `scripts/bundle-report.mjs`) einen verschobenen Pfad. Beides von Hand nachziehen.
   Die Quelltextprüfungen per `import.meta.glob()` (`PageSection.test.tsx`, `AreaPageHeader.test.tsx`, `secondaryText.test.tsx`, `ConfirmHost.test.tsx`, `sessionStores.test.ts`) lesen `features/*/…` ohne Themenliste und finden die verschobenen Dateien von selbst. `PageSection` prüft die Komponenten jedes Themas; ein Thema, dessen Komponenten keinen ruhenden Seiteninhalt tragen, steht dort in `THEMEN_OHNE_REGEL`. Ein `check glob` auf diese Tests ist daher erwartet: Die Testliste aus Schritt 6 bestätigt, dass dieselben Dateien geprüft werden.
4. `index.ts` anlegen und alles exportieren, was andere Themen brauchen. `pnpm run lint` zeigt jede Stelle, die noch in das Thema hineingreift; dort auf `@/features/<thema>` umstellen. Hat das Thema Seiten, zusätzlich `routes.ts` mit `export { default as X } from './pages/X'` je Seite anlegen und die Seitenimporte in `App.tsx` auf `import { X } from '@/features/<thema>/routes'` umstellen. `themes.js` bleibt unverändert, die alten Globs treffen einfach nichts mehr.
5. `AGENTS.md` und `CLAUDE.md` (Inhalt `@AGENTS.md`) des Themas anlegen.
6. Nachweis, alles aus `frontend/`:
   - `pnpm run format:check`, `pnpm run lint`, `pnpm run test`, `pnpm run build` grün;
   - Testliste unverändert:
     ```bash
     pnpm exec vitest list 2>/dev/null | sed -E 's#^[^ >]*/##' | sort > /tmp/tests-nachher.txt
     diff /tmp/tests-vorher.txt /tmp/tests-nachher.txt
     ```
   - Diff-Prüfung ohne Befund: `node scripts/move-diff-check.mjs` vergleicht jede geänderte Datei unter `src/` mit ihrem Gegenstück (Rename-Paar oder gleicher normalisierter Inhalt). Relative und `@/`-Pfade in Imports, Exporten, `import()`, `vi.mock()`, `vi.doMock()` und `vi.importActual()` werden aufgelöst und über die Umbenennungen auf das Modul vor dem Umzug abgebildet; ein Import über die neue `index.ts` oder `routes.ts` zählt als Import des Moduls und Exports, die sie re-exportiert. Bindungen (`a as b`, Default, Namespace) und Paketpfade werden wörtlich verglichen. Befund ist jede andere Abweichung, eine zusätzliche oder fehlende Importanweisung, jede Änderung außerhalb von `frontend/src/` (Workflows, `e2e/`, `package.json`, Konfiguration, `eslint/`, `scripts/` – nötige Anpassungen dort im PR begründen), jede neue Datei außer der `index.ts` mit reinen Re-Exports, der `routes.ts` mit reinen Re-Exports der Default-Exporte eigener Seiten und `AGENTS.md`/`CLAUDE.md` des Themas, eine `index.ts`, die eine Seite exportiert, eine `routes.ts`, die etwas anderes als eine Seite des eigenen Themas exportiert, und eine Übergangsliste, die sich über die Umbenennungen hinaus ändert;
   - E2E-Suite auf dem Branch: `gh workflow run e2e.yml --ref <branch>`.

   Alle drei Ergebnisse gehören in die PR-Beschreibung.
