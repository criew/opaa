# Fakten-Manifest nachpflegen

Das Fakten-Manifest unter `backend/src/main/resources/compliance/manifest/` beschreibt, welche Daten OPAA speichert,
welche Protokollereignisse es schreibt, was Rollen sehen und welche System-Prompts es sendet; aus ihm entstehen die
Behördenunterlagen ([ADR-0052](../../docs/decisions/0052-compliance-manifest.md),
[Spezifikation](../../docs/features/behoerdenunterlagen.md#das-faktenmodell)). Je Bereich gibt es eine Datei; Schema
und Pflichtfelder stehen in `io.opaa.compliance.ComplianceManifest`.

## Was der Wächter verlangt

`ComplianceManifestTest` scheitert, sobald Code und Manifest auseinanderlaufen, in beiden Richtungen. Er läuft ohne
Docker in jedem Testlauf (Shard `core`). Die Fehlermeldung nennt die Lücke, die Datei und das einzufügende YAML; der
Schnipsel lässt sich übernehmen, nur Platzhalter in `<…>` sind auszufüllen.

| Änderung im Code | Eintrag im Manifest |
|---|---|
| neuer Wert von `AuditEventType` | `auditEvents` in `audit-log.yaml`: `id` und `trigger`, der Rest kommt aus `defaults` |
| neuer Wert von `SystemRole` oder `Capability` | `roles` bzw. `capabilities`: `id` und `seesAboutOthers` |
| geänderter Prompt-Text (auch Log- oder Fehlertext derselben Klasse) | neue `sha256` beim Prompt in `prompts.yaml` |
| neue Tabelle | unter `tables` eines Datums oder, ganz ohne Personenbezug, unter `nonPersonalTables` |
| neue Spalte | in `personalColumns` oder `nonPersonalColumns` ihrer Tabelle bzw. in die Spaltenliste unter `nonPersonalTables` |
| neuer ausgehender Client (`ModularArchitectureTest` verlangt `@DataFlow("<kennung>")` an der Klasse) | `dataFlows` in `outbound.yaml`: `id`, `group` (Gruppe im Übersichtsbild des Faktenblatts), `target`, `data`, `trigger`, `switchOff`, `settings`, `viaOutboundProxy` (ob die Verbindung über den Ausgangsproxy geht; gegen den Code prüfen: über `OutboundProxySelector` laufen nur `http`/`https`, nie `socket://` wie SMB und SMTP, das AWS SDK nur, wo es `proxyFor` bekommt); beide Felder je Eintrag, nie unter `defaults`; eine bestehende Kennung braucht keinen neuen Eintrag |
| entfernte oder umbenannte Tabelle, Spalte, Konstante | Eintrag entfernen oder umbenennen |
| neuer `opaa.*`-Schlüssel | Beschreibung und Markierung im Code, bei Datenwirkung ein Eintrag unter `settings` (siehe unten) |
| geänderter Standardwert eines datenwirksamen Schlüssels in `application.yml` | neuer `defaultValue` seines Eintrags |
| geänderte Voreinstellung in `deploy/helm/opaa/values.yaml` für einen solchen Schlüssel | neuer `helm.defaultValue` |

Die Prüfung der Konfiguration führt `ConfigurationSettingsTest`, ebenfalls ohne Docker im Shard `core`.

## Tabellen und Spalten

Jede Spalte jeder Tabelle steht genau einmal im Manifest. Eine Tabelle mit Personenbezug gehört zu dem Datum, dessen
Daten sie hält:

```yaml
data:
  - id: accounts
    tables:
      - name: users
        personalColumns: [subject, email, display_name, last_login_at]
        nonPersonalColumns: [id, created_at, organization_id]
```

**`personalColumns`**, wenn die Spalte eine Person bezeichnet oder festhält, was eine Person wann getan hat:
Anmeldekennungen und Pseudonyme, Fremdschlüssel auf Personen, Namen, Adressen, Freitext und Zeitpunkte von
Handlungen. Alles andere kommt nach **`nonPersonalColumns`**. Im Zweifel `personalColumns`; die Einordnung prüfen
Review und Drift-Routine.

Eine Tabelle ganz ohne Personenbezug nennt ihre Spalten unter `nonPersonalTables`:

```yaml
nonPersonalTables:
  audit_retention_settings: [id, retention_months, last_cutoff, last_run_month, updated_at]
```

Bekommt eine solche Tabelle eine Spalte mit Personenbezug, wandert sie unter die `tables` eines Datums. Eine Tabelle,
die nicht zu einem bestehenden Datum passt, bekommt ein neues Datum mit allen Pflichtfeldern des Records `DataEntry`.

## Zugriffsweg

`accessPaths` nennt jeden Weg, auf dem das Datum lesbar ist: `UI` (Oberfläche), `API`, `EXPORT` und den direkten
Zugriff auf den Speicher, in dem es liegt. Liegt es in der Anwendungsdatenbank, ist das `DATABASE`; liegt es in einer
Ablage des Betriebs außerhalb davon (Dateisystem, Objektspeicher, Container-Log), ist es `STORAGE`. Ein Datum ohne
Tabelle (`tables: []`) trägt nie `DATABASE`, das prüft `ComplianceManifestTest`.

## Konfigurationsschlüssel

Jeder `opaa.*`-Schlüssel steht mit Beschreibung in den Konfigurationsmetadaten, die der Build erzeugt
(`META-INF/spring-configuration-metadata.json`): aus dem `@param` der Record-Komponente, für Schlüssel ohne
`@ConfigurationProperties`-Typ aus `src/main/resources/META-INF/additional-spring-configuration-metadata.json` mit
der lesenden Klasse als `sourceType`. Der Standardwert ist der aus dem Basisdokument von `application.yml`.

Jeder Schlüssel ist eingeordnet, an der Komponente oder pauschal am Typ:

```java
@NoDataImpact("credentials, timeouts and probing of the rerank role")
@ConfigurationProperties(prefix = "opaa.rerank")
public record RerankProperties(@DataImpact boolean enabled, @DataImpact String baseUrl, ...)
```

**`@DataImpact`**, wenn der Standardwert bestimmt, was über Personen anfällt, wie lange es bleibt, wer es sieht, was
sich über Personen auswerten lässt oder wohin Daten gehen (Fristen, Löschung, Sichtbarkeit, Schwellen,
Modellrollen, Adressen, Schalter von Funktionen mit Daten). **`@NoDataImpact("Grund")`** für Schutzgrenzen,
Zeitlimits, Budgets, Ratenlimits, Suchgüte und Geheimnisse ohne Standardwert. Im Zweifel `@DataImpact`. Die
Pauschalmarkierung am `@ConfigurationProperties`-Typ deckt keine Schalter, Modelle, Adressen, Fristen und Abstände
(letztes Segment `enabled`, `model`, `…url`, `…uri`, `…endpoint`, `…-days`, `…-months`, `…-hours`, `…-minutes`,
`…ttl`, `…lifetime…`, `…retention…`, `…interval…`) und keine Komponente vom Typ `Duration` oder `Period`; sie werden
an der Komponente entschieden. Ein Vollabgleich-Rhythmus ist datenwirksam: Erst der Vollabgleich entfernt, was in
der Quelle gelöscht wurde. Eine Begründung an einer Prompt-Klasse ändert deren Prüfsumme; dort die
Markierung an eine andere lesende Klasse legen.

Ein datenwirksamer Schlüssel steht unter `settings`, in der Datei des Bereichs, auf den er wirkt, sonst in
`configuration.yaml`. Dort stehen auch die Standardmodelle: Modell und Adresse jeder Modellrolle
(`spring.ai.openai.*.model`, `*.base-url`, `opaa.rerank.*`, `opaa.transcription.*`).

```yaml
settings:
  - key: opaa.rerank.enabled
    source: PROPERTY
    defaultValue: "false"
    effect: Ob die Rerank-Rolle Suchtreffer samt Frage an ein Rerank-Modell sendet
    bearings: [DATA_FLOW]
    helm:
      values: rerank.enabled
      defaultValue: "false"
```

`helm` ist Pflicht, sobald `deploy/helm/opaa/templates/backend-configmap.yaml` die Umgebungsvariable des Schlüssels
setzt, und verboten, sobald nicht mehr. `helm.values` muss die Variable speisen: `.Values.<pfad>` steht in ihrer
Zeile, in einem umschließenden `if`/`with`/`range` oder in einem eingebundenen Helfer aus `_helpers.tpl`. Per
`range $name, $wert := .Values.<map>` erzeugte Variablen (`OPAA_X_{{ $name | snakecase | upper }}_Y`) liest der
Test je Schlüssel der Map; jede andere zusammengesetzte Variable lässt ihn scheitern. Überschreibt ein Produktivprofil von `application.yml` (alle außer `dev`)
den Standardwert, nennt der Eintrag den Wert in `notes` als `"Profil <name>: <wert>"`.

## Delta zwischen zwei Versionen

`./gradlew complianceDelta -PfromRef=<tag oder commit> [-PtoRef=<tag oder commit>]` schreibt das kumulierte
Delta mit Stufenvorschlägen nach `build/compliance/delta/` (`manifest-delta.md`, `manifest-delta.json`); statt
eines Stands nimmt `-PfromJson`/`-PtoJson` die JSON-Fassung der Faktenblätter, ohne `-PtoRef` und `-PtoJson`
gilt der Arbeitsstand. `-PlabelledPrs=<datei>` nimmt die Ausgabe von
`gh pr list --state merged --label data-impact --json number,title,url`. Ein Stand muss lokal vorhanden sein
(Checkout mit Tags, `fetch-depth: 0`), Netz braucht der Lauf nicht
([ADR-0052, Entscheidung 10](../../docs/decisions/0052-compliance-manifest.md#10-delta-zwischen-zwei-versionen)).

Das Delta liest das Manifest ohne die Records, Feld für Feld; Listen vergleicht es ohne Reihenfolge. Git
startet nur `ManifestSnapshot`, jeder Wert von außen steht nach `--end-of-options`, und ein Commit aus der
JSON-Fassung zählt nur als Commit-Kennung (`ModularArchitectureTest` lässt keinen anderen Prozessstart zu). Wer einen Abschnitt oder ein Feld zum Schema
hinzufügt, ordnet es in `StageRules` ein, der einzigen Stelle der Stufenregeln im Code, und gibt ihm in
`ManifestDeltaRenderer` eine deutsche Bezeichnung; ein Feld ohne Regel wird „Zustimmung, Zuordnung offen“.
Die Zuordnung steht auch in der
[Spezifikation](../../docs/features/behoerdenunterlagen.md#zuordnung-im-werkzeug); beide Stellen ändern sich
zusammen. Die Fälle sichert `ManifestDeltaTest` auf der Vorlage `src/test/resources/compliance/delta/base.yaml`.

## Grenzen

- Der Wächter prüft, **dass** ein Eintrag existiert, nicht, ob er stimmt. Ob Personenbezug, Zweck und Sichtbarkeit
  zutreffen, sichern Review und die wöchentliche Drift-Routine.
- Das Schema liest `ChangelogSchema` aus den `sql:`-Blöcken des Master-Changelogs, ohne Datenbank. Es scheitert statt
  zu überspringen: bei nativen Liquibase-Changes, bei jeder `CREATE TABLE`-, `ALTER TABLE`- oder `DROP TABLE`-Anweisung,
  die es nicht vollständig liest, bei einem anderen Schema als `public`, bei `CREATE SCHEMA`,
  `CREATE MATERIALIZED VIEW` und `SELECT … INTO` sowie bei jeder Tabellen-DDL in `DO`-Blöcken und Funktionen außer
  der Partitionspflege der Protokolltabellen. Aus Bruchstücken zusammengesetztes dynamisches SQL sieht es nicht.
  `ChangelogSchemaParityTest` (`io.opaa.migration`, braucht Docker, läuft in der CI) vergleicht das Ergebnis mit dem
  Katalog einer migrierten Datenbank.
- `vector_store` legt Spring AI an, nicht Liquibase; seine Spalten stehen fest in
  `ChangelogSchema.TABLES_OUTSIDE_THE_CHANGELOG`. `ChangelogSchemaParityTest` lässt Spring AI die Tabelle anlegen und
  vergleicht die Spalten; ändert ein Spring-AI-Update sie, scheitert er dort.
- Ob ein Schlüssel richtig eingeordnet ist, prüft der Wächter nicht. Ein Schlüssel, den nur eine Bedingung wie
  `@ConditionalOnProperty` liest und der in `application.yml` fehlt, erscheint nicht in den Metadaten. Die Belegung
  aus `.env.docker.example` (Compose) prüft er nicht; sie fällt unter die Kennzeichnung am PR.
