# ADR-0034: Migrationen als reines PostgreSQL-SQL, Zielschema konfigurierbar

## Status

Akzeptiert · zuletzt geändert 2026-10-24 · ergänzt ADR-0002 und ADR-0015

## Kontext

Zwei Hauskonventionen aller Changesets waren nirgends begründet: Jedes Changeset besteht
ausschließlich aus `sql:`-Blöcken (kein natives Liquibase-Change), und jedes DDL trug hart das
Präfix `public.` (337 Vorkommen in der Baseline), während JPA-Entities und native Abfragen ihre
Tabellen unqualifiziert über den `search_path` auflösen — bei abweichendem `search_path` wären DDL
und Anwendung auseinandergelaufen. Es gibt keinen Produktionsbestand; Rückwärtskompatibilität zu
migrierten Datenbanken (Checksummen, `validCheckSum`) ist kein Kriterium.

## Entscheidung

### 1. Migrationen bleiben reines SQL, bewusst PostgreSQL-only

OPAA zielt exakt auf PostgreSQL 18 mit pgvector (ADR-0002). Das Schema nutzt durchgehend Konstrukte,
die native Changes nicht oder nur umständlich abbilden: partielle Unique-Indizes, Ausdrucksindizes,
`CHECK (… IN (…))`, `gen_random_uuid()`, `vector`-Spalten, Partitionierung, PL/pgSQL-Trigger und
SECURITY-DEFINER-Funktionen, Rollen und Rechtevergabe (ADR-0015), `CREATE INDEX CONCURRENTLY`.
Eine Migration ist ein SQL-Skript für Flyway (ADR-0056) und liest sich genau so, wie PostgreSQL es
ausführt; ein datenbankneutrales Beschreibungsformat würde eine Portabilität vortäuschen, die bei
der nächsten Migration wieder in SQL endet. Regeln für neue Migrationen:
[backend/AGENTS.md](../../backend/AGENTS.md#datenbankmigrationen), Herleitung in
[backend/docs/migrationen.md](../../backend/docs/migrationen.md).

### 2. Kein Schemapräfix — das Zielschema kommt aus der Konfiguration

Migrationen qualifizieren keine eigenen Objekte. Wo SQL einen Schemanamen zwingend braucht, kommt er
aus der Migrationsverbindung: Rechtevergabe auf das Schema über `format('… %I …', current_schema())`
in einem `DO`-Block (`USAGE` für `opaa_audit_owner` ist nur in `public` implizit), der gepinnte
`search_path` von Funktionen über `${flyway:defaultSchema}` mit `pg_temp` ausdrücklich am Ende
(`SET search_path FROM CURRENT` scheidet deshalb aus). Eine Einstellung bestimmt das Schema:
`opaa.database.schema` (`OPAA_DB_SCHEMA`, Vorgabe `public`). Daraus folgen der `search_path` jeder
Verbindung (`currentSchema=<schema>,public` des JDBC-Treibers; `hibernate.default_schema` wird
bewusst nicht gesetzt, weil es native Abfragen nicht erfasst), `spring.flyway.default-schema` mit
`create-schemas: false` (ein fehlendes Schema lässt die Migration scheitern, statt still nach
`public` durchzufallen oder angelegt zu werden) und
`spring.ai.vectorstore.pgvector.schema-name`. `public` bleibt als Rückfall hinter dem Zielschema,
damit eine vorab dort installierte Erweiterung `vector` auffindbar bleibt; eigene Objekte legt OPAA
dort nicht an (`SchemaPortabilityTest`). `DatabaseSchemaGuard` lässt nur einfache
Bezeichner in Kleinschreibung zu (`[a-z_][a-z0-9_]*`, höchstens 63 Zeichen, keine
PostgreSQL-Schlüsselwörter; der Name wird unquotiert eingesetzt) und verweigert den Start, wenn die
Vektortabelle auf ein anderes Schema zeigt; die Prüfung läuft vor Datenquelle und Migration.

## Konsequenzen

- **Einfacher:** OPAA lässt sich in ein beliebiges, vorab angelegtes Schema installieren, auch neben
  anderen Anwendungen ([Handbuch: Eigenes Datenbankschema](../handbuch/betrieb/datenbank.md#eigenes-datenbankschema)).
  Ein Wechsel des Schemas nach der Installation verschiebt keine Daten. Tests fragen Kataloge über
  `current_schema()` ab.
- **Schwieriger:** Ein Wechsel des Zielschemas ist keine Migration; bestehende Installationen werden
  im neuen Schema neu aufgesetzt.
- **Restrisiken:** Die Rolle `opaa_audit_owner` ist clusterweit — eine zweite Installation mit
  anderem Konto in derselben Instanz scheitert an der Rollenvergabe, mit demselben Konto könnte sie
  das Protokoll der ersten verändern; getrennte Installationen brauchen getrennte Instanzen. Gehört
  das Zielschema dem Anwendungskonto, greift die Eigentümertrennung nach ADR-0015 nicht (für `public`
  ebenso, wenn das Konto Eigentümer der Datenbank ist); das Handbuch empfiehlt ein Schema im Besitz
  einer Verwaltungsrolle.

## Verworfene Alternativen

- `${schema}`-Platzhalter vor jedem Objektnamen — macht jede Migration unlesbarer; der `search_path` erreicht dasselbe mit dem Mechanismus, den Hibernate und native Abfragen ohnehin nutzen.
- Schema vom Migrationswerkzeug anlegen lassen — es legt seine Verwaltungstabelle an, bevor die erste Migration läuft; das Schema anzulegen bleibt ein Schritt des Betriebs.

## Offen

—
