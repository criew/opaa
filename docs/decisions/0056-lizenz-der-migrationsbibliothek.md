# ADR-0056: Lizenz der Migrationsbibliothek (Liquibase 5, FSL-1.1-ALv2)

## Status

Akzeptiert · zuletzt geändert 2026-10-24

## Kontext

OPAA steht unter der AGPL-3.0 und migriert sein Datenbankschema mit Liquibase (`liquibase-core`, über
`spring-boot-starter-liquibase`; Spring Boot 4.1.1 bringt 5.0.3 mit). Ab Version 5.0 steht `liquibase-core` unter
der [Functional Source License 1.1 mit Apache-2.0 als künftiger Lizenz](https://github.com/liquibase/liquibase/blob/master/LICENSE.txt)
(FSL-1.1-ALv2). Nach dem Lizenztext ist jede Nutzung erlaubt außer einer „Competing Use“: die Software anderen in
einem kommerziellen Produkt oder Dienst bereitzustellen, das sie ersetzt oder im Wesentlichen dieselbe Funktion
bietet. Am zweiten Jahrestag ihrer Veröffentlichung wird jede Version zusätzlich unter Apache-2.0 lizenziert.
Die FSL steht weder auf der [Liste der OSI](https://opensource.org/licenses) noch unter den freien Lizenzen der
[FSF](https://www.gnu.org/licenses/license-list.html). Die AGPL-3.0 verbietet in § 10, Empfängern weitere
Beschränkungen aufzuerlegen. Ob die Nutzungsbeschränkung der FSL in einer gemeinsam weitergegebenen
Installation eine solche Beschränkung ist, beantwortet dieser ADR nicht; er ist keine Rechtsberatung.

Die Lizenzprüfung (`docs/sbom.md`, Abschnitt „Lizenzprüfung“) gibt FSL-1.1-ALv2 nicht frei. Liquibase steht bis zu
dieser Entscheidung als Ausnahme in `.github/licenses/exceptions.txt`. Betroffen sind vor allem Behörden, die OPAA
weitergeben.

Geprüft wurde der Rückgriff auf Liquibase 4.33.0 (09.07.2025), die letzte Version unter Apache-2.0:

- Build und Kompilieren mit Spring Boot 4.1.1 gelingen. Jede Liquibase-Methode, die `spring-boot-liquibase` 4.1.1
  aufruft, gibt es in 4.33.0, auch `setAnalyticsEnabled` für `spring.liquibase.analytics-enabled`.
- Von 716 Tests der Pakete `io.opaa.migration`, `io.opaa.compliance` und `io.opaa.config` (dazu Changelog-, Sperr-
  und Enum-Wächter) bestehen 712. `VectorStoreColdStartTest` scheitert in 2 bis 4 von 5 Wiederholungen: Zwei
  Liquibase-Läufe in derselben JVM gegen eine leere Datenbank hinterlassen die Changelog-Sperre gesetzt, der zweite
  wartet bis zum Timeout. Unter 5.0.3 besteht derselbe Test 5 von 5. Die Ursache ist nicht geklärt. Der Kaltstart
  zweier echter Prozesse (`InstanceStartTest`) bestand unter 4.33.0 in fünf von fünf Läufen.
- Seit 4.33.0 gibt es keine 4.x-Version mehr. Für 4.33.0 ist kein CVE bekannt (GitHub Advisory Database: nur
  CVE-2022-0839, behoben in 4.8.0). 5.0.3 und 5.0.4 enthalten Sicherheitskorrekturen, die es für 4.x nicht gibt:
  Eingabeprüfung in `generate-changelog`, Schwärzen von Zugangsdaten in Fehlermeldungen und Logs, Abweisen von
  `DOCTYPE` in XML-Changelogs, XSS im Status-Servlet. OPAA nutzt davon nur den Log-Pfad.

## Entscheidung

### 1. Flyway als Ziel der Schemamigration

OPAA stellt die Schemamigration auf Flyway (Community-Ausgabe, Apache-2.0) um, das Spring Boot ebenso
autokonfiguriert. Die Umstellung ist ein eigenes Vorhaben mit Issue; Umfang siehe Konsequenzen. Danach entfällt
Liquibase aus dem Laufzeit-Klassenpfad und aus `exceptions.txt`.

Begründung: Flyway Community ist frei, OSI-anerkannt und wird gepflegt. Die Changesets bestehen fast nur aus
PostgreSQL-SQL in `sql:`-Blöcken und lassen sich deshalb weitgehend mechanisch übertragen.

### 2. Bis zur Umstellung bleibt Liquibase 5 als Ausnahme

Bis Flyway ausgeliefert ist, folgt Liquibase der Version aus der Spring-Boot-BOM und bleibt mit Begründung in
`.github/licenses/exceptions.txt`. Es gibt keinen Pin auf 4.33.0.

Begründung: 4.33.0 bekommt keine Korrekturen mehr, ein Regressionswächter scheitert mit dem Pin, und jedes
Spring-Boot-Update kann Liquibase-5-APIs voraussetzen. Die Ausnahme hält die Lizenzfrage sichtbar, ohne den
Betrieb an eine eingefrorene Bibliothek zu binden.

## Konsequenzen

- **Aufwand der Umstellung (Schätzung, L):** 96 Changelog-Dateien mit 103 Changesets in V-Skripte übertragen; dazu
  23 Verweise auf `${database.defaultSchemaName}` (Flyway-Platzhalter), zwei Preconditions mit `onFail: CONTINUE` in
  `knowledge/2026-09-27-baseline.yaml` (als `DO`-Block in SQL), 29 Dateien mit `splitStatements` und je eine mit
  `runOnChange`, `runInTransaction` und `validCheckSum`. Im Code: `DatabaseMigrationMode`,
  `DatabasePrerequisiteCheck`, `VectorStoreChangelogParameters` und rund 30 Testklassen mit Liquibase-Bezug,
  darunter die Wächter `ChangelogModuleBoundaryTest`, `RollingUpdateGuardTest` (`labels: contract` braucht eine
  Namensregel), `ChangesetLockTimeoutTest` und `ComplianceManifestTest`. Dazu kommen `backend/docs/liquibase.md`,
  die Liquibase-Regeln in `backend/AGENTS.md`, Handbuch (`betrieb/datenbank.md`, `konfiguration.md`) und Helm-Chart
  (`SPRING_LIQUIBASE_ENABLED`).
- **Bestehende Installationen:** Ihre Historie steht in `databasechangelog`. Die Umstellung braucht eine
  Versionsgrenze, an der Flyway den erreichten Stand als Baseline übernimmt; nach `backend/AGENTS.md` liegen
  Baselines nur an ausgewiesenen Versionsgrenzen ohne Rolling Update.
- **Restrisiko bis zur Umstellung:** Die Lizenzfrage bei der Weitergabe durch Behörden bleibt offen. Die
  Lizenzprüfung bleibt grün, weil die Ausnahme genau FSL-1.1-ALv2 nennt; wechselt die Lizenz erneut, schlägt sie an.

## Verworfene Alternativen

- Pin auf Liquibase 4.33.0 — keine Korrekturen mehr, `VectorStoreColdStartTest` scheitert, Bruch bei künftigen Spring-Boot-Updates.
- Ausnahme dauerhaft beibehalten — löst die Frage der Weitergabe nicht, nur ihre Sichtbarkeit.
- FSL-Umwandlung abwarten — 5.0.0 wird erst am 30.09.2027 Apache-2.0, danach liefe OPAA stets zwei Jahre hinter den Korrekturen.

## Offen

- Ob die Beschränkung der FSL bei der Weitergabe von OPAA durch Behörden zur AGPL-3.0 passt, klärt bei Bedarf eine
  rechtliche Prüfung, nicht dieses Projekt.
- Ursache des Sperrverhaltens von 4.33.0 in `VectorStoreColdStartTest`, falls ein Pin erneut erwogen wird.
