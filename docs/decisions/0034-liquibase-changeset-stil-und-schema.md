# ADR-0034: Liquibase-Changesets als reines PostgreSQL-SQL, Zielschema konfigurierbar

## Status

**Akzeptiert (18.09.2026)** — Issue #1366. Ergänzt [ADR-0002](0002-mvp-technology-stack.md)
(PostgreSQL 18 + pgvector, Liquibase) und [ADR-0015](0015-eigentuemertrennung-protokollablage.md)
(Eigentümertrennung der Protokollablage).

## Kontext

Beim Review von PR #1342 fielen zwei Eigenschaften auf, die nicht an einem einzelnen PR liegen,
sondern Hauskonvention aller Changesets waren:

1. **Alle Changesets bestehen ausschließlich aus `sql:`-Blöcken.** Kein Changeset nutzt native
   Liquibase-Changes (`createTable`, `createIndex`, `addColumn`, …). Die Baseline entstand als
   konsolidiertes SQL im `pg_dump`-Stil, jedes spätere Changeset hat den Stil übernommen — begründet
   war das nirgends.
2. **Jedes DDL trug hart das Präfix `public.`** (337 Vorkommen allein in der Baseline). Die Objekte
   waren damit fest an `public` gebunden. Zugleich lösen die JPA-Entities und alle nativen Abfragen
   ihre Tabellen unqualifiziert über den `search_path` der Verbindung auf — bei abweichendem
   `search_path` wären DDL und Anwendung auseinandergelaufen.

Es gibt noch keinen Produktionsbestand; Installationen werden neu aufgesetzt. Rückwärtskompatibilität
zu bereits migrierten Datenbanken (Checksummen, `validCheckSum`, Umstellungs-Changeset) ist deshalb
kein Kriterium.

## Entscheidung

### 1. Changesets bleiben reines SQL, bewusst PostgreSQL-only

OPAA zielt exakt auf PostgreSQL 18 mit pgvector (ADR-0002). Das Schema nutzt durchgehend Konstrukte,
die native Changes nicht oder nur umständlich abbilden: partielle Unique-Indizes
(`WHERE is_default`), Ausdrucksindizes (`rtrim(issuer_uri, '/')`, `metadata ->> 'library_id'`),
`CHECK (… IN (…))`, `gen_random_uuid()`, `vector`-Spalten, deklarative Partitionierung,
PL/pgSQL-Trigger und SECURITY-DEFINER-Funktionen, Rollen und Rechtevergabe (ADR-0015),
`CREATE INDEX CONCURRENTLY`. Native Changes würden eine Datenbankportabilität vortäuschen, die beim
nächsten Changeset wieder in `sql:` endet, und das Schema auf zwei Schreibweisen verteilen. Ein
Changeset liest sich als SQL genau so, wie PostgreSQL es ausführt.

### 2. Kein Schemapräfix — das Zielschema kommt aus der Konfiguration

- **Changesets qualifizieren keine eigenen Objekte.** `public.` ist aus Baseline und allen
  Delta-Changesets entfernt; neue Changesets schreiben unqualifizierte Namen. Wo SQL einen
  Schemanamen zwingend braucht, kommt er aus der Migrationsverbindung:
  - Rechtevergabe auf das Schema (`GRANT USAGE, CREATE ON SCHEMA`, `ALTER DEFAULT PRIVILEGES … IN
    SCHEMA`) über `format('… %I …', current_schema())` in einem `DO`-Block. `USAGE` für
    `opaa_audit_owner` ist in `public` implizit vorhanden, in einem eigenen Schema nicht — ohne sie
    finden die SECURITY-DEFINER-Löschfunktionen ihre eigenen Tabellen nicht.
  - Der gepinnte `search_path` von Funktionen über den Liquibase-Parameter
    `${database.defaultSchemaName}`: `SET search_path TO 'pg_catalog', '${database.defaultSchemaName}', 'pg_temp'`.
    `SET search_path FROM CURRENT` scheidet aus, weil `pg_temp` dann nicht ausdrücklich ans Ende
    gesetzt wäre — für SECURITY-DEFINER-Funktionen eine bekannte Lücke.
- **Eine einzige Einstellung bestimmt das Schema:** `opaa.database.schema`
  (Umgebungsvariable `OPAA_DB_SCHEMA`, Vorgabe `public`). Daraus folgen:
  - der `search_path` jeder Verbindung (`currentSchema=<schema>,public` des JDBC-Treibers). Liquibase,
    Hibernate und native Abfragen teilen sich dieselbe Verbindung und damit dieselbe Auflösung;
    `hibernate.default_schema` wird bewusst **nicht** gesetzt, weil es native Abfragen nicht erfasst
    und eine zweite Quelle derselben Wahrheit wäre.
  - `spring.liquibase.default-schema` — die Verwaltungstabellen `databasechangelog*` liegen im
    Zielschema, und ein fehlendes Schema lässt die Migration scheitern, statt über `current_schema()`
    stillschweigend nach `public` durchzufallen.
  - `spring.ai.vectorstore.pgvector.schema-name` — die von Spring AI angelegte Vektortabelle und
    die eigenen nativen Abfragen auf `vector_store` liegen im selben Schema.
- `public` bleibt als **Rückfall hinter** dem Zielschema im `search_path`, damit eine vorab in
  `public` installierte Erweiterung `vector` auffindbar bleibt. Eigene Objekte legt OPAA dort nicht an;
  `SchemaPortabilityMigrationTest` sichert das zu.
- `DatabaseSchemaGuard` lässt nur einfache Bezeichner in Kleinschreibung zu (`[a-z_][a-z0-9_]*`,
  höchstens 63 Zeichen) und verweigert den Start, wenn die Vektortabelle auf ein anderes Schema zeigt.
  Der Name wird unquotiert in `search_path` und SQL eingesetzt; die Prüfung läuft vor Datenquelle und
  Liquibase.

### Verworfene Alternativen

- **`${schema}`-Platzhalter vor jedem Objektnamen.** Hält die Qualifizierung explizit, macht aber
  jedes Changeset unlesbarer und verlangt einen eigenen Parameter in jedem Migrationstest. Der
  `search_path` erreicht dasselbe mit einem Mechanismus, den Hibernate und native Abfragen ohnehin
  nutzen.
- **Schema von Liquibase anlegen lassen.** Liquibase legt seine Verwaltungstabellen an, bevor das
  erste Changeset läuft; ein `CREATE SCHEMA` im Changelog käme zu spät. Das Schema anzulegen bleibt
  ein einmaliger Schritt des Betriebs.

## Konsequenzen

- OPAA lässt sich in ein beliebiges, vorab angelegtes Schema installieren, auch neben anderen
  Anwendungen in einer gemeinsam genutzten Datenbank (Handbuch: `deployment.md`, „Eigenes
  Datenbankschema").
- Die Checksummen aller Changesets haben sich geändert. Bestehende Installationen werden neu
  aufgesetzt — ein bewusster Einmalvorgang vor Produktionsbetrieb, wie bei den Baselines #904 und
  #1492.
- Ein Wechsel des Schemas nach der Installation verschiebt keine Daten.
- Die Rolle `opaa_audit_owner` ist clusterweit; mehrere Installationen in verschiedenen Schemas
  derselben PostgreSQL-Instanz teilen sie.
- Tests fragen Kataloge über `current_schema()` statt über das Literal `'public'` ab.
  `SchemaPortabilityMigrationTest` wendet den vollständigen Master-Changelog in ein anderes Schema an
  und prüft, dass nichts in `public` landet und die Funktionen das Zielschema pinnen.
- Neue Changesets: reines SQL, unqualifizierte Namen, Schemanamen nur über `current_schema()` bzw.
  `${database.defaultSchemaName}`.
