# Modul workspace

Pakete (`io.opaa.*`): space, revision, diagnosticaccess. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Räume mit ihren Mitgliedschaften und Asset-Zuordnungen (`space`), die eigenen Lesewege der Revision
in die Rechtehistorie, heute die Stichtagsauskunft (`revision`), und das Befugnis- und
Protokollmodell für „Sicht als" in der Suchdiagnose (`diagnosticaccess`). workspace hängt nur von
foundation, identity, rights und knowledge ab.

## Invarianten und Stolpersteine

- **Eine Mitgliedschaft nennt ein Subjekt, keine Person.** Die wirksame Rolle ist das Beste aus
  eigener Mitgliedschaft, Mitgliedschaften der eigenen Gruppen und Eigentum
  (`SpaceAccessPolicy#effectiveRole`). Der Eigentümer ist mindestens `ADMIN`. Der Vorrang der
  Systemverwaltung steckt bewusst nicht darin, sondern in den einzelnen Prüfungen.
- **`space` erreicht die Bibliotheksbestände in `io.opaa.knowledge`** — die einzige erklärte
  Ausnahme von der Regel, dass Fachpakete einander nicht kennen.
- **`revision` liegt über den Fachpaketen, deren Auskünfte es zusammensetzt**, weil diese selbst in
  `io.opaa.audit` schreiben; in `audit` entstünde ein Zyklus. Die Schranke jeder Revisionsauskunft
  (Rolle AUDITOR, Pflichtanlass, begrenztes Zeitfenster und Paging) bleibt in
  `io.opaa.audit.AuditAccessGate`.
- **„Sicht als" hat genau einen Ausführungsweg:** `ForeignDiagnosticContextService` prüft Befugnis,
  Begründung und Diagnosesperre, schreibt den Protokolleintrag im selben Aufruf und speichert nie
  ein Ergebnis. Die Befugnis leitet sich aus keiner Rolle ab
  (`DiagnosticImpersonationGrantService`); die Sperre ist standardmäßig gesetzt.
- **Das Diagnoseprotokoll steht unter derselben Eigentümertrennung wie `audit_log`:** Das
  Anwendungskonto hat nur `INSERT` und `SELECT`, gelöscht wird nur per Partitions-Drop einer
  `SECURITY DEFINER`-Funktion nach 12 Monaten.

## Verweise

- ADRs (`docs/decisions/`): 0015, 0036 (Entscheidung 6 und 8)
- Handbuch: `docs/handbuch/bibliotheken-und-berechtigungen.md`, Abschnitte 5, 12 und 14;
  `docs/handbuch/suche.md`, Abschnitt 8
- Strukturtests: `PermissionPackageBoundaryTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.space.*' --tests 'io.opaa.revision.*' \
  --tests 'io.opaa.diagnosticaccess.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/workspace/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
