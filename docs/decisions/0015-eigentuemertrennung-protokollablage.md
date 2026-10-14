# ADR-0015: Eigentümertrennung der Protokollablage

## Status

Akzeptiert · zuletzt geändert 2026-10-04 · ergänzt durch ADR-0041 (Entscheidung 7)

## Kontext

Das revisionssichere Protokoll `audit_log` (ADR-0014 Entscheidung 13,
[security-and-compliance.md](../features/security-and-compliance.md#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen))
verlangt, dass das Anwendungskonto nur anfügen und lesen kann und keine Rechte am Schema der Tabelle
hat, auch nicht über eine eingeschleuste Anweisung. Ein bloßes
`REVOKE`/`GRANT` gegen das Anwendungskonto reicht dafür nicht: Wer eine Tabelle **besitzt**, kann
sich Rechte jederzeit zurückgewähren, `CHECK`-Constraints entfernen, Partitionen abhängen und die
Tabelle löschen; Eigentümerschaft umfasst die volle DDL-Hoheit und lässt sich nicht entziehen.
Migrations- und Laufzeitkonto sind in OPAA dieselbe Datenbankrolle, das Anwendungskonto ist also
Ersteller und damit ursprünglicher Eigentümer jeder Tabelle. Zudem trägt jede Partition einer
partitionierten Tabelle eine eigene ACL; ein `REVOKE` nur an der Elterntabelle schützt nicht vor
`UPDATE audit_log_2026_08 …`.

## Entscheidung

### 1. Eine dedizierte Eigentümerrolle für jedes Protokoll

Eine nicht anmeldefähige Rolle `opaa_audit_owner` (`NOLOGIN`) besitzt die Protokolltabellen, jede
ihrer Partitionen, die zugehörigen Einstellungstabellen und die Löschfunktionen der Aufbewahrung. Das
Anwendungskonto erhält auf den Protokolltabellen nur `INSERT, SELECT`, auf den Einstellungstabellen
`SELECT` und ein enges `UPDATE`; Rechte werden an der Elterntabelle vergeben, nie an einzelnen
Partitionen, und `ALTER DEFAULT PRIVILEGES FOR ROLE opaa_audit_owner` gäbe Partitionen, die die
Rolle selbst anlegt, dieselbe Grant-Menge; heute legt die Baseline die Partitionen mit festem
Horizont an, eine Nachprovisionierung zur Laufzeit gibt es nicht. Das Modell gilt für drei Protokolle: `audit_log` mit `audit_actor_pseudonyms`
und `audit_retention_settings` (Modul identity), `diagnostic_context_log` mit seinen Einstellungen
(Modul workspace, seit #1052) und `connection_log` mit seinen Einstellungen (Modul connections,
seit #2163, ADR-0041 Entscheidung 7). Ein künftiges Protokoll nutzt dieselbe Rolle.

### 2. Rechte-Choreographie jeder DDL an einem Protokoll

Jede Migration, die eine dieser Tabellen anlegt oder ändert, läuft als Dreischritt: `GRANT
opaa_audit_owner TO current_user WITH SET TRUE`, die Anweisungen als Eigentümer (`ALTER TABLE … OWNER
TO`, `ALTER TABLE`, `ALTER FUNCTION … OWNER TO`), danach `REVOKE opaa_audit_owner FROM current_user`.
Das Migrationskonto braucht dafür `CREATEROLE` (dann legt die Migration die Rolle selbst an) oder eine
vorab angelegte Rolle, an der es `ADMIN OPTION` hält, dazu `USAGE, CREATE` auf dem Schema mit
Weitergaberecht; beide Wege lassen den Rest aus Entscheidung 3 bestehen. Die
Betriebsanleitung steht in [datenbank.md](../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql).
Die Rolle gehört zur PostgreSQL-Instanz, nicht zur Datenbank: Eine Sicherung wird nur in eine Instanz
zurückgespielt, in der die Rolle existiert, und zwei OPAA-Installationen mit verschiedenen Konten
teilen sich keine Instanz.

### 3. Restrisiko: der nicht entziehbare `ADMIN OPTION`-Rest

`CREATE ROLE` gewährt dem anlegenden `CREATEROLE`-Konto automatisch `ADMIN OPTION` auf die neue Rolle,
mit der Bootstrap-Identität als Grantor. Ein vom Anwendungskonto ausgeführtes `REVOKE` entfernt diesen
Eintrag nicht. Mit zwei Anweisungen (`GRANT opaa_audit_owner TO <konto> WITH SET TRUE; DELETE FROM
audit_log;`) hebt das Anwendungskonto die Eigentümertrennung deshalb wieder auf, solange das Anwendungskonto
`ADMIN OPTION` an der Rolle hält, sei es automatisch durch `CREATE ROLE` oder durch eine vorab
angelegte Rolle. Die Append-only-Zusage auf Datenbankebene gilt damit vollständig
erst, wenn `opaa_audit_owner` von einer Identität provisioniert wird, die dem Anwendungskonto nie
`ADMIN OPTION` an ihr gibt, was ein vom Laufzeitkonto getrenntes Migrationskonto voraussetzt. Bis
dahin ist das Abnahmekriterium „keine Rechte am Schema der Tabelle“ gegen eine eingeschleuste
Anweisung nicht erfüllt. Der Schutz ist gegenüber dem Ausgangszustand dennoch eine deutliche
Verbesserung:
Statt eines unauffälligen `UPDATE` braucht es eine ungewöhnliche, im Systemkatalog sichtbare
`GRANT … WITH SET TRUE`-Anweisung. Eine Rollentrennung von Migration und Laufzeit (ehemals #426) ist
vom Maintainer zurückgestellt; die Härtung gilt als offen ohne Ticket.

## Konsequenzen

**Einfacher:** Rechte zurückgewähren, Constraints entfernen, Partitionen abhängen und Tabellen
löschen scheitern für das Anwendungskonto im Normalzustand; direkte Partitionszugriffe sind so
wirkungslos wie Zugriffe auf die Elterntabelle; neue Partitionen erben den Schutz.

**Schwieriger:** Das Migrationskonto braucht dauerhaft mehr Rechte als ein reines Tabellenkonto, und
jede Protokoll-DDL trägt den Dreischritt aus Entscheidung 2.

**Restrisiken:** Entscheidung 3: Wer beliebiges SQL über das Anwendungskonto ausführen kann, kann
auch die zwei Anweisungen ausführen; gegen diesen Angreifer trägt die Zusage heute nicht. Eine
Manipulation über direkten Datenbankzugang fällt ohne Prüfsummenverkettung nicht auf; dieser Zugang
wird betrieblich beschränkt und außerhalb von OPAA nachgewiesen.

## Verworfene Alternativen

- `REVOKE`/`GRANT` gegen das Anwendungskonto ohne Eigentümerwechsel — der Eigentümer gewährt sich
  jedes Recht zurück.
- Rechte je Partition statt über die Elterntabelle und Default-Privilegien — jede neue Partition
  müsste erneut bedacht werden.

## Offen

- Getrenntes Migrationskonto und Provisionierung von `opaa_audit_owner` durch eine Identität, die
  dem Laufzeitkonto nie `ADMIN OPTION` gibt, damit der Rest aus Entscheidung 3 entfällt
  (zurückgestellt, kein Ticket).
