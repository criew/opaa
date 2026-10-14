# Frontend: Themen und Grenzen

Ergänzt die Regeln in der [AGENTS.md im Wurzelverzeichnis](../AGENTS.md). Das Frontend ist nach fachlichen Themen geschnitten (Epic #2057); die Grenzen zwischen ihnen prüft ESLint.

## Aufbau

```
src/
  app/                 Routenbaum (App.tsx), Einstieg (main.tsx), ErrorBoundary, ProtectedRoute
    shell/             AppShell, Sidebar, GlobalRail, MobileHeader, Info-Dialog, Rahmen der globalen Bereiche
    areas/             Bereichsseiten aus mehreren Themen
    mocks/             MSW-Sammelstelle (handlers.ts) und Browser-Worker (browser.ts)
  shared/              übergreifende Bausteine: UI, Sitzung, API-Client, Theme, Hilfsfunktionen
    mocks/             Fixtures und Handler für Sitzung und Health
  features/<thema>/    pages/ components/ stores/ api/ hooks/ utils/ mocks/
    index.ts           öffentliche Schnittstelle des Themas, ohne Seiten
    routes.ts          Routen des Themas (nur für app)
    AGENTS.md, CLAUDE.md
  test/                Test-Setup, MSW-Server der Tests (server.ts) und Render-Helfer
```

Der Name eines Themas ist der Tag seiner Operationen in der OpenAPI-Spezifikation (kebab-case), zusammengehörige Tags bilden ein Thema. Verwaltungsthemen liegen flach unter `features/`. Andere Ordner gibt es unter `src/` nicht.

## Wo die Grenzen stehen

[`eslint/themes.js`](eslint/themes.js) ist die einzige Quelle:

- `THEMES` nennt die Themen; die Dateien eines Themas liegen unter `src/features/<thema>/`. `SHARED`, `APP` und `TEST_INFRA` stehen für `src/shared/`, `src/app/` und `src/test/`, `MOCK_GLOBS` für die Fixtures und Handler.
- `ALLOWED` ist der erlaubte Abhängigkeitsgraph zwischen den Themen, ohne Zyklen.

[`eslint/boundaries.js`](eslint/boundaries.js) erzeugt daraus die Regel `boundaries/dependencies` von [eslint-plugin-boundaries](https://www.jsboundaries.dev/). Dass jede Datei unter `src/` genau einem Thema oder Bereich zugeordnet ist, prüfen `eslint/themes.test.mjs` (dazu: Graph zyklenfrei) und der CI-Schritt „Themengrenzen prüfen“; `eslint/boundaries.test.mjs` prüft die Regel an einem Beispielprojekt.

## Regeln

- Ein Thema hängt nur von sich selbst, von `shared` und von den Themen in `ALLOWED` ab. `shared` hängt von nichts ab. `app` und `src/test/` dürfen alles.
- Ein Thema ist von außen nur über seine `index.ts` erreichbar, `app` zusätzlich über `routes.ts`. `index.ts` exportiert keine Seiten.
- Imports über Themengrenzen schreiben den Alias `@/` (`import { SubjectPicker } from '@/features/principal'`), Imports innerhalb eines Themas sind relativ.
- Tests und Mock-Handler dürfen die Fixtures und Handler aller Themen und von `shared` nutzen (`mocks/`), Produktivcode nie, auch nicht die des eigenen Themas; nur `app` bindet die MSW-Sammelstelle ein.
- Ein Test darf mit `vi.mock()` ein einzelnes Modul hinter der `index.ts` eines erlaubten Themas ersetzen; wer über die `index.ts` importiert, sieht den Mock ebenfalls.
- `vi.mock()`, `vi.doMock()` und `vi.importActual()` zählen wie Imports.
- Eine neue Datei entsteht im Ordner ihres Themas oder Bereichs. Eine Datei, die bei einer Entflechtung das Thema wechselt, zieht in den Ordner ihres neuen Themas. Liegt eine Datei außerhalb dieser Ordner, scheitern `eslint/themes.test.mjs` und der CI-Schritt „Themengrenzen prüfen“.
- Eine neue Kante im Graphen ist eine Architekturentscheidung: erst abstimmen, dann `ALLOWED` ändern. Die Kanten folgen `ALLOWED_MODULE_EDGES` im Backend, Abweichungen stehen im Planungskommentar des Epics #2057.

## Keine Ausnahmen

Für die Grenzregel gibt es keine Ausnahmen, weder als Liste noch per Kommentar. Der CI-Schritt „Themengrenzen prüfen“ im Job `frontend` (`node scripts/check-theme-boundaries.mjs`) scheitert,

- wenn `eslint-suppressions.json` existiert: ESLint wendet [Bulk Suppressions](https://eslint.org/docs/latest/use/suppressions) aus dieser Datei stillschweigend an, deshalb nie `--suppress-rule` oder `--suppress-all` verwenden;
- an `eslint-disable`-Kommentaren, die `boundaries/…` nennen oder ohne Regelliste alles abschalten, und an Inline-Konfiguration für `boundaries/…`;
- an einer Datei unter `src/`, die keinem oder mehreren Themen zugeordnet ist.

Ein Verstoß wird behoben, nicht unterdrückt: über die `index.ts` des Themas, durch eine Naht in `app` oder nach Abstimmung über eine neue Kante in `ALLOWED`.

## Ablauf eines Umzugs-PRs

Ein Umzugs-PR verschiebt Dateien in den Ordner eines anderen Themas oder Bereichs, etwa ein neues Thema, das aus einem bestehenden herausgelöst wird, und ändert sonst nur Modulpfade. Entflechtungen, die Code ändern, kommen in eigene PRs mit Test für die neue Naht.

1. Liste der Umzüge schreiben, je Zeile `<alt> <neu>` relativ zu `frontend/`; Ordner ziehen mit allem darunter um, Tests wandern mit ihrer Datei:
   ```
   src/features/<alt>/components/<ordner> src/features/<neu>/components/<ordner>
   src/features/<alt>/api/<neu>Api.ts src/features/<neu>/api/<neu>Api.ts
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
   Das Skript verschiebt per `git mv`, schreibt relative und `@/`-Pfade in Imports, Exporten, `import()`, `vi.mock()`, `vi.doMock()` und `vi.importActual()` aller Dateien unter `src/` um, die verschobenen eingeschlossen. Ein Pfad behält seine Form (relativ bleibt relativ, `@/` bleibt `@/`, eine weggelassene Endung oder `index` bleibt weggelassen). Meldet es `check glob`, findet ein `import.meta.glob()` danach andere Dateien als vorher; meldet es `check reference`, nennt eine Datei außerhalb von `src/` (`index.html`, `vite.config.ts`, `scripts/bundle-report.mjs`) einen verschobenen Pfad. Beides von Hand nachziehen.
   Die Quelltextprüfungen brauchen dafür keine Themenliste: `PageSection.test.tsx` und `AreaPageHeader.test.tsx` lesen `features/*/components/**` bzw. `features/*/pages/*`, `secondaryText.test.tsx`, `ConfirmHost.test.tsx` und `sessionStores.test.ts` lesen `**`. Ein `check glob` auf `PageSection` oder `AreaPageHeader` ist erwartet, wenn der Umzug Dateien unter deren Mustern bewegt. Ob danach dieselben Dateien geprüft werden, zeigt `vitest list` nicht, weil `it.each` dort nur die Vorlage nennt; dafür die Fälle beider Tests vorher und nachher mit `pnpm exec vitest run <test> --reporter=verbose` sammeln und vergleichen. Komponenten eines neuen Themas fallen damit unter die `PageSection`-Regel.
4. `index.ts` anlegen und alles exportieren, was andere Themen brauchen. `pnpm run lint` zeigt jede Stelle, die noch in das Thema hineingreift; dort auf `@/features/<thema>` umstellen. Hat das Thema Seiten, zusätzlich `routes.ts` mit `export { default as X } from './pages/X'` je Seite anlegen und die Seitenimporte in `src/app/App.tsx` auf `import { X } from '@/features/<thema>/routes'` umstellen. Ein neues Thema bekommt in `themes.js` einen Eintrag in `THEMES` und seine Kanten in `ALLOWED` (abstimmen, siehe Regeln).
5. `AGENTS.md` und `CLAUDE.md` (Inhalt `@AGENTS.md`) des Themas anlegen.
6. Nachweis, alles aus `frontend/`:
   - `pnpm run format:check`, `pnpm run lint`, `pnpm run test`, `pnpm run build` grün;
   - Testliste unverändert:
     ```bash
     pnpm exec vitest list 2>/dev/null | sed -E 's#^[^ >]*/##' | sort > /tmp/tests-nachher.txt
     diff /tmp/tests-vorher.txt /tmp/tests-nachher.txt
     ```
   - Diff-Prüfung ohne Befund: `node scripts/move-diff-check.mjs` vergleicht jede geänderte Datei unter `src/` mit ihrem Gegenstück (Rename-Paar oder gleicher normalisierter Inhalt). Relative und `@/`-Pfade in Imports, Exporten, `import()`, `vi.mock()`, `vi.doMock()` und `vi.importActual()` werden aufgelöst und über die Umbenennungen auf das Modul vor dem Umzug abgebildet; ein Import über die `index.ts` oder `routes.ts` des Themas zählt als Import des Moduls und Exports, die sie re-exportiert, auch wenn die Datei schon vor dem Umzug bestand. Bindungen (`a as b`, Default, Namespace) und Paketpfade werden wörtlich verglichen. Beide Seiten werden mit den aufgelösten Pfaden als Quelltext zurückgeschrieben und mit Prettier und der Projektkonfiguration formatiert, bevor sie zeilenweise verglichen werden; ein Umbruch, den Prettier nur wegen der geänderten Pfadlänge anders setzt, zählt damit nicht, JSX-Text, Regex und Operatoren bleiben exakt. Befund ist jede andere Abweichung, eine zusätzliche oder fehlende Importanweisung, jede Änderung außerhalb von `frontend/src/` (Workflows, `e2e/`, `package.json`, Konfiguration, `eslint/`, `scripts/` – nötige Anpassungen dort im PR begründen), jede neue Datei außer der `index.ts` mit reinen Re-Exports, der `routes.ts` mit reinen Re-Exports der Default-Exporte eigener Seiten und `AGENTS.md`/`CLAUDE.md` des Themas, eine `index.ts`, die eine Seite exportiert, eine `routes.ts`, die etwas anderes als eine Seite des eigenen Themas exportiert, eine schon vorhandene `index.ts` oder `routes.ts`, die mehr als hinzugekommene Re-Exports enthält oder einen bestehenden Re-Export entfernt oder auf ein anderes Modul oder einen anderen Export lenkt (neben einem bestehenden `export *` auch jeder neue Name);
   - E2E-Suite auf dem Branch: `gh workflow run e2e.yml --ref <branch>`.

   Alle drei Ergebnisse gehören in die PR-Beschreibung.
