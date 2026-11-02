# Sicherung und Wiederherstellung

Dieses Kapitel beschreibt, was eine Installation sichern muss, was beim Zurückspielen zu beachten
ist und wie eine Wiederherstellungsprobe belegt, dass beides funktioniert. Gesichert wird nur, was
Zustand hält: Datenbank, Originalablage und Geheimnisse. Das Kapitel gehört zum Teil Betrieb des
[Handbuchs](../README.md); die Bausteine einer Installation beschreibt die
[Betriebsübersicht](README.md). Befehle: [Docker Compose](docker-compose.md#sicherung-und-wiederherstellung),
[Kubernetes](kubernetes.md#sicherung-und-wiederherstellung).

## Sicherung

| Bestandteil | Gesichert durch |
|---|---|
| Datenbank | `pg_dump` oder die Sicherung des Datenbankbetriebs |
| Originalablage | Kopie, Snapshots oder Replikation; die Versionierung des Buckets nicht, denn ein Löschen in OPAA entfernt alle Versionen ([Originalablage](originalablage.md#was-im-objektspeicher-liegt)) |
| Geheimnisse | Kopie außerhalb der Installation, vor allem `OPAA_CREDENTIALS_ENCRYPTION_KEY`, `OPAA_SETTINGS_ENCRYPTION_KEY` und `OPAA_AUTH_JWT_SECRET` |
| Konfiguration (`.env.docker` bzw. Werte-Datei) | Versionsverwaltung |

- **Reihenfolge:** erst Datenbank, dann Originale sichern; umgekehrt wiederherstellen
  ([Originalablage](originalablage.md#sicherung-und-wiederherstellung)). Ein genau passender Stand
  entsteht bei angehaltenem Backend.

## Wiederherstellung

- **Vor dem Einspielen** müssen auf dem Ziel die Erweiterung `vector` und die Rolle
  `opaa_audit_owner` existieren ([Datenbank](datenbank.md#voraussetzungen-einer-eigenen-postgresql)).
- **Nach jeder Rücksicherung** dieselben Verschlüsselungsschlüssel, aber ein neues
  `OPAA_AUTH_JWT_SECRET`
  ([Nacharbeit](authentifizierung.md#nacharbeit-nach-einer-rücksicherung-der-datenbank)).
- **Von Hand gesetzte Metadaten abgleichen**, wenn die Datenbank auf einen älteren Stand zurückgeht
  als das Revisionsprotokoll. Nur diese Werte erzeugt kein erneuter Lauf; liegt das Protokoll in
  derselben Sicherung, ist nichts zu tun. Ablauf im folgenden Abschnitt.

### Manuelle Metadatenwerte abgleichen

Jede manuelle Setzung, Änderung und Löschung eines
[Metadatenwerts](../arbeitsmittel/wissensbibliotheken/metadaten.md#7-manuelle-pflege) schreibt das
Ereignis `DOCUMENT_METADATA_CHANGED`. Je Bibliothek:

1. Ereignisse ab dem Wiederherstellungspunkt lesen: Objekttyp `KNOWLEDGE_LIBRARY`, Objekt-ID der
   Bibliothek, über
   `GET /api/v1/audit/events/by-object?objectType=KNOWLEDGE_LIBRARY&objectId=…&from=…&to=…&reason=…`
   (Rolle `AUDITOR`, höchstens 92 Tage je Abfrage, der Zugriff wird protokolliert) oder per SQL auf
   `audit_log`.
2. In Aufzeichnungsreihenfolge (`recorded_at`, dann `event_id`) den letzten Zustand je Dokument und
   Feld bestimmen. `after` trägt den Zielzustand (`documentId`, `fieldKey`, `state` `SET` mit `value`
   und gegebenenfalls `datePrecision`, `NOT_DETERMINABLE` oder `EMPTY`); `before` dient nur zur
   Plausibilisierung.
3. Anwenden über die Korrektur-API
   `PUT /api/v1/libraries/{libraryId}/documents/{documentId}/metadata/{fieldKey}` bei `SET` und
   `NOT_DETERMINABLE` (dann ohne Wert), `DELETE` bei `EMPTY`, als Person mit Bearbeitungsrecht. Der Abgleich erzeugt so selbst Ereignisse.
   Dokumente, die es nach der Rücksicherung nicht mehr gibt, werden übersprungen.

Direkte `INSERT`s in `document_metadata_values` sind kein Ersatz: Sie ziehen die Chunk-Schlüssel nicht
nach und hinterlassen kein Ereignis. Bei [privaten Bibliotheken](../arbeitsmittel/wissensbibliotheken/private-bibliotheken.md)
nennt das Ereignis nur Feld und Zustand, nicht den Wert; dort zeigt der Abgleich nur, welche Felder
die Person neu setzen muss.

## Wiederherstellungsprobe

Die Probe belegt, dass Datenbank, Index und Originale aus einer Sicherung passend zurückkommen, und
misst Wiederherstellungszeit (RTO) und Wiederherstellungspunkt (RPO). Befehle für einen Testcluster:
[Kubernetes](kubernetes.md#wiederherstellungsprobe-auf-einem-testcluster); andere Betriebswege
folgen demselben Ablauf.

- In eine zweite, **leere** Installation zurückspielen, nie in die laufende.
- Dieselbe Version, dasselbe Embedding-Modell, dieselben Verschlüsselungsschlüssel und dasselbe
  Datenbankpasswort, aber ein neues `OPAA_AUTH_JWT_SECRET`.
- Testbestand statt Echtdaten; eine Kopie echter Daten wie Produktivdaten behandeln und danach
  löschen.

**Testbestand** in der Quelle:

| Nr. | Objekt | Zweck |
|---|---|---|
| T1 | Upload-Bibliothek mit mindestens drei einfachen Dateien (etwa PDF, DOCX, TXT), ohne Archive und E-Mails mit Anhängen | Originale und Index |
| T2 | in einer dieser Dateien der Satz „Probe-Marker vor der Sicherung: [zufälliges Wort M1]“ | belegte Antwort |
| T3 | ein Space mit zwei Mitgliedern, T1 zugeordnet | Rechte und Mitgliedschaften |
| T4 | eine Testperson ohne Zugriff auf T1 | Rechteprobe |
| T5 | ein Chat mit einer Frage nach M1 | Chats |
| T6 | ein zweiter, nicht aktiver Chat-Modell-Eintrag auf denselben Endpunkt mit beliebigem Zugangsschlüssel; „Verbindung testen“ gelingt | Schlüssel der Einstellungen (P11) |
| T7 | eine Bibliothek mit Webquelle und Zugangsdaten für die Basic-Anmeldung | Schlüssel der Zugangsdaten (P12) |

**Ablauf** (Zeitpunkte fortlaufend notieren):

1. **Ausgangsstand:** Version, Image-Digests, Embedding-Modell, Zahl der Dokumente in T1. Als T4 oder
   Mitglied von T3 angemeldet bleiben (für P10).
2. **Sichern (`tS`):** Backend anhalten, Zeilen zählen (Abfrage unten), Datenbank sichern,
   Prüfsummenliste der Originale erzeugen, Originale sichern, Backend starten.
3. **Datenverlust erzeugen (`tA`):** in T1 eine Datei mit „Probe-Marker nach der Sicherung:
   [zufälliges Wort M2]“ hochladen und einen Chat dazu führen.
4. **Wiederherstellen (ab `t0`):** Ziel mit angehaltenem Backend aufbauen, Originale zurückspielen
   und prüfsummen (`t1`), Rolle `opaa_audit_owner` anlegen, Datenbank zurückspielen (`t2`), Zeilen
   **vor** dem Start zählen, Backend mit neuem Signaturgeheimnis starten bis zur Readiness (`t3`),
   P4 bis P12 prüfen (`t4`).

Zeilenzählung über alle Tabellen, unabhängig von der Version:

```sql
SELECT format('%I.%I', n.nspname, c.relname) AS tabelle,
       (xpath('/row/n/text()',
              query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname),
                           false, true, '')))[1]::text AS zeilen
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg\_toast%'
 ORDER BY 1;
```

| Nr. | Prüfung | Erwartet |
|---|---|---|
| P1 | `pg_restore --exit-on-error` | Rückgabewert 0 |
| P2 | Zeilenzählung Quelle gegen Ziel | gleich für jede Tabelle |
| P3 | Prüfsummenlisten der Originale | gleich |
| P4 | Version; Zeilen in `flyway_schema_history` vor und nach dem Start | Version wie in Schritt 1; Zahl unverändert ([Rückweg](kubernetes.md#rückweg)) |
| P5 | Seite „Suche & Indexierung“; Health Groups `embedding-model` und `vector-store` | gleiches Embedding-Modell, kein „Index veraltet“; beide `UP` |
| P6 | Bericht verwaister Originale für T1 ([Originalablage](originalablage.md#originale-einer-vorhandenen-bibliothek)) | keine verwaisten; so viele referenzierte Originale wie Dokumente |
| P7 | Anmeldung, Space T3, Frage nach M1, „Original öffnen“ | belegte Antwort aus T1, Original mit gleicher Prüfsumme, Chat T5 vorhanden |
| P8 | T4 fragt nach M1 | keine Fundstelle aus T1 |
| P9 | Frage nach M2, Chatliste | M2 nicht auffindbar, Chat aus Schritt 3 fehlt |
| P10 | Sitzung aus Schritt 1 gegen das Ziel | ungültig, neue Anmeldung nötig |
| P11 | T6: „Verbindung testen“ ohne neuen Schlüssel | gelingt wie in der Quelle |
| P12 | T7: „Verbindung testen“ ohne neue Zugangsdaten | Zugangsdaten gelten als hinterlegt, Test gelingt wie in der Quelle |

**Messung:** RTO der Probe ist `t4 − t0`, RPO `tA − tS` (im Betrieb höchstens der Abstand zweier
Sicherungen). Größe von Datenbank und Originalen gehört zum Ergebnis; nur eine Probe mit
Produktivmenge belegt die echte Wiederherstellungszeit. Danach Ziel und Testsicherungen löschen.
