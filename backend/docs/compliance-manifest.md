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
| entfernte oder umbenannte Tabelle, Spalte, Konstante | Eintrag entfernen oder umbenennen |

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
- Konfigurationsschlüssel (#2548) und ausgehende Datenflüsse (#2549) prüft der Wächter noch nicht.
