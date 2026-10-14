# ADR-0030: Originalablage der Uploads — ein Port, zwei Adapter, Dateisystem als Standard

## Status

Akzeptiert · zuletzt geändert 2026-09-11

## Kontext

Hochgeladene Originale lagen in einem Verzeichnis (`opaa.upload.storage-path`), je Bibliothek ein
Unterverzeichnis, je Dokument eine Datei unter zufälligem Namen; `documents.file_path` trug den
absoluten Pfad, und zwei Klassen fassten die Ablage an. Das trägt den Einzelinstanzbetrieb
(ADR-0021) und ein Netzlaufwerk, nicht den Multiinstanzbetrieb (#1292): Zwei Backends hinter einem
Lastverteiler brauchen ein RWX-Volume oder einen Speicher, der von mehreren Prozessen beschreibbar
ist. Die Upload-Ablage ist der einzige Dateitopf, der ein anderes Backend bekommen darf: Bei einer
`FILESYSTEM`-Bibliothek gehören die Dateien der Quelle, Upload-Originale gehören der Anwendung.
Sechs Wege zu den Bytes eines Uploads müssen gekapselt werden: Ablegen, Weiterverarbeiten auf einem
anderen Thread nach der Rückkehr von `uploadDocument`, Ausliefern, Wiederlesen für Reindex und
Metadaten-Nachlauf, Rückextraktion von Anhängen (ADR-0022, synthetischer `file_path` mit dem
Elternpfad als Präfix) und Löschen. Der S3-Konnektor (ADR-0027) hatte das SDK und einen erprobten
Clientbau bereits ins Projekt gebracht; sein Port `S3ObjectStore` passt aber nicht (lesend, an eine
Bibliothek gebunden, je Lauf erzeugt, mit Budget).

## Entscheidung

### 1. Ein Port mit zwei Adaptern; das Dateisystem bleibt der Standard

`UploadedOriginalStore` mit `FilesystemUploadedOriginalStore` und `S3UploadedOriginalStore`,
ausgewählt über `opaa.upload.store` (`OPAA_UPLOAD_STORE`, Standard `filesystem`). Ein Bestand, der
nichts konfiguriert, verhält sich unverändert; S3 ist der zusätzliche Weg für Rechenzentrums- und
Multiinstanzbetrieb, nicht der neue Normalfall. Operationen: Annehmen (Strom → Arbeitsdatei),
Festschreiben, Freigeben der Arbeitsdatei, Verwerfen, Lesen zum Ausliefern, Lesen als lokale Kopie
für die Dauer einer Aktion, Löschen, Auflösen eines Locators, Auflisten je Bibliothek (seit #1478).

### 2. Die Schreibseite nimmt den Strom und gibt eine Arbeitsdatei zurück

Der Port nimmt den Multipart-Strom, nicht eine fertige Datei (sonst zwei Vollkopien je Upload, weil
`java.io.tmpdir` und `/app/uploads` nie auf demselben Dateisystem liegen). Annehmen und
Festschreiben sind zwei Schritte, weil dazwischen Endungsprüfung und Entdoppelung liegen — ein
abgelehnter Doppel-Upload kostet sonst `PutObject` samt `DeleteObject`. Drei Ausgänge: **Freigeben**
(Verarbeitung durch, Original bleibt; Dateisystem No-op, S3 Temp-Datei löschen), **Verwerfen**
(Upload gescheitert, beides weg), **Löschen** (abgelegtes Original entfernen). Die Freigabe gehört
dem asynchronen Auftrag: `DocumentIngestService#processUploadedFileAsync` ruft sie in einem
`finally` um seinen gesamten Rumpf, als `AutoCloseable` ohne Store-Wissen.

### 3. Auflösen heißt Zugehörigkeit und Existenz

Eine Auflösung je Adapter mit der strengeren Semantik: Zugehörigkeit zu Organisation und Bibliothek,
Symlink-Auflösung (`toRealPath`), Existenz (S3: `HeadObject`). Ein Symlink im Upload-Verzeichnis, der
nach draußen zeigt, wird nicht mehr ausgeliefert — eine gewollte Verschärfung. Die Existenz gehört
zur Auflösung, weil die 404-Disziplin von `loadContent` darauf beruht: unbekanntes Dokument, fremde
Organisation, fehlende Freigabe, Quellentyp ohne lokale Datei und verschwundene Datei antworten
dasselbe `404`.

### 4. `documents.file_path` trägt weiter den Locator, seine Form gehört dem Adapter

Dateisystem: absoluter Pfad. S3: `s3://<bucket>/<schlüssel>` wie bei Konnektor-Dokumenten; der
`source_type` unterscheidet (`UPLOAD` gegen `S3`). Schlüsselschema seit #1544:
`<key-prefix><organizationId>/<libraryId>/<uuid><endung>` (`UploadS3Properties`) und
`<storage-path>/<organizationId>/<libraryId>/<uuid><endung>` auf der Platte — dieselbe Struktur in
beiden Ablagen, damit ein Bestand mit rekursivem Kopieren wandert. Das Organisationssegment trägt
die Mandantengrenze für den Aufräumlauf ohne Bibliotheksbezug (#1518): Jedes Original nennt seine
Organisation, ohne dass eine Datenbankzeile befragt werden muss; die Zugehörigkeitsprüfung prüft
beide Segmente, ein unpassendes Organisationssegment löst auf „nicht vorhanden" auf. Anhangzeilen
sind die Ausnahme: Ihr synthetischer `file_path` benennt kein Objekt; auf den Lesewegen leitet
`isReExtractableAttachment` sie vor jedem Adapter um, auf dem Löschweg löst der Locator regulär auf
„nicht vorhanden" auf und löscht nichts.

### 5. Die Umstellung eines Bestands ist eine Präfixersetzung, und zwar ein Betriebsvorgang

Bytes kopieren, dann in `documents.file_path` den Pfadpräfix ersetzen (`replace(file_path,
'/app/uploads/', 's3://mein-bucket/') WHERE source_type = 'UPLOAD'`); Anhangzeilen wandern ohne
Sonderfall mit, die Ersetzung ist injektiv. Kein Bestandsnachzug im Code, kein Parallel-Lesen beider
Ablagen: Die Umstellung ist ein Wartungsfenster, dokumentiert im
[Handbuch](../handbuch/originalablage.md) samt Rückweg.

### 6. Herunterladen streamt, ohne Zwischendatei — und verliert dabei Bereichsanfragen

Der S3-Adapter liefert den Objektkörper als Strom (`InputStreamResource`); für S3-gestützte
Originale gibt es kein HTTP-`Range` und keine Wiederaufnahme, der Dateisystem-Adapter behält beides
über `FileSystemResource`. Für Dokumente bis 50 MiB vertretbar.

### 7. Wer eine lokale Datei braucht, bekommt eine Kopie mit Aufräumpflicht beim Port

Reindex, Metadaten-Nachlauf und Rückextraktion bekommen eine lokale Kopie für die Dauer einer
Aktion, die der Port danach löscht; Temp-Verzeichnis `opaa.upload.s3.temp-directory`. Der
Platzbedarf unter S3 ist größer als eine Dateigröße je Upload (Multipart-Spool, Arbeitsdatei,
Kopie); das Handbuch nennt die Rechnung. Ein getöteter Prozess hat kein `finally`:
`UploadPendingRecoveryRunner` räumt beim Start `PENDING`-Zeilen und das Temp-Verzeichnis auf; das
verwaiste Objekt im Bucket sammelt der Aufräumlauf ein (Entscheidung 11).

### 8. Geteilter Clientbau, eigene Zieladressprüfung mit eigenem Namensraum

Der Clientbau (`io.opaa.s3`, `S3SdkClient`: Endpunkt, Region, Adressstil, Prüfsummen-Schalter
`WHEN_REQUIRED`, Zeitschranken, Ausgangsproxy, Wiederholstrategie, Fehlerübersetzung) wird von
Konnektor und Ablage benutzt, statt zweimal erarbeitet zu werden; der `ExecutionInterceptor`
(`S3RequestGuard`) ist parametrisiert — Validator für beide, Messwerk und Budget nur beim
Konnektor. Die Zieladressprüfung bekommt den Namensraum `opaa.upload.s3.target-validation`
(`UploadS3TargetPolicy`, Vorbild `OidcAddressPolicy` aus ADR-0025): Der konfigurierte Endpunkt
selbst ist nach Schema, Host und Port immer erlaubt, alles andere wird geprüft; das Abschalten der
Konnektorprüfung schaltet die Ablage nicht mit. `S3Credentials` des Konnektors wird nicht
wiederverwendet (sein Doppelpunkt-Verbot stammt aus dem Speicherformat der Bibliothek); die
Betriebskonfiguration trägt Zugangsschlüssel und Geheimnis als zwei Werte, die in keiner
Protokollzeile und keiner Meldung erscheinen.

### 9. Fehlende Konfiguration bricht den Start ab, ein nicht erreichbarer Speicher nicht

`opaa.upload.store=s3` ohne Bucket, Endpunkt oder Zugangsdaten beendet den Start mit einer Meldung,
die den fehlenden Wert nennt (Haltung `AuthProfileGuard`). Ein nicht erreichbarer Speicher beendet
den Start nicht: Chat, Suche und indizierte Inhalte funktionieren ohne ihn. Daraus folgt: Der
Health-Beitrag (`UploadStoreHealthIndicator`) geht nicht in den Gesamtstatus — er nähme im
Mehrinstanzbetrieb eine Instanz aus der Lastverteilung, die Chat und Suche weiter bedient. Der
Abruf eines Originals unterscheidet „Objekt nicht vorhanden" (`404` ohne Unterscheidbarkeit) von
„Speicher nicht erreichbar" (`503` mit deutscher Meldung).

### 10. Verschlüsselung ruhender Daten, Aufbewahrung und Replikation gehören dem Speicher

OPAA setzt keine Verschlüsselungskopfzeilen und verwaltet keine Schlüssel; SSE, Versionierung,
Lebenszyklusregeln und Replikation konfiguriert der Betrieb am Bucket.

### 11. Unberührt, und der Aufräumlauf

Netzlaufwerk bleibt der dritte Weg ohne Code; Temp-Dateien der Konnektoren bleiben lokal; das
Kontingent zählt `documents.file_size`; Entdoppelung bleibt `uk_documents_library_checksum`;
`FILESYSTEM`-Bibliotheken bleiben, wie sie sind. Eine `UPLOAD`-Bibliothek lässt sich nur leer
löschen — das schließt Zeilen aus, nicht Objekte. Deshalb gibt es den **Aufräumlauf**
(`OrphanedOriginalCleanupService`, `UploadStoreAdminController`, nur `SYSTEM_ADMIN`): zwei Aufrufe,
nie einer — melden ändert nichts, löschen entfernt nur die übergebenen Locators mit einem
Audit-Ereignis je Aufruf; Schonfrist `opaa.upload.orphan-grace-minutes` (60); je Bibliothek (#1478)
und über den ganzen Ablagebereich der Organisation für Originale gelöschter Bibliotheken (#1518).
Kein Zeitplan, der von selbst löscht.

## Konsequenzen

- **Einfacher:** Der Multiinstanzbetrieb verliert eine seiner Speicherfragen; große Bestände hängen
  nicht an einem Volume; die Ablage ist an genau einer Stelle gekapselt, mit einer Auflösung.
- **Schwieriger:** Ein zweiter Betriebsweg mit eigenem Backup-Ziel und Wiederherstellungsreihenfolge
  (erst Objekte, dann Datenbank). Zeile und Bytes fallen nicht mehr gemeinsam (fehlgeschlagenes
  `DeleteObject`, Abbruch zwischen `PutObject` und Einfügen) — dafür der Aufräumlauf.
  Bereichsanfragen fallen für S3 weg; die Temp-Platte wird Teil der Kapazitätsplanung.
- **Nachweisbarkeit:** Der Beleg bleibt greifbar; ein Prüfer schaut in einen Bucket statt in ein
  Verzeichnis, das Handbuch sagt ihm, wie.

## Verworfene Alternativen

- Originale als Blob in Postgres — WAL, Replikation und `pg_dump` tragen jede Datei; `bytea` streamt nicht; kalte Bytes verdrängen `shared_buffers`; Large Objects passen nicht zu JPA. Das Maß ist die Menge (`branding_settings.logo_content` bleibt bewusst `bytea`).
- Beide Ablagen parallel lesen — dauerhafte Verzweigung, die eine unvollständige Kopie verdeckt.
- Eigener S3-Client ohne geteilten Bau — am Clientbau hängt mehr als am Prüfschritt.
- Kein Organisationssegment im Schlüssel — ein verwaistes Objekt fände nach dem Löschen der Bibliothek nicht mehr zu seiner Organisation.

## Offen

- Vorsigniert ausgelieferte URLs — eine URL, die ohne Anmeldung funktioniert, ist eine andere Zugriffseigenschaft.
- Mehrere Ablagen nebeneinander (je Organisation oder Bibliothek ein Bucket) — das Schlüsselschema verbaut es nicht.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-11 | #1478, #1518 | Aufräumlauf für verwaiste Originale, je Bibliothek und ohne Bibliotheksbezug | 1, 7, 11 |
| 2026-09-11 | #1544 | Organisationssegment im Schlüssel beider Ablagen | 4 |
