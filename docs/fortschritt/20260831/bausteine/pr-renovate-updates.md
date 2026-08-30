# Renovate-Abhängigkeits-Updates (Sammelbaustein, 43 PRs)

- Gemergt: 2026-08-27 bis 2026-08-29
- Bezug: #751 (Renovate-Einführung), #951 (Auto-Merge)
- PRs: #916, #917, #918, #919, #920, #947, #948, #949, #950, #953, #963, #964, #965, #967,
  #969, #970, #971, #972, #973, #974, #975, #976, #977, #978, #979, #980, #981, #982, #983,
  #984, #985, #986, #987, #988, #989, #990, #991, #993, #994, #1009, #1010, #1011, #1021

**Geliefert:** Erste große Update-Welle des selbst betriebenen Renovate (#751) nach Aktivierung
des Auto-Merge (#951): Dependency-Pins, Gradle 9.7.1, Node 22.23.2, pnpm 11.24, Spring-Plattform,
JUnit, MUI, Vite 8.2.2, Vitest, ESLint 10.9, Keycloak 26.7, Ollama u. v. m. — mechanische
Versionshebungen ohne eigenständigen Feature-Gehalt, daher hier gesammelt statt je PR ein
Baustein.

Die Welle deckte Schwächen des Auto-Merge-Betriebs auf, die als eigene Issues behoben wurden:
semantischer Lockfile-Bruch (#996/#1000), ungeprüftes Temurin-Major (#1001/#1002),
`minimumReleaseAge`-Konflikt (#954), inkompatible Majors Tika 4 und TypeScript 7 (#1005/#1007).
Ein Update wurde zurückgerollt: `eclipse-temurin` v25 (#988) per #1003.

**Verifikation:** Versionsstände in `backend/gradle/libs.versions.toml`,
`frontend/package.json` und den Workflow-Dateien entsprechen den Updates; `renovate.json5`
trägt die nachgeschärften Regeln.

**Themen:** Abhängigkeitsverwaltung, Renovate, Auto-Merge
