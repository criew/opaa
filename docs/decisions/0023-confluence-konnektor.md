# ADR-0023: Confluence-Konnektor — Space-Auswahl, Editionen, Zugangsdaten und Löschsemantik je Lauf

## Status

Akzeptiert · zuletzt geändert 2026-10-12 · ergänzt ADR-0017 (Entscheidung 5) und ADR-0022 · Ablage der Einstellungen geändert durch ADR-0038

## Kontext

Confluence war die erste Quelle gegen ein Fremdsystem mit eigener Rechteverwaltung, eigenem
Versionsbegriff und zwei nicht deckungsgleichen REST-APIs: Cloud (`/wiki/api/v2`, HTTP Basic aus
E-Mail und API-Token, Cursor-Paginierung, v1-Inhaltsendpunkte abgeschaltet) und Data Center
(`/rest/api`, Bearer-PAT, Offset-Paginierung, `expand`). Drei Lücken der Grundlage aus
[ADR-0017](0017-quellentypmodell-indizierung.md) und [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md):

1. Die Konfiguration hat erstmals einen Listenwert (die Space-Auswahl).
2. Die Edition bestimmt API und Form der Zugangsdaten und muss vor deren Eingabe feststehen.
3. Ein günstiger Änderungsabgleich kann Löschungen nicht sehen; ADR-0017 kennt die Löschsemantik
   aber nur je Quellentyp, nicht je Lauf.

Vom Maintainer vorgegebener Rahmen: *eine* Bibliothek für alle ausgewählten Spaces (die Freigabe
der Bibliothek gilt für alle Spaces gemeinsam; getrennte Leserkreise brauchen getrennte
Bibliotheken), beliebig viele Confluence-Bibliotheken auch gegen dieselbe Instanz, beide Editionen
von Anfang an. Drei Fallen der Hersteller-APIs binden die Umsetzung: Die E-Mail ist bei Cloud Teil
der Zugangsdaten; CQL kappt mit `expand=body.*` still bei 50 Treffern, deshalb liefern Suche und
Auflistung nur Kennungen und Inhalte werden einzeln geholt; die Zieladressprüfung
(`opaa.indexing.target-validation`) lehnt private Adressbereiche ab, und die Fehlermeldung muss den
Allowlist-Eintrag nennen.

## Entscheidung

### 1. Die Space-Auswahl ist ein Listenwert der Quellkonfiguration

Eine Confluence-Bibliothek trägt mindestens einen und höchstens 500 Spaces
(`ConfluenceSourceConnector.MAX_SPACES`), je Space Schlüssel und Anzeigename; eine Anlage ohne
Auswahl wird abgelehnt. Seit #1977 liegt die Auswahl als Teil des Konnektor-Objekts
`ConfluenceSourceSettings` in `knowledge_libraries.source_settings` (ADR-0038, Entscheidung 2),
nicht mehr in einer Kindtabelle. Die Auswahl ist nach der Anlage als Ganzes ersetzbar; jede
Änderung erzwingt als nächsten Lauf einen Vollabgleich (Entscheidung 4), weil nur er abgewählte
Spaces aus dem Index nimmt. Jedes Dokument trägt Space-Schlüssel und Gliederungspfad
(`documents.source_container_key`, `source_hierarchy_path`) für Beleg, Laufprotokoll und
Chunk-Kontext, ohne die Instanz zu fragen; eine Abbildung der Seitenhierarchie auf Ordner
(ADR-0020) findet nicht statt.

### 2. Die Edition wird erkannt, steht an der Bibliothek und ist danach unveränderlich

Die Edition (`ConfluenceEdition`: `CLOUD`, `DATA_CENTER`) steht seit #1977 in
`source_settings`; ein Zugang (ADR-0041) darf sie als Vorgabe tragen (`DefaultKey` der
`ProfileDeclaration`), dann steht sie an der Bibliothek nur lesbar. Die gespeicherte Basisadresse
ist die Confluence-Wurzel — Cloud ohne `/wiki` (der Adapter hängt es an), Data Center samt
Kontextpfad —, normalisiert (Schema und Host klein, kein abschließender Schrägstrich, kein
`userinfo`).

`ConfluenceEditionDetector` erkennt die Edition ohne Zugangsdaten aus dem Antwortverhalten:
`GET <Host-Wurzel>/_edge/tenant_info` mit `cloudId` ist Cloud; sonst `GET <Basis>/status`
(`{"state": …}`) bzw. `GET <Basis>/rest/api/space` im Data-Center-Fehlerformat; trifft nichts zu,
ist die Adresse kein Confluence (auch hinter einem SSO-Reverse-Proxy, der jede Sonde auf eine
Anmeldeseite umleitet). Keine Erkennung über den Hostnamen (`*.atlassian.net`): falsch für Cloud
hinter eigener Domain, aussagelos für Data Center. Die Anlage prüft die übernommene Edition erneut
gegen die Instanz; Erkennung und Space-Auflistung machen die Anlage netzabhängig. Nach der Anlage
ist die Edition wie der Quellentyp unveränderlich (ADR-0018, Entscheidung 1): Eine migrierte
Instanz trägt andere Kennungen, URLs und Versionen und bekommt eine neue Bibliothek.

Die Zugriffsschicht ist ein Port mit zwei Adaptern (`ConfluenceClient`; `CloudConfluenceClient`,
`DataCenterConfluenceClient`), aufgelöst über die gespeicherte Edition, mit gemeinsamem
Zwischenmodell (`ConfluenceSpace`, `ConfluencePage`, `ConfluenceAttachment`); kein nachgelagerter
Schritt unterscheidet die Edition. Eine Unterscheidung im Adapter: Data Center weist ein
ungültiges Token nicht ab, sondern antwortet anonym mit 200 — jeder Lauf und jeder Verbindungstest
liest deshalb zuerst das angemeldete Konto und wertet einen anonymen Benutzer als abgelehntes Token,
weil eine anonyme leere Auflistung sonst von einer vollständigen nicht zu unterscheiden wäre. Beide
Adapter werden gegen dasselbe Testdoppel abgenommen; die Container-Suite gegen ein echtes Data
Center (`confluenceIntegrationTest`, #1171) ist eine zusätzliche Ebene.

### 3. Zugangsdaten je Edition in der bestehenden verschlüsselten Spalte

Confluence-Zugangsdaten liegen in `knowledge_libraries.source_credentials`, ruhend verschlüsselt
über `SourceCredentialsConverter`/`CredentialsEncryptor` (AES-256-GCM,
`OPAA_CREDENTIALS_ENCRYPTION_KEY`), angenommen über das nur-schreibende Feld `sourceCredentials`.
Das Format hängt von der Edition ab (`ConfluenceCredentials#parse`): Cloud `<E-Mail>:<API-Token>`
(HTTP Basic, zerlegt am ersten Doppelpunkt; die Oberfläche erfasst zwei Felder), Data Center das
nackte PAT als `Bearer` (ein Doppelpunkt wird als irrtümliches Cloud-Paar zurückgewiesen). Weil
das Feld nur schreibbar ist, zeigt die Oberfläche auch die E-Mail nicht mehr an — in Kauf
genommen, um keinen zweiten Ablageweg zu eröffnen.

Zugangsdaten erscheinen in keiner API-Antwort, keinem Log, keiner Exception-Message und keiner
Fehlermeldung, auch nicht in verschachtelten Ursachen: `ConfluenceCredentials` gibt das Geheimnis
nur über `authorizationHeader()` heraus, `toString()` nie; ein Test der Zugriffsschicht prüft, dass
keine ihrer Exceptions das Token enthält. Die Ursprungsbindung der Zugangsdaten (ADR-0018,
Entscheidung 4; seit ADR-0041 über `credentialBinding`) gilt; das Rotieren eines Tokens ist eine
gewöhnliche Konfigurationsänderung.

### 4. Löschsemantik je Lauf, nicht je Quellentyp

Confluence hat zwei Betriebsarten: Der **Vollabgleich** (`FULL`) listet alle Seiten und Anhänge
der ausgewählten Spaces und ist „vollständig auflistend"; der **inkrementelle Abgleich**
(`INCREMENTAL`) fragt per CQL (`lastModified >= Anker − incremental-overlap`) nur nach
Kennungen des Geänderten und ist „ergänzend" — eine gelöschte Seite ist dort von einer
unveränderten nicht zu unterscheiden. Deshalb deklariert jeder Executor seine Betriebsarten samt
Löschkategorie (`SourceIndexingExecutor#runModes()`: `IndexingRunMode` →
`VanishedDocumentPolicy`), ohne impliziten Standard; der Lauf trägt seine Betriebsart in
`indexing_jobs.run_mode`, und `StaleDocumentCleanupService` weist einen Aufruf aus einem
ergänzenden Lauf zurück. `FILESYSTEM` und `HTTP_DIRECTORY` kennen nur `FULL`, `RSS_FEED` nur
`INCREMENTAL`; die dritte Betriebsart `EVENT` (genau die gemeldeten Objekte, keine Auflistung)
führt ADR-0027, Entscheidung 3 für S3 ein.

**Löschung braucht einen positiven Befund der Instanz.** Der Vollabgleich liefert ihn durch die
vollständige Auflistung und ruft die Abwesenheitsbereinigung erst nach vollständiger Auflistung
aller Spaces (`ListingOutcome`); ein nicht auflistbarer Space (`403`, fehlt in der Auflistung)
oder eine nicht lesbare Anhangsliste macht sie unvollständig, der Lauf bereinigt nichts und meldet
es im Protokoll. Der Geltungsbereich bleibt „je Bibliothek und Quellentyp": Ein abgewählter Space
fehlt in der Menge und fällt weg, eine zweite Bibliothek gegen dieselbe Instanz bleibt unberührt.
Ein `404`/`403` beim Einzelabruf ist kein Befund — Rechteentzug und Token-Fehler leeren den Index
nie; ein Space ohne Leserecht bleibt indiziert, bis das Token ihn wieder liest oder eine Person ihn
abwählt, sichtbar im Laufprotokoll. Nur `status = trashed` beim Einzelabruf ist ein Befund und
entfernt die Seite samt Anhängen in jeder Betriebsart
(`StaleDocumentCleanupService#removeWithAttachments`, Auflage aus ADR-0022, Entscheidung 3). Ein
Vollabgleich, der null Dokumente antrifft, löscht nichts.

**Webhook als Anlass, nie als Befund** (#1140): Data Center signiert den Rohkörper mit dem je
Bibliothek erzeugten Geheimnis (`X-Hub-Signature`, HMAC-SHA256), eine Cloud-Automation-Regel
sendet es als `X-OPAA-Webhook-Secret` (`ConfluenceWebhookSignature`, zeitkonstante Vergleiche);
jede nicht authentifizierte Anfrage erhält dieselbe `401`. Ausgewertet werden nur Seitenkennungen,
nicht die Ereignisart. Gemeldete Seiten werden je Bibliothek gesammelt (`opaa.indexing.events.*`,
geteilt mit S3) und in einem kurzen Lauf mit Auslöser `WEBHOOK` und Betriebsart `INCREMENTAL`
einzeln geholt, der den Anker nicht bewegt; ein zu großer Stapel läuft als gewöhnlicher Lauf, ein
Stapel gegen einen laufenden Lauf wartet begrenzt und wird dann verworfen. Kein Replay-Schutz: Eine
wiedereingespielte Nachricht ändert nichts, was der Abruf nicht bestätigt.

**Betriebsarten im Zeitplan** (`ConfluenceIndexingExecutor#defaultRunMode`): Ohne ausdrückliche
Betriebsart ist ein Lauf ein Vollabgleich, wenn noch keiner abgeschlossen ist, der letzte
unterbrochen wurde, die Einstellungen sich geändert haben (`SyncStateBasis`, `DefaultRunModes`)
oder der Rhythmus abgelaufen ist — je Bibliothek `fullSyncIntervalDays` (1–365, seit #1200),
sonst `opaa.indexing.confluence.full-sync-interval` (sieben Tage); sonst inkrementell. Der
Vollabgleich ist verlängerbar, nicht abschaltbar; ein fälliger Vollabgleich, der auf einen laufenden
Lauf trifft, wird beim nächsten Tick genommen, nicht verworfen.

**Anker und Wiederaufnahme:** Der Laufzustand lebt in `SourceSyncState` je Bibliothek (Anker, Runde,
erledigte Spaces). Der Anker rückt nur nach einem Lauf vor, der keine Seite fehlschlagen ließ und
dessen Anfragebudget (`request-budget-per-run`) nichts ablehnte; ein Vollabgleich setzt nach
Budgeterschöpfung mit den offenen Spaces fort, und nach einem Vollabgleich mit Ablehnungen ist der
nächste Lauf wieder ein Vollabgleich.

**Identität:** `documents.file_path` ist die titelfreie URL aus Basisadresse und Seitenkennung
(Cloud `<Basis>/wiki/spaces/<Space>/pages/<id>`, Data Center
`<Basis>/pages/viewpage.action?pageId=<id>`), für einen Anhang die Download-Adresse mit
Anhangskennung (ADR-0022, Entscheidung 2) — zugleich der Beleg-Link (`SourceConnectorDescriptor`,
`deepLink`). Der `webui`-Link taugt nicht, weil er den Titel enthält. `file_name` trägt den
Seitentitel, `last_modified_remote` die Versionsnummer, geprüft vor dem Abruf des Inhalts. Eine bei
Cloud zwischen zwei gewählten Spaces verschobene Seite erscheint als neu, die alte wird als
positiver Befund entfernt.

### 5. Folgen des Mehrfach-Bibliotheken-Modells

Keine Eindeutigkeitsregel auf Adresse oder Token. Zugangsdaten strikt je Bibliothek, auch das
Webhook-Geheimnis (`source_webhook_secret`); ein Zugang (ADR-0041) bündelt nur Adresse und Edition,
das Token trägt weiterhin jede Bibliothek. Überlappende Space-Auswahlen indizieren doppelt — keine
Deduplizierung über Bibliotheksgrenzen, weil die Bibliotheken verschiedene Leserkreise haben.
Läufe verschiedener Bibliotheken beeinflussen einander nur über das Ratenbudget der Instanz
(`429`/`Retry-After`, je Lauf beachtet); eine instanzweite Koordination gibt es nicht. Die
Zieladressprüfung ist eine Betriebseinstellung je Adresse, keine Eigenschaft der Bibliothek.

## Konsequenzen

- **Einfacher:** Ein Typ mit mehreren Betriebsarten ist modellierbar, ohne ADR-0017 zu brechen.
  Die Löschregel ist ein Satz. Beide Editionen teilen alles oberhalb des Adapters. Zugangsdaten
  brauchen keinen neuen Weg.
- **Schwieriger:** Ein inkrementeller Lauf verschleppt Löschungen bis zum nächsten Vollabgleich,
  im Standard bis zu eine Woche; das steht im Handbuch
  ([Konnektor Confluence](../handbuch/konnektoren/confluence.md)). Die Anlage ist netzabhängig.
  Überlappende Spaces kosten doppelt. Ein Adresswechsel baut den Bestand neu auf, weil
  `file_path` die Basisadresse enthält.
- **Restrisiken:** Ein Space ohne Leserecht bleibt indiziert, bis eine Person eingreift; das
  Laufprotokoll muss das deutlich zeigen. Der Webhook hat keinen Replay-Schutz.

## Verworfene Alternativen

- Eine Bibliothek je Space über ein gemeinsames Verbindungsobjekt — Rahmenbedingung des Maintainers; das Verbindungsobjekt wäre die in ADR-0018 verworfene Konnektor-Tabelle mit eigenem Rechtemodell.
- Keine Space-Auswahl, alles indizieren, was das Token liest — der Umfang würde von der Rechteverwaltung in Confluence bestimmt, jede Token-Erweiterung weitete still den Bestand.
- Edition aus dem Hostnamen ableiten oder wählen lassen — weder notwendig noch hinreichend; eine Nutzerwahl ist Raten mit irreführender Fehlermeldung.
- Edition änderbar lassen — nach einer Migration passen Kennungen, URLs und Versionen des Bestands nicht mehr zur Instanz.
- Eigene Spalten für E-Mail und Token — zweiter verschlüsselter Pfad und zweite Ursprungsbindung für ein Format, das ohnehin `user:password` ist.
- Löschsemantik je Typ, Confluence „ergänzend" — eine gelöschte Seite verschwände nie.
- Löschsemantik je Typ, jeder Lauf ein Vollabgleich — überfordert Punktebudget und Server.
- Papierkorb-Abfrage statt Vollabgleich — sieht nicht endgültig Gelöschtes, Archiviertes, Verschobenes oder per Seitenbeschränkung Entzogenes.
- Adressunabhängige Identität (`confluence:<Space>:<id>`) — bräuchte eine zweite Spalte für den Beleg-Link; ein Umzug ist selten.
- Webhook-Lösch-Ereignisse löschen unmittelbar — ein falscher oder fremder Absender könnte den Index leeren.
- Instanzweite Ratenkoordination — geteilte Struktur je Hostname ohne gemessenen Bedarf.

## Offen

- Rechteabbildung Confluence → OPAA wäre ein eigenes Epic mit eigener Rechtequelle.
- Weitere Inhaltsarten (Blogbeiträge, Kommentare, Whiteboards, Datenbanken) und archivierte Seiten als gekennzeichneter Bestand.
- Seitenhierarchie als Bibliotheksordner — konkurrierte mit der Ordnerverwaltung aus ADR-0020.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-03 | #1139 | Vollabgleich-Rhythmus zunächst instanzweit (`full-sync-interval`) | 4 |
| 2026-09-03 | #1140 | Webhook als Anlass zum Einzelabruf, zwei Authentisierungswege | 4 |
| 2026-09-05 | #1200 | Rhythmus je Bibliothek (`fullSyncIntervalDays`, 1–365) | 4 |
| 2026-09-26 | #1977 | Space-Auswahl, Edition und Rhythmus in `source_settings` (ADR-0038) | 1, 2, 4 |
| 2026-10-03 | #2159 | Zugang darf die Edition vorgeben (ADR-0041) | 2, 5 |
| 2026-10-12 | #2496 | Proxy je Bibliothek entfernt, hausweiter Ausgangsproxy (ADR-0041) | Kontext |
