# ADR-0015: Eigentümertrennung der Protokollablage

## Status

Vorgeschlagen

## Kontext

#391 baut die revisionssichere Protokollablage `audit_log` (Entscheidung #355,
`docs/features/security-and-compliance.md#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen`).
Der Anspruch dort ist eindeutig: das Anwendungskonto bekommt `INSERT` und ein begrenztes `SELECT`
und **keine Rechte am Schema der Tabelle**.

Der ursprüngliche Entwurf von Migration 017 hat das über `REVOKE ALL` / `GRANT INSERT, SELECT`
gegen `current_user` versucht, ohne die Eigentümerschaft zu verschieben. Das Code-Review zu PR #428
hat gegen ein echtes `pgvector/pgvector:pg18` nachgestellt, dass das nicht ausreicht: Wer eine
Tabelle **besitzt**, kann sich Rechte jederzeit selbst zurückgewähren (`GRANT ALL ON audit_log TO
current_user`), `CHECK`-Constraints entfernen, Partitionen abhängen (`DETACH PARTITION`) und danach
frei verändern, oder die Tabelle komplett löschen — alles ohne jede vorherige Rechteänderung, weil
Eigentümerschaft implizit die volle DDL-Hoheit über das eigene Objekt umfasst und sich nicht
wegrevoken lässt. Migrations- und Laufzeitkonto sind in diesem Projekt dieselbe Datenbankrolle
(`spring.datasource.username`), das Anwendungskonto ist also zwangsläufig auch der Ersteller und
damit ursprüngliche Eigentümer jeder Tabelle, die eine Migration anlegt.

Zusätzlich hat das Review gezeigt: Jede Partition einer partitionierten Tabelle trägt in Postgres
eine **eigene** ACL. Ein `REVOKE`/`GRANT`, das nur die Elterntabelle adressiert, schützt Zugriffe,
die über die Elterntabelle geroutet werden (der normale Anwendungspfad), aber nicht einen direkten
Zugriff auf eine benannte Partition (`UPDATE audit_log_2026_08 SET ...`) — genau der
Angriffsweg, den `AuditLogRepository`s eigene Javadoc als Bedrohungsmodell nennt ("eine
eingeschleuste Anweisung, die dieses Repository umgeht").

## Entscheidung

Migration 017 legt eine dedizierte, nicht anmeldefähige Rolle `opaa_audit_owner` (`NOLOGIN`) an und
überträgt die Eigentümerschaft von `audit_log`, jeder ihrer Partitionen und
`audit_actor_pseudonyms` auf diese Rolle (`ALTER TABLE ... OWNER TO`). Das Anwendungskonto erhält
danach ausschließlich `INSERT, SELECT` auf `audit_log` und `audit_actor_pseudonyms`, granted über
die Elterntabelle — nicht auf einzelne Partitionen. `ALTER DEFAULT PRIVILEGES FOR ROLE
opaa_audit_owner` sorgt dafür, dass eine künftig von `opaa_audit_owner` angelegte Partition (die
spätere Nachprovisionierung, außerhalb des Umfangs von #391) automatisch dieselbe eingeschränkte
Grant-Menge für das Anwendungskonto erbt, ohne dass diese Migration erneut laufen muss.

Um die Eigentümerschaft überhaupt übertragen zu können, muss das migrationsausführende Konto
`CREATEROLE` besitzen (um `opaa_audit_owner` anzulegen), `CREATE ON SCHEMA public WITH GRANT
OPTION` (um `opaa_audit_owner` selbst das für `ALTER TABLE ... OWNER TO` nötige `CREATE` auf dem
Schema zu geben — Mitgliedschaft in einer Rolle mit diesem Recht reicht dafür nicht, die Zielrolle
braucht das Recht selbst) und vorübergehende Mitgliedschaft in `opaa_audit_owner` (für `ALTER
DEFAULT PRIVILEGES FOR ROLE ...`, das ebenfalls Mitgliedschaft in der Zielrolle verlangt). Die
Migration entzieht sich die explizit gewährte Mitgliedschaft am Ende wieder (`REVOKE
opaa_audit_owner FROM <konto>`).

**Ein zweiter empirischer Befund gegen echtes Postgres 18, aus der Re-Review-Runde zu PR #428,
begrenzt das entscheidend:** `CREATE ROLE` selbst gewährt dem anlegenden `CREATEROLE`-Konto
automatisch `ADMIN OPTION` auf die neue Rolle — ein echter `pg_auth_members`-Eintrag, dessen
Grantor nicht das Anwendungskonto, sondern die Bootstrap-Identität der Datenbank ist. Ein `REVOKE
opaa_audit_owner FROM <konto>`, ausgeführt vom Anwendungskonto selbst, entfernt nachweislich nur die
vom selben Konto explizit gewährte Mitgliedschaft, nicht diese automatische. Der Rest bleibt
bestehen, mit `admin_option = true`, `inherit_option = false`, `set_option = false`.

Die erste Einschätzung dieses Rests — er sei begrenzt auf reine Mitgliedschaftsverwaltung und
harmlos, weil `inherit`/`set` beide `false` seien — war **falsch, und zwar nachweislich**:
`ADMIN OPTION` erlaubt dem Anwendungskonto, sich die Mitgliedschaft **erneut selbst zu gewähren**,
diesmal mit `WITH SET TRUE`. Zwei Anweisungen genügen:

```sql
GRANT opaa_audit_owner TO <anwendungskonto> WITH SET TRUE;
DELETE FROM audit_log;
```

Das `DELETE` braucht nicht einmal ein vorheriges `SET ROLE` — die neue Mitgliedschaft wird
automatisch geerbt (`INHERIT` ist die Voreinstellung für die neu ausgestellte Mitgliedschaft,
unabhängig vom `inherit_option`-Wert des alten, automatischen Eintrags), und Eigentümerrechte wirken
ab da ambient. Im selben Zug gelingt danach auch alles andere, was zuvor scheiterte:
`CHECK`-Constraint entfernen, Partition abhängen, Tabelle löschen. `Migration017AuditLogTest`s
ursprüngliche Prüfung (`SET ROLE opaa_audit_owner` scheitert) maß korrekt, dass die Tür verschlossen
ist, und übersah, dass der Schlüssel — die `ADMIN OPTION` selbst — danebenliegt.

Die Migration kann das **nicht selbst schließen**: Der Grantor des automatischen Eintrags ist die
Bootstrap-Identität, nicht das Anwendungskonto, und ein vom Anwendungskonto selbst ausgeführtes
`REVOKE ADMIN OPTION FOR opaa_audit_owner FROM <konto>` ist nachweislich ein wirkungsloses No-op
(`WARNING: role "..." has not been granted membership in role "opaa_audit_owner" by role "..."`).
Der Rest ist unvermeidbar, **solange `opaa_audit_owner` von genau dem Konto angelegt wird, das
anschließend eingeschränkt sein soll**.

**Ehrliche Bilanz: Die Append-only-Garantie auf Datenbankebene gilt mit dem heutigen Stand dieser
Migration noch nicht vollständig.** Sie gilt vollständig erst, sobald die Eigentümerrolle
`opaa_audit_owner` außerhalb des Migrations-/Anwendungskontos provisioniert wird — von einer
Identität, die das Anwendungskonto niemals `CREATEROLE`-äquivalent zu dieser Rolle macht. Bis dahin
ist der Schutz dieser Migration eine reale, deutliche Verbesserung gegenüber dem Ausgangszustand
(vorher genügte ein einzelnes, unauffälliges `UPDATE` auf eine Partition; jetzt braucht es eine
ungewöhnliche, im Systemkatalog sichtbare `GRANT ... WITH SET TRUE`-Anweisung), aber sie ist keine
abgeschlossene Erfüllung des Abnahmekriteriums "keine Rechte am Schema der Tabelle" — wer beliebiges
SQL über das Anwendungskonto ausführen kann (der in `AuditLogRepository`s Javadoc benannte
Bedrohungsfall "eine eingeschleuste Anweisung"), kann auch diese zwei Anweisungen ausführen.
`Migration017AuditLogTest` hält beide Zustände fest: einen roten Reproduktionstest, der die
Eskalation im heutigen Bootstrap-Modell als (unerwünscht) erfolgreich dokumentiert, und einen
grünen Test, der zeigt, dass dieselbe Eskalation scheitert, sobald `opaa_audit_owner` von einer
dritten, vom Anwendungskonto verschiedenen Identität angelegt wird.

Damit bleiben drei reale Voraussetzungen offen, alle auf #426 verschoben: Das
migrationsausführende Konto braucht `CREATEROLE` und `CREATE ON SCHEMA public WITH GRANT OPTION`
als dauerhafte Attribute/Rechte, und der `ADMIN OPTION`-Eskalationsweg bleibt offen, solange
Migrations- und Laufzeitkonto dieselbe Rolle sind. #426 führt seit dieser Runde das
Abnahmekriterium "Eigentümerrolle wird außerhalb des Anwendungs-/Migrationskontos provisioniert;
der `ADMIN OPTION`-Eskalationsweg ist geschlossen" — das ist keine optionale Härtung mehr, sondern
die Bedingung dafür, dass die hier getroffene Entscheidung tatsächlich trägt.

## Konsequenzen

**Einfacher:**

- Die vier vom ersten Review nachgestellten Angriffe (Rechte zurückgewähren, `CHECK`-Constraint
  entfernen, Partition abhängen und verändern, Tabelle löschen) scheitern für das Anwendungskonto
  im Normalzustand, weil es weder Eigentümer noch (ohne den `ADMIN OPTION`-Umweg unten) wirksam
  Mitglied von `opaa_audit_owner` ist — nicht nur, weil ihm einzelne Privilegien fehlen.
- Direkter Zugriff auf eine benannte Partition ist im Normalzustand ebenso wirkungslos wie auf die
  Elterntabelle, weil keine Partition dem Anwendungskonto eine eigene ACL gewährt.
- Künftige Partitionen (Nachprovisionierung, außerhalb #391) erben den Schutz automatisch über
  `ALTER DEFAULT PRIVILEGES`, ohne dass diese oder eine künftige Migration erneut an jede neue
  Partition denken muss.
- Der Angriffspfad ist gegenüber dem Ausgangszustand (ein unauffälliges `UPDATE` auf eine Partition)
  deutlich eingeschränkt: Er verlangt jetzt eine ungewöhnliche, im Systemkatalog sichtbare
  `GRANT ... WITH SET TRUE`-Anweisung statt eines beliebigen DML-Befehls.

**Schwieriger / neue Voraussetzung:**

- Das migrationsausführende Konto braucht `CREATEROLE` und `CREATE ON SCHEMA public WITH GRANT
  OPTION`. Das ist eine zusätzliche, dauerhaft benötigte Rechteerweiterung gegenüber dem bisherigen
  Stand (reines `CREATE ON SCHEMA public` + `REFERENCES`), solange kein getrenntes Migrationskonto
  existiert.
- **Ein nicht wegrevokbarer `ADMIN OPTION`-Rest bleibt zwischen dem Anwendungskonto und
  `opaa_audit_owner` bestehen und ist ein offener Eskalationsweg, kein harmloser** (`CREATE ROLE`s
  automatischer Nebeneffekt, siehe oben): Mit zwei Anweisungen (`GRANT ... WITH SET TRUE` gefolgt
  von einer beliebigen DDL-/DML-Anweisung) hebt das Anwendungskonto die gesamte Eigentümertrennung
  wieder auf. Diese Entscheidung schließt den Weg damit **nicht vollständig**, solange Migrations-
  und Laufzeitkonto identisch sind — sie dokumentiert und testet ihn, statt ihn stillschweigend zu
  übergehen, und macht seine Schließung zur Bedingung für #426.
- Die Rollback-Pfade dieser Migration sind entsprechend komplexer (Mitgliedschaft erneut gewähren,
  Eigentümerschaft zurückübertragen, `ALTER DEFAULT PRIVILEGES`-Eintrag entfernen, Mitgliedschaft
  wieder entziehen) und in ihrer Reihenfolge fehleranfälliger als ein einfaches `GRANT`/`REVOKE`.
- #426 führt seit der Re-Review-Runde zu PR #428 das Abnahmekriterium "Eigentümerrolle wird
  außerhalb des Anwendungs-/Migrationskontos provisioniert; der `ADMIN OPTION`-Eskalationsweg ist
  geschlossen" — die Umsetzung selbst (getrenntes Migrationskonto, externe Provisionierung von
  `opaa_audit_owner`) ist weiterhin nicht Teil von #391.
