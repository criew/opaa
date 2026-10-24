# ADR-0058: Ereignisvertrag, Aufbewahrung und Integrität des Revisionsprotokolls

## Status

Akzeptiert · zuletzt geändert 2026-10-25

## Kontext

Das Revisionsprotokoll (`audit_log`) hält fest, was Zugriff verändert oder Verwaltungshandeln ist
([Sicherheit und Compliance](../features/security-and-compliance.md#revisionssicheres-protokoll)).
Die Ablage gehört der Rolle `opaa_audit_owner`, das Anwendungskonto fügt an und liest, entfernt wird
nur eine abgelaufene Monatspartition ([ADR-0015](0015-eigentuemertrennung-protokollablage.md)); die
Ausleitung an ein Sicherheitsmonitoring sendet die Einträge ohne Pseudonyme
([ADR-0055](0055-ausleitung-an-ein-sicherheitsmonitoring.md)); OPAA läuft mit einer oder mehreren
Instanzen ([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md)).

Drei Fragen waren damit noch nicht entschieden, und eine Prüfung stellt sie zuerst: Welche
Ereignisse schreibt OPAA und nach welchem Maßstab, sodass eine Prüferin weiß, was sie im Protokoll
erwarten darf und was nicht? Wie lange bleibt welches Ereignis, und wie bleibt die Datenbank dabei
in ihrer Größe beherrschbar? Und wie belegt eine Prüferin, dass ein Zeitraum des Protokolls
vollständig und unverändert ist, ohne selbst an die Datenbank zu gehen? Die Grenze der ersten Stufe
war ausgesprochen: Wer mit Datenbankrechten an der Anwendung vorbei schreibt, fiel nicht auf.

Die Spezifikation führt 144 Ereignisarten in `AuditEventType`, gewachsen über viele Vorhaben; ob
jede den Maßstab trifft, war nicht geprüft. Eine Aufbewahrung je Ereignisart wurde gefordert, die
Ablage ist aber nur nach Zeit unterteilt. Eine Prüfsummenverkettung stand als nächste Stufe offen.

## Entscheidung

### 1. Der Ereignisvertrag: ein Maßstab, eine geschlossene Liste

Ein Ereignis steht im Protokoll genau dann, wenn es einen Zugriff verändert (Rechte, Freigaben,
Mitgliedschaften, Konten, Zugangsmittel) oder Verwaltungshandeln ist (Einstellungen, Fristen,
Arbeitsmittel, Zugriffe auf Protokolldaten). Nutzung, Lesen von Inhalten und Fehlversuche stehen
nicht darin. Die geschlossene Liste ist `AuditEventType`; jede Art ist im Register der
persistierten Aufzählungen als lesbar geführt, trägt im Fakten-Manifest (`audit-log.yaml`) Anlass
und Felder und ihre Schlüssel in `after`/`before` in `AuditSelfDisclosurePayload`, sonst scheitert
der Build. Jeder Satz trägt die Felder des Protokollsatzes der Spezifikation; `event_id` und
`recorded_at` entstehen in der Transaktion des Eintrags auf der Datenbankuhr.

Alle 144 Arten wurden gegen den Maßstab geprüft und bleiben. Die Grenzfälle und ihr Grund:
`BRANDING_SETTINGS_CHANGED` belegt den Hinweis nach Art. 50 KI-Verordnung als Verwaltungshandeln;
`EMBEDDING_REINDEX_COMPLETED` ist der Moment, in dem die Suche auf ein anderes Modell umschaltet, und
damit eine Änderung dessen, was Personen sehen; `DOCUMENT_METADATA_CHANGED` ist der Abgleich nach
einer Wiederherstellung, der Sichtbarkeit verändert; die `PROMPT_*`-Arten sind Verwaltungshandeln an
Arbeitsmitteln, die Antworten für andere prägen.

### 2. Eine systemweite Aufbewahrungsfrist, keine je Ereignisart

Für jede Ereignisart gilt dieselbe Frist: `audit_retention_settings.retention_months`, 12 bis 120
Monate, Vorgabe 36, geändert nur über das protokollierte Verwaltungshandeln
`AUDIT_LOG_CONFIGURATION_CHANGED`. Das ist die Antwort auf „Aufbewahrung je Ereignisart“: jede Art
hat eine festgelegte Frist, und es ist für alle dieselbe. Eine Frist je Art hieße, einzelne Sätze
zu löschen; das Anwendungskonto kann das nicht und soll es nicht können (ADR-0015), die Ablage ist
nur nach Zeit unterteilt, eine Installation dient einer Behörde, und Personalrat wie Prüferin
brauchen eine Zahl statt einer Tabelle. Die Grenzen 12 und 120 Monate setzt das Produkt, nicht die
Konfiguration. Die Freigaben der anlassbezogenen Klärung sind kein Teil des Protokolls und haben
ihre eigene Frist.

### 3. Das Wachstum der Datenbank

Das Protokoll wächst bis zur Frist und bleibt dann in seiner Größe ungefähr gleich: Es ist nach
Monaten partitioniert, `opaa_audit_delete_expired_partitions()` entfernt am 1. jedes Monats jede
Partition, die vollständig vor der Frist liegt, und schreibt den erreichten Stand (`last_cutoff`)
selbst. `AuditStorageMetrics` zeigt Größe, Alter des ältesten Monats, Frist, Zeit seit dem letzten
Lauf und den Horizont der angelegten Monate; der Alarm `OpaaAuditRetentionOverdue` des Helm-Charts
meldet einen ausgebliebenen Lauf. Eine Archivierung außerhalb der Datenbank gibt es nicht; wer
länger aufbewahren muss, wählt die Frist, bis zur Obergrenze.

### 4. Integrität: Prüfsummenkette je Monat mit Siegel

Jeder Protokollmonat trägt eine Kette von Prüfsummen, nachgezogen von `AuditChainService` alle zehn
Minuten unter der Sperre `audit-chain` (ADR-0053, Entscheidung 7), nicht im Schreibweg des
Protokolls. Verkettet wird in der Reihenfolge `(recorded_at, event_id)` und nur, was unter der
Grenze liegt, unter der jede Transaktion abgeschlossen ist (`AuditSettledLimit`, dieselbe Grenze wie
in ADR-0055, Entscheidung 5). Der Anfang ist `h0 = SHA-256("OPAA-AUDIT-CHAIN-1" LF "JJJJ-MM")`, jedes
Glied `h_i = SHA-256(h_(i-1) || kanonische Form des Eintrags)`; die kanonische Form nimmt jede der
16 Spalten von `audit_log` in fester Reihenfolge, NULL als ein Byte `0x00`, sonst `0x01`, die Länge
als 32 Bit und den Wert (uuid als 16 Bytes, `recorded_at` als Mikrosekunden seit 1970 in 8 Bytes,
alles andere als UTF-8), umgesetzt in `AuditChainHasher`. Das Verfahren steht als Name in jeder
Zeile der Kette; eine neue Spalte des Protokolls ist ein neues Verfahren, kein geändertes, damit
versiegelte Monate prüfbar bleiben.

Je Stapel von höchstens 2000 Einträgen entsteht ein Kontrollpunkt in `audit_chain_checkpoints`
(Monat, Position, Schlüssel des letzten Eintrags, Kopf-Prüfsumme); der Primärschlüssel `(month,
position)` ist die Marke gegen einen zweiten Lauf. Ist ein Monat zu Ende und bis zum Ende verkettet,
versiegelt ihn der Lauf: eine Zeile in `audit_chain_seals` und in derselben Transaktion der Eintrag
`AUDIT_LOG_MONTH_SEALED` (`SYSTEM_PROCESS`, Objekt `AUDIT_LOG`, Monat als Kennung) mit Monat,
Anzahl und Kopf-Prüfsumme in `after`. Dieser Eintrag liegt im Folgemonat und wird dort selbst
verkettet, sodass die Monate ineinandergreifen; die Ausleitung sendet ihn mit der Prüfsumme als
eigenes Feld (ADR-0055, Entscheidung 2). Beide Tabellen gehören `opaa_audit_owner`, das
Anwendungskonto fügt an und liest; `opaa_audit_delete_expired_partitions()` entfernt mit einer
Partition deren Kontrollpunkte und Siegel. Die Grenze bleibt ausgesprochen: Wer die Datenbank
beherrscht, kann Einträge ändern und die Kette neu rechnen. Erkennbar wird das an dem Siegel, das
die Datenbank verlassen hat, im Sicherheitsmonitoring oder in einem anderen System, das die
Siegel-Einträge aufbewahrt.

### 5. Der Prüfweg der Revision

`GET /api/v1/audit/chain/{JJJJ-MM}` rechnet die Kette eines Monats in einem Lesesnapshot nach und
vergleicht sie mit jedem Kontrollpunkt und dem Siegel (`AuditChainService#verify`). Die Antwort
nennt `INTACT`, `BROKEN` oder `UNCHAINED`, Anzahl der verketteten und der noch unverketteten
Einträge, Kopf-Prüfsumme und Siegel-Eintrag und bei einem Bruch die erste Abweichung: Position, ob
ein Eintrag geändert (`CHANGED`) oder eingefügt beziehungsweise entfernt (`INSERTED_OR_REMOVED`)
wurde, und das Zeitsegment zwischen den Kontrollpunkten, in dem sie liegt. Kein Inhalt eines
Eintrags verlässt dabei das Protokoll. Der Weg läuft durch `AuditQueryService` wie jeder andere:
Rolle AUDITOR, Anlass als Pflichtfeld, ein Eintrag `AUDIT_LOG_ACCESSED` mit `accessPath:
chain-verification` je Aufruf, auch beim abgewiesenen. Die Prüferin belegt die Vollständigkeit
eines Monats mit dieser Antwort und der Kopf-Prüfsumme aus dem Sicherheitsmonitoring, ohne
Datenbankzugang. Die Metriken `opaa.audit.chain.lag` und `opaa.audit.chain.unsealed.months`
zeigen, wie weit die Kette hinterherläuft; der Alarm `OpaaAuditChainStalled` meldet einen Stillstand.

## Konsequenzen

- Einfacher: Eine Prüfung bekommt für jede ihrer drei Fragen eine Stelle: die geschlossene Liste
  mit Maßstab, die eine Frist, den Prüfweg je Monat. Eine Änderung mit Datenbankrechten fällt auf,
  sobald das Siegel die Datenbank verlassen hat.
- Schwieriger: Eine neue Spalte von `audit_log` ist ein neues Verfahren der Kette und braucht
  Verketter und Prüfer für beide. Ein Monat, in den nachträglich ein Eintrag geschrieben wird
  (etwa ein Test, der `recorded_at` zurücksetzt), ist danach gebrochen; das ist gewollt.
- Restrisiken: Zwischen Eintrag und Kontrollpunkt liegen bis zu zehn Minuten, in denen eine
  Änderung unbemerkt bleibt; ein Siegel, das kein System außerhalb der Datenbank aufbewahrt, ist nur
  so vertrauenswürdig wie die Datenbank. Die Kette liest jede Spalte des Protokolls; sie gibt keine
  heraus, und `AuditLogReaderStructureTest` hält die Leser geschlossen.

## Verworfene Alternativen

- Prüfsumme im Schreibweg, jede Zeile mit der Prüfsumme der vorigen — jeder Schreiber bräuchte die
  letzte Zeile, alle Instanzen schrieben hintereinander, und das Anwendungskonto könnte die Kette
  selbst fortschreiben.
- Signatur je Eintrag mit einem Schlüssel der Anwendung — ein kompromittiertes Anwendungskonto
  signierte, was es will; ein Hardware-Schlüssel ist für Behörden ohne eigenes HSM keine Vorgabe.
- Eine Kette über die Lebenszeit der Installation — der Wegfall einer abgelaufenen Partition risse
  sie; je Monat endet die Kette mit der Partition.
- Spalte für die Prüfsumme in `audit_log` — änderte die Tabelle, die nur anfügbar bleiben soll, und
  verlangte ein Schreibrecht auf bestehende Zeilen.
- Frist je Ereignisart — ein Löschen einzelner Sätze durch die Anwendung, siehe Entscheidung 2.
- Zeitstempeldienst (RFC 3161) für jedes Siegel — eine weitere Abhängigkeit nach außen; das
  Sicherheitsmonitoring hält das Siegel schon außerhalb der Datenbank fest.

## Offen

- Der Ereigniskatalog mit `schema_version` je Satz ([#3118](https://github.com/criew/opaa/issues/3118))
  ergänzt Entscheidung 1 um die Version des Protokollsatzes; mit ihm wird `schema_version` Teil
  eines neuen Verfahrens der Kette.
- Eine Ansicht des Prüfergebnisses in der Oberfläche; bis dahin der Weg über die Schnittstelle.
- Ein zweiter Ort für die Siegel außerhalb des Sicherheitsmonitorings, etwa eine Ablage der Behörde,
  die den Eintrag `AUDIT_LOG_MONTH_SEALED` aufbewahrt.
