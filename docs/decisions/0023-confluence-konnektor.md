# ADR-0023: Confluence-Konnektor — Space-Auswahl, Editionen, Zugangsdaten und Löschsemantik je Lauf

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt ADR-0017 (Entscheidung 5) und ADR-0022 · Ablage der Einstellungen geändert durch ADR-0038

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
Bibliotheken — die Oberfläche sagt das in der Anlage **vor** der Space-Auswahl), beliebig viele Confluence-Bibliotheken auch gegen dieselbe Instanz, beide Editionen
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
`ConfluenceSourceSettings` in `knowledge_libraries.source_settings` (ADR-0038, Entscheidung 2).
Die Auswahl ist nach der Anlage als Ganzes ersetzbar; jede
Änderung erzwingt als nächsten Lauf einen Vollabgleich (Entscheidung 4), weil nur er abgewählte
Spaces aus dem Index nimmt; die Oberfläche sagt das an der Stelle, an der die Auswahl geändert
wird. Der Bestand einer vollständig geleerten Auswahl verschwindet über das Löschen der
Bibliothek, nicht über einen Lauf. Jedes Dokument trägt Space-Schlüssel und Gliederungspfad
(`documents.source_container_key`, `source_hierarchy_path`) für Beleg, Laufprotokoll und
Chunk-Kontext, ohne die Instanz zu fragen; eine Abbildung der Seitenhierarchie auf Ordner
(ADR-0020) findet nicht statt.

### 2. Die Edition wird erkannt, steht an der Bibliothek und ist danach unveränderlich

Die Edition (`ConfluenceEdition`: `CLOUD`, `DATA_CENTER`) steht in
`source_settings`; ein Zugang (ADR-0041) darf sie als Vorgabe tragen (`DefaultKey` der
`ProfileDeclaration`, ADR-0038, Entscheidung 7), dann steht sie an der Bibliothek nur lesbar. Die gespeicherte Basisadresse
ist die Confluence-Wurzel — Cloud ohne `/wiki` (der Adapter hängt es an), Data Center samt
Kontextpfad —, normalisiert (Schema und Host klein, kein abschließender Schrägstrich, kein
`userinfo`).

`ConfluenceEditionDetector` erkennt die Edition ohne Zugangsdaten aus dem Antwortverhalten:
`GET <Host-Wurzel>/_edge/tenant_info` mit `cloudId` ist Cloud; sonst `GET <Basis>/status`
(`{"state": …}`) bzw. `GET <Basis>/rest/api/space` im Data-Center-Fehlerformat; trifft nichts zu,
ist die Adresse kein Confluence (auch hinter einem SSO-Reverse-Proxy, der jede Sonde auf eine
Anmeldeseite umleitet). Mit Zugangsdaten bestätigt der Verbindungstest die Edition zusätzlich
über den jeweiligen Spaces-Endpunkt. Keine Erkennung über den Hostnamen (`*.atlassian.net`): falsch
für Cloud hinter eigener Domain, aussagelos für Data Center; die Signaturen sind im Testdoppel
beider Editionen abgebildet, damit ein Wechsel auf Herstellerseite als Teständerung sichtbar wird.
Die Anlage prüft die übernommene Edition erneut gegen die Instanz; Erkennung und Space-Auflistung machen die Anlage netzabhängig. Nach der Anlage
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

Confluence-Zugangsdaten gehören der Bibliothek und liegen ruhend verschlüsselt (AES-256-GCM,
`OPAA_CREDENTIALS_ENCRYPTION_KEY`): bei eigener Adresse in `knowledge_libraries.source_credentials`
(`SourceCredentialsConverter`/`CredentialsEncryptor`, `SecretOwner.LibraryOwned`), an einem Zugang
als Zeile der Bibliothek im Token-Speicher `connection_tokens`
(`SecretOwner.LibraryOnProfile`, `kind = 'PERSONAL_SECRET'`; ADR-0048, Entscheidung 9). Angenommen werden sie
über das nur-schreibende Feld `sourceCredentials`.
Das Format hängt von der Edition ab (`ConfluenceCredentials#parse`): Cloud `<E-Mail>:<API-Token>`
(HTTP Basic, zerlegt am ersten Doppelpunkt; die Oberfläche erfasst zwei Felder), Data Center das
nackte PAT als `Bearer` (ein Doppelpunkt wird als irrtümliches Cloud-Paar zurückgewiesen). Weil
das Feld nur schreibbar ist, zeigt die Oberfläche auch die E-Mail nicht mehr an — in Kauf
genommen, um keinen zweiten Ablageweg zu eröffnen.

Zugangsdaten erscheinen in keiner API-Antwort, keinem Log, keiner Exception-Message und keiner
Fehlermeldung, auch nicht in verschachtelten Ursachen: `ConfluenceCredentials` gibt das Geheimnis
nur über `authorizationHeader()` heraus, `toString()` nie; ein Test der Zugriffsschicht prüft, dass
keine ihrer Exceptions das Token enthält. Die Ursprungsbindung der Zugangsdaten (ADR-0018,
Entscheidung 4; über `SourceConnector#credentialBinding`, ADR-0038, Entscheidung 7)
gilt; das Rotieren eines Tokens ist eine
gewöhnliche Konfigurationsänderung.

### 4. Löschsemantik je Lauf, nicht je Quellentyp

Jeder Executor deklariert seine Betriebsarten samt Löschkategorie (`SourceIndexingExecutor#runModes()`:
`IndexingRunMode` → `VanishedDocumentPolicy`), ohne impliziten Standard; der Lauf trägt sie in
`indexing_jobs.run_mode` und `IndexingRunResponse`. Confluence kennt den auflistenden Vollabgleich
(`FULL`) und den ergänzenden inkrementellen Abgleich (`INCREMENTAL`, CQL seit Anker −
`incremental-overlap`). Der manuelle Anstoß bietet beide an; sonst wählt
`ConfluenceIndexingExecutor#defaultRunMode` den Vollabgleich beim ersten Lauf, nach Unterbrechung,
nach einer Einstellungsänderung (`SyncStateBasis`) und nach Ablauf von `fullSyncIntervalDays`.

**Löschung braucht einen positiven Befund der Instanz:** eine vollständige Auflistung aller Spaces
und Anhangslisten (`ListingOutcome`) oder `status = trashed` beim Einzelabruf
(`StaleDocumentCleanupService#removeWithAttachments`, ADR-0022, Entscheidung 3); einen Aufruf aus
einem ergänzenden Lauf weist `StaleDocumentCleanupService` zurück. Der Geltungsbereich ist je
Bibliothek und Quellentyp; eine zweite Bibliothek gegen dieselbe Instanz bleibt unberührt.
`404`/`403` ist kein Befund: Ein unlesbarer Space bleibt indiziert, sichtbar im Laufprotokoll und an
der Bibliothek. Archivierte Seiten fallen wie gelöschte weg; ein Vollabgleich ohne Dokumente löscht
nichts. Ein Webhook (`ConfluenceWebhookSignature`: Signatur bzw. geteiltes Geheimnis, zeitkonstant
verglichen, sonst einheitlich `401`) ist nur Anlass für einen Einzelabruf (`WEBHOOK`/`INCREMENTAL`),
der den Anker nicht bewegt. Identität ist die titelfreie Seiten-URL (ADR-0022, Entscheidung 2); der
`webui`-Link taugt nicht als Identität, weil er den Titel enthält. Rhythmus, Anker und Budget:
[Handbuch](../handbuch/konnektoren/confluence.md), „Betriebsarten und Änderungserkennung“ bis
„Löscherkennung“.

### 5. Folgen des Mehrfach-Bibliotheken-Modells

Keine Eindeutigkeitsregel auf Adresse oder Token. Zugangsdaten strikt je Bibliothek, auch das
Webhook-Geheimnis (`source_webhook_secret`); ein Zugang (ADR-0041) bündelt nur Adresse und Edition,
das Token gehört weiterhin jeder Bibliothek (Ablageort: Entscheidung 3). Überlappende Space-Auswahlen indizieren doppelt — keine
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
