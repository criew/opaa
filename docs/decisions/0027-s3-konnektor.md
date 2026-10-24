# ADR-0027: S3-Konnektor — typisierte Quellkonfiguration, Geltungsbereiche, Vollabgleich als einzige Betriebsart und Ereignisse als Beschleuniger

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt ADR-0017 (Entscheidung 5), ADR-0018 und ADR-0023 · Entscheidung 1 verallgemeinert durch ADR-0038 · Abgleich über den gemeinsamen Datei-Abgleich (ADR-0045, Entscheidung 1)

## Kontext

S3-kompatible Objektspeicher (AWS S3, MinIO, Ceph RGW, Hetzner Object Storage) sind die sechste
Herkunft. Vier Stellen, an denen die Grundlage aus ADR-0017, ADR-0018, ADR-0022 und ADR-0023 nicht
reicht: Die Quellkonfiguration ist erstmals ein Bündel (Region, Adressstil, Muster, Liste von
Bereichen); S3 listet vollständig und billig (1000 Schlüssel je Aufruf), kennt aber keine
Änderungssuche; der ETag ist kein Inhaltshash (Multipart, SSE-KMS, SSE-C); Benachrichtigungen sind
je Anbieter verschieden gebaut (AWS nur über EventBridge/SNS/SQS, MinIO eingebauter Webhook mit
Bearer, Ceph nur `user:password` in der Endpunkt-URI, Hetzner gar keine). Vier Fallen binden die
Umsetzung: Der Objektschlüssel ist in einer S3-Benachrichtigung URL-kodiert, in EventBridge nicht;
ein `HeadObject` auf einen fehlenden Schlüssel antwortet nur mit `s3:ListBucket` `404`, sonst `403`;
`ListObjectsV2` zeigt in versionierten Buckets nur aktuelle Versionen; MinIO baut `Bearer` selbst.

## Entscheidung

### 1. Die Quellkonfiguration bekommt ein typisiertes JSON-Feld

`knowledge_libraries.source_settings` (jsonb) trägt je Quellentyp einen Record, validiert beim
Lesen und Schreiben: `S3SourceSettings(region, pathStyle, scopes, includePatterns,
excludePatterns)` mit `S3Scope(bucket, prefix)`. Seit ADR-0038 (#1977) gilt das für jeden
Konnektor; die Datenbank prüft, dass `source_settings` ein JSON-Objekt ist, mindestens
einen Bereich sichert allein der Record. Was typübergreifend Regeln hat, bleibt Spalte: Endpoint in
`source_url` (Zieladressprüfung, Ursprungsbindung), Zugangsdaten verschlüsselt in
`source_credentials`, Zeitplan. **Geheimnisse stehen nie in `source_settings`** — die Spalte ist
unverschlüsselt und erscheint in `LibraryResponse`. Es gibt keinen Proxy je Bibliothek
(hausweiter Ausgangsproxy, ADR-0050) und keinen Schalter zum Aussetzen der
Zertifikatsprüfung; `S3SdkClient` setzt `TRUST_ALL_CERTIFICATES` nicht.

### 2. Geltungsbereiche: eine Liste von (Bucket, Präfix), mindestens einer, ohne Überlappung

Ein bis fünfzig Bereiche (`S3Scope.MAX_PER_LIBRARY`), Bucket nach AWS-Namensregeln, Präfix
normalisiert (kein führender, ein abschließender Schrägstrich bei nichtleerem Präfix). Zwei
Bereiche derselben Bibliothek dürfen sich nicht überlappen, weil ein Objekt in zwei Bereichen
zweimal denselben `file_path` hätte (`uk_documents_library_path`); zwei Bibliotheken dürfen
denselben Bereich tragen (getrennte Leserkreise, doppelte Indizierung als Preis, wie ADR-0023,
Entscheidung 5). Die Freigabe der Bibliothek gilt für alle Bereiche gemeinsam; der Wizard sagt das
vor der Eingabe. Bereiche sind änderbar; Entfernen wirkt mit dem nächsten Vollabgleich. Jede
Änderung von Bereichen oder Endpoint verwirft den Wiederaufnahmezustand (`SyncStateBasis`).
`ListBuckets` ist Komfort: Ohne `s3:ListAllMyBuckets` gibt es den Hinweis, den Bucket einzutragen.

### 3. Genau eine Betriebsart — der Vollabgleich — und ein Ereignislauf, der nie durch Abwesenheit löscht

`S3IndexingExecutor` deklariert `FULL → REMOVE_ON_ABSENCE` und `EVENT → KEEP_ON_ABSENCE`;
`defaultRunMode` ist konstant `FULL`. Es gibt keine inkrementelle Betriebsart: Die vollständige
Auflistung kostet tausend Aufrufe je Million Objekte und ist damit die Regelbetriebsart. Der
Vollabgleich läuft über den gemeinsamen Datei-Abgleich `indexing.filesync` (ADR-0045,
Entscheidung 1): `S3FileStore` ist der `FileStore` (ein Bereich ein Container, der Schlüssel die
Kennung, `S3ChangeMarker` das Änderungsmerkmal), `S3FullSync` liefert Wortlaut und Grenzen aus
`S3Properties`. Was dort für S3 gilt: Jeder gelistete Schlüssel ist vorhanden, auch ein
übersprungener (Ordnermarker, Archivklasse, Format, Größe); ein Schlüssel außerhalb der Muster
gehört nicht zum Bestand. `ListingOutcome.Complete` nur, wenn jeder Bereich bis zur letzten Seite
gelistet wurde; ein nicht listbarer Bereich (`404 NoSuchBucket`, `403 AccessDenied`) macht die
Auflistung unvollständig, die übrigen werden verarbeitet, bereinigt wird nichts — Rechteentzug ist
kein Löschbefund. Ein erschöpftes Anfragebudget beendet den Lauf als „unvollständig, wird
fortgesetzt"; der nächste Lauf listet alle Bereiche erneut (unvollendete zuerst) und spart nur die
Downloads; der `ContinuationToken` wird nie gespeichert. Ein Lauf, der trotz erschöpftem Budget
kein Objekt neu aufgenommen hat, meldet das als Fehler („reicht für diese Bibliothek nicht aus").
Mehr als `max-objects-per-run` Objekte sind ein sichtbarer Fehler („Bereiche enger fassen"), kein
stiller Schnitt. Ein Lauf, der null Objekte sieht, löscht nichts (Failsafe in
`StaleDocumentCleanupService`).

Der **Ereignislauf** (`IndexingRunMode.EVENT`) ist ein eigener Wert, nicht ein weiterer
`INCREMENTAL`: Er listet nichts, prüft gemeldete Schlüssel einzeln (`S3FullSync#refresh`), läuft nur
mit `JobTriggerSource.WEBHOOK` (Anstoß-Endpunkt und Zeitplan weisen ihn ab), meldet
`ListingOutcome.Partial` und rührt den Wiederaufnahmezustand nicht an. **Löschung braucht einen
positiven Befund der Quelle** (ADR-0023, Entscheidung 4): Ein `ObjectRemoved`-Ereignis stößt ein
`HeadObject` an; `404 NoSuchKey` (eindeutig, weil `s3:ListBucket` Pflichtrecht ist) entfernt das
Dokument samt Anhängen, `200` ändert nichts, `403` lässt es bis zum nächsten Vollabgleich stehen.
Ein wiedereingespieltes oder mit gestohlenem Token gesendetes Löschereignis kann den Index so nicht
leeren.

### 4. Änderungserkennung: der ETag ist das Änderungsmerkmal, die SHA-256 die Prüfsumme

`documents.last_modified_remote` trägt `e:<ETag>|<Größe>` (`S3ChangeMarker`), unabhängig von der
ETag-Länge (Spalte `text`); nur ein Speicher ohne ETag bekommt die Rückfallform
`t:<LastModified als Epochenmillisekunden>|<Größe>`. `LastModified` ist nie Teil des Merkmals:
Kopie, Metadatenänderung und Replikation setzen den Zeitstempel neu, ohne dass sich etwas ändert,
und es gibt keinen Fall, in dem sich der Inhalt ändert und ETag und Größe gleich bleiben. Stimmt das
Merkmal, wird nicht heruntergeladen; `LastModified` erscheint nur im Laufprotokoll und in der
Dokumentanzeige. Verbindlich bleibt die SHA-256 nach dem Download
(`ChecksumService`): ein neuer ETag bei gleicher Prüfsumme führt zu `refreshProvenance`, Dokument
und Zerlegung bleiben. `LastModified` wird nicht als Kernfeld „Datum/Stand" gesetzt (ADR-0024).

### 5. Identität, Ordner, Versionen, Archivklassen

`file_path = s3://<bucket>/<key>`, Schlüssel unverändert (Umbenennen ist ein neues Dokument);
`source_container_key` ist der Bucket, `source_hierarchy_path` das Präfix relativ zum Bereich. Bucket
und Schlüssel sind die Identität, der Endpoint nur der Weg: Ein Umzug des Speichers baut den Bestand
nicht neu. Kein Beleg-Link (`SourceConnectorDescriptor#withoutDeepLink`); stattdessen lädt
`S3OriginalAccess` das Objekt beim Klick durch OPAA, ohne eigene Rechtelogik (die `VIEWER`-Prüfung
steht in `loadContent` vor jeder Verzweigung), nur innerhalb der Bereiche und mit den zwei
Fehlerbildern der Originalablage (ADR-0030, Entscheidung 9): „gibt es nicht“ `404`, Speicher nicht
erreichbar `503` ohne Einzelheit der Quellkonfiguration, beide in der Spezifikation von `getDocumentContent` deklariert. Präfixsegmente
werden schreibgeschützte Ordner (`SourceFolderMirror`, `S3FolderPath`); gelesen wird nur die aktuelle
Version (Löschmarker gilt als Löschung, `versionId` wird nie gespeichert); Ordnermarker, Archivklassen
(`S3ArchiveState`, `GLACIER_IR` wird gelesen) und per Endung oder Größe vorab ausgefilterte Schlüssel
gelten als gesehen. Anhänge von Mail-Objekten hängen per `parent_document_id` am Objekt (ADR-0022,
Entscheidung 3) und fallen nur mit einem neu verarbeiteten Elternobjekt weg (`markReprocessed` statt
`markPresent` in `FileSync`). Einzelheiten: [Handbuch](../handbuch/arbeitsmittel/wissensbibliotheken/konnektoren/s3.md), „Ordner“ bis
„Grenzen und Schutzmechanismen“.

### 6. Der Push-Weg: ein Endpunkt je Bibliothek, drei Absicherungsformen, jedes Ereignis ein Hinweis

`POST /api/v1/libraries/{libraryId}/push` (seit ADR-0038 typneutral; das Geheimnis über
`/push-secret`: MANAGER+, einmal angezeigt, verschlüsselt in `source_webhook_secret` wie
Zugangsdaten, im Audit als `LIBRARY_SOURCE_UPDATED` mit Feldnamen) antwortet `202`, jede nicht
authentifizierte Anfrage gleichförmig `401`; der Eingang liegt im Rate-Limit-Topf `webhook`
(`RateLimitConfiguration`). Drei Formen (`S3EventAuthentication`, zeitkonstant):
`Authorization: Bearer <Token>` (MinIO), `Authorization: Basic` mit dem Token als Passwort (Ceph,
dort `https` Pflicht) und `X-OPAA-Webhook-Secret` (EventBridge API-Destination). Die Bearer-Form
braucht eine eigene, früher geordnete `SecurityFilterChain` (`PushIntakeSecurityConfig`) ohne
Resource-Server, Sitzung und CSRF, in beiden Profilen, weil der Resource-Server-Filter des
`oidc`-Profils jeden fremden Bearer sonst vorher mit `401` abwiese; ein Test belegt das gegen die
echte `oidc`-Kette. Rückfall für einen Betreiber, der die Kette nicht ändern kann: der zweiteilige
`auth_token` (`Basic <Base64>`) im Handbuch.
Nutzlast: S3-`Records` (Schlüssel URL-dekodiert), EventBridge-Umschlag (Schlüssel roh),
`s3:TestEvent` (`202`, keine Wirkung); Körper bis 256 KiB. Ereignisse außerhalb der Bereiche oder
Muster werden verworfen und gezählt. Gemeldete Schlüssel werden je Bibliothek gesammelt
(`opaa.indexing.events.*`, geteilt mit dem Confluence-Webhook) und in einem `EVENT`-Lauf
abgearbeitet; ein zu großer Stapel läuft als Vollabgleich, ein Stapel gegen einen laufenden Lauf
wartet begrenzt und wird dann verworfen. Kein Replay-Schutz, keine Auswertung des `sequencer`: Eine
wiedereingespielte Nachricht kostet einen `HeadObject` innerhalb der Ratenbegrenzung.
SNS-Handshake und -Signaturprüfung (Abruf einer URL auf Zuruf des Absenders, SSRF-Fläche) und die
SQS-Abfrage sind nicht gebaut; für AWS ist EventBridge der Weg.

### 7. Zugangsdaten: statischer Schlüssel mit optionalem Session-Token

`source_credentials` trägt `accessKey:secretKey[:sessionToken]`; ein Doppelpunkt in Access- oder
Secret-Key wird abgewiesen (würde still falsch zerlegt). `S3SdkClient` benutzt ausschließlich
`StaticCredentialsProvider` und eine explizite Region (leer → `us-east-1`): Die
Default-Credential- und Default-Region-Kette des SDK läse Umgebungsvariablen, Profile und den
Instanz-Metadatendienst des OPAA-Hosts und gäbe jeder Bibliothek die Identität des Hosts.
`S3Credentials` (`io.opaa.s3`) redigiert `toString()`; jede `S3Exception` wird in eine deutsche,
geheimnisfreie Meldung übersetzt (`S3FailureTranslator`), das Original nicht angehängt — **kein
Geheimnis in Antwort, Log, Exception-Message, `getCause()` oder Fehlermeldung**. Die
Ursprungsbindung gilt: Ein Wechsel von Schema, Host oder Port des Endpoints verwirft die
Zugangsdaten. Die Instanzrolle bleibt Zielbild als Betriebsentscheidung, nicht Bibliothekseinstellung.

### 8. Zieladressprüfung und die Schutzmechanismen, die der Adapter nachbildet

Das SDK bringt seinen eigenen HTTP-Client mit und läuft an `io.opaa.sourceaccess` vorbei; `io.opaa.s3`
bildet deshalb nach, Stück für Stück: `TargetAddressValidator` auf Endpoint und bei Virtual-Host
je Bereich auf `<bucket>.<host>` beim Speichern, beim Bau des Clients und vor jeder Anfrage
(`S3RequestGuard` als `ExecutionInterceptor`, Allowlist-Hinweis in der Meldung); Größenobergrenze
beim Kopieren in die temporäre Datei; Timeouts aus `request-timeout`; Ausgangsproxy als
`ProxyConfiguration`; keine Weiterleitungen (`301 PermanentRedirect` ist ein Konfigurationsfehler:
Region oder Adressstil); Wiederholungen bei `503 SlowDown` und `429` mit exponentiellem Backoff;
Anfragebudget je Lauf (nicht für die Sonden des Wizards); keine Politeness-Verzögerung;
Fehlerabbildung (`403` beim Listen gegen beim Lesen, `InvalidAccessKeyId`, `SignatureDoesNotMatch`,
`RequestTimeTooSkewed`, TLS). Der Clientbau ist mit der Originalablage geteilt (ADR-0030,
Entscheidung 8).

### 9. AWS SDK for Java v2 mit Apache-HTTP-Client; RustFS im Container als Testdoppel

`software.amazon.awssdk:s3` mit `apache5-client` statt des MinIO-SDK (kein Session-Token, kein Weg
zur Instanzrolle, zweiter HTTP-Stack). Port `S3ObjectStore` mit einem Adapter `AwsSdkS3ObjectStore`;
Testdoppel sind `FakeS3ObjectStore` und ein Container im regulären `./gradlew test`, und zwar
**RustFS** (`S3TestFixture.IMAGE`), nicht MinIO, dessen Image nicht mehr frei beziehbar ist:
RustFS führt als einziger Kandidat eingeschränkte Schlüssel mit denselben IAM-Policy-Dokumenten und
Fehlerformen. Der Ereignisweg wird gegen eine vom Test selbst zugestellte `Records`-Nutzlast
geprüft; die Zustellung durch den Speicher selbst ist Dokumentation, kein Testnachweis.

### 10. Zielanbieter und die Grenze Nextcloud/ownCloud

AWS S3, MinIO, Ceph RGW, Hetzner Object Storage und jeder Dienst mit `ListObjectsV2`, `HeadObject`,
`GetObject`, `HeadBucket` unter Signature v4; Anbietervorlagen im Wizard belegen Endpoint, Region
und Adressstil vor, jede Vorbelegung bleibt editierbar. Nextcloud und ownCloud als S3-Primärspeicher
sind kein Ziel (opake `urn:oid`-Objekte ohne Endung); für Nextcloud gibt es den eigenen Konnektor.

### 11. Namensraum `opaa.indexing.s3.*`

Grenzwerte unter `opaa.indexing.s3.*` (`OPAA_INDEXING_S3_*`): `list-page-size` 1000,
`max-objects-per-run` 1 000 000, `max-object-size-bytes` 50 MiB, `request-budget-per-run` 20 000,
`request-timeout` 30 s, `download-concurrency` 2, `max-retries` 5, `retry-backoff` 500 ms;
`request-budget-per-run` 0 heißt unbegrenzt. Die Ereignissammlung liegt seit 07.09.2026 unter
`opaa.indexing.events.*` (geteilt mit Confluence): `debounce` 5 s, `max-pending-keys` 500,
`max-deferrals` 120. Die Obergrenze von fünfzig Bereichen ist eine Konstante des Records, keine
Property.

## Stakeholder-Zusagen

Rahmen des Maintainers (Epic #1291) und Stand:

| Zusage | Stand | geändert mit |
|---|---|---|
| Ein Lauf ist immer ein Vollabgleich | gilt (Entscheidung 3) | — |
| Push ist Beschleuniger, nicht Ersatz, und Teil des Epics | gilt (Entscheidung 6) | — |
| Mehrere Bereiche je Bibliothek mit gemeinsamer Freigabe | gilt (Entscheidung 2) | — |
| Typisierte Konfiguration in einem JSON-Feld statt Spalten | gilt, für jeden Konnektor | #1977 (ADR-0038) |
| AWS SDK for Java v2 | gilt (Entscheidung 9) | — |
| Nextcloud und ownCloud sind kein Ziel | gilt (Entscheidung 10); eigener Nextcloud-Konnektor | #2152 |
| Dokumentpfad `s3://bucket/key`, Präfixe als Ordner, Ordnermarker und Archivklassen übersprungen, nur die aktuelle Version | gilt (Entscheidung 5) | — |
| Änderungsmerkmal „ETag, Größe und `LastModified`" | abweichend: `LastModified` ist nicht Teil des Merkmals (Entscheidung 4) | #1378 |
| Löschereignisse dürfen sofort löschen | gilt mit Nachprüfung: sofort im Ereignislauf, aber erst nach `HeadObject` mit `404` (Entscheidung 3) | #1381 |

## Konsequenzen

- **Einfacher:** Ein neuer Konnektor braucht keine Spalten. Eine Betriebsart, eine Löschregel —
  keine Verschleppung von Löschungen bis zu einem selteneren Lauf. Der Container-Nachweis läuft mit
  jedem Build. Ein Push-Geheimnis je Bibliothek, gleich welchen Typs.
- **Schwieriger:** Ein Vollabgleich lädt nie weniger als die vollständige Auflistung — bei AWS
  fallen `LIST`-Anfragen ins Entgelt. Der Adapter trägt die Schutzmechanismen zweimal (zentral in
  `sourceaccess`, nachgebildet in `io.opaa.s3`). Der Beleg-Abruf kostet je Klick einen `GetObject`.
  Die Anlage ist netzabhängig. Umbenennen und Verschieben bauen Dokumente neu.
- **Restrisiko:** `source_settings` wird von der Datenbank nur als JSON-Objekt geprüft; die
  Validierung liegt im Record, und ein Record, der einen Fall vergisst, wird von keiner Constraint
  gefangen. Ein Leserecht auf die Bibliothek genügt, jedes Objekt über den Beleg-Abruf wiederholt zu
  laden.

## Verworfene Alternativen

- Spalten an `knowledge_libraries` plus Kindtabelle (Muster Confluence) — sechs Spalten je Konnektor.
- Generisches Schlüssel-Wert-Schema — keine Typisierung; der Record ist die Typisierung, die JSON fehlt.
- Eine Bibliothek je Bucket über ein Verbindungsobjekt — die in ADR-0018 verworfene Konnektor-Tabelle.
- Inkrementelle Betriebsart über `LastModified` — S3 kann nicht danach filtern; Kopien setzen ihn neu.
- ETag als Prüfsumme — bei Multipart, SSE-KMS, SSE-C kein Inhaltshash.
- Ereignisse löschen ohne Nachprüfung — ein gestohlenes Token könnte den Bestand leeren.
- Ereignisse als `INCREMENTAL`-Lauf — täuschte im Protokoll eine Änderungssuche vor.
- Ein Endpunkt je Anbieter — die Nutzlastformen sind am Aufbau erkennbar.
- Eigene Geheimnisspalte für das S3-Token — Spaltenvermehrung, zweiter Verschlüsselungsweg.
- SNS-Handshake im ersten Ausbau — SSRF-Fläche auf Zuruf.
- Default-Credential-Kette des SDK — Rechteausweitung auf die Identität des Hosts.
- Vorsignierte Links als Beleg — eigenes Rechtemodell für eine Signatur im Browser.

## Offen

- Objektmetadaten (`x-amz-meta-*`) und Tags als Metadatenquelle (ADR-0024).
- SNS-Handshake und -Signaturprüfung, SQS-Abfrage; Instanzrolle als Zugangsdaten-Art.
- Rechteübernahme aus Bucket-Policies oder IAM; schreibender Zugriff; vorsignierte Beleg-Links.
- Schonzeitraum je Bibliothek (quellentypübergreifend); ein Vertragstest für Konnektoren (Epic #1293).
