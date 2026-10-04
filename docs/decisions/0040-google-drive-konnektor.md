# ADR-0040: Google-Drive-Konnektor und gemeinsamer Datei-Abgleich

## Status

Vorgeschlagen (03.10.2026). Issue [#2148](https://github.com/criew/opaa/issues/2148), Epic
[#2146](https://github.com/criew/opaa/issues/2146). Bindet [#2149](https://github.com/criew/opaa/issues/2149)
(gemeinsamer Datei-Abgleich) und [#2151](https://github.com/criew/opaa/issues/2151) (Konnektor).
Ergänzt [ADR-0027](0027-s3-konnektor.md), [ADR-0038](0038-steckbare-konnektoren.md) (vierte
Anmeldeart im Nachtrag „Verbindungsprofile“) und [ADR-0041](0041-verbindungen-als-eigenes-modul.md).
Setzt den Umbau der Lauf-SPI voraus ([#2178](https://github.com/criew/opaa/issues/2178)).

## Kontext

Google Drive soll als erste weitere Dateiablage nach S3 angebunden werden. Seit ADR-0038 ist ein
Konnektor ein eigenes Paket unter `indexing.source`. Was S3 und Drive gemeinsam brauchen (Download,
Formatfilter, Änderungsmerkmal, Ordnerspiegel, Wiederaufnahme), steckt heute S3-gebunden in
`S3FullSync`. Drei Dinge unterscheiden Drive von S3: Drive hat ein Änderungsprotokoll, Google-Formate
lassen sich nur exportieren, und das Ziel der Zugangsdaten ist fest (`googleapis.com`).

### Belege

Geprüft am 03.10.2026 gegen die Google-Dokumentation. **Unsicher** heißt: nicht dokumentiert oder
nicht eindeutig; #2151 belegt es gegen ein echtes Workspace.

| Thema | Befund | Stand |
|---|---|---|
| Kontingente | je Projekt 1 000 000 Einheiten/min, je Nutzer und Projekt 325 000/min; `files.get` 5, `files.list` 100, `files.download` 200 Einheiten. Seit 01.05.2026 neu; Projekte mit Nutzung zwischen 11/2025 und 04/2026 behalten alte Werte. Abrechnung oberhalb von 400 Mio. Einheiten/Tag ist für später in 2026 angekündigt | belegt |
| Kosten von Export und `alt=media` | nicht ausgewiesen | **unsicher**, angenommen 200 |
| Drosselung | `429 rateLimitExceeded` **und** `403 rateLimitExceeded`/`userRateLimitExceeded`; `403 dailyLimitExceeded` | belegt |
| `404 notFound` | „keine Leserechte oder existiert nicht“, nicht unterscheidbar | belegt |
| Exportgrenze | `files.export` liefert höchstens 10 MB | belegt |
| Fehlerbild über 10 MB | nicht dokumentiert; vermutlich `403 exportSizeLimitExceeded` | **unsicher** |
| `files.download` (LRO) | liefert eine Download-URI aus der Antwort; eine Größengrenze ist nicht genannt | **unsicher** |
| Exportformate | Docs: docx, odt, pdf, txt, md, …; Sheets: xlsx, ods, pdf, csv (nur erstes Blatt); Slides: pptx, odp, pdf, txt | belegt |
| Dienstkonten | haben kein Speicherkontingent und besitzen keine Dateien; „Meine Ablage“ eines Dienstkontos ist leer | belegt |
| Schlüssel | Organisationen ab 03.05.2024 verbieten die Schlüsselerzeugung standardmäßig (`iam.managed.disableServiceAccountKeyCreation`) | belegt |
| Verifizierung | entfällt für Dienstkonten mit eigenen bzw. delegierten Daten und für interne OAuth-Apps einer Workspace-Organisation; `drive.readonly` ist sonst ein eingeschränkter Scope mit Sicherheitsprüfung | belegt |
| Token-Endpunkt | `aud` und Ziel der JWT-Assertion sind immer `https://oauth2.googleapis.com/token`, Token gilt 3600 s | belegt |
| Geteilte Ablagen | höchstens 500 000 Elemente, 100 Ebenen; jede Datei hat genau einen Elternordner | belegt |
| Änderungsprotokoll | `changes.list` mit `pageToken`, höchstens 1000 je Seite, Token verfällt nicht; `driveId` für eine geteilte Ablage; `removed` = gelöscht **oder** Zugriff verloren | belegt |
| Änderungsmerkmal | `md5Checksum` nur für Binärdateien; `version` steigt bei *jeder* Änderung, auch unsichtbaren; `modifiedTime` = letzte Änderung durch irgendwen | belegt |
| `modifiedTime` bei Kommentar oder Umbenennung | nicht beschrieben | **unsicher** |
| Exporte bytegleich wiederholbar | nicht beschrieben | **unsicher** |
| Verknüpfungen | eigener MIME-Typ, `shortcutDetails.targetId`, ein Elternordner, brechen bei Rechteverlust | belegt |
| Dienstkonto als Mitglied einer Ablage bei eingeschränkter externer Freigabe | nicht beschrieben | **unsicher** |
| Ordner in einer geteilten Ablage, nur per Ordnerfreigabe sichtbar: erscheinen seine Änderungen im Strom des Kontos? | nicht beschrieben | **unsicher** |

Quellen: Drive API Guides `limits`, `handle-errors`, `manage-downloads`, `long-running-operations`,
`ref-export-formats`, `manage-changes`, `shortcuts`, `about-shareddrives`, `folder`,
`api-specific-auth`, Referenz `files` und `changes.list`; Google Identity „OAuth 2.0 for Server to
Server Applications“; Google Cloud „Secure by default organizations“; Google Cloud Help „When is
verification not needed“.

## Entscheidung

### 1. Der gemeinsame Datei-Abgleich ist ein Kernpaket `indexing.filesync`

Der Abgleich aus #2149 liegt in **`io.opaa.indexing.filesync`**, im Modul knowledge, als **letzter**
Eintrag von `ModularArchitecture.INDEXING_CORE` hinter `indexing.maintenance`. Abhängigkeiten:

```
indexing.source.s3 ─┐
indexing.source.googledrive ─┴─► indexing.filesync ─► maintenance, source, document, job, chunk
```

- Konnektoren hängen von `filesync` ab (connectors → knowledge, erlaubt). `filesync` kennt keinen
  Konnektor und keinen Anbieter (kein `io.opaa.s3`, keine Google-Typen). Keine neue Modulkante.
- **Nicht** als Unterpaket von `indexing.source`: Jedes direkte Unterpaket dort ist ein Konnektor,
  und S3 dürfte es nicht nutzen.
- **Nicht** im Vertragspaket `indexing.source`: Der Abgleich entfernt Bestätigt-Gelöschtes über
  `StaleDocumentCleanupService` (`maintenance`), und `source` liegt im Kern davor.

Am Code geprüft mit Wegwerf-Klassen gegen `ModularArchitectureTest`: `indexing.filesync` mit
Zugriff auf `maintenance`, `source` und `document`, genutzt aus `indexing.source.s3` — alle 20 Tests
grün. Als `indexing.source.filesync` scheitert `connectorsDoNotKnowEachOther`; direkt in
`indexing.source` scheitern `theIndexingCoreDependsOnlyDownward` und `subpackagesAreFreeOfCycles`.

**Schnittstelle (Skizze, Namen bindet #2149):** Der Konnektor implementiert einen Port, `filesync`
besitzt den Lauf.

```java
interface FileStore extends AutoCloseable {               // eine offene Verbindung je Lauf
  List<FileContainer> containers();                       // konfigurierte Bereiche, in Reihenfolge
  FilePage list(FileContainer c, String continuation);    // Vollabgleich, seitenweise
  FileEntry head(FileContainer c, String id);             // Einzelprüfung (Ereignis, Endung fehlt)
  FetchedFile fetch(FileEntry e, long maxBytes);          // Download oder Export in eine Temp-Datei
  Optional<ChangeFeed> changes();                         // leer = kein Änderungsprotokoll
  SourceRequestMeter meter();
}
interface ChangeFeed {
  String feedKey(FileContainer c);                        // welcher Strom den Bereich bedient
  String startCursor(String feedKey);
  ChangePage read(String feedKey, String cursor);         // Änderungen, nächste Seite, neuer Start
}
record FilePage(List<FileEntry> entries, String next,
    List<String> unchangedSubtrees) {}                    // nicht gelistet, gilt als vorhanden
record ChangePage(List<Change> changes, String next, String newStart,
    boolean fullSyncNeeded) {}                            // etwa nach einer Strukturänderung
record FileEntry(String id, String filePath, String fileName, List<String> folderSegments,
    long size /* -1 unbekannt */, String changeMarker, String mediaType, Exclusion exclusion) {}
// Fehler als neutrale Arten: ContainerUnlistable, Gone, Unreadable, TooLarge, RunEnding, Transient,
// CursorExpired (Cursor verfallen: Strom verwerfen, nächster Lauf ist ein Vollabgleich)
```

Die drei Signale braucht Drive nur teilweise, die übrigen Datei-Konnektoren aber sicher.
`unchangedSubtrees` trägt den ETag-Abstieg von Nextcloud: Ein unveränderter Teilbaum wird nicht
gelistet. `filesync` wertet dann jedes gespeicherte Dokument darunter als vorhanden, sonst entfernte
`REMOVE_ON_ABSENCE` den Teilbaum. `CursorExpired` deckt verfallende Delta-Token ab (Graph
`410 resyncRequired`, Dropbox `reset`). `fullSyncNeeded` meldet die Strukturänderung aus
Entscheidung 6.

| | S3 | Google Drive |
|---|---|---|
| Container | Bucket + Präfix | geteilte Ablage, Ordner, „Meine Ablage“ |
| Listing, Fortsetzungsmarke | `ListObjectsV2`, `ContinuationToken` (nur im Lauf) | `files.list`, `pageToken` (nur im Lauf) |
| Änderungsprotokoll | keins (`changes()` leer) | `changes.list`, Cursor dauerhaft (Entscheidung 6) |
| Download/Export | `GetObject` | `files.get?alt=media` bzw. `files.export` mit Zielformat aus Entscheidung 8 |
| Änderungsmerkmal | `e:<ETag>\|<Größe>` | Entscheidung 7 |
| Ordnerpfad | Präfixsegmente | Elternkette, aufgelöst im Konnektor |
| Deep Link | keiner (`withoutDeepLink`) | `filePath` (Entscheidung 7) |
| Ausschlüsse | Ordnermarker, Archivklasse | Verknüpfung, Download gesperrt, nicht exportierbarer Typ |

`filesync` besitzt: `markPresent` für jeden gelisteten Eintrag, Endungs- und Größenvorfilter,
Merkmalsvergleich, Download-Pool und Temp-Dateien, Aufnahme über `DocumentIngestService`,
Ordnerspiegel und Aufräumen, Wiederaufnahme je Container-Schlüssel, `Incomplete` für nicht listbare
Container, Fehlerabbildung auf Protokollkategorien, Zusammenfassung. Der Konnektor besitzt Anmeldung,
Auflistung, Merkmalsform, Identität, Ordnersegmente, Exportwahl und die Übersetzung seiner Fehler in
die neutralen Arten. Der Vertragstest aus #2149 läuft gegen den Port.

### 2. Anmeldung: Dienstkonto-Schlüssel als vierte Anmeldeart, signiert im Kern

- Jeder Betreiber nutzt **sein eigenes GCP-Projekt** und ein Dienstkonto mit JSON-Schlüssel. Es gibt
  keine zentrale OPAA-App: Sie bräuchte die Verifizierung eines eingeschränkten Scopes samt
  jährlicher Sicherheitsprüfung, Dienstkonten brauchen keine.
- **Neue Anmeldeart „Dienstkonto-Schlüssel“** im Verbindungsmodell. Sie steht im Nachtrag
  „Verbindungsprofile“ zu ADR-0038, in `connector-connections.md` und mit einem Hinweis in ADR-0025.
  - Ablauf: JWT-Assertion nach RFC 7523, RS256, Zugriffstoken für eine Stunde.
  - Kein Refresh-Token, kein Eintrag im Token-Speicher.
  - Besitzart nur Bibliothek.
  - Die Beschreibung des Konnektors meldet die Art mit Token-Endpunkt
    (`https://oauth2.googleapis.com/token`) und Scope (`https://www.googleapis.com/auth/drive.readonly`).
- **Der Konnektor sieht den Schlüssel nie.** Die Assertion bildet und signiert der Kern: zuerst der
  Übergangs-Resolver aus #2178, später connections (ADR-0041, Entscheidung 3).
  - Signiert wird mit Nimbus, abgeschickt über einen POST-Weg in `sourceaccess` (Entscheidung 3).
  - Der Konnektor bekommt über `SourceSettings` nur ein Geheimnis der Art Zugriffstoken.
  - Ein Lauf kann länger als eine Stunde dauern. Deshalb muss dieses Geheimnis erneuerbar sein: Der
    Konnektor fragt es je Anfrage beim Port ab, und der Kern erneuert es vor Ablauf. Dieselbe
    Anforderung haben OAuth-Zugriffstoken. #2178 legt den Port so an.
- **Ablage des Schlüssels:** Ohne Profil liegt er in `source_credentials`, mit Profil am Platz des
  Client-Secrets (ADR-0038, Nachtrag). Gelesen werden nur `client_email`, `private_key_id` und
  `private_key`. Die übrigen Felder (`token_uri`, `auth_uri`, …) bestimmen kein Ziel
  (Entscheidung 3).
- **Spalte und Spec werden gemeinsam erweitert.** Die Datei (rund 2,4 KB) passt verschlüsselt
  (`"enc:v1:" + base64(IV‖Geheimtext‖Tag)`) nicht in `source_credentials varchar(3000)`; die Spalte
  trägt höchstens 2216 Byte Klartext. Darum wird sie auf `text` erweitert, und die Spec-Grenze von
  `sourceCredentials` steigt an allen vier Stellen von 500 auf 4096. Erst die breitere Spalte macht
  die 4096 zu einer typneutralen Zusage. Sonst liefe jeder Wert zwischen 2217 und 4096 Byte bei
  jedem Konnektor auf einen Spaltenüberlauf, und der käme als nichtssagendes `409` an. Auch
  RSA-4096-Schlüssel passen dann.
- **Migration als Aufgabe von #2151:** ein Changeset in `db/changelog/knowledge/`
  (`knowledge_libraries.source_credentials` → `text`), die Längenangabe an der Entity und die
  Spec-Grenze in einem Zug. Die Breite ändert keine vorhandenen Werte. Als Typänderung braucht sie
  nach `backend/AGENTS.md` trotzdem einen Delta-Test. Das Client-Secret am Profil (#2160) braucht
  dieselbe Breite.
- Die Standard-Anmeldekette von Google (Umgebungsvariable, Metadatendienst) wird nie benutzt, aus
  demselben Grund wie bei S3 (ADR-0027, Entscheidung 7).
- Das Handbuch nennt die Organisationsrichtlinie, die Schlüssel standardmäßig verbietet, und wie
  der Betreiber eine Ausnahme für das eine Projekt setzt.

### 3. Ursprungsbindung bei festem Ziel

`sourceUrl` ist für Google Drive **fest `https://www.googleapis.com`**; die Validierung setzt den
Wert und weist jeden anderen ab, das Formular zeigt ihn nicht. Material aus den Zugangsdaten geht an
genau zwei Ziele:

- die signierte Assertion an den **Token-Endpunkt aus der Beschreibung**
  (`https://oauth2.googleapis.com/token`),
- das Zugriffstoken an `https://www.googleapis.com` (= `sourceUrl`).

Das ist die Regel aus dem Nachtrag „Verbindungsprofile“ zu ADR-0038. Der Token-Endpunkt aus der
Beschreibung ist mit und ohne Profil das einzige Ziel außerhalb von `sourceUrl`. Einen eigenen
Nachtrag zur Invariante gibt es nicht. Kein Ziel kommt aus der Schlüsseldatei, aus
`source_settings` oder aus einer Antwort.

- **Weiterleitungen:** API-Aufrufe und Downloads nutzen `REJECT_OFF_ORIGIN`, die Hausregel für
  JSON-APIs und Downloads. Eine Weiterleitung auf einen anderen Ursprung ist ein Fehler. Zeigt #2151,
  dass Google `alt=media` nachweislich auf einen anderen Host weiterleitet, entscheidet ein Nachtrag
  über `DROP_AUTHORIZATION_OFF_ORIGIN` für genau diesen Aufruf, mit Begründung.
- **Token-Abruf:** Er ist ein POST, und `sourceaccess` kennt heute nur GET. #2151 ergänzt dort einen
  POST-Weg mit Zieladressprüfung, Proxy, Zeitlimit, Größengrenze und ohne Weiterleitung. Ihn nutzen
  der Resolver im Kern und später connections für jeden Token-Endpunkt. Ein eigener Client, wie ihn
  `KeycloakAdminApi` baut, wäre eine zweite Stelle für dieselben Schutzmechanismen.
- `exportLinks`, `webContentLink` und die Download-URI von `files.download` werden nie mit Token
  abgerufen.
- **Übernahme gespeicherter Zugangsdaten:** `requestedSettingsChange` übernimmt einen gespeicherten
  Schlüssel heute nur, wenn die *Anfrage* denselben Ursprung trägt. Ein `sourceUrl`, das fehlt, zählt
  dabei als anderer Ursprung, und der Vergleich läuft vor `validate`. Sendet das Formular die feste
  Adresse nicht mit, verwürfe darum jede Änderung eines Verbindungsfelds (etwa `sourceProxy`) still
  den Schlüssel. Ein Leck entsteht dabei nicht. Deshalb vergleicht der Kern gegen die von `validate`
  **normalisierte** Adresse. #2151 testet, dass eine Proxy-Änderung den Schlüssel behält.

### 4. Profilangabe und domänenweite Delegation

- **Profilangabe „optional“**, einmal für den Typ und ohne Sonderfall. Der Schlüssel enthält seine
  App-Registrierung selbst. Deshalb erzwingt die Anmeldeart, anders als OAuth und
  Client-Credentials, keine Profilpflicht.
- **Ohne Delegation (Regelfall):** Das Dienstkonto sieht, was mit ihm geteilt ist, also geteilte
  Ablagen mit ihm als Mitglied und freigegebene Ordner. Es entspricht einem eingeschränkten
  S3-Schlüssel.
- **Mit Delegation** nennt `subject` das zu imitierende Konto.
  - Es ist eine Konnektor-Einstellung, in `source_settings` oder bei einem Profil in dessen
    konnektoreigenen Vorgaben.
  - Der Kern braucht `subject` für die Assertion, kennt aber keine Einstellungsschlüssel. Darum
    liefert der Konnektor es über einen schmalen SPI-Aufruf (Arbeitsname
    `SourceConnector#assertionSubject(ConnectorData)`). Ein Geheimnis ist es nicht.
  - Das Handbuch verlangt ein **Funktionskonto**, kein persönliches; technisch prüfen kann OPAA das
    nicht. Das Konto steht sichtbar in den Bibliotheksdetails und im Audit.
  - Das Handbuch empfiehlt für Delegation ein Profil: Ein solcher Schlüssel kann jedes Konto der
    Domäne lesen, und am Profil legt die Systemverwaltung das Konto fest.
- **`subject` ist ein Ziel der Zugangsdaten, und der Kern schützt es.** Der Konnektor sieht den
  Schlüssel nicht und könnte nicht unterscheiden, ob er neu eingegeben oder übernommen wurde; der
  Kern ergänzt die gespeicherten Zugangsdaten schon vor `validateChange`. Deshalb gilt im Kern: **Er
  übernimmt gespeicherte Zugangsdaten nur, wenn der Ursprung gleich bleibt und `assertionSubject`
  vor und nach der Änderung gleich ist.** Sonst sind die Zugangsdaten Pflicht, wie bei einem Wechsel
  des Ursprungs. Andernfalls könnte jede Person mit Verwaltungsrecht an der Bibliothek einen
  vorhandenen Delegationsschlüssel auf ein beliebiges Konto der Domäne umlenken. Die Regel gilt für
  jede künftige Anmeldeart mit Subjekt.
- **`subject` am Profil:** Eine Änderung verwirft, wie eine geänderte Server-Adresse, alle
  Verbindungen auf dem Profil. Die Bibliotheken ruhen mit Hinweis, bis sie neu verbunden sind; dabei
  wird der Schlüssel bestätigt. Ein Dienstkonto kennt keine Zustimmung je Verbindung, deshalb nicht „neue Zustimmung“.
  Die Spezifikation führt das als eigene Zeile.
- Persönliche Ablagen echter Personen sind nicht Teil dieses ADR. Ihr Weg wäre ein verbundenes Konto
  über eine *interne* OAuth-App des Betreibers, die keine Verifizierung braucht (Epic #2147).

### 5. Geltungsbereiche

`source_settings.scopes`: ein bis fünfzig Bereiche, je einer von
`{"drive": "<driveId>"}` (ganze geteilte Ablage), `{"folder": "<folderId>"}` (Ordner samt
Unterordnern, in geteilter Ablage oder freigegeben) oder `{"myDrive": true}` (nur mit `subject`,
denn ein Dienstkonto besitzt nichts). Der Container-Schlüssel ist `drive:<id>`, `folder:<id>` oder
`myDrive`.

- **Überlappung wird nicht abgewiesen.** Die Identität ist die Datei-ID (Entscheidung 7), eine Datei
  bleibt also ein Dokument. Ihr Ordner kommt aus dem ersten Bereich, der sie listet. Eine
  Überlappungsprüfung bräuchte beim Speichern API-Aufrufe.
- Auflistung: Eine geteilte Ablage wird flach gelistet (`corpora=drive`, `q=trashed=false`), dazu
  vorab ihre Ordner für die Pfade. Ein Ordnerbereich wird ebenenweise über `'<id>' in parents`
  gelistet.
- `SourceBrowser`: `drives.list` und die für das Konto freigegebenen Ordner.
- **Vor jedem Lauf** prüft der Konnektor, ob jeder Bereich erreichbar ist (`drives.get` bzw.
  `files.get` auf den Ordner). Ein nicht erreichbarer Bereich ist `ContainerUnlistable`: kein
  Abgleich, keine Löschung aus seinen Änderungen. Rechteentzug ist kein Löschbefund (ADR-0027).

### 6. Zwei Betriebsarten: Vollabgleich und Änderungsprotokoll

`FULL → REMOVE_ON_ABSENCE`, `INCREMENTAL → KEEP_ON_ABSENCE`, Vollabgleichsrhythmus wie Confluence
(`fullSyncInterval`, Vorgabe 7 Tage, je Bibliothek verlängerbar).

- **Cursor je Strom:** Jede geteilte Ablage hat einen eigenen Strom (`changes.list` mit `driveId`),
  alle übrigen Bereiche teilen den Strom des Kontos. Die Cursor liegen in einer neuen, nullbaren
  Spalte `change_cursors` an `source_sync_state` (Modul knowledge, rein additiv). Eine
  Konnektortabelle entsteht nicht.
- **Vollabgleich:** Beim **ersten** Beginn eines Vollabgleichs holt der Lauf `startCursor` für
  jeden Strom, **vor** der Auflistung. Die Cursor werden sofort als *ausstehend* in `change_cursors`
  gesichert. Ein fortgesetzter Lauf (`beginFullSync` bei unterbrochenem Vorlauf) behält sie und holt
  keine neuen. Erst `completeFullSync` macht sie zu den gültigen Cursorn. So gehen weder Änderungen
  während der Auflistung verloren noch Änderungen in schon abgeschlossenen Bereichen zwischen
  Abbruch und Fortsetzung. Das entspricht dem Anker von Confluence (ADR-0023, Entscheidung 4).
- **Verfallener Cursor** (`CursorExpired`; bei Drive laut Doku nie): Der Strom wird verworfen, und
  der nächste Lauf ist ein Vollabgleich.
- **Änderungslauf:** liest jeden Strom ab seinem Cursor. Er übernimmt den neuen Cursor nur, wenn
  kein Element vorübergehend scheiterte; ein Wiederholen ist dank Merkmal billig.
- **Löschbefund** im Änderungslauf: `removed=true`, `file.trashed=true` oder eine Datei, die in
  keinem Bereich mehr liegt. Er gilt nur für Bereiche, die in diesem Lauf erreichbar sind. Das
  entspricht dem Fehlen in einer vollständigen Auflistung. Ein `404` auf `files.get` ist **kein**
  Befund.
- **Strukturänderung** (Ordner verschoben, umbenannt, gelöscht): `ChangePage.fullSyncNeeded`; der
  Lauf merkt im Zustand vor, dass der nächste Lauf ein Vollabgleich ist. Sonst blieben Dateien eines herausgeschobenen Ordners bis
  zum Rhythmus durchsuchbar.

### 7. Identität, Änderungsmerkmal, Ordner, Deep Link

- **`file_path` = `https://drive.google.com/open?id=<fileId>`**. Er ist Identität und Deep Link
  zugleich (Beschreibung mit `deepLink`). Umbenennen und Verschieben behalten das Dokument. Ob der
  Link für jede Dateiart zum richtigen Editor führt, belegt #2151 (**unsicher**).
- **Merkmal** in `last_modified_remote`: Binärdatei `m:<md5Checksum>|<size>`; Google-Format
  `g:<modifiedTime in ms>|<Zielendung>`. Ein Wechsel des Zielformats löst so einen neuen Export aus.
  `version` wird verworfen, weil es laut Google bei jeder Änderung steigt, auch bei unsichtbaren;
  ein Fehlalarm kostet einen Export und, falls Exporte nicht bytegleich sind (**unsicher**), eine
  Neuverarbeitung. Verbindlich bleibt die SHA-256 nach dem Laden.
- **Ordner:** Elternkette bis zur Bereichswurzel, gespiegelt über `SourceFolderMirror`. Bei mehreren
  Bereichen steht der Name der Ablage bzw. des Ordners vorn, wie bei S3. Ein Name, den keine
  Ordnerzeile tragen kann (etwa mit `/`), und zu tiefe Ketten folgen der bestehenden Regel
  (`SourceFolderPath`).

### 8. Google-Formate: Export in Office-Formate, über 10 MB ein Protokolleintrag

| Typ | Zielformat | über 10 MB |
|---|---|---|
| Docs | docx | erneut als `text/plain`, Protokollhinweis „als Text exportiert“ |
| Slides | pptx | erneut als `text/plain`, Hinweis wie oben |
| Sheets | xlsx | übersprungen (`REJECTED`); csv enthielte nur das erste Blatt |
| Drawings, Forms, Sites, Vids, Apps Script, sonstige | — | übersprungen (`UNSUPPORTED_FORMAT`, Sammelhinweis) |

Office statt PDF, weil die vorhandenen Formate Überschriften, Tabellen und Blätter daraus lesen.
Markdown bleibt außen vor, weil nicht belegt ist, wie es Bilder einbettet. `fileName` trägt die
Zielendung (`Protokoll.docx`), damit der Vorfilter in `filesync` ohne Sonderfall greift; die Größe
eines Google-Formats gilt als unbekannt, die Byte-Grenze beim Kopieren sichert. Dateien mit
`capabilities.canDownload=false` und als missbräuchlich markierte Dateien werden übersprungen;
`acknowledgeAbuse` wird nie gesetzt. Der Textweg hängt am Grund `exportSizeLimitExceeded`
(vermutlich `403`, **unsicher**). #2151 bestätigt das am echten Fehlerbild. Jedes andere `403` des
Exports, das keine Drosselung ist, gilt als nicht lesbar und bricht den Lauf nicht ab.

### 9. Verknüpfungen werden übersprungen

Verknüpfungen (`application/vnd.google-apps.shortcut`) werden nie aufgelöst, gelten aber als
gesehen und erscheinen im Sammelhinweis. Liegt das Ziel in einem Bereich, wird es dort ohnehin
indexiert. Liegt es außerhalb, würde Auflösen den Geltungsbereich still erweitern, bei
Ordnerverknüpfungen auch rekursiv. Dubletten entstehen so nicht.

### 10. Quoten und Anfragebudget

- Das Budget zählt **Anfragen** je Lauf wie bei allen Konnektoren (`RequestBudget`), keine
  Google-Einheiten.
- Drosselung ist `429` sowie `403` mit Grund `rateLimitExceeded` oder `userRateLimitExceeded`. Der
  Konnektor liest den Grund aus dem Fehlerkörper, wartet mit exponentiellem Backoff über
  `RequestBudget#throttled` und wiederholt. Jedes andere `403` ist keine Drosselung.
- `dailyLimitExceeded` beendet den Lauf mit Meldung; der nächste geplante Lauf setzt an.
- Größenordnung: Eine geteilte Ablage mit 100 000 Dateien kostet für die Auflistung rund
  10 000 Einheiten, ein Änderungslauf ohne Änderungen 100 Einheiten je Strom (als Listenaufruf
  gerechnet). Das Handbuch nennt die
  angekündigte Abrechnung.
- Grenzwerte unter `opaa.indexing.google-drive.*` nach dem Muster von ADR-0027, Entscheidung 11;
  Vorgaben misst #2151.

### 11. REST über `io.opaa.sourceaccess` statt Google-Client-Bibliothek

Der Konnektor spricht die Drive-REST-API selbst an: HTTP über `SourceHttpClientFactory` und
`RedirectFollowingFetcher`, JSON mit Jackson. Die Assertion signiert der Kern mit Nimbus
(Entscheidung 2; schon eine Abhängigkeit, siehe `LocalAccessTokenService`). Es kommt keine neue
Abhängigkeit hinzu. Gebraucht werden rund acht
Endpunkte. Zieladressprüfung, Proxy, TLS-Schalter, Byte-Grenze, Weiterleitungsregel und
Anfragezähler gelten damit zentral. Bei S3 mussten sie am SDK nachgebaut werden (ADR-0027,
„Schwieriger“).

### 12. Rechte und Push

Rechte aus Drive werden nicht übernommen; die Bibliothek bestimmt, wer liest. `changes.watch` ist
nicht Teil des Epics, sondern ein Folge-Issue: Ein Änderungslauf alle paar Minuten kostet je Strom
eine Anfrage, und Push bräuchte einen öffentlich erreichbaren Eingang mit Kanalerneuerung.

## Ausdrücklich offen

- `files.download` (LRO) als Ausweg für Exporte über 10 MB, sobald Grenze und Ziel-Host belegt sind.
- Schlüssellose Anmeldung (Workload Identity Federation mit OPAA als Aussteller).
- Persönliche Ablagen über verbundene Konten (Epic #2147).
- Die unsicheren Befunde aus der Belegtabelle; #2151 trägt die Ergebnisse hier als Nachtrag ein.

## Konsequenzen

### Einfacher

- Ein weiterer Datei-Konnektor (Nextcloud, SMB, SharePoint, Dropbox) implementiert nur den Port,
  einschließlich der Signale für unveränderte Teilbäume und verfallene Cursor. Abgleich, Protokoll
  und Ordnerspiegel kommen aus `filesync`.
- Die Schutzmechanismen für den Netzzugriff gibt es für Drive nur einmal, in `sourceaccess`
  (einschließlich des neuen POST-Wegs).
- `source_credentials` nimmt für jeden Konnektor bis zu 4096 Zeichen an.
- Umbenennen und Verschieben in Drive bauen keine Dokumente neu.
- Keine Verifizierung, keine Sicherheitsprüfung, keine OPAA-eigene Google-App.

### Schwieriger

- `INDEXING_CORE` wächst um ein Paket; was `filesync` von oben bräuchte, muss als Port kommen.
- Eigene REST-Anbindung: Paging, Fehlerkörper und API-Änderungen pflegt OPAA selbst; WireMock-Tests
  müssen die JSON-Formen von Google treffen.
- Die Spec ändert sich trotz steckbarer Konnektoren (`sourceCredentials` 500 → 4096). Dazu kommt
  eine Typänderung an `knowledge_libraries` mit Delta-Test.
- Viele Ordnerbereiche kosten je Ordner eine Auflistung.
- Dateien, deren Export über 10 MB liegt, fehlen bei Sheets ganz und sind bei Docs und Slides nur
  als Text enthalten.
- Delegation ohne Profil hängt am Handbuch (Funktionskonto) und an der Pflicht, den Schlüssel neu
  einzugeben.
- Der Konnektor wartet auf #2178. Der Kern bekommt eine Signatur je Anmeldeart, und sein Port muss
  erneuerbare Zugriffstoken liefern.

## Verworfene Alternativen

- **Abgleich als `indexing.source.filesync` oder im Vertragspaket:** Strukturtest rot (Entscheidung 1).
- **Basisklasse im S3-Paket, von Drive geerbt:** Konnektoren kennen einander nicht.
- **Eigenes Modul zwischen knowledge und connectors:** eine neue Modulkante für ein Paket, das im
  Kern denselben Platz hat.
- **Zentrale OPAA-OAuth-App:** Verifizierung und jährliche Sicherheitsprüfung, ein Geheimnis für alle
  Installationen.
- **`google-api-services-drive` mit `google-auth-library`:** zweiter HTTP-Stack, Schutzmechanismen
  doppelt, `token_uri` aus der Schlüsseldatei, Standard-Anmeldekette.
- **Schlüssel verdichtet speichern, Spalte unverändert lassen:** Er hätte in `varchar(3000)` gepasst.
  Die Spec-Grenze 4096 hätte dann aber jedem Konnektor Werte zugesagt, die die Spalte nicht trägt.
  RSA-4096 wäre ausgeschlossen gewesen.
- **Spec-Grenze nur im Drive-Konnektor prüfen:** Die `400` gäbe es dann nur dort, jeder andere
  Konnektor bekäme ein `409`.
- **`version` als Merkmal für Google-Formate:** zu viele Fehlalarme (Entscheidung 7).
- **PDF als einheitliches Zielformat:** verliert Überschriften, Tabellen und Blätter.
- **`exportLinks` als Ausweg über 10 MB:** Token an ein Ziel aus der Antwort, Grenze nicht belegt.
- **Verknüpfungen auflösen:** erweitert den Geltungsbereich still.
- **Nur Vollabgleich wie S3:** Drive bietet ein verlässliches Änderungsprotokoll, der Vollabgleich
  bleibt als Rhythmus.
- **Profilpflicht nur mit Delegation:** Die Profilangabe gilt einmal je Typ, so ließe sich das
  nicht ausdrücken. Eine Pflicht für jede Drive-Bibliothek sperrte den Regelfall ohne Delegation.
- **Dienstkonto-Schlüssel als Client-Credentials einordnen:** Der Ablauf ist ein anderer (Assertion
  statt Client-Secret). Außerdem machte Client-Credentials das Profil zur Pflicht.
- **Signieren im Konnektor:** Der Konnektor sähe den Schlüssel, entgegen ADR-0041, Entscheidung 3.
- **Ziel als „Konstante des Konnektors“ in einem eigenen Nachtrag:** Das wäre eine zweite
  Formulierung neben dem Token-Endpunkt aus der Beschreibung.

## Zuschnitt der Folge-Issues

| Issue | Folgt aus diesem ADR |
|---|---|
| #2149 | Paket `indexing.filesync`, letzter Eintrag in `INDEXING_CORE`; Port nach Entscheidung 1 mit neutralen Fehlerarten (einschließlich `CursorExpired`), optionalem `ChangeFeed`, `unchangedSubtrees` und `fullSyncNeeded`; ausstehende Startcursor beim ersten Beginn des Vollabgleichs gesichert, bei Wiederaufnahme behalten; S3 als erster Nutzer ohne Verhaltensänderung; Spalte `change_cursors` kann hier oder in #2151 kommen; Vertragstest gegen den Port |
| #2151 | Paket `indexing.source.googledrive`, Typ `GOOGLE_DRIVE`; Changeset `source_credentials` → `text` mit Delta-Test, Entity-Länge und Spec-Grenze 4096; **nach #2178**; im Kern: Anmeldeart „Dienstkonto-Schlüssel“ in der Beschreibung (Token-Endpunkt, Scope), Signatur im Übergangs-Resolver, erneuerbares Zugriffstoken über den Port, `assertionSubject`; POST-Weg in `sourceaccess`; feste `sourceUrl`, `REJECT_OFF_ORIGIN`, Übernahme der Zugangsdaten gegen die normalisierte Adresse (Test: Proxy-Änderung behält den Schlüssel); Kernregel: gespeicherte Zugangsdaten nur bei gleichem Ursprung **und** gleichem `assertionSubject` übernehmen (Test: `subject`-Änderung ohne Schlüssel → `400`, mit Schlüssel → gespeichert); `subject`-Änderung am Profil verwirft dessen Verbindungen (mit #2160); ArchUnit belegt, dass kein Konnektorpaket den Schlüssel liest; Bereiche, Erreichbarkeitsprüfung, Cursor je Strom, Strukturänderung erzwingt Vollabgleich; Merkmal, Exporttabelle, Verknüpfungen überspringen; Drosselung über `403`-Gründe; unsichere Befunde gegen ein echtes Workspace belegen und hier nachtragen; Handbuch mit Schlüsselrichtlinie und Funktionskonto |

## Nachtrag: Unveränderte Ordner und Nextcloud (#2152, 03.10.2026)

Nextcloud hat für Dateien kein Änderungsprotokoll. Die Prüfsumme (ETag) eines Ordners ändert sich
aber, sobald sich darunter etwas ändert, bis hinauf zur Wurzel des Nutzers. Am offiziellen Image
(Nextcloud 34) geprüft: Das gilt auch für Freigaben an den technischen Nutzer und für Group
Folders. Ein umbenannter Ordner behält dagegen seine eigene Prüfsumme.

Zwei Annahmen aus #2149 tragen das nicht:

- **`unchangedSubtrees` als `file_path`-Präfixe** setzen voraus, dass die Identität den Ordnerpfad
  enthält. Nextcloud soll Umbenennungen und Verschiebungen über `oc:fileid` erkennen wie Drive
  (Entscheidung 7). Dann ist `file_path` die Adresse `…/index.php/f/<fileid>` und hat keine
  Ordnerstruktur mehr.
- **Woher die Prüfsummen des letzten Laufs kommen**, sagt der Port nicht. Ein Konnektor hat keinen
  eigenen Zustand.

Entscheidung:

1. **Ein Ordner wird über den Hierarchiepfad benannt**, den seine Dokumente tragen
   (`SourceDocumentContext`, Spalten `source_container_key` und `source_hierarchy_path`), `""` für
   die Wurzel des Containers. `FilePage.unchangedSubtrees` nennt solche Pfade. `FileSync` behält
   jedes gespeicherte Dokument des Containers in oder unter einem davon. Ein Store, der Ordner
   meldet, setzt den Container-Schlüssel als Kontext jedes Eintrags, und kein Ordnername enthält
   den Trenner ` / `. `FileSync` prüft das Erste.
2. **`FileSync` merkt sich die Ordnermerkmale.** Eine Seite meldet in `listedSubtrees` die Merkmale
   der Ordner, die sie gelistet hat. Ein vollständiger Vollabgleich speichert sie in der neuen,
   nullbaren Spalte `source_sync_state.subtree_markers` (rein additiv). Der nächste Lauf übergibt
   sie dem Store vor der ersten Seite (`FileStore#recall`). Unveränderte Ordner tragen ihre Merkmale
   samt der darunter weiter.
3. **Ein Ordner mit einem ungeklärten Eintrag wird nicht gemerkt**, ebenso wenig jeder Ordner
   darüber. Ungeklärt ist ein Eintrag, dessen gespeicherter Stand diesen Lauf nicht abbildet:
   fehlgeschlagener Abruf oder Aufnahme, Kontingent, nicht lesbar, eine nicht verfügbare Datei und
   ein vorhandenes Dokument, das zu groß ist. Sonst holte ihn kein späterer Lauf nach,
   solange sich der Ordner nicht ändert.
4. **Das Gedächtnis verfällt.** Es gilt nur unter der Größengrenze und dem Formatsatz, unter denen
   es entstand. Sonst blieben Dateien eines neu unterstützten Formats in unveränderten Ordnern
   liegen. Außerdem gilt es nur bis zu einem Höchstalter (`FileSyncSettings.subtreeMemoryMaxAge`),
   gemessen ab dem letzten Lauf, der alle Ordner gelistet hat. Danach listet ein Lauf wieder alles.
   Das fängt Änderungen ab, die keine Prüfsumme weitertragen (externer Speicher ohne
   Änderungserkennung).
5. **Das Gedächtnis hängt am Ort.** Ein Merkmal gilt nur für genau den Pfad, unter dem es gemerkt
   wurde; ein umbenannter oder verschobener Ordner hat keines und wird gelistet. Ein Eintrag eines
   Stores, der Ordner meldet, wird nur übersprungen, wenn sein Dokument am gelisteten Ort steht
   (Container, Hierarchiepfad, Dateiname). Sonst wird er einmal geholt; bei gleicher SHA-256
   übernimmt die Aufnahme Titel, Hierarchiepfad und Ordner, ohne neu zu schneiden. Ein Pfad, den
   die Spalte kürzt (über 2000 Zeichen), hält seine Ordner aus dem Gedächtnis. So verschwindet kein
   Dokument mit veraltetem Hierarchiepfad unter einem unveränderten Ordner.
6. **Was zwischen den Läufen geschieht, entwertet Merkmale.** Ein Ordner mit einem Dokument, das
   für den nächsten Lauf vorgemerkt (Nachzüge, ADR-0018) oder nicht indexiert ist, bekommt kein
   Merkmal übergeben und wird gelistet. Hat ein Container weniger Zeilen als beim Merken, wurde
   ein Dokument außerhalb eines Laufs gelöscht; sein Gedächtnis gilt dann nicht, und der Lauf
   holt das Dokument zurück. Meldet ein Store einen Ordner als unverändert, dessen Merkmal ihm
   nicht übergeben wurde, gilt der Container als unvollständig gelistet.

Den Vertrag hält `FileStore#recall` fest, die Fälle prüft `FileStoreFolderContract`.

Verworfen:

- **Identität aus dem Ordnerpfad, Umbenennung als Löschen und Neuanlegen:** jede Umbenennung eines
  Ordners verarbeitete alle Dokumente darunter neu.
- **Kette aus Ordner-IDs als `file_path`** (`…/<ordnerId>/<ordnerId>/<fileId>`): hielte die
  Präfixe, aber jede Verschiebung erzeugte ein neues Dokument, und der Deep Link bräuchte eine
  eigene Schnittstelle neben `file_path`.
- **Merkmale im Konnektor speichern:** Ein Konnektor hat keinen Zustand, und nur `FileSync` weiß,
  welcher Eintrag ungeklärt blieb.

## Nachtrag: Lange Hierarchiepfade und Löschvermerk (#2201, 04.10.2026)

Zwei Annahmen des Nachtrags „Unveränderte Ordner“ hielten nicht:

- **Punkt 5, gekürzter Pfad:** `source_hierarchy_path` war `varchar(2000)`. Ein längerer Pfad wurde
  beim Anlegen gekürzt, beim Nachführen über `refreshConnectorTitleAndContext` aber ungekürzt
  geschrieben. Bei gleicher Prüfsumme scheiterte deshalb jeder Lauf mit einem Fehler und einem
  Download; der Ordner kam nie ins Gedächtnis.
- **Punkt 6, Zeilenzähler:** Der Vergleich „weniger Zeilen als beim Merken“ übersah eine Löschung,
  die ein Ereignislauf mit einem neuen Dokument im selben Container ausglich. Auch eine Löschung
  während des Laufs übersah er, weil er die Zeilen erst beim Speichern zählte. Der Ordner blieb
  gemerkt, und das gelöschte Dokument kam nicht zurück.

Entscheidung:

1. **`source_hierarchy_path` ist `text`.** Die Kürzung und ihr Sonderfall im Abgleich entfallen.
   Eine früher gekürzte Zeile stellt der nächste Lauf einmal richtig (Abruf, gleiche Prüfsumme,
   Nachführen ohne neuen Schnitt). Ein Backfill ist dafür nicht nötig. Identität bleibt
   `file_path` in seiner bisherigen Breite.
2. **Löschvermerk statt Zähler.** Die neue Tabelle `source_sync_revisits` hängt am
   Abgleichszustand und fällt mit ihm (`ON DELETE CASCADE`). `LibraryDocumentService.deleteDocument`
   schreibt in derselben Transaktion eine Zeile mit Container und Hierarchiepfad, wenn die
   Bibliothek einen Abgleichszustand hat und das Dokument einen Container trägt. Das ist der
   einzige Löschweg außerhalb eines Laufs; Ordner werden über ihn geleert. Eine ArchUnit-Regel
   (`onlyTheKnownClassesDeleteDocuments`) hält die Aufrufer von `DocumentRepository#delete…` fest:
   `LibraryDocumentService`, `KnowledgeLibraryService` (Bibliothek samt Zustand) und
   `StaleDocumentCleanupService` (im Lauf).
3. **Wirkung im Lauf.** Ein Vollabgleich liest die Vermerke zu Beginn. Für keinen Ordner in oder
   über einem davon übergibt er ein Merkmal. Beim Speichern des Gedächtnisses liest er sie erneut;
   ein Ordner über einem Vermerk, der während des Laufs kam, wird nicht gemerkt. Verbraucht werden
   nach dem Speichern nur Vermerke vom Laufbeginn, deren Container bis zur letzten Seite gelistet
   wurde. Ein Änderungs- oder Ereignislauf lässt die Vermerke stehen.
4. **Grundlage `v2`.** Das Gedächtnis trägt keine Zeilenzahlen mehr. Ein unter `v1` gemerktes
   Gedächtnis gilt nicht; jede Bibliothek mit Ordnergedächtnis listet nach dem Update einmal alle
   Ordner.

Nebenwirkung: Auch eine Bibliothek, deren Store keine Ordner meldet, bekommt je manueller Löschung
einen Vermerk. Ihr nächster vollständiger Vollabgleich verbraucht ihn.

Grenze, gemessen an PostgreSQL 18: Der eindeutige Index `(library_id, file_path)` nimmt höchstens
2676 Byte eines nicht komprimierbaren Pfads auf. Ein `file_path` mit 2000 Zeichen passt deshalb
nur, solange er fast nur aus Ein-Byte-Zeichen besteht; zufällige Zwei-Byte-Zeichen enden bei 1338,
Drei-Byte-Zeichen bei 892 Zeichen. Ein längerer Pfad scheitert beim Speichern mit „index row size
… exceeds btree version 4 maximum 2704“. Das betrifft Stores mit Pfad-Identität (SMB) und ist hier
nicht gelöst (`DocumentFilePathIndexLimitTest`).

## Nachtrag: SMB (#2155, 03.10.2026)

Windows-Dateifreigaben sind der Vollscan-Fall des Ports: kein Änderungsprotokoll, kein
Ordnermerkmal. Paket `indexing.source.smb`, Typ `SMB`, nur über `filesync` und den Kern; keine
neue Modulkante.

1. **Bibliothek smbj** (Apache 2.0, Version 0.15.0 vom 08/2026): SMB 2.0.2 bis 3.1.1, Signatur,
   Verschlüsselung; Laufzeitabhängigkeiten asn-one, mbassador und Bouncy Castle (über den
   bestehenden Pin). SMB 1 spricht smbj nicht, OPAA also auch nicht. Der Zugriff liegt im
   Konnektorpaket (`SmbShareClient`), nicht in foundation: Nur der Konnektor braucht ihn.
2. **Ziel und Ursprung:** `sourceUrl` ist `smb://server[:port]/freigabe`, ein UNC-Pfad wird in
   diese Form gebracht. Gespeichert wird sie normalisiert (Server klein, Port 445 weggelassen,
   Freigabe prozentkodiert), damit die Ursprungsbindung des Kerns greift. Jeder Socket entsteht in
   einer eigenen `SocketFactory`, die zuerst `TargetAddressValidator#validateHost` prüft. DFS-Verweise
   folgt der Client nicht (`withDfsEnabled(false)`); Zugangsdaten verlassen den Server also nie. Gespeicherte Zugangsdaten gelten nur für dieselbe Freigabe (`SourceConnector#keepsCredentials`, durchgesetzt im Kern beim Speichern, Testen und Auflisten); administrative Freigaben (`*$`) werden abgewiesen.
3. **Anmeldung:** NTLM mit Dienstkonto aus `source_credentials` (`DOMÄNE\Benutzer:Passwort`).
   Signatur ist Pflicht, Verschlüsselung wird genutzt, wo der Server SMB 3 anbietet. Eine Sitzung
   als Gast oder anonym gilt als abgelehnte Anmeldung. **Kerberos fehlt:** smbj bräuchte dafür ein
   JAAS-Subject mit `krb5.conf`, KDC-Erreichbarkeit und Keytab oder Ticket; das ist eigene
   Betriebsinfrastruktur je Installation und nicht Teil dieses Issues. Domänen, die NTLM
   abgeschaltet haben, lassen sich deshalb noch nicht anbinden; der Verbindungstest nennt das
   (`STATUS_NTLM_BLOCKED`). Jede Ablehnung des Sitzungsaufbaus, auch „Zugriff verweigert“, ist ein
   Anmeldebefund.
4. **Identität und Merkmal:** `file_path` ist `smb://server/freigabe/pfad` (unkodiert). Umbenennen
   oder Verschieben ist Löschen und Neuanlegen, wie bei Dateisystem und S3. Das Merkmal ist
   `m:<Änderungszeit in Windows-Ticks>|<Größe>`. Kein Deep Link; das Original liest
   `OriginalAccess` von der Freigabe. **Grenzen:** Ändert sich der Inhalt bei gleicher
   Änderungszeit und gleicher Größe (ein Werkzeug setzt die Zeit zurück), bemerkt der Lauf das
   nicht. Teilen sich zwei Ordner eine Datei-ID (etwa bei Samba mit mehreren Dateisystemen unter
   einer Freigabe), gilt der zweite als Verknüpfung (Punkt 6); er fehlt dann ohne Befund, und sein
   Bestand wird entfernt.
5. **Kein Ordnergedächtnis:** Die Änderungszeit eines Ordners ändert sich unter NTFS und Samba nur,
   wenn sich ein direkter Eintrag ändert, nicht beim Schreiben einer Datei und nicht bei Änderungen
   tiefer im Baum. Sie erfüllt den Vertrag von `FileStore#recall` nicht. Jeder Lauf listet alles.
6. **Verknüpfungen werden nicht verfolgt:** Ein Eintrag mit Reparse-Punkt, dessen Tag ein
   Namensersatz ist (symbolischer Link, Junction, Mount-Point) oder ein DFS-Verweis, wird als
   „kein Dokument“ gezählt. Andere Reparse-Punkte (Deduplizierung) werden gelesen. Ein Samba-Server
   löst Unix-Links selbst auf; ein Ordner, dessen Datei-ID der Lauf schon kennt, gilt deshalb als
   Verknüpfung. Das beendet Schleifen. Zusätzlich endet der Abstieg nach 64 Ebenen.
   Folgen hätte bedeutet: Schleifen, doppelte Dokumente und Ziele außerhalb der Freigabe.
   **Auch beim Öffnen** wird nicht gefolgt: Jeder Pfad (Ordner zum Auflisten, Datei zum Abruf,
   Original, konfigurierter Ordner) wird mit `FILE_OPEN_REPARSE_POINT` geöffnet. Ist das Geöffnete
   eine Verknüpfung (Tag über `FSCTL_GET_REPARSE_POINT`), wird es abgewiesen; ein anderer
   Reparse-Punkt wird ohne die Option neu geöffnet. Samba öffnet einen Unix-Link mit dieser Option
   gar nicht; ein Eintrag, den die Auflistung zeigte, der sich so aber nicht öffnen lässt, gilt als
   Verknüpfung bzw. als verschwunden. Eine Verknüpfung weiter vorn im Pfad löst smbj selbst auf
   (`SymlinkPathResolver`, immer aktiv, ohne Grenze); der Transport bricht ein Öffnen nach 16
   solchen Sprüngen ab, die Verbindung wird neu aufgebaut. Eine Schleife endet so mit einem
   Befund statt mit einem `StackOverflowError`.
   **Namen:** Ein Name aus der Auflistung mit `/`, `\`, `:` oder Steuerzeichen (nur von einem
   Nicht-Windows-Server oder einem manipulierten Server möglich) wird als „kein Dokument“ gezählt;
   ein gespeicherter Pfad mit solchen Zeichen liegt in keinem Ordner der Bibliothek.
7. **Unlesbare Unterordner:** Ein Ordner, den das Dienstkonto nicht öffnen darf, wird übersprungen
   und im Protokoll genannt. Der Rest wird gelistet, der Bereich gilt aber als unvollständig: Im
   Bereich wird nichts entfernt. Ist der konfigurierte Ordner selbst unlesbar, gilt das sofort.
   **Grenze, Access-Based Enumeration:** Ist sie auf der Freigabe aktiv, liefert der Server einen
   Ordner ohne Leserecht gar nicht erst, statt den Zugriff zu verweigern. Für OPAA ist er dann
   verschwunden; die Auflistung gilt als vollständig, und seine Dokumente werden entfernt. Das lässt
   sich am Protokoll nicht erkennen. Betreiber geben dem Dienstkonto deshalb Leserecht auf alles im
   Bereich.
8. **Ausgelagerte Dateien** (Offline, Recall bei Zugriff) werden nicht abgerufen; ein Abruf löste
   eine Rückholung vom Archiv aus. Sie gelten als vorhanden.
9. **Große Ordner und Budget:** Eine Seite hat höchstens `list-page-size` Einträge, ein Ordner wird
   stapelweise über mehrere Seiten gelesen. Jede SMB-Nachricht kostet eine Anfrage des Budgets und
   wird vor dem Senden abgelehnt, wenn es erschöpft ist; der Standard liegt deshalb höher als bei
   Nextcloud (ein Download sind mindestens vier Nachrichten: öffnen, prüfen, lesen, schließen).
10. **Tests:** Ein echter Samba (`dockurr/samba`, rund 100 MB, Start in rund 1 s, wenige MiB
    Speicher) läuft im regulären `test`, wie der S3-Speicher. `FileStoreContract` läuft gegen ihn.

## Referenzen

- [ADR-0017](0017-quellentypmodell-indizierung.md), [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md)
- [ADR-0023](0023-confluence-konnektor.md) — Laufrahmen, Anker, Löschbefund
- [ADR-0027](0027-s3-konnektor.md) — S3, Vorbild für Bereiche, Merkmal, Ordner
- [ADR-0038](0038-steckbare-konnektoren.md) — steckbare Konnektoren (vierte Anmeldeart im Nachtrag „Verbindungsprofile“)
- [ADR-0041](0041-verbindungen-als-eigenes-modul.md) — Verbindungen, Port, Umbau der Lauf-SPI (#2178)
- [connector-connections.md](../features/connector-connections.md) — Verbindungsprofile
- `backend/src/main/java/io/opaa/indexing/source/AGENTS.md`, `ModularArchitectureTest`
