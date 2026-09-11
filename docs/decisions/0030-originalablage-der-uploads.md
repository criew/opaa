# ADR-0030: Originalablage der Uploads — ein Port, zwei Adapter, Dateisystem als Standard

## Status

Akzeptiert

## Kontext

Hochgeladene Originale liegen heute in einem konfigurierten Verzeichnis
(`opaa.upload.storage-path`, Standard `./uploads`, Compose: Bind-Mount `./uploads` auf
`/app/uploads`). Darunter gibt es je Bibliothek ein Unterverzeichnis, darin eine Datei je Dokument
unter einem zufälligen Namen (`<libraryId>/<uuid><endung>`); `documents.file_path` trägt den
absoluten Pfad. Zwei Klassen fassen diese Ablage an: `LibraryDocumentService` (schreiben, löschen,
ausliefern) und `StoredDocumentSourceAccess` (Wiederlesen für Pipeline-Reindex und
Metadaten-Nachläufe).

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

### Die sechs Wege zu den Bytes eines Uploads

Wer die Ablage kapseln will, muss alle sechs kennen — vier davon fallen beim ersten Hinsehen nicht
auf:

1. **Ablegen** beim Upload: `Files.copy` aus dem Multipart-Strom direkt an den Zielort.
2. **Weiterverarbeiten**: `processUploadedFileAsync` ist `@Async("uploadTaskExecutor")` und liest die
   abgelegte Datei **nach** der Rückkehr von `uploadDocument` auf einem anderen Thread. Die Datei
   muss den Aufruf überleben.
3. **Ausliefern** über `/api/v1/documents/{id}/content`.
4. **Wiederlesen** durch Pipeline-Reindex und Metadaten-Nachlauf (`StoredDocumentSourceAccess`).
5. **Rückextraktion von Anhängen** ([ADR-0022](0022-anhang-als-eigenes-dokument.md)): ein
   hochgeladenes `.eml` erzeugt Kindzeilen, die ebenfalls `source_type = 'UPLOAD'` tragen, deren
   `file_path` aber synthetisch ist und den Pfad des Elternteils als Präfix enthält
   (`<elternpfad>/<index>/<dateiname>`).
6. **Löschen**: beim Löschen eines Dokuments, beim Ersetzen eines fehlgeschlagenen Uploads und auf
   jedem Fehlerpfad des Uploads selbst.

### Was an Bausteinen schon existiert

Der S3-Konnektor ([ADR-0027](0027-s3-konnektor.md)) hat das AWS SDK for Java v2 und in
`AwsSdkS3ObjectStore` einen Clientbau in das Projekt gebracht, der mehr enthält, als er von außen
aussieht: Endpunkt, Signaturregion und Adressstil, `RequestChecksumCalculation.WHEN_REQUIRED` und
`ResponseChecksumValidation.WHEN_REQUIRED` (Verträglichkeit mit S3-kompatiblen Speichern — nicht
offensichtlich und nicht optional), Apache5-Client mit abgestimmten Zeitschranken,
Proxy-Konfiguration, `TRUST_ALL_CERTIFICATES` für selbstsignierte Endpunkte, eine Wiederholstrategie
mit gedeckeltem Rückzug und eine Übersetzung der SDK-Fehler in deutsche, geheimnisfreie Meldungen.
ADR-0027 zählt diese Liste als „Stück für Stück, mit Test je Stück" erarbeitet auf. Dazu kommen
`MinioFixture` und eine MinIO-Suite, die innerhalb von `test`/`build` läuft, sobald Docker erreichbar
ist.

Was dagegen **nicht** passt, ist der Port `S3ObjectStore` selbst: er ist lesend
(`list`/`head`/`get`), an die Verbindung *einer Bibliothek* gebunden, wird je Lauf erzeugt und
geschlossen und trägt ein Anfragebudget mit geordnetem Abbruch. Die Originalablage ist das Gegenteil:
eine Verbindung für die gesamte Laufzeit, schreibend, ohne Lauf und ohne Budget.

## Entscheidung

### 1. Ein Port mit zwei Adaptern; das Dateisystem bleibt der Standard

Die Upload-Ablage bekommt einen Port `UploadedOriginalStore` mit zwei Adaptern:
`FilesystemUploadedOriginalStore` (Verhalten wie heute) und `S3UploadedOriginalStore`. Ausgewählt
wird über `opaa.upload.store` mit dem Standard `filesystem` — ein Bestandsbetrieb, der nichts
konfiguriert, verhält sich unverändert, und die kleine Installation ohne Objektspeicher bleibt der
einfachste Fall. S3 ist der zusätzliche Weg für Rechenzentrums- und Multiinstanzbetrieb, nicht der
neue Normalfall.

| Operation | Weg (oben) | Dateisystem | S3 |
|---|---|---|---|
| Annehmen: Strom entgegennehmen, Arbeitsdatei zurückgeben | 1 | `Files.copy` an den Zielort | Temp-Datei schreiben |
| Festschreiben: die angenommene Datei wird zum Original, Rückgabe des Locators | 1 | nichts zu tun, die Datei liegt schon dort | `PutObject` |
| Freigeben der Arbeitsdatei (Original bleibt) | 2 | nichts zu tun | Temp-Datei löschen |
| Verwerfen: Original und Arbeitsdatei | 6 | beide löschen | Objekt und Temp-Datei löschen |
| Lesen zum Ausliefern | 3 | lokale Datei (`FileSystemResource`) | Objektkörper als Strom |
| Lesen als lokale Kopie für die Dauer einer Aktion | 4, 5 | dieselbe Datei, keine Kopie | `GetObject` in eine Temp-Datei, danach gelöscht |
| Löschen | 6 | `Files.deleteIfExists` | `DeleteObject` |
| Auflösen eines Locators: Zugehörigkeit, Symlink-Auflösung, Existenz | 3, 4, 5, 6 | `toRealPath` plus Präfixvergleich | Schlüssel-Präfixvergleich plus `HeadObject` |

### 2. Die Schreibseite nimmt den Strom und gibt eine Arbeitsdatei zurück

Der Port nimmt beim Ablegen den Multipart-Strom, nicht eine fertig geschriebene Datei. Andernfalls
bräuchte der Dateisystemweg erst eine Staging-Datei und dann eine zweite Vollkopie je Upload:
`java.io.tmpdir` (Container-Overlay) und `/app/uploads` (Bind-Mount) liegen praktisch nie auf
demselben Dateisystem, ein `move` wäre also keines.

**Annehmen und Festschreiben sind zwei Schritte**, denn zwischen ihnen liegen die Prüfungen, die
einen Upload noch ablehnen: die Übereinstimmung von Inhalt und Endung und die Entdoppelung über die
Prüfsumme. Ein einziger Schritt würde die Bytes vor diesen Prüfungen in den Bucket legen, sodass
jeder abgelehnte Doppel-Upload ein volles `PutObject` samt `DeleteObject` kostet — und der erneute
Upload derselben Datei ist der häufige Fall, nicht der seltene. Die Temp-Datei existiert ohnehin,
also kostet die Trennung nur eine Operation mehr im Port. Auf dem Dateisystemweg fallen beide
Schritte zusammen: die angenommene Datei liegt bereits an ihrem endgültigen Platz.

Zurück kommt der Locator **und eine lokale Arbeitsdatei**, denn Weg 2 liest sie erst nach der
Rückkehr von `uploadDocument` auf einem anderen Thread. Die Arbeitsdatei gehört ab da dem
asynchronen Auftrag, der sie auf jedem Ausgang freigibt — beim Dateisystem-Adapter ist die
Freigabe ein No-op (die Arbeitsdatei *ist* das abgelegte Original), beim S3-Adapter löscht sie die
Temp-Datei. Prüfsumme, `Files.probeContentType` und Größenermittlung arbeiten unverändert auf dieser
Arbeitsdatei; nichts an der Inhaltstyp-Erkennung und am Tika-Parsen ändert sich.

**Freigeben ist nicht Löschen, und die Freigabe gehört dem asynchronen Auftrag.** Der Port kennt
drei Ausgänge, und ihre Unterscheidung ist der eigentliche Vertrag:

- **Freigeben** — die Verarbeitung ist durch, die Arbeitsdatei wird nicht mehr gebraucht, das
  Original bleibt. Dateisystem: nichts zu tun, die Arbeitsdatei *ist* das Original. S3: Temp-Datei
  löschen.
- **Verwerfen** — der Upload ist gescheitert, Original und Arbeitsdatei verschwinden beide.
- **Löschen** — ein längst abgelegtes Original wird entfernt (Weg 6).

Den Freigabe-Aufruf setzt der asynchrone Auftrag ab, nicht `uploadDocument` — dessen Thread ist zu
diesem Zeitpunkt längst zurück. `LibraryDocumentService` reicht die Freigabe deshalb als Handle
zusammen mit dem Auftrag weiter, und `DocumentIngestService#processUploadedFileAsync` ruft sie in
einem `finally` um seinen gesamten Rumpf auf, sodass sie auf jedem Ausgang fällt — auch auf dem, an
dem die Verarbeitung selbst scheitert. Das Handle ist ein `AutoCloseable` ohne Wissen über die
Ablage; die Paketgrenze trägt kein Store-Wissen, nur „dieser Auftrag hatte eine Arbeitsdatei, und
sie ist jetzt frei". Auf den Fehlerpfaden, die noch vor der Übergabe liegen, und in
`failAlreadyPersistedUpload` verwirft der Aufrufer stattdessen — Verwerfen schließt Freigeben ein.

### 3. Auflösen heißt Zugehörigkeit **und** Existenz — und schärft den Upload-Weg bewusst nach

Heute stehen an den zwei Aufrufstellen zwei verschiedene Prüfungen, und das ist kein Zufall, sondern
ein Fehler: `LibraryDocumentService#uploadedFileIfManagedByThisService` vergleicht rein lexikalisch
(`toAbsolutePath().normalize()`), während `StoredDocumentSourceAccess` und der `FILESYSTEM`-Zwilling
derselben Klasse `toRealPath` verwenden — Letzterer mit der ausdrücklichen Begründung, dass ein
Symlink innerhalb des Verzeichnisses, der nach draußen zeigt, den lexikalischen Vergleich passiert,
und dass die Auflösung genau dort nötig ist, wo die Datei geöffnet und an einen HTTP-Aufrufer
gestreamt wird.

Der Adapter bekommt **eine** Auflösung, und sie folgt der strengeren Semantik: Zugehörigkeit zur
Bibliothek, Symlink-Auflösung, Existenz. Das ist eine **gewollte Verschärfung** des
Upload-Auslieferwegs, kein verhaltensneutraler Umbau: ein Symlink im Upload-Verzeichnis, der nach
draußen zeigt, wird danach nicht mehr ausgeliefert. Wer den Port baut (#1475), deklariert das als
Verhaltensänderung und sichert sie mit einem Test ab, statt sie unter „neutral" laufen zu lassen.

Die Existenz ist Teil der Auflösung, weil die 404-Disziplin von `loadContent` (#736) darauf beruht:
unbekanntes Dokument, fremde Organisation, fehlende Freigabe, Quellentyp ohne lokale Datei und
verschwundene Datei antworten alle dasselbe `404`, damit kein Aufrufer die Fälle unterscheiden kann.
Der S3-Adapter erreicht das mit `HeadObject`.

### 4. `documents.file_path` trägt weiter den Locator, seine Form gehört dem Adapter

Dateisystem: absoluter Pfad, unverändert. S3: `s3://<bucket>/<schlüssel>` — dieselbe Form, die
ADR-0027 für Konnektor-Dokumente verwendet; der `source_type` unterscheidet die beiden Fälle
(`UPLOAD` gegen `S3`), nicht der Pfad. Kein neues Spaltenschema, keine Migration an der Tabelle.

Schlüsselschema: `<key-prefix><libraryId>/<uuid><endung>`, mit leerem `key-prefix` als Standard —
**dieselbe Struktur wie auf der Platte**, damit ein Bestand mit einem rekursiven Kopieren
(`mc mirror`, `aws s3 sync`) in den Bucket wandert. Kein Organisations-Segment: eine Bibliothek
gehört genau einer Organisation, und die Mandantenfähigkeit (#1442) kann über `key-prefix` oder
einen eigenen Bucket je Haus trennen, ohne das Schema zu ändern.

**Anhangzeilen sind die Ausnahme.** Ihr `file_path` ist synthetisch
(`<elternpfad>/<index>/<dateiname>`) und benennt kein abgelegtes Objekt. Der Store löst ihn nie auf:
auf den **Lesewegen** löst der Store ihn nie auf, weil `isReExtractableAttachment` die Form am
Elternpfad erkennt und die Zeile in die Rückextraktion umleitet, bevor ein Adapter sie sieht. Auf
dem Löschweg gibt es diese Weiche nicht: `deleteDocument` löst den Locator der übergebenen Zeile
auf, und eine Anhangzeile ist über denselben Endpunkt löschbar. Das ist unschädlich, muss aber im
Vertrag stehen: ein synthetischer Locator liegt zwar im richtigen Präfix, benennt aber kein Objekt
und **löst regulär auf `nicht vorhanden` auf** — er darf kein Fehler sein und nichts löschen. Für
die Ablage heißt das ein `HeadObject` je Anhangslöschung; für die Umstellung eines Bestands heißt es
alles (Entscheidung 5).

### 5. Die Umstellung eines Bestands ist eine Präfixersetzung, und zwar ein Betriebsvorgang

Bytes kopieren, dann in `documents.file_path` **den Pfadpräfix ersetzen**, nicht den Wert neu
bilden:

```sql
UPDATE documents
   SET file_path = replace(file_path, '/app/uploads/', 's3://mein-bucket/')
 WHERE source_type = 'UPLOAD';
```

Die Präfixersetzung ist der Grund, warum Anhangzeilen ohne Sonderfall mitwandern: ihr synthetischer
Pfad trägt den Elternpfad als Präfix und bleibt nach derselben Ersetzung auf seinen Elternteil
bezogen, sodass `AttachmentFilePath.indexIn` weiter greift. Sie ist injektiv, also bleibt
`uk_documents_library_path` eindeutig. Der zu ersetzende Präfix ist installationsabhängig
(`/app/uploads/` im Container, ein anderer Pfad auf dem Host) und gehört mit einer Zählabfrage davor
und einer Stichprobe danach ins Handbuch — ebenso der Rückweg, der dieselbe Ersetzung umgekehrt
fährt.

Kein Bestandsnachzug im Code, kein Parallel-Lesen beider Ablagen: eine dauerhafte Verzweigung im
Leseweg ist teurer als ein einmaliger Betriebsschritt, und sie würde genau die Fehler verdecken, die
bei einer unvollständigen Kopie auffallen sollen. Die Umstellung ist ein Wartungsfenster, kein
Betriebszustand.

### 6. Herunterladen streamt, ohne Zwischendatei — und verliert dabei Bereichsanfragen

Der S3-Adapter gibt den Objektkörper als Strom zurück; `DocumentContent` kann das seit #747 und der
Controller liefert ihn als `InputStreamResource` aus. Folge: für S3-gestützte Originale gibt es
kein HTTP-`Range` und keine Wiederaufnahme eines abgebrochenen Downloads mehr — der
Dateisystem-Adapter behält beides über `FileSystemResource`. Für Dokumente bis 50 MiB ist das
vertretbar; vorsigniert ausgelieferte URLs, die beides zurückbrächten, sind ausdrücklich nicht Teil
dieses Schnitts.

### 7. Wer eine lokale Datei braucht, bekommt eine Kopie mit Aufräumpflicht beim Port

Pipeline-Reindex, Metadaten-Nachlauf und die Rückextraktion von Anhängen (ADR-0022) brauchen einen
echten Pfad. Der Port stellt ihn als „lokale Kopie für die Dauer einer Aktion" bereit und löscht sie
danach selbst — dieselbe Disziplin, die `S3ObjectStore#getObject` schon hat, nur mit dem Aufräumen
auf der richtigen Seite. Temp-Verzeichnis über `opaa.upload.s3.temp-directory` konfigurierbar,
Standard das JVM-Temp-Verzeichnis.

Der Platzbedarf ist damit unter S3 neu und **größer als eine Dateigröße je Upload**: Springs
Multipart-Spool (ohne gesetzten `file-size-threshold` landet jede Datei auf der Platte), die
Arbeitsdatei aus Entscheidung 2 und, bei einem gleichzeitigen Reindex derselben Bibliothek, die
Kopie aus dieser Entscheidung können nebeneinander liegen. Das Handbuch nennt die Rechnung, statt
eine Zahl zu behaupten.

**Ein getöteter Prozess hat kein `finally`.** Die Freigabe aus Entscheidung 2 und das Aufräumen
hier hängen beide daran; ein harter Abbruch lässt die Temp-Datei liegen. Das Gegenstück für genau
diesen Fall existiert bereits: `UploadPendingRecoveryRunner` räumt beim Start die Zeilen auf, die
ein gestorbener Prozess in `PENDING` zurückgelassen hat. Das Temp-Verzeichnis der Ablage bekommt
denselben Kehraus am selben Ort — das verwaiste **Objekt** im Bucket, das derselbe Abbruch
hinterlassen kann, sammelt dagegen der Aufräumlauf #1478 ein.

### 8. Geteilter Clientbau, eigene Zieladressprüfung mit eigenem Namensraum

Der Clientbau aus `AwsSdkS3ObjectStore` (Endpunkt, Region, Adressstil, die beiden
Prüfsummen-Schalter, Zeitschranken, Proxy, selbstsignierte Endpunkte, Wiederholstrategie,
Fehlerübersetzung) wird **herausgelöst und von beiden Seiten benutzt**, statt ein zweites Mal
erarbeitet zu werden. Er ist mühsam erworbenes Wissen über die Verträglichkeit mit nicht-AWS-Stores;
eine zweite Fassung davon würde still auseinanderlaufen.

**Der `ExecutionInterceptor` gehört mit in den geteilten Teil**, und zwar parametrisiert. Er bündelt
heute in einer Klasse drei Dinge, die nicht zusammen wandern: die Host-Prüfung vor jeder Anfrage
(ADR-0027, Entscheidung 8 — sie soll die Ablage behalten), die Anfrage- und Drosselzählung über
`SourceRequestMeter` und die Durchsetzung des Anfragebudgets (beide bleiben beim Konnektor). Er
nimmt deshalb Validator, optionales Messwerk und optionales Budget entgegen — `0` als „unbegrenzt"
kennt `createForProbe` bereits. Ohne diesen Satz fällt beim Bauen entweder das Budget in die Ablage
oder die Prüfung je Anfrage aus ihr heraus.

Die Zieladressprüfung bleibt erhalten, bekommt aber einen eigenen Namensraum
`opaa.upload.s3.target-validation` — nach dem Vorbild von `OidcAddressPolicy`
([ADR-0025](0025-mehrere-oidc-anbieter.md)), das genau diesen Fall schon gelöst hat: der
**konfigurierte Endpunkt selbst ist immer erlaubt** (nach Schema, Host *und* Port), weil er aus der
Betriebskonfiguration stammt und damit dieselbe Vertrauensstufe hat wie die Freigabeliste; alles
andere wird geprüft. Damit funktioniert `http://minio:9000` im Compose-Netz ohne Eintrag, und ein
verirrter Endpunkt wie `169.254.169.254` bleibt trotzdem erkennbar. Ein eigener Namensraum statt des
indizierungsseitigen sorgt dafür, dass das Abschalten der Konnektorprüfung nicht die Ablage
mitschaltet — dieselbe Begründung, die ADR-0025 für die Anmeldeseite gibt.

Nicht wiederverwendet wird `S3Credentials`: sein Doppelpunkt-Verbot stammt aus dem Speicherformat
`knowledge_libraries.source_credentials`, nicht aus S3, und würde ein MinIO-Secret mit Doppelpunkt in
einer Umgebungsvariablen grundlos ablehnen. Die Betriebskonfiguration trägt Zugangsschlüssel und
Geheimnis als zwei getrennte Werte — mit derselben Zusage, dass sie in keiner Protokollzeile und in
keiner Meldung erscheinen.

### 9. Fehlende Konfiguration bricht den Start ab, ein nicht erreichbarer Speicher nicht

`opaa.upload.store=s3` ohne Bucket, Endpunkt oder Zugangsdaten ist ein Konfigurationsfehler und
beendet den Start mit einer Meldung, die den fehlenden Wert nennt — dieselbe Haltung wie bei
`AuthProfileGuard` ([ADR-0005](0005-authentication-strategy.md)). Ein konfigurierter, aber momentan
nicht erreichbarer Objektspeicher beendet den Start **nicht**: Chat, Suche und die bereits
indizierten Inhalte funktionieren ohne ihn.

Daraus folgen zwei Dinge, die der ADR mitentscheidet, weil sie sonst beim Bauen beliebig ausfallen:

- **Der Gesundheitsbeitrag geht nicht in den Gesamtstatus.** Die drei vorhandenen Indikatoren
  (Chat, Embeddings, Vektorspeicher) tun das; ein vierter, der es ebenso täte, würde ausgerechnet im
  Mehrinstanzbetrieb — dem Zweck dieser Arbeit — eine Instanz wegen eines nicht erreichbaren
  Objektspeichers aus der Lastverteilung nehmen, obwohl sie Chat und Suche weiter bedienen kann. Der
  Beitrag erscheint deshalb als eigener Eintrag mit eigener Gruppe, nicht im Gesamturteil.
- **Der Abruf eines Originals unterscheidet zwei Fälle.** „Objekt nicht vorhanden" ist das
  bestehende `404` ohne Unterscheidbarkeit (Entscheidung 3). „Speicher nicht erreichbar" ist ein
  eigener Fehler mit deutscher Meldung und `503`, weil eine vorübergehende Störung dem Aufrufer
  nicht als „gibt es nicht" erscheinen darf — und weil ein `404` hier den Betrieb in die falsche
  Richtung schicken würde.

### 10. Verschlüsselung ruhender Daten, Aufbewahrung und Replikation gehören dem Speicher

OPAA setzt keine Verschlüsselungskopfzeilen und verwaltet keine Schlüssel. Bucket-weite
Verschlüsselung (SSE-S3, SSE-KMS), Versionierung, Lebenszyklusregeln und Replikation konfiguriert
der Betrieb am Bucket; das Handbuch nennt sie als Empfehlung. Derselbe Grundsatz gilt heute für das
Dateisystem, wo Verschlüsselung am Dateisystem oder am Speichersystem hängt.

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
ist nach diesem Umbau erstmals an genau einer Stelle gekapselt statt an zwei — mit **einer**
Auflösung statt zweier, die sich in ihrer Strenge unterscheiden. Schon das ist unabhängig von S3
eine Verbesserung, und es schließt nebenbei die Lücke, dass ein Symlink im Upload-Verzeichnis heute
ausgeliefert wird.

**Schwieriger.** Es gibt einen zweiten Betriebsweg mit eigener Konfiguration, eigenem Backup-Ziel und
eigener Wiederherstellungsreihenfolge (erst Objekte, dann Datenbank — umgekehrt zeigen Zeilen auf
noch nicht vorhandene Objekte). Zeile und Bytes fallen nicht mehr gemeinsam: ein fehlgeschlagenes
`DeleteObject` hinterlässt ein verwaistes Objekt, ein Abbruch zwischen `PutObject` und dem Einfügen
der Zeile ebenso — beides existiert heute auf der Platte genauso, fällt dort aber beim Hineinschauen
auf, während im Bucket niemand nachsieht. Dafür braucht es einen Aufräumlauf (#1478); er ist eigener
Arbeitsumfang und nicht Voraussetzung der Umstellung. Bereichsanfragen beim Download fallen für S3
weg (Entscheidung 6). Die Temp-Platte wird erstmals Teil der Kapazitätsplanung (Entscheidung 7). Und
der herausgelöste Clientbau (Entscheidung 8) fasst Konnektor-Code an, der heute funktioniert — das
gehört mit eigener Testabdeckung abgesichert, nicht nebenbei verschoben.

**Nachweisbarkeit.** Der Beleg bleibt greifbar — das ist die Bedingung, unter der überhaupt
umgestellt werden darf. Was sich ändert, ist der Ort: ein Prüfer, der heute in ein Verzeichnis
schaut, schaut künftig in einen Bucket. Das Handbuch muss ihm sagen, wie.

## Verworfene Alternativen

### Originale als Blob in Postgres

Fällt nicht grundsätzlich aus, sondern an der Größenordnung. Dafür spricht Erhebliches: eine
gemeinsame Transaktion für Zeile und Bytes, also keine verwaisten Dateien und keine Zeilen ohne
Datei; **ein** Backup und **ein** Wiederherstellungszeitpunkt, was gegenüber Betrieb und Prüfern das
stärkste Argument ist; keine zusätzliche Komponente, auch im Betrieb ohne Netzanbindung; und der
Multiinstanzbetrieb wäre ohne Objektspeicher gelöst.

Dagegen steht die Menge:

- Jede hochgeladene Datei läuft durch das Write-Ahead-Log und damit in Replikation, Sicherung und
  `pg_dump`-Dauer. Die Datenbank hält bereits Chunks und Vektoren.
- `bytea` streamt nicht: 50 MiB liegen beim Schreiben und beim Lesen vollständig im Heap und im
  JDBC-Puffer. Large Objects streamen, verlangen aber eine eigene API, eigene `vacuumlo`-Pflege und
  passen nicht zu JPA.
- Kalte Originalbytes verdrängen `shared_buffers` — also den Cache, von dem die Vektorsuche lebt.
- Ein Download hält für die Dauer der Übertragung eine Verbindung aus dem Pool.
- Datenbankspeicher ist das teuerste und am unhandlichsten zu vergrößernde Volume einer
  Installation.

Das Maß ist also Datenmenge je Zeile und Gesamtwachstum, nicht eine Regel gegen Bytes in der
Datenbank: `branding_settings.logo_content` liegt bewusst als `bytea` in seiner Zeile — ein Logo je
Organisation, höchstens 512 KiB, bei fast jeder Anfrage gebraucht. Upload-Originale sind das
Gegenteil davon: viele, groß, selten gelesen. In dieselbe Richtung, aber ohne dass hier etwas
entschieden wäre, zeigt der Stichpunkt „Speicher-Backends trennen" in #1439.

### Beide Ablagen parallel lesen

Erspart der Umstellung einen Betriebsschritt und kostet dauerhaft eine Verzweigung in jedem
Lesepfad — samt der Eigenschaft, dass eine unvollständige Kopie nicht auffällt, weil die alte Ablage
sie auffängt. Verworfen zugunsten von Entscheidung 5.

### Ein eigener S3-Client ohne geteilten Bau

War der erste Entwurf dieses ADR, mit der Begründung, die Zieladressprüfung passe nicht zum
Betriebsendpunkt. Das Review hat gezeigt, dass das Projekt diesen Fall zweimal anders gelöst hat
(Freigabeliste für das Demo-MinIO, eigener Prüf-Namensraum für die Anmeldeseite) und dass am
Clientbau deutlich mehr hängt als am Prüfschritt. Ersetzt durch Entscheidung 8.

## Ausdrücklich offen

- **Vorsigniert ausgelieferte URLs** (der Browser holt das Original direkt vom Speicher). Brächten
  Bereichsanfragen zurück und nähmen dem Backend die Bytes ab, verlangen aber eine eigene Abwägung:
  eine URL, die für ihre Gültigkeitsdauer auch ohne Anmeldung funktioniert, ist eine andere
  Zugriffseigenschaft als die heutige.
- **Ein Objektspeicher-Weg für `FILESYSTEM`-Bibliotheken.** Dafür gibt es den S3-Konnektor (#1291);
  eine Quelle ist keine Ablage.
- **Mehrere Ablagen nebeneinander** (je Organisation oder je Bibliothek ein eigener Bucket). Das
  Schlüsselschema aus Entscheidung 4 verbaut es nicht; entschieden ist es nicht.

## Referenzen

- Epic #1440, Umsetzung in #1475, #1476, #1477, #1478
- [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) — Quellkonfiguration in der Bibliothek
- [ADR-0021](0021-single-instance-betrieb.md) — Single-Instance-Annahme
- [ADR-0022](0022-anhang-als-eigenes-dokument.md) — Anhang als eigenes Dokument
- [ADR-0025](0025-mehrere-oidc-anbieter.md) — Zieladressprüfung mit eigenem Namensraum
- [ADR-0027](0027-s3-konnektor.md) — S3-Konnektor, Zugriffsschicht und Clientbau
- `docs/features/deployment-infrastructure.md` — Speicher-Backends
