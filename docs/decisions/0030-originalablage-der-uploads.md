# ADR-0030: Originalablage der Uploads — ein Port, zwei Adapter, Dateisystem als Standard

## Status

Vorgeschlagen

## Kontext

Hochgeladene Originale liegen heute in einem konfigurierten Verzeichnis
(`opaa.upload.storage-path`, Standard `./uploads`, Compose: Bind-Mount `./uploads` auf
`/app/uploads`). Darunter gibt es je Bibliothek ein Unterverzeichnis, darin eine Datei je Dokument
unter einem zufälligen Namen (`<libraryId>/<uuid><endung>`); `documents.file_path` trägt den
absoluten Pfad. Zwei Klassen fassen diese Ablage an:
`LibraryDocumentService` (schreiben, löschen, ausliefern) und `StoredDocumentSourceAccess`
(Wiederlesen für Pipeline-Reindex und Metadaten-Nachläufe).

Das trägt den Einzelinstanzbetrieb ([ADR-0021](0021-single-instance-betrieb.md)) und trägt ein
Netzlaufwerk, das der Betrieb auf dieses Verzeichnis einhängt. Es trägt **nicht** den
Multiinstanzbetrieb (#1292): zwei Backends hinter einem Lastverteiler brauchen entweder ein
gemeinsam beschreibbares Volume — unter Kubernetes ein RWX-Volume, das nicht jedes Rechenzentrum
anbietet — oder einen Speicher, der von sich aus von mehreren Prozessen beschreibbar ist.
`docs/features/deployment-infrastructure.md` führt objektbasierten Speicher deshalb seit der
Spezifikation als Zielbild und als den einzigen Speicherweg, der „einen eigenen Pfad im Code"
braucht.

Die Upload-Ablage ist dabei der **einzige** Dateitopf, der überhaupt ein anderes Backend bekommen
darf. Bei einer `FILESYSTEM`-Bibliothek ist das Verzeichnis der Vertrag
([ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md)): die Dateien gehören der Quelle, OPAA
liest sie nur. Upload-Originale gehören dagegen der Anwendung — sie allein schreibt, ersetzt und
löscht sie, und genau diese Grenze setzt `deleteDocument` heute durch. Eigentum, nicht
Speichertechnik, ist der Grund für die Trennung der beiden Verzeichnisse, und sie ist der Grund,
warum hier überhaupt eine Wahl möglich ist.

### Was an Bausteinen schon existiert

Der S3-Konnektor ([ADR-0027](0027-s3-konnektor.md)) hat das AWS SDK for Java v2, die Typen
`S3Connection`/`S3Credentials`/`S3AccessException`, die Endpunkt-Normalisierung, den Umgang mit
Adressstil und Signaturregion sowie `MinioFixture` samt einer innerhalb von `test`/`build`
laufenden MinIO-Suite in das Projekt gebracht. Der Demo-Stack betreibt MinIO bereits als
Compose-Dienst hinter dem Profil `demo`.

Sein Port `S3ObjectStore` ist jedoch **nicht** der Port, den die Upload-Ablage braucht: er ist
lesend (`list`/`head`/`get`), an die Verbindung *einer Bibliothek* gebunden, wird je Lauf erzeugt
und geschlossen, trägt ein Anfragebudget mit geordnetem Abbruch — und er schickt jede Zieladresse
durch `TargetAddressValidator`, weil dort ein *Benutzer* den Endpunkt eingibt. Beim
Betriebsspeicher konfiguriert der Betrieb den Endpunkt, und der liegt im Normalfall gerade auf einer
privaten Adresse (`http://minio:9000` im Compose-Netz). Dieselbe Prüfung wäre hier also nicht
Schutz, sondern Fehlerquelle.

## Entscheidung

### 1. Ein Port mit zwei Adaptern; das Dateisystem bleibt der Standard

Die Upload-Ablage bekommt einen Port `UploadedOriginalStore` mit zwei Adaptern:
`FilesystemUploadedOriginalStore` (Verhalten wie heute) und `S3UploadedOriginalStore`. Ausgewählt
wird über `opaa.upload.store` mit dem Standard `filesystem` — ein Bestandsbetrieb, der nichts
konfiguriert, verhält sich unverändert, und die kleine Installation ohne Objektspeicher bleibt der
einfachste Fall. S3 ist der zusätzliche Weg für Rechenzentrums- und Multiinstanzbetrieb, nicht der
neue Normalfall.

Fünf Operationen, mehr braucht keiner der vier Aufrufpfade:

| Operation | Wer ruft sie | Dateisystem | S3 |
|---|---|---|---|
| Ablegen einer fertig geschriebenen lokalen Datei, Rückgabe des Locators | `LibraryDocumentService#uploadDocument` | Verschieben/Kopieren in `<storage-path>/<libraryId>/` | `PutObject` |
| Lesen zum Ausliefern | `LibraryDocumentService#loadContent` | lokale Datei | Objektkörper als Strom |
| Lesen als lokale Datei, mit Aufräumen danach | Pipeline-Reindex, Metadaten-Nachlauf, Anhang-Rückextraktion | dieselbe Datei, nichts zu kopieren | `GetObject` in eine Temp-Datei, danach gelöscht |
| Löschen | `deleteDocument`, jeder Fehlerpfad des Uploads | `Files.deleteIfExists` | `DeleteObject` |
| Zugehörigkeitsprüfung (gehört dieser Locator zu dieser Bibliothek?) | alle Lese- und Löschpfade | Realpfad-Präfixvergleich | Schlüssel-Präfixvergleich |

Die **Zugehörigkeitsprüfung gehört in den Adapter**. Sie ist heute zweimal dasselbe
`toRealPath`-plus-`startsWith` (`LibraryDocumentService#uploadedFileIfManagedByThisService`,
`StoredDocumentSourceAccess#uploadedFileWithinManagedStorage`) und damit die Stelle, an der ein
manipulierter `file_path` abgefangen wird. Sie bleibt in beiden Adaptern eine eigene, einzeln
getestete Methode; kein Lese- oder Löschpfad erreicht Bytes, ohne sie passiert zu haben.

### 2. Die Schreibseite sieht immer zuerst eine lokale Datei

Der Port nimmt beim Ablegen eine fertig geschriebene lokale Datei, keinen Strom. Damit bleiben
Prüfsumme, `Files.probeContentType` und die Größenermittlung unverändert dort, wo sie heute sind,
und das Tika-Parsen des Uploads arbeitet weiter auf einer lokalen Datei. Der S3-Adapter lädt diese
Datei hoch; der Dateisystem-Adapter legt sie an ihren Platz. Ein Strom-basierter Schreibweg würde
Inhaltstyp-Erkennung und Parsen gleichzeitig umbauen und wäre kein verhaltensneutraler Umbau mehr.

### 3. `documents.file_path` trägt weiter den Locator, seine Form gehört dem Adapter

Dateisystem: absoluter Pfad, unverändert. S3: `s3://<bucket>/<schlüssel>` — dieselbe Form, die
ADR-0027 für Konnektor-Dokumente verwendet; der `source_type` unterscheidet die beiden Fälle
(`UPLOAD` gegen `S3`), nicht der Pfad. Kein neues Spaltenschema, keine Migration an der Tabelle.

Schlüsselschema: `<key-prefix><libraryId>/<uuid><endung>`, mit leerem `key-prefix` als Standard.
Das ist **dieselbe Struktur wie auf der Platte**, damit ein Bestand mit einem rekursiven Kopieren
(`mc mirror`, `aws s3 sync`) in den Bucket wandert und die Umstellung keine Umrechnung von Namen
braucht. Kein Organisations-Segment: eine Bibliothek gehört genau einer Organisation, und die
Mandantenfähigkeit (#1442) wird, wenn sie Speichergrenzen je Haus ziehen will, Buckets oder Prefixe
trennen wollen — das ist über `key-prefix` erreichbar, ohne das Schlüsselschema zu ändern.

### 4. Die Umstellung eines Bestands ist ein dokumentierter Betriebsvorgang, kein Code

Bytes kopieren, dann `file_path` der `UPLOAD`-Zeilen umschreiben — ein SQL-Statement, das das
Handbuch mitliefert, samt der Zählabfrage davor und dem Rückweg. Kein Bestandsnachzug im Code, kein
Parallel-Lesen beider Ablagen: eine dauerhafte Verzweigung im Leseweg ist teurer als ein einmaliger
Betriebsschritt, und sie würde genau die Fehler verdecken, die bei einer unvollständigen Kopie
auffallen sollen. Die Umstellung ist ein Wartungsfenster, nicht ein Betriebszustand.

### 5. Herunterladen streamt, ohne Zwischendatei — und verliert dabei Bereichsanfragen

Der S3-Adapter gibt den Objektkörper als Strom zurück; `DocumentContent` kann das seit #747 und der
Controller liefert ihn als `InputStreamResource` aus. Folge: für S3-gestützte Originale gibt es
kein HTTP-`Range` und keine Wiederaufnahme eines abgebrochenen Downloads mehr — der
Dateisystem-Adapter behält beides über `FileSystemResource`. Für Dokumente bis 50 MiB ist das
vertretbar; vorsigniert ausgelieferte URLs, die beides zurückbrächten, sind ausdrücklich nicht Teil
dieses Schnitts (siehe [Nicht entschieden](#nicht-entschieden)).

### 6. Wer eine lokale Datei braucht, bekommt eine Kopie mit Aufräumpflicht beim Port

Pipeline-Reindex, Metadaten-Nachlauf und die Anhang-Rückextraktion
([ADR-0022](0022-anhang-als-eigenes-dokument.md)) brauchen einen echten Pfad. Der Port stellt ihn
als „lokale Kopie für die Dauer einer Aktion" bereit und löscht sie danach selbst — dieselbe
Disziplin, die `S3ObjectStore#getObject` schon hat, nur mit dem Aufräumen auf der richtigen Seite.
Temp-Verzeichnis über `opaa.upload.s3.temp-directory` konfigurierbar, Standard das
JVM-Temp-Verzeichnis. Der Platzbedarf ist damit „größte Datei × gleichzeitige Verarbeitungen" und
gehört ins Handbuch, weil er unter S3 neu ist.

### 7. Keine Zieladressprüfung für den Betriebsendpunkt, deshalb ein eigener Client

Der Endpunkt der Originalablage kommt aus der Betriebskonfiguration, nicht aus einer
Benutzereingabe, und zeigt im Regelfall auf eine private Adresse im eigenen Netz. `S3ClientFactory`
und `TargetAddressValidator` werden deshalb **nicht** verwendet; der Adapter baut seinen eigenen,
langlebigen `S3Client` für die gesamte Laufzeit der Anwendung. Wiederverwendet werden die Typen
(`S3Credentials`, Endpunkt-Normalisierung, Adressstil, Signaturregion, die Ausnahmehierarchie) und
die Testbausteine (`MinioFixture`), nicht der lesende Port und nicht sein Laufzeitgerüst
(Anfragebudget, Messwerk, Erzeugung je Lauf).

### 8. Fehlende Konfiguration bricht den Start ab, ein nicht erreichbarer Speicher nicht

`opaa.upload.store=s3` ohne Bucket, Endpunkt oder Zugangsdaten ist ein Konfigurationsfehler und
beendet den Start mit einer Meldung, die den fehlenden Wert nennt — dieselbe Haltung wie bei
`AuthProfileGuard` ([ADR-0005](0005-authentication-strategy.md)). Ein konfigurierter, aber
momentan nicht erreichbarer Objektspeicher beendet den Start dagegen **nicht**: Chat, Suche und die
bereits indizierten Inhalte funktionieren ohne ihn, nur Upload und Originalabruf nicht. Dieser Fall
gehört in einen Zustandsbeitrag von `/actuator/health` und in eine Warnung beim Start, nicht in
einen Abbruch.

### 9. Verschlüsselung ruhender Daten, Aufbewahrung und Replikation gehören dem Speicher

OPAA setzt keine Verschlüsselungskopfzeilen und verwaltet keine Schlüssel. Bucket-weite
Verschlüsselung (SSE-S3, SSE-KMS), Versionierung, Lebenszyklusregeln und Replikation konfiguriert
der Betrieb am Bucket; das Handbuch nennt sie als Empfehlung. Derselbe Grundsatz gilt heute für das
Dateisystem, wo Verschlüsselung am Dateisystem oder am Speichersystem hängt.

### 10. Verworfen: Originale als Blob in Postgres

Einzeldokumente im Objektspeicher gegen Einzeldokumente in der Datenbank abzuwägen, fällt nicht
grundsätzlich aus, sondern an der Größenordnung. Dafür spricht Erhebliches: eine gemeinsame
Transaktion für Zeile und Bytes, also keine verwaisten Dateien und keine Zeilen ohne Datei; **ein**
Backup und **ein** Wiederherstellungszeitpunkt, was gegenüber Betrieb und Prüfern das stärkste
Argument ist; keine zusätzliche Komponente, auch im Betrieb ohne Netzanbindung; und der
Multiinstanzbetrieb wäre ohne Objektspeicher gelöst.

Dagegen steht die Menge:

- Jede hochgeladene Datei läuft durch das Write-Ahead-Log und damit in Replikation, Sicherung und
  `pg_dump`-Dauer. Die Datenbank hält bereits Chunks und Vektoren; #1439 arbeitet gerade daran, die
  Datentöpfe zu **trennen**, nicht weitere hineinzulegen.
- `bytea` streamt nicht: 50 MiB liegen beim Schreiben und beim Lesen vollständig im Heap und im
  JDBC-Puffer. Large Objects streamen, verlangen aber eine eigene API, eigene `vacuumlo`-Pflege und
  passen nicht zu JPA.
- Kalte Originalbytes verdrängen `shared_buffers` — also den Cache, von dem die Vektorsuche lebt.
- Ein Download hält für die Dauer der Übertragung eine Verbindung aus dem Pool.
- Datenbankspeicher ist das teuerste und am unhandlichsten zu vergrößernde Volume einer
  Installation.

Das Maß ist also Datenmenge je Zeile und Gesamtwachstum, nicht eine Regel gegen Bytes in der
Datenbank: `branding_settings.logo_content` liegt bewusst als `bytea` in seiner Zeile — ein Logo je
Organisation, höchstens ein halbes MiB, bei fast jeder Anfrage gebraucht. Upload-Originale sind das
Gegenteil davon: viele, groß, selten gelesen.

### 11. Unberührt

- **Netzlaufwerk bleibt der dritte Weg ohne Code.** SMB und NFS hängt der Betrieb auf den
  `storage-path`; der Dateisystem-Adapter sieht ein Verzeichnis.
- **Temp-Dateien der Konnektoren** (`HTTP_DIRECTORY`, `RSS_FEED`, S3-Downloads) bleiben lokal. Sie
  sind instanzlokal und kurzlebig; sie in den Objektspeicher zu legen löst kein Problem und macht
  jeden Lauf von ihm abhängig.
- **Kontingent (#119)** zählt weiter `documents.file_size` aus der Datenbank und ist vom Backend der
  Ablage unabhängig.
- **Entdoppelung** bleibt die Prüfsummenbedingung der Datenbank (`uk_documents_library_checksum`).
- **Löschen einer Bibliothek** braucht kein Aufräumen eines Präfixes: eine `UPLOAD`-Bibliothek lässt
  sich nur leer löschen (`KnowledgeLibraryService#deleteLibrary`), also ist zu diesem Zeitpunkt
  nichts mehr abgelegt. Auf der Platte bleibt ein leeres Verzeichnis zurück wie heute; in S3
  existiert es gar nicht.
- **`FILESYSTEM`-Bibliotheken** bleiben, wie sie sind (ADR-0018).

## Konsequenzen

**Einfacher.** Der Multiinstanzbetrieb (#1292) verliert eine seiner drei offenen Speicherfragen.
Große Bestände hängen nicht mehr an der Größe eines Volumes. Verschlüsselung, Versionierung,
Replikation und Aufbewahrung kommen aus dem Speicher statt aus unserem Code. Und die Upload-Ablage
ist nach diesem Umbau erstmals an genau einer Stelle im Code gekapselt statt an zwei — schon das ist
unabhängig von S3 eine Verbesserung.

**Schwieriger.** Es gibt einen zweiten Betriebsweg mit eigener Konfiguration, eigenem Backup-Ziel
und eigener Wiederherstellungsreihenfolge (erst Objekte, dann Datenbank — umgekehrt zeigen Zeilen
auf noch nicht vorhandene Objekte). Zeile und Bytes fallen nicht mehr gemeinsam: ein fehlgeschlagenes
`DeleteObject` hinterlässt ein verwaistes Objekt, ein Abbruch zwischen `PutObject` und dem Einfügen
der Zeile ebenso — beides existiert heute auf der Platte genauso, fällt dort aber beim Hineinschauen
auf, während im Bucket niemand nachsieht. Dafür braucht es einen Aufräumlauf; er ist eigener
Arbeitsumfang im Epic und nicht Voraussetzung der Umstellung. Bereichsanfragen beim Download fallen
für S3 weg (Entscheidung 5). Und die Temp-Platte wird erstmals Teil der Kapazitätsplanung
(Entscheidung 6).

**Nachweisbarkeit.** Der Beleg bleibt greifbar — das ist die Bedingung, unter der überhaupt
umgestellt werden darf. Was sich ändert, ist der Ort: ein Prüfer, der heute in ein Verzeichnis
schaut, schaut künftig in einen Bucket. Das Handbuch muss ihm sagen, wie.

## Nicht entschieden

- **Vorsigniert ausgelieferte URLs** (der Browser holt das Original direkt vom Speicher). Brächten
  Bereichsanfragen zurück und nähmen dem Backend die Bytes ab, verlangen aber eine eigene
  Abwägung: eine URL, die für ihre Gültigkeitsdauer auch ohne Anmeldung funktioniert, ist eine
  andere Zugriffseigenschaft als die heutige.
- **Ein Objektspeicher-Weg für `FILESYSTEM`-Bibliotheken.** Dafür gibt es den S3-Konnektor (#1291);
  eine Quelle ist keine Ablage.
- **Trennung der Postgres-Datentöpfe** (#1439) und **Mandantenfähigkeit** (#1442) berühren das
  Schlüsselschema, ändern es nach Entscheidung 3 aber nicht.
