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
- **`subject` am Profil:** Unter einem Profil setzt nur das Profil `subject`; ein Wert der
  Bibliothek ist ein `400`, und fehlt er am Profil, wird niemand imitiert. Eine Änderung verwirft den
  Abgleichsstand aller Bibliotheken auf dem Profil, ihr nächster Lauf ist ein Vollabgleich. Vor dem
  Speichern nennt die Bestätigung die Zahl der Bibliotheken, danach erhalten ihre Verwaltenden eine
  Benachrichtigung. Solange eine der Bibliotheken läuft, wird die Änderung abgelehnt: Ein laufender
  Abgleich bekäme ab dann Token des neuen Kontos und endete mit dem Stand zweier Konten. Bekannte
  Grenze: Startet ein Lauf zwischen dieser Prüfung und dem Speichern, kann sein Abgleichstand
  gemischt bleiben, bis der nächste Vollabgleich ihn ersetzt. Der
  Schlüssel bleibt, denn er liegt am Profil und wird nicht je Bibliothek
  bestätigt (Nachtrag #2220, Entscheidung des Maintainers vom 04.10.2026; ursprünglich sollten die
  Bibliotheken ruhen, bis sie neu verbunden sind).
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
   Bei einem Store, der Ordner meldet (Nextcloud), stellt der nächste Lauf eine früher gekürzte
   Zeile einmal richtig (Abruf, gleiche Prüfsumme, Nachführen ohne neuen Schnitt). Bei Drive, S3
   und SMB überspringt der Lauf eine Datei mit unverändertem Merkmal, ohne den Ort zu prüfen; dort
   bleibt die Zeile gekürzt, bis sich die Datei ändert. Ein Backfill ist für keinen der beiden Fälle
   vorgesehen. Identität bleibt
   `file_path` in seiner bisherigen Breite.
2. **Löschvermerk statt Zähler.** Die neue Tabelle `source_sync_revisits` hängt am
   Abgleichszustand und fällt mit ihm (`ON DELETE CASCADE`). `LibraryDocumentService.deleteDocument`
   schreibt in derselben Transaktion eine Zeile mit Container und Hierarchiepfad, wenn die
   Bibliothek einen Abgleichszustand hat und das Dokument einen Container trägt. Das ist der
   einzige Löschweg außerhalb eines Laufs, der eine Bibliothek mit Datei-Abgleich trifft; Ordner
   werden über ihn geleert. Die beiden Löschstellen beim Ersatz einer fehlgeschlagenen Upload-Zeile
   schreiben bewusst keinen Vermerk: Sie betreffen nur Upload-Bibliotheken. Eine ArchUnit-Regel
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

Nebenwirkungen:

- Auch eine Bibliothek, deren Store keine Ordner meldet (Drive, S3, SMB), bekommt je manueller
  Löschung einen Vermerk. Ihr nächster vollständiger Vollabgleich verbraucht ihn.
- Eine Bibliothek mit Abgleichszustand, aber ohne Datei-Abgleich (etwa Confluence), bekommt
  ebenfalls Vermerke. Kein Lauf verbraucht sie; sie fallen mit dem Abgleichszustand.

Grenze, gemessen an PostgreSQL 18: Der eindeutige Index `(library_id, file_path)` nimmt höchstens
2676 Byte eines nicht komprimierbaren Pfads auf. Ein `file_path` mit 2000 Zeichen passt deshalb
nur, solange er fast nur aus Ein-Byte-Zeichen besteht; zufällige Zwei-Byte-Zeichen enden bei 1338,
Drei-Byte-Zeichen bei 892 Zeichen. Ein längerer Pfad scheitert beim Speichern mit „index row size
… exceeds btree version 4 maximum 2704“. Das betrifft Stores mit Pfad-Identität (SMB) und ist hier
nicht gelöst (`DocumentFilePathIndexLimitTest`).

Nachtrag (#2241): Die Grenze gilt jetzt im Store. `filesync.FilePathLimit` fasst sie als 2000
Zeichen und 2676 Byte UTF-8; ein Store mit Pfad-Identität meldet einen längeren Pfad als
`Exclusion.Unavailable`, also abgewiesen ohne Download und ohne Fehler, in jedem Lauf.
`DocumentFilePathIndexLimitTest` misst die Konstante gegen das Liquibase-Schema. Der Index
bleibt unverändert; ein eindeutiger Index über einen Hash des Pfads wurde verworfen, weil er
eine Migration und eine geänderte Suche nach `file_path` gebraucht hätte, für Pfade, die unter
Windows ohnehin kaum vorkommen.

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
   folgt der Client nicht (`withDfsEnabled(false)`); Zugangsdaten verlassen den Server also nie. Gespeicherte Zugangsdaten gelten nur für dieselbe Freigabe (seit #2218 über `SourceConnector#credentialBinding`, durchgesetzt im Kern beim Speichern, Testen, Auflisten und Zuordnen); versteckte Freigaben (`*$`, auch `C$`, `ADMIN$`, `IPC$`) werden abgewiesen.
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

## Nachtrag: Abgleichsrunde über mehrere Läufe (#2202, 04.10.2026)

Bis hierhin galt die Fortsetzung eines Stores nur im Lauf, und das Ordnergedächtnis entstand erst
nach einem vollständigen Abgleich. Eine Bibliothek, deren Auflistung mehr kostet als das
Anfragebudget, listete deshalb jeden Lauf dieselben ersten Ordner, bei Nextcloud wie bei SMB. Ein
Vollabgleich ist jetzt eine **Runde**, die sich über mehrere Läufe erstrecken darf.

1. **SPI.** `FilePage.checkpoint` ist eine dauerhafte, für den Kern undurchsichtige Marke je Seite;
   `FileStore#resume(container, checkpoint)` liefert die erste Seite danach. Vertrag: Ein späterer
   Lauf erhält jede Datei, die bis einschließlich dieser Seite nicht geliefert wurde; eine
   fortgesetzte Seite meldet als gelistet nur Ordner, von denen noch keine Datei geliefert wurde.
   Die Marke trägt nie ein Geheimnis und keine Adresse; der Kern behält höchstens eine Million
   Zeichen. `FileAccessException.CheckpointExpired` lässt den Container in derselben Runde neu
   beginnen. `FileStore#absenceProof` sagt, was eine Runde über mehrere Läufe beweist
   (`AbsenceProof`). Stores ohne Marke (Drive, S3) bleiben unverändert: Ihre fertigen Container
   werden wie bisher erneut gelistet, und nur ein Lauf, der alles selbst gelistet hat, gleicht ab.
   `SecretCheckedStore` fragt vor `resume` das Geheimnis.
2. **Persistenz.** `source_sync_state.scan_progress` (`jsonb`, nullbar) hält die Runde:
   Kennung, Gedächtnisgrundlage, je Container Marke, Verfälle in Folge, gelistete Einträge, ob er
   Marken gibt, und die beim Start seiner Auflistung sichtbaren Löschvermerke; dazu die Merkmale,
   übernommenen Merkmale und ungeklärten Ordner der Runde. Fertige Container stehen weiter in
   `completed_scope_keys`. Die Tabelle `source_sync_presence(document_id, sync_state_id, scan_id)`
   hält, welche Dokumente die Runde gesehen hat, und fällt mit Dokument und Zustand.
   `ScanJournal` (in `indexing.source`, weil `indexing.filesync` Spring nicht kennt) schreibt
   Zustand und Präsenz in einer Transaktion und liest die Löschvermerke; nur das Schreiben eines
   Vermerks bleibt am Repository, in der Transaktion der manuellen Löschung.
3. **Sicherungsregel.** Eine Marke wird erst festgeschrieben, wenn jeder Download der Seiten bis zu
   ihr aufgenommen oder als Fehler verbucht ist; gesichert wird am Ende eines Containers und am
   geordneten Ende des Budgets. Präsenz, die erst eine Seite hinter der gesicherten Marke gesehen
   hat, wird dabei nicht geschrieben: Die Seite wird erneut gelistet, und eine inzwischen gelöschte
   Datei gälte sonst als gesehen. Jedes andere Ende (Sperre, abgelehnte Anmeldung, Fehler) schreibt
   nichts Neues und lässt den gespeicherten Stand stehen.
4. **Abwesenheitsbeweis.** Ein Lauf, der jeden Container selbst von der ersten bis zur letzten
   Seite gelistet hat, gleicht ab wie bisher. Sonst entscheidet der Store:
   - `SINGLE_RUN` (Vorgabe, Nextcloud): Die Runde endet ohne Abgleich, speichert aber das
     Gedächtnis. Entfernt wird erst in einem Lauf, der allein alles gelistet oder per Merkmal
     bestätigt hat. Ordner über einem gespeicherten Dokument, das die Runde nicht gesehen hat,
     kommen nicht ins Gedächtnis; sonst bestätigte der nächste Lauf ein gelöschtes Dokument über
     das Merkmal seines Ordners dauerhaft. Dafür schreibt auch eine solche Runde Präsenz, sobald sie
     über Läufe reicht.
   - `LOCATION_IDENTITY` (SMB): Abgleich gegen die Präsenz der Runde. Ein Ort, an dem in keinem
     Lauf eine Datei war, ist unter diesem Pfad wirklich weg. Präsenz schreibt jeder Weg, der eine
     Datei bestätigt, solange eine Runde offen ist, auch der Ereignis- und der Änderungslauf.
   - `CHANGE_FEED` (SharePoint, Dropbox): Am Ende der Runde liest der Kern das Änderungsprotokoll ab
     den Zeigern, die zu Rundenbeginn gehalten wurden, und gleicht erst nach einem fehlerfreien
     Lesen ab. Gebaut mit #2408, siehe Nachtrag „`CHANGE_FEED`-Abschluss“.
5. **Gedächtnis.** Das erste Merkmal, das eine Runde für einen Ordner sieht, gilt: Alles darunter
   wurde danach gelistet. Ändert sich die Grundlage mitten in der Runde, verwirft sie ihre Merkmale.
   Einen Ordner, den die Runde erst als unverändert übernahm und später neu listete, merkt sie
   sich nicht, samt seinen Elternordnern: Seine Zeilen galten vor der Änderung als gesehen. Ob ein
   Store Ordner meldet, entscheidet das Gedächtnis der Runde mit, nicht nur die Seiten des Laufs;
   sonst überspränge eine fortgesetzte Seite ohne Ordnerangabe eine verschobene Datei, ohne ihren
   Ort zu prüfen. Alle drei Fälle fand der Zufallstest (`FileSyncRandomizedRoundTest`) über
   längere Folgen; `FileSyncRoundTest` hält sie einzeln fest.
   Löschvermerke zählen je Container über die beim Start seiner Auflistung gesehene ID-Menge, nicht
   über `created_at`; ein Vermerk, der danach kam, nimmt seine Ordner aus dem Gedächtnis, auch wenn
   der Container schon fertig ist, und bleibt für die nächste Runde.
6. **Reihenfolge und Fortschritt.** Offene Container mit Marke zuerst, dann offene ohne, dann solche
   mit verfallener Marke, zuletzt fertige ohne Marke. Verfällt eine Marke, rückt ihr Container
   hinter die übrigen; nach zwei Verfällen in Folge gilt er für diesen Lauf als nicht listbar.
   `maxEntriesPerRun` gilt je Runde. Die Stillstandsmeldung des Rahmens entfällt für einen Lauf,
   der eine spätere Marke festschrieb oder einen Container abschloss.
7. **Stores.** Nextcloud und SMB gehen in sortierter Tiefensuche. Nextcloud: Die Marke ist der Pfad
   des zuletzt gelisteten Ordners; `resume` fragt die Ordner auf diesem Pfad erneut ab (Kosten: die
   Ordnertiefe) und listet, was in Namensreihenfolge folgt. SMB: Die Marke ist die Stelle im Baum
   (Namen und Datei-IDs der offenen Ordner, letzter erledigter Eintrag), dazu die Datei-IDs aller
   schon betretenen Ordner und die Befunde (nicht lesbar, zu tief). Eine Seite endet nach
   `list-page-size` Einträgen oder geöffneten Ordnern. Ein Ordner wird beim Betreten ganz gelesen und
   sortiert; das ersetzt das stapelweise Lesen über Seiten (Nachtrag SMB, Punkt 9).
8. **SMB-Verknüpfungen über Läufe.** Ein Ordner gilt als Verknüpfung, wenn seine Datei-ID in der
   Runde schon betreten wurde. Die Menge steht in der Marke (vorzeichenlos sortiert, Abstände in
   Basis 36), deshalb ist der Schutz über die Läufe derselbe wie in einem Lauf: Jede Schleife führt
   zu einer schon betretenen ID. Neu ist nur, dass in Namensreihenfolge der zuerst betretene Name
   gewinnt, nicht der zuerst gelistete; in Bestandsbibliotheken kann so einmalig der indexierte
   Pfad eines doppelt erreichbaren Ordners wechseln (gewollte Verhaltensänderung, Handbuch SMB 4).
   Den bisherigen Namen zu bevorzugen machte die Wahl vom Bestand abhängig; sie bliebe über Läufe
   nicht bestimmt. Ein Ordner auf dem Weg zur Marke, der jetzt eine andere, noch nicht betretene ID
   trägt, wird betreten; trägt er eine schon betretene, ist er eine Verknüpfung.
9. **Löschregel.** `onlyTheKnownClassesDeleteDocuments` erfasst auch `@Modifying`-Abfragen von
   `DocumentRepository`, deren Abfrage mit `delete` beginnt, unabhängig vom Namen.
   `onlyTheRunRemovesThroughTheCleanupService` erlaubt die löschenden Methoden von
   `StaleDocumentCleanupService` (und `VanishedDocumentReconciler#reconcile`) nur dem Laufrahmen,
   `FileSync` und dem Confluence-Lauf.

Grenzen:

- SMB: Wird eine Datei oder ein Ordner während einer Runde umbenannt oder verschoben und liegt der
  neue Ort vor der Marke, entfernt die Runde das Dokument unter dem alten Pfad, bevor der neue
  gelistet ist. Ebenso eine Datei, die gelöscht und am selben Ort neu angelegt wird, nachdem ihr
  Ordner gelistet war. Die Lücke dauert bis zur nächsten Runde. Die Alternative `SINGLE_RUN` hieße,
  dass große Freigaben nie etwas entfernen.
- SMB: Die Menge der betretenen Ordner wächst mit dem Bereich. Ab einigen hunderttausend Ordnern je
  Bereich überschreitet die Marke die Grenze des Kerns; dann wird keine neue Marke mehr gesichert.
- Nextcloud: Passt der bestätigende Lauf nie in ein Budget, wird aufgenommen, aber nicht entfernt;
  das Protokoll meldet jeden Abschluss ohne Abgleich.
- Eine Runde über mehrere Läufe schreibt Präsenz je gesehenem Dokument und liest beim Abschluss
  die ganze Präsenz der Runde. Ein Lauf in einer Runde allein schreibt bei `SINGLE_RUN` keine.

Nachbesserung aus dem Review:

- **Unlistbarer Container:** Endet ein Lauf ohne Budget-Ende unvollständig, sind alle übrigen
  Container fertig. Eine Runde mit Marken wird dann verworfen (ohne Abgleich, ohne Gedächtnis),
  und der nächste Lauf beginnt eine neue, die die übrigen Container wieder listet. Sonst würde ein
  dauerhaft nicht listbarer Ordner alle anderen einfrieren.
- **Seitenbuchhaltung:** Gehalten werden nur die letzte festschreibbare Marke und die Seiten mit
  offenen Downloads. Präsenz, die erst nach der gesicherten Marke gesehen wurde, wird am
  Budget-Ende zurückgehalten. Eine Marke über der Grenze nennt das Protokoll einmal je Lauf und
  Container.
- **Verworfener Abgleichszustand:** Ein Zustand, der aus der Datenbank kam, wird nur geschrieben,
  solange seine Zeile besteht (gesperrt in derselben Transaktion). Ändert sich die Quelle während
  eines Laufs und löscht dabei den Zustand, endet der Lauf, statt Runde und Präsenz wieder
  anzulegen.
- **Zurückgebliebene Zeile:** Trifft die Auflistung eine Datei an einem anderen Ort als ihre Zeile
  und scheitert die Aufnahme dort, steht die Zeile weiter am alten Ort. Dieser Ordner wird deshalb
  nicht gemerkt; sonst trüge ein Ordner, der später denselben Namen bekommt, die Zeile als
  unverändert weiter, und sie würde nie entfernt.

Prozesslokaler Zustand entsteht nicht: Die Runde steht in der Datenbank, die Tiefensuche eines
Stores lebt nur im Lauf (ADR-0021 unverändert).

## Nachtrag: Google Drive über Zugang (#2220, 04.10.2026)

- **Profilangabe „optional“** mit der Anmeldeart Dienstkonto-Schlüssel, fester Adresse und der
  Vorgabe `subject`, die nur das Profil setzt (`DefaultKey#profileOnly`). Die Sperre der Registry
  für Konnektoren mit Dienstkonto-Schlüssel entfällt.
- **Ablage:** Der Schlüssel liegt am Profil in `client_secret_ciphertext`, jetzt `text` wie
  `source_credentials`; die Spec-Grenze von `clientSecret` steigt auf 4096. Die Client-ID ist die
  `client_email` des Schlüssels und wird nicht abgefragt; ein Schlüssel eines anderen Kontos ist eine
  Änderung der Registrierung.
- **Signaturpfad:** `EffectiveSourceSettings` → `ConnectionSecrets#current(ProfileOwned)` →
  `SecretIssuer#mint` → `ProfileSignIn` → `ServiceAccountTokens`. Signiert wird weiter nur im Kern,
  der Proxy ist der des Profils. Den Schlüssel sehen `ConnectionProfileService` (prüfen,
  verschlüsseln), `ProfileRegistrations`, `ProfileSignIn` und `ServiceAccountTokens`; library und
  der Konnektor nie. library liest `ServiceAccountKey` nur noch für Bibliotheken ohne Profil.
- **Speichern fragt Google nicht:** Die Prüfung einer Bibliothek vor dem Speichern meldet sich am
  Profil nicht an (`EffectiveSourceSettings#ofDraftToValidate`); Verbindungstest und Auflistung tun
  es.

## Nachtrag: SharePoint (#2153, 05.10.2026)

Dokumentbibliotheken von SharePoint über Microsoft Graph. Paket `indexing.source.sharepoint`, Typ
`SHAREPOINT`, Graph-Zugriff in einem eigenen Paket `io.opaa.msgraph` (foundation, Vorbild
`io.opaa.s3`). Plan: [Kommentar an #2153](https://github.com/criew/opaa/issues/2153#issuecomment-5984246614).
Aus [#2264](https://github.com/criew/opaa/issues/2264) braucht der Konnektor nichts: Client-Credentials
mit Mandanten-Vorlage, `ProfileOwned` und `ProfileSignIn` liegen seit #2257 vor.

**Maintainer-Entscheidungen vom 05.10.2026**
([Übersicht](https://github.com/criew/opaa/issues/2147#issuecomment-5990074460)):

| Punkt | Entscheidung |
|---|---|
| M1 – Identität | `sharepoint://<driveId>/<itemId>`, ohne Deep Link |
| M2 – Ordner umbenannt oder verschoben | Täglicher Vollabgleich als Vorgabe, kein Kern-Umbau; das Handbuch nennt das Fenster von bis zu einem Tag |
| M3 – Berechtigung | `Sites.Selected` empfohlen, `Sites.Read.All` als dokumentierte Alternative |
| K1 – `CHANGE_FEED`-Abschluss | Freigegeben, eigenes Issue [#2408](https://github.com/criew/opaa/issues/2408) unter #2146, vor dem Konnektor |

### Zielbild

**Anmeldung**

- Nur App-only: `SignIn.clientCredentials` mit
  `Endpoint.WithTenant("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token")`, Scope
  `https://graph.microsoft.com/.default`, `CLIENT_SECRET_POST`.
- Profilangabe `REQUIRED`, Adresse fest `https://graph.microsoft.com`, kein TLS-Schalter. Am Zugang
  bleibt das Feld „Scopes“ leer; ein anderer Wert als `.default` scheitert bei Entra.
- Delegiert (OAuth) ist nicht Teil von #2153; das wäre der Weg für OneDrive als verbundenes Konto.

**Berechtigungen**

- Empfohlen `Sites.Selected` (Anwendung) mit Rolle `read` je Site; Alternative `Sites.Read.All`.
  `Files.Read.All` nennt das Handbuch nicht, weil es OneDrives einschließt.
- OneDrive wird technisch abgewiesen: Die Erreichbarkeitsprüfung liest `driveType`; alles außer
  `documentLibrary` ist `ContainerUnlistable`.

**Quelle und Einstellungen**

- `source_settings`: `libraries` mit 1 bis 50 Einträgen `{driveId, folders?: [itemId…]}`, dazu
  `fullSyncIntervalDays` (Vorgabe 1, siehe M2).
- Container ist die Dokumentbibliothek, Schlüssel `drive:<driveId>`. Ordner sind ein Filter im Store,
  kein eigener Container: je Laufwerk eine Aufzählung und ein Strom. Eine geänderte Auswahl verwirft
  den Abgleichszustand (`onSourceChanged`, wie Drive).
- `SourceBrowser` in Stufen über Parameter in den Konnektor-Einstellungen der Anfrage: Site (Suche bei
  `Sites.Read.All`, sonst Adresse eingeben und über `/sites/{host}:/{pfad}` auflösen), Bibliotheken
  (`/sites/{id}/drives`), Ordner (`/drives/{id}/items/{id}/children`).

**Identität und `file_path`**

- `sharepoint://<driveId>/<itemId>`, rund 110 ASCII-Zeichen; die Grenze aus #2267 ist nicht
  erreichbar, keine Abhängigkeit.
- Umbenennen und Verschieben im Laufwerk behalten das Dokument. Ein Wechsel der Bibliothek ist Löschen
  und Neuanlegen.
- Kein Deep Link (`withoutDeepLink()`); das Original liefert `OriginalAccess` über Graph.
  `source_hierarchy_path` ist die Ordnerkette, bei mehreren Bibliotheken steht der Bibliotheksname vorn.

**Änderungsmerkmal**

- `x:<quickXorHash>|<size>|<h(name, parentId)>`, ersatzweise `c:<cTag>|…` oder
  `t:<lastModified>|<size>|…`.
- Der Ortsteil sorgt dafür, dass eine umbenannte oder verschobene Datei einmal geholt wird. Bei
  gleicher SHA-256 führt die Aufnahme Titel und Pfad nach, ohne neu zu schneiden.

**Rechte** werden nicht übernommen (Epic: außerhalb des Umfangs). Das Handbuch sagt deutlich: Was die
App lesen darf, sehen alle Leser der Bibliothek.

**Delta**

- Listing: `GET /drives/{id}/root/delta` mit `$select` und `$top`. `continuation` und `checkpoint`
  sind der Token des `nextLink`; gespeichert wird nur der Token, nie die Adresse, der Store baut die
  URL selbst. `410` ist `CheckpointExpired`. Als gelöscht gemeldete Einträge und Doppelte im Lauf
  filtert der Store.
- Ordnerkette: Delta-Einträge tragen keinen Pfad. Der Store führt je Lauf eine Tabelle
  id → (Name, Eltern) aus den Ordner-Einträgen und holt fehlende Ordner einzeln. Ist die Kette nicht
  auflösbar, ist die Datei `Transient`; sie wird nicht an die Wurzel gelegt, sonst kippte das Merkmal.
- `ChangeFeed`: `feedKey` ist der Container-Schlüssel, `startCursor` kommt über `delta?token=latest`,
  `410` ist `CursorExpired`. `deleted` wird `Change.Removed`, eine Datei außerhalb des Ordnerfilters
  ebenfalls. `absenceProof()` ist `CHANGE_FEED`.

**Drosselung:** `429` und `503` mit `Retry-After` warten über `RateLimitPolicy#waitFor` mit Deckel,
melden sich am Budget (`throttled`) und höchstens `max-retries` mal; danach `Transient`. Die
Gesamtwartezeit je Lauf begrenzt `RequestBudget(maxThrottleWait)`.

**Download:** `GET …/items/{id}/content` leitet auf einen vorab signierten Fremdhost weiter. Nur dieser
Aufruf nutzt `RedirectPolicy.DROP_AUTHORIZATION_HTTPS_ONLY_OFF_ORIGIN` (`GraphClient#download`; nur https, Zielprüfung je Sprung). Die Ziel-URL erscheint
nie in Log oder Meldung, weil sie ein Kurzzeit-Token trägt. Größe aus dem Listing als Vorfilter,
Byte-Deckel beim Kopieren (Vorgabe 50 MiB). OneNote-Pakete (`package`) sind `NotADocument`, Einträge
mit `malware`-Facette `Unavailable`.

**Dauerhaft kaputte Dateien (Vorgabe für S1 aus dem Review von #2269, keine Maintainer-Entscheidung).** `GraphClient` meldet jede Nicht-2xx-Antwort des Downloadhosts und alle
4xx ohne eigene Art (etwa `400`, `409`) als `GraphException.Kind.TRANSIENT`; den Status liefert
`GraphException#status()`. Der `SharePointFileStore` muss daran dauerhaft gescheiterte Dateien von
vorübergehenden unterscheiden: Bei `TRANSIENT` mit 4xx-Status (außer `429`) wählt er eine
Datei-Ausnahme, die die Runde nicht offen hält. Sonst hielte eine einzige dauerhaft kaputte Datei die
Runde für immer offen, der `CHANGE_FEED`-Abschluss würde nie erreicht und Löschungen wären nie
bewiesen. Ein Store-Test mit `FakeGraphServer.failNext("/blob/", 403, …, n)` über mehrere Läufe sichert
das.

**Löschungen, Umbenennen, Verschieben**

| Ereignis | Verhalten |
|---|---|
| Datei gelöscht | Änderungslauf entfernt sie (`deleted`), sonst der Vollabgleich durch Abwesenheit |
| Datei umbenannt oder verschoben | Dokument bleibt, ein Abruf, Titel und Pfad nachgeführt |
| Ordner umbenannt oder verschoben | Der Vollabgleich führt den gespiegelten OPAA-Ordner ohne Download nach (`placeSeen`); `source_hierarchy_path` bleibt alt, bis sich die Datei ändert |
| Ordner aus einem Ordnerfilter herausgeschoben | Dateien bleiben bis zum nächsten Vollabgleich durchsuchbar (M2) |
| Bibliothek nicht erreichbar | `ContainerUnlistable`, kein Löschbefund |

### Belege

Alle Aussagen über Graph und Entra stammen aus API-Kenntnis und sind nicht gegen die Dokumentation
oder einen Tenant geprüft. Bis zum Tenant-Lauf (S3) ist der `FakeGraphServer` die einzige Wahrheit;
Formfehler fallen erst dort auf. Die Ergebnisse des Laufs kommen als weiterer Nachtrag.

| Aussage | Stand |
|---|---|
| Client-Credentials, Mandanten-Vorlage, `ProfileOwned`, `ProfileSignIn` auf main (#2257) | belegt (Code) |
| Länge von `file_path` unterhalb der Grenze aus #2267 | belegt (Rechnung) |
| `GraphException#status()` und Art `TRANSIENT` für 4xx ohne eigene Art | belegt (Code, #2269) |
| `driveId`/`itemId` bleiben bei Umbenennen und Verschieben stabil | unsicher (API-Kenntnis) |
| Wirkung von `Sites.Selected`: zulässige Aufrufe, Fehlerbild für nicht freigegebene Sites (`403` oder `404`), Verhalten der Site-Suche | unsicher |
| Entra-Fehlercodes (falsches oder abgelaufenes Secret, fehlender Admin-Consent, unbekannter Mandant) gegen die Abbildung in `ClientCredentialsGrant` | unsicher |
| `/content`: Zielhosts der Weiterleitung, Durchlass durch die Zielprüfung | unsicher |
| Delta: `$top` wird beachtet; Haltbarkeit von `nextLink`- und Delta-Token; Formen des `410` | unsicher |
| Delta: Eltern vor Kind; `parentReference.path` fehlt; gelöschter Ordner meldet seine Dateien einzeln; Facetten gelöschter Einträge | unsicher |
| Delta meldet bei umbenanntem oder verschobenem Ordner nur den Ordner, nicht die Dateien darunter | unsicher |
| Graph meldet Elternordner bei jeder Änderung darunter mit | unsicher |
| Delta auf Ordnern (`items/{id}/delta`) als spätere Optimierung | unsicher |
| Reale Drosselung und `Retry-After`-Werte (für die Vorgaben) | unsicher |
| `quickXorHash` für alle Dateiarten; `cTag` bei reinen Metadatenänderungen; Umschreiben von Office-Dateien durch SharePoint | unsicher |
| Sonderfälle: ausgecheckte und Entwurfsfassungen, Vertraulichkeitsbezeichnungen, OneNote, Systembibliotheken, Sites privater Teams-Kanäle | unsicher |
| Ein im Browser öffnbarer, ID-stabiler SharePoint-Link | unsicher (nicht belegbar; Anlass für M1) |

### Verworfene Alternativen

- **Offizielles Graph-SDK.** Rund 60 MB Abhängigkeit für eine Handvoll GET-Aufrufe. Die Aufrufe laufen
  über `sourceaccess` in `io.opaa.msgraph`, das tokenneutral ist und von Exchange
  ([#2172](https://github.com/criew/opaa/issues/2172)) mitgenutzt wird.
- **`webUrl` als Identität.** Sie ist pfadbasiert: Umbenennen oder Verschieben ändert sie, das Dokument
  wäre neu. Ein ID-stabiler, öffnbarer Link ist ohne Tenant nicht belegbar. Ein späterer Wechsel der
  Identität hieße Neuindexierung aller SharePoint-Bibliotheken; ein Link-Feld neben `file_path` wäre ein
  großer Umbau am Dokumentmodell.
- **Ordner als Container.** Das vervielfachte Aufzählungen und Ströme je Laufwerk. Der Container ist
  die Bibliothek, der Ordner ein Filter im Store.
- **`fullSyncNeeded` bei Ordner-Einträgen im Protokoll** (Regel von Drive). Sie passt nicht, weil Graph
  Elternordner bei jeder Änderung darunter mitmeldet; fast jeder Änderungslauf würde zum Vollabgleich.
  Stattdessen gilt der tägliche Vollabgleich (M2). Die Alternative, eine dauerhafte Ordnertabelle je
  Bibliothek mit Changeset und einer neuen `Change`-Variante im Kern, wäre ein großer Kern-Umbau.
- **`Files.Read.All` als Empfehlung.** Es schlösse OneDrives ein und gäbe mandantenweite Leserechte;
  empfohlen ist `Sites.Selected` (M3).

### Risiken

Überdauern die `nextLink`-Token keine Läufe, beginnt ein Container neu; nach zwei Verfällen ist er im
Lauf nicht listbar. Unterhalb des Budgets ist das unschädlich.

### Nicht gebaut

Nationale Clouds (`graph.microsoft.us`, China; die Adresse ist fest), Freigabe je Bibliothek über
`Lists.SelectedOperations.Selected`, delegierter Zugang für OneDrive, Rechte der Quelle. Folge-Issues
sind „Ort ohne Download nachführen“ im Kern (träfe auch Drive) und „Änderungslauf mit mehr Seiten als
Budget“.

## Nachtrag: `CHANGE_FEED`-Abschluss (#2408, 05.10.2026)

Der Kern-Eingriff K1 ([#2408](https://github.com/criew/opaa/issues/2408), unter #2146) ist
freigegeben und kommt vor dem SharePoint-Konnektor, damit dieser `CHANGE_FEED` von Anfang an erklärt
und Dropbox ([#2154](https://github.com/criew/opaa/issues/2154)) ihn mitnutzt.

**Warum vor dem Konnektor.** Fachlich ist er für SharePoint keine harte Voraussetzung: Ohne ihn endet
eine Runde über mehrere Läufe wie bei `SINGLE_RUN`, die gehaltenen Cursor werden gültig, und der
nächste Änderungslauf holt alles nach. Tragend wird er, wenn die reine Auflistung das Budget übersteigt
(bei 200 Einträgen je Seite und 20 000 Anfragen rund 4 Mio. Einträge) oder nach einem verfallenen
Cursor.

**Schnitt**

- `AbsenceProof.CHANGE_FEED`: Das Änderungsprotokoll ab den zu Rundenbeginn gehaltenen Cursorn beweist
  Abwesenheit.
- Neuer Zweig in `FileSync#run` nach der Containerschleife, nur wenn `!provenByThisRun()`:
  1. Jeden Strom ab `state.pendingChangeCursors()` bis zur letzten Seite lesen.
  2. `Updated` geht durch `visit` (präsent, bei Bedarf Abruf). `Deselected` und `Removed` laufen wie im
     Änderungslauf, einschließlich `applyRemovals` und Erreichbarkeitsprüfung.
  3. Bei Erfolg: `presenceToFrame()`, Abgleich durch den Rahmen. Im Haken `afterReconciliation(true)`
     erst die neuen Startcursor als ausstehend setzen, dann `finish(true)`.
- Ausgänge ohne Abgleich:
  - `fullSyncNeeded` oder `CursorExpired`: `finish(false)`, Protokollnotiz. Die alten Cursor bleiben;
    der nächste Änderungslauf entscheidet wie heute.
  - Vorübergehender Fehler, nicht erreichbarer Container oder vorübergehend gescheiterte Datei: Die
    Runde bleibt offen, Ergebnis `truncated`. Der nächste Lauf wiederholt nur den Abschluss; fertige
    Container werden nicht neu gelistet.
  - Budget-Ende im Abschluss: Der `catch`-Zweig umfasst ihn, die Runde bleibt offen.
- `ScanRound`: `resumable` behandelt `CHANGE_FEED` nicht als „erschöpft“. Eine Runde ohne gehaltene
  Cursor für alle Ströme wird verworfen, weil der Beweis sonst nicht ab Rundenbeginn gilt. Präsenz wird
  wie heute geschrieben, sobald die Runde über Läufe reicht.
- Das Stromlesen (`readStream`, `apply`, `applyRemovals`) wandert in eine eigene paketprivate Klasse,
  die `runChanges` und der Abschluss teilen. Löschaufrufe bleiben in `FileSync`, sonst müsste
  `RUN_REMOVERS` erweitert werden.
- Kein Changeset, keine SPI-Änderung außer der Enum-Konstante.

**Tests**

- `FileStoreResumptionContract`: Zweig für `CHANGE_FEED` in
  `aFileDeletedAtTheSourceIsRemovedOnlyOnceItsAbsenceIsProven`; am Rundenende wird entfernt.
- Neue Fälle in `FileSyncRoundTest` und im Vertrag:
  - Ein Altdokument ohne Datei und ohne Protokolleintrag wird am Ende einer Runde über mehrere Läufe
    entfernt (Reproduktionsfall; mit `SINGLE_RUN` bleibt es stehen).
  - Eine Datei wandert hinter die Marke: Das Dokument bleibt am neuen Ort.
  - Eine neue Datei im schon gelisteten Teil ist am Rundenende aufgenommen.
  - `fullSyncNeeded` und verfallener Cursor: nichts entfernt.
  - Vorübergehender Fehler: Runde offen, der nächste Lauf listet keinen Container.
  - Budget-Ende im Abschluss.
  - Runde ohne gehaltene Cursor wird verworfen.
  - Ein Lauf, der alles allein listet, liest das Protokoll nicht.
- `FileSyncRandomizedRoundTest`: drittes Szenario (stabile IDs, Protokoll, Marken, `CHANGE_FEED`) mit
  der Invariante „kein Dokument entfernt, dessen Datei existiert“. `InMemoryFileStore` protokolliert
  Änderungen dafür selbst.

**Auswirkungen.** Drive, Nextcloud, SMB und S3 ändern sich nicht; ihre Vertragstests und
`FileSyncChangeRunTest` bleiben unverändert grün (Abnahmekriterium). Der Eingriff ist additiv, hat aber
Löschwirkung (geschätzt 400 bis 600 Zeilen plus Tests) und ist deshalb meldepflichtig. Der Schutz:
Abgleich nur nach fehlerfrei gelesenem Protokoll ab Rundenbeginn, sonst kein Abgleich.

**Umsetzung (#2408).** Gebaut wie oben. Der Schutz braucht zusätzlich die **vollständige Präsenz der
Runde**: Das Protokoll belegt nur, was sich seit Rundenbeginn geändert hat. Alles Unveränderte muss
eine Auflistung der Runde als gesehen eingetragen haben. Unter `CHANGE_FEED` schreibt die Runde ihre
Präsenz deshalb bei jedem Speichern, wie unter `LOCATION_IDENTITY`, und nicht erst, wenn sie über
Läufe reicht. Sonst bliebe ein Container ohne Präsenz, wenn der Lauf, der ihn fertig gelistet hat,
danach scheitert (abgelehnte Zugangsdaten, Eintragsgrenze, Abbruch, Neustart). Der nächste Lauf
überspränge ihn, und der Abschluss entfernte alle seine Dokumente (Review von #2425,
`aContainerCompletedByARunThatThenFailedKeepsItsDocumentsAtTheRoundsEnd`). Aus demselben Grund
trägt auch ein Ereignislauf bei offener Runde Präsenz ein.

Vier Abweichungen vom Schnitt ergaben der Zufallstest und die Abschlussfälle:

- **Nicht erreichbarer Container.** Er zählt beim Abschluss wie ein nicht auflistbarer Container in
  der Auflistung: Die Runde wird aufgegeben, der nächste Lauf beginnt eine neue und listet die übrigen
  Container wieder. Bliebe die Runde offen, wiederholte jeder Lauf nur den Abschluss; ein dauerhaft
  gesperrter Container hielte dann die ganze Bibliothek an, und neue Dateien in erreichbaren
  Containern kämen nie mehr hinein. Gelesen wird kein Strom, solange ein Container nicht erreichbar
  ist.
- **Reihenfolge der Meldungen.** Löschbefunde und abgewählte Dateien warten, bis alle Ströme gelesen
  sind. Meldet ein Strom eine Datei danach wieder als vorhanden, verfällt ihr früherer Löschbefund.
  Der Änderungslauf macht das noch nicht ([#2430](https://github.com/criew/opaa/issues/2430)).
- **Ordnergedächtnis.** Eine im Abschluss gemeldete Datei hält ihren Ordner aus dem Gedächtnis der
  Runde heraus (`unsettle`), wie der Änderungslauf das Gedächtnis der Container verwirft, die er
  geändert hat. Die nächste Runde listet diesen Ordner wieder, statt ihn über ein Merkmal von vor
  der Änderung als unverändert zu übernehmen.
- **Neue Runde, neue Zeiger.** Eine Runde, die nicht aus einem früheren Lauf fortgesetzt wird, holt
  frische Startzeiger, auch wenn eine abgebrochene Runde noch welche hielt. So bleibt das Protokoll
  des Abschlusses so kurz wie die Runde.

Offen bleibt die Grenze aus dem Plan: Braucht das Protokoll seit Rundenbeginn mehr Anfragen als ein
Lauf hat, endet jeder Abschluss am Budget, und die Runde kommt nicht zum Ende. Gelöscht wird dabei
nichts. Folge-Issue: [#2413](https://github.com/criew/opaa/issues/2413), es gilt auch für den
Änderungslauf.

## Nachtrag: Abgleichstand mit Fingerabdruck seiner Einstellungen (#2268, 09.10.2026)

Bis hierhin verwarf eine Änderung, die den Abgleichstand ungültig macht, ihn nur einmal: Der
Konnektor löschte die Zeile in `source_sync_state` (`onSourceChanged`). Ein Lauf, der die alten
Einstellungen schon gelesen hatte, schrieb danach einen neuen Stand unter ihnen; der nächste Lauf
setzte Runde, Präsenz, Änderungszeiger, Anker und Ordnergedächtnis unter den neuen Einstellungen
fort. Bei `LOCATION_IDENTITY` und `CHANGE_FEED` entfernte der Abschluss einer solchen Runde
Dokumente, deren Datei unter den neuen Einstellungen existiert (die Runde hatte sie unter den alten
nicht gesehen). Bei Drive und Confluence las der nächste Lauf Zeiger bzw. Anker der alten Auswahl
weiter. `RunStateResets` (ADR-0041, #2164) milderte das nur im Speicher und nur für private
Bibliotheken.

1. **Fingerabdruck.** `SyncStateBasis` bildet SHA-256 über Adresse, Pfad und
   `SourceConnector#settingsState` der effektiven Einstellungen, mit sortierten Schlüsseln. Was der
   Konnektor nicht vergleicht (etwa der Vollabgleich-Rhythmus), erzwingt keinen Vollabgleich.
   Proxy, Zertifikatsprüfung und Geheimnis zählen nicht. Ein Rahmen ohne Konnektorregister (Tests)
   nimmt alle Konnektoreinstellungen.
2. **Persistenz.** `source_sync_state.settings_basis` (`varchar(64)`, nullbar, additiv). Ein Stand
   ohne Fingerabdruck, also jeder vor dieser Änderung geschriebene, gilt als abweichend; es gibt
   keinen Backfill.
3. **Übernahme.** Der Laufrahmen bildet den Fingerabdruck aus den Einstellungen, mit denen der Lauf
   beginnt (`IndexingRun#settingsBasis`). `IndexingRun#adopt` verwirft einen Stand mit anderem
   Fingerabdruck vollständig (Runde, fertige Bereiche, Änderungszeiger, Anker, Ordnergedächtnis,
   letzter Vollabgleich) und vermerkt das im Protokoll; Löschvermerke bleiben. `FileSync` übernimmt
   im Konstruktor, also für Vollabgleich, Änderungs- und Ereignislauf; Confluence beim Laden. Jeder
   gespeicherte Stand trägt so den Fingerabdruck des Laufs, der ihn schrieb. Ein Stand, den ein
   Lauf mit alten Einstellungen nach der Änderung schreibt, wird damit vom nächsten Lauf verworfen,
   auch nach einem Neustart.
4. **Abwesenheitsbeweis in der Runde.** `ScanProgress.proof` hält den `AbsenceProof`, unter dem die
   Runde ihre Präsenz schrieb. Eine Runde unter einem anderen Beweis wird nicht fortgesetzt: Eine
   Runde unter `SINGLE_RUN` schreibt Präsenz erst, wenn sie über Läufe reicht, und fortgesetzt unter
   `LOCATION_IDENTITY` entfernte sie den Bestand eines Containers, den ein gescheiterter Lauf fertig
   gelistet hatte.
5. **Änderung während des Laufs.** Vor jedem Entfernen durch Abwesenheit liest der Rahmen die
   Einstellungen der Bibliothek neu (`IndexingRun#settingsUnchanged`, über `resolveForChange` und
   die frisch gelesene Bibliothek). Weicht der Fingerabdruck ab oder lassen sie sich nicht lesen,
   gleicht der Lauf nicht ab und gilt als unvollständig. `FileSync` prüft dasselbe vor dem
   `CHANGE_FEED`-Abschluss (die Runde bleibt offen) und im Änderungslauf vor den Löschbefunden
   (keine Löschung, kein Zeiger rückt vor). Ein ABA-Wechsel während eines Laufs (hin und zurück)
   bleibt unerkannt; beide Lesungen sehen dann dieselben Einstellungen.
6. **Was entfällt, was bleibt.** `RunStateResets` und `SourceConnectionResolver#runEnded` entfallen.
   `onSourceChanged` bleibt: Es verwirft den Stand sofort, und der Wechsel des verbundenen Kontos
   (`SourceChangeGate#accountChanged`) ist keine Einstellung und hat keinen Fingerabdruck. Die
   Ablehnung `CONNECTION_PROFILE_RUN_IN_PROGRESS` für geteilte Bibliotheken bleibt bewusst: Ein
   laufender Lauf holt jedes Element mit dem Geheimnis, das gerade gilt, nach der Änderung also als
   das neue Konto unter den alten Einstellungen. Der Fingerabdruck hält Stand und Abgleich davon
   frei, nicht die Aufnahme.

Nachbesserung aus dem Review:

- **Abschluss über das Protokoll:** Die Prüfung aus Punkt 5 steht zusätzlich unmittelbar vor dem
  Anwenden der Löschbefunde, nicht nur vor dem Lesen; eine Änderung während des Lesens entfernte
  sonst noch.
- **Abgewählte Dateien im Änderungslauf** warten wie gemeldete Löschungen in `ChangeStreams` auf
  `applyRemovals`, statt sofort zu entfernen; sonst umgingen sie die Prüfung.
- **Betriebsart:** `DefaultRunModes` wählt für einen Lauf ohne gewählte Betriebsart (Zeitplan,
  manuell ohne Angabe, übergelaufener Ereignisstapel) `FULL`, wenn der Executor ihn kennt und der
  gespeicherte Stand einen anderen oder keinen Fingerabdruck trägt. Kein Konnektor baut das nach.
- **Geheimnisfreie Lesart:** Die Nachprüfung liest über `SourceConnectionResolver#settingsOnly`
  (`Purpose.SETTINGS_ONLY`), ohne Anmeldung beim Anbieter. Nicht lesbare Einstellungen haben einen
  eigenen Protokollvermerk, ebenso ein Stand ohne Fingerabdruck.
- **Konto:** `SourceConnectionResolver#connectedAccount` (die Kontokennung der Verbindung, nie das
  Geheimnis, ohne Groß- und Kleinschreibung) geht in den Fingerabdruck. Damit verwirft auch ein
  Kontowechsel einen Stand, den ein Lauf des alten Kontos zurückschreibt, etwa den Anker von
  Confluence. Punkt 6 ist insoweit überholt.
- **Hin- und Rückwechsel während eines Laufs** hat laut Review keine Löschwirkung; es bleibt ohne
  Revisionszähler.
- `SourceChangeGate#discardAgain` entfällt mit `RunStateResets`.

Tests: `FileSyncSettingsBasisTest` (fünf Reproduktionsfälle, auf `main` rot),
`FileSyncRandomizedRoundTest` (der Filter der Bibliothek wechselt zwischen und während der Läufe;
die Invariante gilt für Dateien, die der geltende Filter zulässt), `SourceSyncStateTest`,
`SyncStateBasisTest`, `IndexingRunTemplateTest`, `ConfluenceIndexingExecutorTest` und der
Delta-Test `SourceSyncSettingsBasisMigrationTest`.

## Referenzen

- [ADR-0017](0017-quellentypmodell-indizierung.md), [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md)
- [ADR-0023](0023-confluence-konnektor.md) — Laufrahmen, Anker, Löschbefund
- [ADR-0027](0027-s3-konnektor.md) — S3, Vorbild für Bereiche, Merkmal, Ordner
- [ADR-0038](0038-steckbare-konnektoren.md) — steckbare Konnektoren (vierte Anmeldeart im Nachtrag „Verbindungsprofile“)
- [ADR-0041](0041-verbindungen-als-eigenes-modul.md) — Verbindungen, Port, Umbau der Lauf-SPI (#2178)
- [connector-connections.md](../features/connector-connections.md) — Verbindungsprofile
- `backend/src/main/java/io/opaa/indexing/source/AGENTS.md`, `ModularArchitectureTest`
