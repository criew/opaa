# ADR-0045: Gemeinsamer Datei-Abgleich (`indexing.filesync`)

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ersetzt Entscheidung 1 von [ADR-0040](0040-google-drive-konnektor.md)
· ergänzt [ADR-0027](0027-s3-konnektor.md) · ergänzt durch [ADR-0046](0046-smb-konnektor.md) und
[ADR-0047](0047-sharepoint-konnektor.md)

## Kontext

Vor Google Drive (#2148) steckte alles, was Dateiablagen gemeinsam brauchen — Download, Formatfilter,
Änderungsmerkmal, Ordnerspiegel, Wiederaufnahme — S3-gebunden in `S3FullSync`. Die weiteren Ablagen
unterscheiden sich in dem, was sie über Änderungen wissen: Drive und SharePoint haben ein
Änderungsprotokoll, Nextcloud hat keines, aber Ordner-Prüfsummen (ETag), die sich bis zur Wurzel
fortpflanzen, SMB hat nichts von beidem. Eine Bibliothek, deren Auflistung mehr kostet als das
Anfragebudget eines Laufs, listete zudem in jedem Lauf dieselben ersten Ordner. Gebraucht wird ein
Abgleich im Kern, der je Ablage nur noch einen schmalen Port verlangt und dessen Löschwirkung
(„Dokument fehlt in der Quelle“) auch dann hält, wenn eine Auflistung mehrere Läufe braucht.

## Entscheidung

### 1. Ein Kernpaket `indexing.filesync`

Der Abgleich liegt in `io.opaa.indexing.filesync` (Modul knowledge), letzter Eintrag von
`ModularArchitecture.INDEXING_CORE` hinter `indexing.maintenance`. Konnektoren hängen von ihm ab, es
kennt keinen Konnektor und keinen Anbieter (`theFileSyncKnowsNoProvider`); eine neue Modulkante
entsteht nicht. Nicht unter `indexing.source`, weil jedes direkte Unterpaket dort als Konnektor gilt
(`connectorsDoNotKnowEachOther`). Entfernt wird nur über den Laufrahmen:
`IndexingRun#removeWithAttachments` (Port `VanishedDocumentReconciler#removeWithAttachments`); `indexing.filesync` importiert `indexing.maintenance` nicht. Spring kennt das Paket nicht; was
Transaktionen braucht (`ScanJournal`), liegt im Konnektor-Vertrag `indexing.spi`.

`FileSync` besitzt Vorfilter nach Endung und Größe, Merkmalsvergleich, Download-Pool und
Temp-Dateien, Aufnahme über `DocumentIngestService`, Ordnerspiegel (`SourceFolderMirror`) und
Aufräumen, Wiederaufnahme je Container, Fehlerabbildung auf Protokollkategorien und die
Zusammenfassung. Der Konnektor besitzt Anmeldung, Auflistung, Identität, Merkmalsform,
Ordnersegmente, Formatwahl und die Übersetzung seiner Fehler in die neutralen Arten. Schichtung und
Paketregeln: `backend/AGENTS.md`, `indexing/source/AGENTS.md`.

### 2. Der Port `FileStore`

Ein Konnektor implementiert je Lauf einen `FileStore` (eine offene Verbindung, mit dem Lauf
geschlossen): `containers()` in Konfigurationsreihenfolge, `list(container, continuation)`
seitenweise, `head` für Einzelprüfungen, `fetch(entry, maxBytes)` in eine Temp-Datei mit Byte-Deckel
beim Kopieren, `changes()` (leer ohne Änderungsprotokoll), `recall`, `resume`, `absenceProof` und
`meter`. `FilePage` trägt Einträge, Fortsetzung (nur im Lauf gültig), Marke (`checkpoint`, dauerhaft,
für den Kern undurchsichtig, nie Geheimnis oder Adresse, höchstens `MAX_CHECKPOINT_LENGTH` Zeichen),
`unchangedSubtrees` und `listedSubtrees`. `FileEntry` nennt Identität im Container, `filePath` als
Dokumentidentität, Dateiname, Ordnerkette, Kontext (Container-Schlüssel, Hierarchiepfad), Größe (`-1`
unbekannt), Änderungsmerkmal, Medientyp und `Exclusion` (`NotADocument`, `Deselected`, `Unavailable`).
Fehler sind neutrale Arten in `FileAccessException`: `ContainerUnlistable`, `Gone`, `Unreadable`,
`Unavailable`, `TooLarge`, `RunEnding` mit `CredentialsRejected`, `CursorExpired`, `CheckpointExpired`,
`Transient`. `SecretCheckedStore` fragt vor jeder Anfrage das Geheimnis beim Kern. Ein gelisteter
Eintrag gilt als vorhanden (`markPresent`); ein `ContainerUnlistable` macht den Lauf unvollständig,
in dem Container wird nichts entfernt. Rechteentzug und ein `404` auf eine Einzelprüfung sind kein
Löschbefund; nur `Gone` und das Fehlen in einer vollständigen Auflistung sind einer.
`FileStoreContract` prüft den Port, `FileStoreFolderContract`, `FileStoreResumptionContract` und
`FileStoreChangeFeedContract` die Zusätze.

### 3. Änderungsprotokoll (`ChangeFeed`)

Ein Store mit Protokoll bedient seine Container aus Strömen: `feedKey(container)`,
`startCursor(feedKey)`, `read(feedKey, cursor)` liefert `ChangePage` (`Updated`, `Removed`, nächste
Seite oder neuer Start, `fullSyncNeeded`), `requireReachable(container)`. Die Cursor liegen in
`source_sync_state.change_cursors`. Beim ersten Beginn eines Vollabgleichs holt der Lauf die
Startcursor aller Ströme **vor** der Auflistung und sichert sie als ausstehend; eine Fortsetzung
behält sie, erst `completeFullSync` macht sie gültig (Anker wie ADR-0023, Entscheidung 4). Ein
Container, der beim Start nicht erreichbar ist, bekommt den Platzhalter
`ChangeFeed.UNREACHABLE_AT_START`, der nie gesendet wird; ihn zu lesen ist `CursorExpired`.
`CursorExpired` verwirft den Strom, der nächste Lauf ist ein Vollabgleich. Der Änderungslauf
(`FileSync#runChanges`, `ChangeStreams`) liest jeden Strom ab seinem Cursor und übernimmt den neuen nur,
wenn kein Element vorübergehend scheiterte. Ein Löschbefund gilt nur für Bereiche, die in diesem
Lauf erreichbar sind, wird erst angewendet, wenn alle Ströme gelesen sind, und verfällt, wenn ein
Strom die Datei danach wieder als vorhanden meldet; die Löschbefunde eines Stroms, der
nicht bis zur letzten Seite gelesen wurde, werden verworfen. Abgewählte Dateien warten wie
Löschbefunde auf `applyRemovals`. `fullSyncNeeded` (Strukturänderung) merkt vor, dass der nächste
Lauf ein Vollabgleich ist.

### 4. Ordnergedächtnis

Ein Ordner heißt nach dem Hierarchiepfad seiner Dokumente (`SourceDocumentContext`, `""` für die
Containerwurzel). Ein Store, der Ordner meldet, nennt je Seite die Merkmale der gelisteten Ordner
(`listedSubtrees`) und überspringt Ordner, deren Merkmal dem übergebenen gleicht
(`unchangedSubtrees`); `FileSync` behält jedes gespeicherte Dokument darunter. Vertrag von
`FileStore#recall`: Das Merkmal eines Ordners ändert sich bei jeder Änderung in oder unter ihm; ein
umbenannter oder verschobener Ordner hat an seinem neuen Pfad keines und wird gelistet. Ein Store,
der Ordner meldet, setzt den Container-Schlüssel als Kontext jedes Eintrags, und kein Ordnername
enthält den Trenner ` / ` (`SourceDocumentContext.HIERARCHY_SEPARATOR`). Ein
vollständiger Vollabgleich speichert die Merkmale in `source_sync_state.subtree_markers`, der nächste
Lauf übergibt sie vor der ersten Seite (`FolderMemory`). Regeln:

- Ein Ordner mit einem ungeklärten Eintrag (fehlgeschlagener Abruf oder Aufnahme, Kontingent,
  nicht lesbar, nicht verfügbar, zu groß) wird nicht gemerkt, ebenso wenig jeder Ordner darüber.
- Das Gedächtnis gilt nur unter Größengrenze und Formatsatz seiner Entstehung und nur bis
  `FileSyncSettings.subtreeMemoryMaxAge` ab dem letzten Lauf, der alle Ordner gelistet hat.
- Ein Eintrag wird nur übersprungen, wenn sein Dokument am gelisteten Ort steht (Container,
  Hierarchiepfad, Dateiname); sonst wird er einmal geholt, und bei gleicher SHA-256 führt die Aufnahme
  Titel, Pfad und Ordner nach, ohne neu zu schneiden.
- Ein Ordner über einem Dokument, das vorgemerkt (ADR-0018) oder nicht indexiert ist, oder über
  einem Löschvermerk (Entscheidung 5) bekommt kein Merkmal übergeben. Meldet ein Store einen Ordner
  als unverändert, dessen Merkmal ihm nicht übergeben wurde, gilt der Container als unvollständig.
- Grundlage `v2`: Ein unter `v1` gemerktes Gedächtnis gilt nicht.

### 5. Löschvermerk statt Zeilenzähler

`source_sync_revisits` hängt am Abgleichszustand (`ON DELETE CASCADE`). `LibraryDocumentService
#deleteDocument` schreibt in derselben Transaktion eine Zeile mit Container und Hierarchiepfad, wenn
die Bibliothek einen Abgleichszustand hat und das Dokument einen Container trägt — der einzige
Löschweg außerhalb eines Laufs, der eine Bibliothek mit Datei-Abgleich trifft; die Löschstellen beim
Ersatz einer fehlgeschlagenen Upload-Zeile schreiben bewusst keinen Vermerk. Ein Vollabgleich liest
die Vermerke zu Beginn und beim Speichern des Gedächtnisses (`FolderRevisits`); verbraucht werden
nach dem Speichern nur Vermerke vom Beginn der Auflistung eines bis zur letzten Seite gelisteten
Containers. Änderungs- und Ereignislauf lassen sie stehen. `onlyTheKnownClassesDeleteDocuments` hält
die Aufrufer von `DocumentRepository#delete…` fest (einschließlich `@Modifying`-Abfragen);
`onlyTheRunRemovesThroughTheCleanupService` erlaubt die löschenden Methoden von
`StaleDocumentCleanupService` nur dem Laufrahmen (`ModularArchitecture.RUN_REMOVERS`:
`IndexingRunTemplate`, `IndexingRun`); Konnektoren und `FileSync` löschen über
`IndexingRun#removeWithAttachments`.

### 6. Eine Runde über mehrere Läufe

Ein Vollabgleich ist eine Runde (`ScanRound`), die sich über Läufe erstrecken darf.
`FileStore#resume(container, checkpoint)` liefert die erste Seite nach einer Marke; ein späterer Lauf
erhält jede Datei, die bis einschließlich dieser Seite nicht geliefert wurde, und eine fortgesetzte
Seite meldet als gelistet nur Ordner, von denen noch keine Datei kam. `CheckpointExpired` lässt den
Container in derselben Runde neu beginnen; nach zwei Verfällen in Folge gilt er für diesen Lauf als
nicht listbar. Stores ohne Marke (Drive, S3) bleiben unverändert: Nur ein Lauf, der alles selbst
gelistet hat, gleicht ab.

- **Persistenz:** `source_sync_state.scan_progress` (`jsonb`) hält Kennung, Gedächtnisgrundlage,
  Beweisart (`ScanProgress.proof`), je Container Marke, Verfälle, gelistete Einträge und die beim
  Start seiner Auflistung sichtbaren Löschvermerke, dazu Merkmale und ungeklärte Ordner; fertige
  Container stehen in `completed_scope_keys`. `source_sync_presence(document_id, sync_state_id,
  scan_id)` hält, welche Dokumente die Runde gesehen hat. `ScanJournal` schreibt Zustand und Präsenz in
  einer Transaktion.
- **Sicherungsregel:** Eine Marke wird erst festgeschrieben, wenn jeder Download der Seiten bis zu
  ihr aufgenommen oder als Fehler verbucht ist; gesichert wird am Ende eines Containers und am
  geordneten Ende des Budgets. Präsenz hinter der gesicherten Marke wird nicht geschrieben. Jedes
  andere Ende (Sperre, abgelehnte Anmeldung, Fehler) lässt den gespeicherten Stand stehen. Ein
  Zustand, der aus der Datenbank kam, wird nur geschrieben, solange seine Zeile besteht.
- **Reihenfolge:** offene Container mit Marke, offene ohne, solche mit verfallener Marke, zuletzt
  fertige ohne Marke. `maxEntriesPerRun` gilt je Runde; die Stillstandsmeldung entfällt für einen Lauf,
  der eine Marke festschrieb oder einen Container abschloss.
- **Unlistbarer Container:** Endet ein Lauf ohne Budget-Ende unvollständig, wird eine Runde mit Marken
  verworfen (ohne Abgleich, ohne Gedächtnis), damit ein dauerhaft unlistbarer Ordner nicht alle anderen
  einfriert.
- **Gedächtnis in der Runde:** Das erste Merkmal, das eine Runde für einen Ordner sieht, gilt; ändert
  sich die Grundlage mitten in der Runde, verwirft sie ihre Merkmale. Ob ein Store Ordner meldet,
  entscheidet das Gedächtnis der Runde mit, nicht nur die Seiten des Laufs. Ein
  Ordner, den die Runde erst übernahm und später neu listete, wird nicht gemerkt; eine Zeile, die an
  einem anderen Ort angetroffen und dort nicht aufgenommen wurde, hält ihren alten Ordner aus dem
  Gedächtnis. `FileSyncRandomizedRoundTest` prüft die Invariante „kein Dokument entfernt, dessen Datei
  existiert“ über zufällige Folgen, `FileSyncRoundTest` die Fälle einzeln.

Prozesslokaler Zustand entsteht nicht; die Tiefensuche eines Stores lebt nur im Lauf (ADR-0021).

### 7. Abwesenheitsbeweis

Hat ein Lauf jeden Container selbst von der ersten bis zur letzten Seite gelistet, gleicht er ab.
Sonst entscheidet `FileStore#absenceProof` (`AbsenceProof`):

- `SINGLE_RUN` (Vorgabe, Nextcloud): Die Runde endet ohne Abgleich, speichert aber das Gedächtnis;
  Ordner über einem Dokument, das die Runde nicht sah, kommen nicht hinein.
- `LOCATION_IDENTITY` (SMB, Dropbox): Abgleich gegen die Präsenz der Runde; ein Ort, an dem in
  keinem Lauf eine Datei war, ist unter diesem Pfad weg. Präsenz schreibt jeder bestätigende Weg,
  auch Ereignis- und Änderungslauf.
- `CHANGE_FEED` (SharePoint): Am Rundenende liest `FileSync` jeden Strom ab den zu
  Rundenbeginn gehaltenen Cursorn bis zur letzten Seite; `Updated` geht durch die Aufnahme, Lösch- und
  Abwahlbefunde warten bis alle Ströme gelesen sind; erst nach fehlerfreiem Lesen folgt der Abgleich,
  dann werden die neuen Startcursor ausstehend. Die Runde schreibt ihre Präsenz bei jedem Speichern
  (sonst verlöre ein nach fertiger Auflistung gescheiterter Lauf den Container). `fullSyncNeeded` oder
  `CursorExpired` beenden ohne Abgleich; ein vorübergehender Fehler lässt die Runde offen und der
  nächste Lauf wiederholt nur den Abschluss; ein nicht erreichbarer Container gibt die Runde auf. Eine
  im Abschluss gemeldete Datei nimmt ihren Ordner aus dem Gedächtnis (`unsettle`); eine neue Runde holt
  frische Startcursor. Eine Runde ohne gehaltene Cursor für alle Ströme wird verworfen.

### 8. Abgleichstand mit Fingerabdruck seiner Einstellungen

`SyncStateBasis` bildet SHA-256 über Adresse, Pfad, `SourceConnector#settingsState` der effektiven
Einstellungen und die Kontokennung der Verbindung (`SourceConnectionResolver#connectedAccount`);
Proxy und Geheimnis zählen nicht. Er steht in `source_sync_state.settings_basis`; ein Stand ohne
Fingerabdruck gilt als abweichend, es gibt keinen Backfill. `IndexingRun#adopt` verwirft einen Stand
mit anderem Fingerabdruck vollständig (Runde, fertige Bereiche, Cursor, Anker, Gedächtnis, letzter
Vollabgleich) und vermerkt das im Protokoll; Löschvermerke bleiben. `FileSync` übernimmt im
Konstruktor (Vollabgleich, Änderungs- und Ereignislauf), Confluence beim Laden. Vor jedem Entfernen durch
Abwesenheit liest der Rahmen die Einstellungen ohne Geheimnis neu
(`IndexingRun#settingsUnchanged`, `SourceConnectionResolver#settingsOnly`); weicht der Fingerabdruck
ab, gleicht der Lauf nicht ab — `FileSync` prüft dasselbe vor dem `CHANGE_FEED`-Abschluss und vor den
Löschbefunden des Änderungslaufs. Eine Runde unter einem anderen Beweis wird nicht fortgesetzt.
`DefaultRunModes` wählt für einen Lauf ohne gewählte Betriebsart `FULL`, wenn der Stand einen anderen
oder keinen Fingerabdruck trägt. `onSourceChanged` bleibt: Es verwirft sofort. Ein ABA-Wechsel
während eines Laufs bleibt unerkannt.

### 9. Kontingent im Dateiabgleich

`FileSync` fragt vor dem Abruf `DocumentIngestService#rejectionBeforeDownload` mit der Größe aus
dem Listing — beratend, ohne `holdIntake`; maßgeblich bleibt die Aufnahme (ADR-0049, Entscheidung
4). Jede Ablehnung an einem der beiden Kontingente zählt eigens (`ChangeStreams.Sink#quotaHolds`),
nicht als vorübergehender Fehler: Sie hält für ihren Strom den Cursor und hebt das Ordnergedächtnis
auf, nie den Abschluss einer Runde und nie die Entfernungen; am Ende einer `CHANGE_FEED`-Runde behält
der Strom den Rundenbeginn-Cursor. Eine Ablehnung in der Auflistung eines Vollabgleichs setzt
`SourceSyncState#holdFullSyncAtQuota` (`full_sync_quota_held`): `completeFullSync` lässt
`fullSyncCompletedAt` leer, der nächste Lauf ist wieder ein Vollabgleich, solange die Bibliothek voll
bleibt. Ein Confluence-Vollabgleich setzt dieselbe Marke, sobald ein Lauf etwas ablehnt, und speichert
sie sofort, weil die abgelehnte Seite älter ist als der Anker, mit dem er endet. Eine untertreibende
Listengröße kostet den Download, eine übertreibende hält die Datei zurück, bis Platz für die
gelistete Größe frei ist.

### 10. Pfadlänge

`io.opaa.knowledge.FilePathLimit` fasst die Grenze des eindeutigen Index `(library_id, file_path)`
als 2000 Zeichen und 2676 Byte UTF-8 (`DocumentFilePathIndexLimitTest` misst sie gegen das
Liquibase-Schema). `DocumentIngestService` lehnt einen längeren Pfad vor jedem Schreibzugriff ab
(`FILE_PATH_TOO_LONG`), bei einer Dateiablage schon vor dem Download; die Ablehnung gilt als erledigt,
nicht als ungeklärt. `source_hierarchy_path` ist `text`; eine früher gekürzte Zeile stellt bei
einem Store, der Ordner meldet, der nächste Lauf richtig, bei den übrigen bleibt sie bis zur nächsten
Änderung der Datei.

### 11. Nextcloud-Store

Nextcloud (`indexing.source.nextcloud`, WebDAV über `io.opaa.sourceaccess`) ist der Vollscan-Fall
mit Ordnergedächtnis: Container ist ein konfigurierter Ordner, eine Seite ein `PROPFIND` der Tiefe 1,
Tiefensuche in Namensreihenfolge; das Ordnermerkmal ist der ETag, der sich bis zur Wurzel des Nutzers
fortpflanzt (am offiziellen Image geprüft, auch für Freigaben und Group Folders; ein umbenannter
Ordner behält seinen). Identität ist `oc:fileid` hinter der Adresse, die die Datei in der
Weboberfläche öffnet (`…/index.php/f/<fileid>`); Umbenennen und Verschieben behalten das Dokument.
Die Marke ist der Pfad des zuletzt gelisteten Ordners, `resume` fragt die Ordner auf diesem Pfad
erneut ab. Anmeldung mit App-Passwort für Bibliothek und Person (erster Konnektor für Personen,
ADR-0049). Ein eigener ADR ist nicht nötig: Über den Port hinaus trifft Nextcloud keine eigene
Entscheidung; Betrieb und Grenzen stehen im Handbuch (`konnektoren/nextcloud.md`),
`nextcloudIntegrationTest` läuft gegen das offizielle Image.

## Konsequenzen

**Einfacher:** Ein weiterer Datei-Konnektor implementiert nur den Port; Abgleich, Protokoll,
Ordnerspiegel, Runde und Löschwirkung kommen aus `filesync`. Große Freigaben werden über Läufe
hinweg fertig gelistet.

**Schwieriger:** Was `filesync` von oben bräuchte, muss als Port kommen. Eine Runde über Läufe
schreibt Präsenz je gesehenem Dokument und liest sie beim Abschluss ganz. Der Abgleich hat
Löschwirkung; jede Änderung an Runde, Beweis oder Gedächtnis ist meldepflichtig und läuft gegen den
Zufallstest.

**Restrisiken:** Unter `SINGLE_RUN` wird nie entfernt, wenn der bestätigende Lauf nie in ein Budget
passt; das Protokoll meldet jeden Abschluss ohne Abgleich. Unter `LOCATION_IDENTITY` entfernt die
Runde ein Dokument, das während der Runde hinter die Marke verschoben wurde, bis zur nächsten Runde.
Ein Store ohne Ordnermerkmal (Drive, S3, SMB) bekommt je manueller Löschung einen Vermerk, den erst
sein nächster vollständiger Vollabgleich verbraucht; eine Bibliothek ohne Datei-Abgleich (Confluence)
bekommt Vermerke, die niemand verbraucht und die mit dem Zustand fallen.

## Verworfene Alternativen

- `indexing.source.filesync` oder Abgleich im Vertragspaket — Strukturtests rot (Entscheidung 1).
- Basisklasse im S3-Paket — Konnektoren kennen einander nicht.
- Eigenes Modul zwischen knowledge und connectors — neue Modulkante für ein Paket, das im Kern
  denselben Platz hat.
- Identität aus dem Ordnerpfad, Umbenennung als Löschen und Neuanlegen — jede Ordnerumbenennung
  verarbeitete alles darunter neu.
- Kette aus Ordner-IDs als `file_path` — jede Verschiebung erzeugte ein neues Dokument.
- Ordnermerkmale im Konnektor speichern — ein Konnektor hat keinen Zustand, nur `FileSync` kennt
  ungeklärte Einträge.
- Zeilenzähler statt Löschvermerk — übersah Löschungen, die ein Ereignislauf ausglich.
- `SINGLE_RUN` auch für SMB — große Freigaben entfernten nie etwas.
- Eindeutiger Index über einen Hash von `file_path` — Migration und geänderte Suche für Pfade, die
  kaum vorkommen.
- Pfadgrenze im Store (`Exclusion.Unavailable`) — hielt den Ordner im Gedächtnis offen; zentral in
  der Aufnahme gilt sie für jede Quelle.

## Offen

- Braucht das Protokoll seit Rundenbeginn mehr Anfragen als ein Lauf hat, endet jeder
  `CHANGE_FEED`-Abschluss am Budget und die Runde kommt nicht zum Ende; gelöscht wird nichts
  ([#2413](https://github.com/criew/opaa/issues/2413), gilt auch für den Änderungslauf).
- Die Marke eines Stores mit ID-Menge (SMB) überschreitet ab einigen hunderttausend Ordnern je
  Bereich `MAX_CHECKPOINT_LENGTH`; dann wird keine neue Marke mehr gesichert.
- „Ort ohne Download nachführen“ im Kern (träfe Drive und SharePoint).
