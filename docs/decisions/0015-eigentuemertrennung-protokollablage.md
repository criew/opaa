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

**Ein empirischer Befund gegen echtes Postgres 18 begrenzt das:** `CREATE ROLE` selbst gewährt dem
anlegenden `CREATEROLE`-Konto automatisch `ADMIN OPTION` auf die neue Rolle — ein echter
`pg_auth_members`-Eintrag, dessen Grantor nicht das Anwendungskonto, sondern die
Bootstrap-Identität der Datenbank ist. Ein `REVOKE opaa_audit_owner FROM <konto>`, ausgeführt vom
Anwendungskonto selbst, entfernt nachweislich nur die vom selben Konto explizit gewährte
Mitgliedschaft, nicht diese automatische. Der Rest bleibt bestehen. Das ist kein Sicherheitsloch,
sondern begrenzt: Der verbleibende Eintrag trägt `admin_option = true`, aber `inherit_option =
false` und `set_option = false` — das Anwendungskonto kann damit *verwalten*, wer sonst Mitglied
von `opaa_audit_owner` ist, aber weder automatisch deren Rechte erben noch per `SET ROLE
opaa_audit_owner` deren Identität annehmen. `Migration017AuditLogTest` prüft das direkt: `SET ROLE
opaa_audit_owner` scheitert für das Anwendungskonto, und alle vier vom Review nachgestellten
Angriffe scheitern trotz dieses Rests. Diese Grenze ist damit nachgewiesen, nicht nur behauptet.

Damit bleiben zwei reale Voraussetzungen offen: Das migrationsausführende Konto braucht
`CREATEROLE` und `CREATE ON SCHEMA public WITH GRANT OPTION` als dauerhafte Attribute/Rechte (nicht
nur für die Objekte, sondern das Recht, überhaupt Rollen anzulegen und Schema-Rechte
weiterzugeben). Das ist eine andere, schwächere Form von Rechten als Eigentümerschaft an der
Protokolltabelle selbst — es erlaubt keinen Zugriff auf `audit_log`s Inhalt oder Struktur direkt,
aber es ist trotzdem ein Privileg über das Abnahmekriterium "keine Rechte am Schema der Tabelle"
hinaus. Solange Migrations- und Laufzeitkonto dieselbe Rolle sind (siehe #426), trägt das
Laufzeitkonto diese Attribute dauerhaft mit, ebenso den harmlosen `ADMIN OPTION`-Rest auf
`opaa_audit_owner`. Ein Betreiber, der Migrations- und Laufzeitkonto trennt (Gegenstand von #426),
kann beides auf das Migrationskonto beschränken und dem Laufzeitkonto ganz vorenthalten — das ist
die konsequente nächste Stufe, aber nicht Teil dieser Entscheidung.

## Konsequenzen

**Einfacher:**

- Die vier vom Review nachgestellten Angriffe (Rechte zurückgewähren, `CHECK`-Constraint entfernen,
  Partition abhängen und verändern, Tabelle löschen) scheitern für das Anwendungskonto, weil es
  weder Eigentümer noch Mitglied von `opaa_audit_owner` ist — nicht nur, weil ihm einzelne
  Privilegien fehlen.
- Direkter Zugriff auf eine benannte Partition ist ebenso wirkungslos wie auf die Elterntabelle,
  weil keine Partition dem Anwendungskonto eine eigene ACL gewährt.
- Künftige Partitionen (Nachprovisionierung, außerhalb #391) erben den Schutz automatisch über
  `ALTER DEFAULT PRIVILEGES`, ohne dass diese oder eine künftige Migration erneut an jede neue
  Partition denken muss.

**Schwieriger / neue Voraussetzung:**

- Das migrationsausführende Konto braucht `CREATEROLE` und `CREATE ON SCHEMA public WITH GRANT
  OPTION`. Das ist eine zusätzliche, dauerhaft benötigte Rechteerweiterung gegenüber dem bisherigen
  Stand (reines `CREATE ON SCHEMA public` + `REFERENCES`), solange kein getrenntes Migrationskonto
  existiert.
- Ein harmloser, aber nicht wegrevokbarer `ADMIN OPTION`-Rest bleibt zwischen dem Anwendungskonto
  und `opaa_audit_owner` bestehen (`CREATE ROLE`s automatischer Nebeneffekt, siehe oben) — begrenzt
  durch `inherit_option = false` und `set_option = false`, aber es ist eine Abweichung von der
  ursprünglich angestrebten vollständigen Trennung, die dokumentiert und getestet werden musste,
  statt sie stillschweigend zu übergehen.
- Die Rollback-Pfade dieser Migration sind entsprechend komplexer (Mitgliedschaft erneut gewähren,
  Eigentümerschaft zurückübertragen, `ALTER DEFAULT PRIVILEGES`-Eintrag entfernen, Mitgliedschaft
  wieder entziehen) und in ihrer Reihenfolge fehleranfälliger als ein einfaches `GRANT`/`REVOKE`.
- #426 muss über die reine "kein Superuser"-Forderung hinaus auch beschreiben, dass ein
  gehärtetes Deployment `CREATEROLE` nicht dauerhaft am Laufzeitkonto belassen sollte — das ist in
  diesem PR als Kommentar an #426 nachgetragen, aber die Umsetzung selbst (getrenntes
  Migrationskonto) ist weiterhin nicht Teil von #391.
