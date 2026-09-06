# ADR-0027: S3-Konnektor — typisierte Quellkonfiguration, Geltungsbereiche, Vollabgleich als einzige Betriebsart und Ereignisse als Beschleuniger

## Status

Vorgeschlagen

## Kontext

OPAA kennt fünf Herkünfte: `UPLOAD`, `FILESYSTEM`, `HTTP_DIRECTORY`, `RSS_FEED` und `CONFLUENCE`.
Epic #1291 ergänzt **S3-kompatible Objektspeicher** als sechste — AWS S3, MinIO, Ceph RGW, Hetzner
Object Storage und weitere Dienste, die die S3-API sprechen. Viele Behörden und Rechenzentren legen
Ablagen inzwischen dort ab; der Dateisystem-Konnektor erreicht sie nur über einen Mount, der
Webverzeichnis-Konnektor gar nicht.

Grundlage sind das Quellentypmodell aus [ADR-0017](0017-quellentypmodell-indizierung.md) (Executor je
Typ, Registry, Löschsemantik je Betriebsart seit ADR-0023), die Quellkonfiguration an der Bibliothek
aus [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md), der Anhangsweg aus
[ADR-0022](0022-anhang-als-eigenes-dokument.md) und der Confluence-Konnektor aus
[ADR-0023](0023-confluence-konnektor.md), der den gemeinsamen Laufrahmen (`IndexingRunTemplate`,
`ListingOutcome`, `VanishedDocumentPolicy`), das Anfragebudget, die Wiederaufnahme und den Webhook
als Beschleuniger etabliert hat. An vier Stellen reicht diese Grundlage nicht:

1. **Die Quellkonfiguration ist erstmals ein Bündel.** Eine S3-Bibliothek trägt Region, Adressstil,
   Ein-/Ausschlussmuster und eine Liste von Geltungsbereichen (Bucket plus Präfix). Nach dem Muster
   von Confluence wären das vier bis fünf weitere Spalten an `knowledge_libraries` plus eine
   Kindtabelle — und der nächste Konnektor (SharePoint, ein DMS) brächte die nächsten mit.
2. **S3 listet vollständig und billig, aber nie „geändert seit".** `ListObjectsV2` liefert 1000
   Schlüssel je Aufruf mit ETag, Größe und Zeitstempel; eine Änderungssuche gibt es nicht. Das
   Zwei-Betriebsarten-Modell von Confluence (Vollabgleich selten, inkrementell oft) hat hier keinen
   Gegenstand.
3. **Der ETag ist kein Inhaltshash.** Laut Herstellerdokumentation ist er nur für einfache Uploads
   ohne oder mit SSE-S3-Verschlüsselung ein MD5 der Daten; bei Multipart-Uploads (die AWS-Konsole
   nutzt sie ab 16 MB) sowie bei SSE-KMS und SSE-C ist er es nicht. Er taugt als Änderungsmerkmal,
   nicht als Prüfsumme.
4. **Änderungsbenachrichtigungen sind je Anbieter verschieden gebaut** und bei einem Anbieter gar
   nicht vorhanden (Belege unten). Ein Push-Weg muss deshalb mehrere Nutzlastformen und
   Absicherungen annehmen, ohne je Anbieter einen eigenen Endpunkt zu bekommen.

### Gesetzte Rahmenbedingungen

Sieben Vorentscheidungen des Maintainers (06.09.2026, Epic #1291) stehen **vor** diesem ADR fest und
werden hier begründet, nicht neu verhandelt: ein Lauf ist immer ein Vollabgleich; Push ist
Beschleuniger, nicht Ersatz, und Teil des Epics; eine Bibliothek trägt mehrere Geltungsbereiche
mit gemeinsamer Freigabe; typisierte Quellkonfiguration in einem JSON-Feld statt weiterer Spalten;
AWS SDK for Java v2; Nextcloud und ownCloud sind kein Ziel; Dokumentpfad `s3://bucket/key`, Präfixe
als Ordner, Ordnermarker und Archivklassen übersprungen, nur die aktuelle Version.

### Belege: Anbieter, Ereigniswege, Grenzen

Geprüft am 06.09.2026 gegen die Herstellerdokumentation und den Quelltext von MinIO. Diese
Unterschiede sind der Grund für den Zuschnitt des Push-Wegs in Entscheidung 6.

| | AWS S3 | MinIO | Ceph RGW | Hetzner Object Storage |
|---|---|---|---|---|
| Adressstil | Virtual-Host (Path-Style weiter bedient, aber abgekündigt) | Path-Style (Standard) | Path-Style oder Virtual-Host | Virtual-Host (`<bucket>.<ort>.your-objectstorage.com`) |
| Region | Pflicht, Teil der Signatur | beliebig (`us-east-1` üblich) | beliebig | Ort (`fsn1`, `nbg1`, `hel1`) |
| Bucket-Auflistung (`ListBuckets`) | nur mit `s3:ListAllMyBuckets` | mit Recht | mit Recht | ja |
| Push-Weg | kein direkter HTTP-Webhook: SNS, SQS, Lambda oder EventBridge; EventBridge API-Destination liefert HTTP mit frei gesetzter Kopfzeile | eingebauter Webhook: `mc admin config set … notify_webhook endpoint=… auth_token=…`, je Bucket `mc event add --event put,delete --prefix …`; sendet `Authorization: Bearer <auth_token>`, `Content-Type: application/json`, Körper `{EventName, Key: "bucket/key", Records: [...]}` | Topic mit `push-endpoint=http[s]://…`, Absicherung nur als `user:password` in der Endpunkt-URI (HTTP Basic); Körper im S3-`Records`-Format mit `opaqueData` | **keine Bucket-Benachrichtigungen** („Not supported") |
| Ereignisnutzlast | `Records[].s3.object.key` **URL-kodiert** (`red+flower.jpg`); EventBridge: `detail.object.key` roh, `detail-type` „Object Created"/„Object Deleted", `deletion-type` | wie AWS, zusätzlich Umschlag `EventName`/`Key` | wie AWS | — |
| Verschlüsselung | SSE-S3, SSE-KMS, SSE-C | SSE-S3, SSE-KMS, SSE-C | SSE-S3, SSE-KMS, SSE-C | nur SSE-C |
| Archivklassen | `GLACIER`, `DEEP_ARCHIVE` (Wiederherstellung nötig), `GLACIER_IR` (sofort lesbar), `INTELLIGENT_TIERING` mit Archivstufen | keine (Tiering nach außen möglich) | keine | nur `STANDARD` |

Quellen: [AWS: Event message structure](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html),
[AWS: EventBridge event message structure](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ev-events.html),
[AWS: Object (ETag, StorageClass)](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Object.html),
[MinIO: Publish Events to Webhook](https://docs.min.io/enterprise/aistor-object-store/administration/bucket-notifications/publish-events-to-webhook/),
[MinIO: `mc event add`](https://docs.min.io/enterprise/aistor-object-store/reference/cli/mc-event/mc-event-add/),
[MinIO: `internal/event/target/webhook.go`](https://github.com/minio/minio/blob/master/internal/event/target/webhook.go),
[Ceph: Bucket Notifications](https://docs.ceph.com/en/latest/radosgw/notifications/),
[Hetzner: Supported actions](https://docs.hetzner.com/storage/object-storage/supported-actions),
[Hetzner: Overview](https://docs.hetzner.com/storage/object-storage/overview),
[Testcontainers: MinIO](https://java.testcontainers.org/modules/minio/),
[AWS SDK for Java v2: `S3BaseClientBuilder`](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/S3BaseClientBuilder.html).

Vier Befunde daraus sind Fallen, keine Merkmale, und binden die Umsetzung:

- **Der Objektschlüssel in einer S3-Benachrichtigung ist URL-kodiert, in einer
  EventBridge-Benachrichtigung nicht.** Wer beide Formen mit derselben Dekodierung liest, sucht
  nach `Vergütung%202025.pdf` oder nach `Verg+tung`. Die Dekodierung gehört an die Nutzlastform.
- **Ein `HeadObject` auf einen fehlenden Schlüssel antwortet nur dann mit `404`, wenn der Aufrufer
  `s3:ListBucket` hat — sonst mit `403`.** Ein Schlüssel, der das Recht nicht hat, kann Fehlen und
  Verbot nicht unterscheiden. `s3:ListBucket` ist deshalb Pflichtrecht jeder S3-Bibliothek, nicht nur
  für die Auflistung.
- **`ListObjectsV2` zeigt in versionierten Buckets nur aktuelle Versionen; ein Schlüssel mit
  Löschmarker fehlt in der Auflistung.** Das ist genau das Verhalten, das die Löschung durch
  Abwesenheit braucht — und der Grund, warum keine Versions-API angefasst wird.
- **MinIO baut `Authorization: Bearer <auth_token>` selbst**, Ceph kann nur `user:password` in der
  URI (HTTP Basic), EventBridge setzt eine beliebige Kopfzeile. Ein Endpunkt, der nur eine Form
  annimmt, schließt einen Anbieter aus.

## Entscheidung

### 1. Die Quellkonfiguration bekommt ein typisiertes JSON-Feld — Nachtrag zu ADR-0018

`knowledge_libraries` erhält eine Spalte `source_settings jsonb`. Ihr Inhalt ist je Quellentyp ein
Java-Record, der beim Lesen und Schreiben validiert wird; für S3 ist das
`S3SourceSettings(region, pathStyle, scopes, includePatterns, excludePatterns)` mit
`S3Scope(bucket, prefix)`. Die Abbildung folgt dem bestehenden Muster `@JdbcTypeCode(SqlTypes.JSON)`
auf einer `jsonb`-Spalte (`Chat.metadataFilter`, #1070) — als Text an der Entity, serialisiert und
validiert durch den Record, damit die Entity keinen Jackson-`FormatMapper` von Hibernate braucht.

Was **nicht** in das Feld gehört, bleibt in den bestehenden Spalten, weil es dort bereits Regeln
hat: der Endpoint in `source_url` (Zieladressprüfung, Ursprungsbindung der Zugangsdaten über
`SourceOriginMatcher`, #516), die Zugangsdaten verschlüsselt in `source_credentials` im Format
`accessKey:secretKey[:sessionToken]` (Entscheidung 7), Proxy in `source_proxy` (`host:port`), der
TLS-Schalter in `source_insecure_ssl`, der Zeitplan in `schedule_*`. **Geheimnisse stehen nie in
`source_settings`** — die Spalte ist unverschlüsselt und erscheint (ohne Geheimnisse) in
`LibraryResponse`.

Die Constraint `chk_knowledge_libraries_source_configuration` bekommt nach dem
Drop-und-Neuanlage-Muster einen `S3`-Zweig: `source_url` Pflicht, `source_settings` Pflicht,
`source_path` verboten, Proxy und `source_insecure_ssl` zulässig; für jeden anderen Typ ist
`source_settings` **`NULL`** — der Umzug bestehender Typen in das Feld ist nicht Teil dieses ADR
(siehe „Ausdrücklich offen"). Beide Enums, `DocumentSourceType` (`io.opaa.api.types`,
`isRemote() = true`, `hasIndexingRun() = true`) und `IndexingSourceType`, und die drei Wertelisten
(`chk_documents_source_type`, `chk_knowledge_libraries_source_type`,
`chk_knowledge_libraries_source_configuration`, zuletzt neu angelegt in Changeset 010 für
`CONFLUENCE`) werden um `S3` erweitert.

**Der Nachtrag zu ADR-0018:** Entscheidung 1 dort kennt Quellkonfiguration als Einzelwerte; ADR-0023
hat den ersten Listenwert als Kindtabelle angelegt. Dieses ADR legt fest, dass **typspezifische
Konfiguration ab jetzt in `source_settings` lebt** und die Bibliothek nur noch das trägt, was
typübergreifend Regeln hat (Adresse, Zugangsdaten, Proxy, TLS, Zeitplan). Der Unterschied zur
Confluence-Entscheidung ist nicht, dass eine Liste vorkommt, sondern dass die Konfiguration ein
*Bündel* zusammengehöriger Werte ist, deren Form je Anbieter variiert; Confluence hatte genau einen
Listenwert und sonst zwei Spalten. Die Argumente von ADR-0023 gegen JSON — Eindeutigkeit,
Nichtleere, ein Ort für den Fortschritt je Element — werden hier anders beantwortet: Eindeutigkeit
und Nichtüberlappung prüft der Record im Kompaktkonstruktor; die Nichtleere zusätzlich eine
`CHECK`-Constraint im `S3`-Zweig, die beide Fälle fängt, die ein `jsonb_array_length` allein
durchließe (`NULL`-Ergebnis bei fehlendem Schlüssel gilt als erfüllt; ein Nicht-Array wirft
statt zu verletzen): `jsonb_typeof(source_settings -> 'scopes') = 'array' AND
jsonb_array_length(source_settings -> 'scopes') >= 1`. Der Fortschritt je Bereich lebt in der
Zustandstabelle (Entscheidung 3), nicht an der Konfiguration. Eine Abfrage „welche Bibliotheken
lesen Bucket X" braucht heute niemand; wird sie gebraucht, ist ein Ausdrucksindex über
`jsonb_path_query` billiger als eine Kindtabelle je Typ.

### 2. Geltungsbereiche: eine Liste von (Bucket, Präfix), mindestens einer, ohne Überlappung

Eine S3-Bibliothek trägt **einen bis fünfzig** Geltungsbereiche. Jeder besteht aus einem Bucket
(3–63 Zeichen, Kleinbuchstaben, Ziffern, Punkte und Bindestriche, kein IP-Format — die
AWS-Namensregeln, die MinIO und Ceph übernehmen) und einem optionalen Präfix. Das Präfix wird
normalisiert: kein führender Schrägstrich, ein abschließender Schrägstrich wird ergänzt, wenn das
Präfix nicht leer ist (`2025/protokolle` → `2025/protokolle/`), weil nur so `2025/protokolle/…`
gemeint ist und nicht `2025/protokolle-alt/…`. Ein leeres Präfix ist der ganze Bucket.

Zwei Bereiche derselben Bibliothek dürfen sich **nicht überlappen** — weder gleich sein noch darf
einer das Präfix des anderen im selben Bucket sein. Der Grund ist kein Geschmack, sondern
`uk_documents_library_path`: Ein Objekt, das in zwei Bereichen liegt, hätte zweimal denselben
`file_path` (Entscheidung 5), und der zweite Bereich würde jedes Objekt des ersten als „bereits
vorhanden" überschreiben oder abweisen. Die Anlage weist Überlappung mit einer Meldung ab, die
beide Bereiche nennt. Zwei *Bibliotheken* dürfen dagegen denselben Bereich tragen — dieselbe Regel
wie bei Confluence (ADR-0023, Entscheidung 5): getrennte Leserkreise, getrennter Bestand, doppelte
Indizierung als Preis.

**Die Freigabe der Bibliothek gilt für alle Bereiche gemeinsam.** Das ist die Rahmenbedingung aus
ADR-0023, unverändert übernommen: „Person darf Bucket A, aber nicht Präfix B" ist in diesem Modell
nicht ausdrückbar, sondern eine zweite Bibliothek. Der Wizard nennt das **vor** der Eingabe der
Bereiche; die Detailansicht zeigt es dauerhaft.

Die Bereiche sind **nach der Anlage änderbar** (wie die Space-Auswahl). Entfernen wirkt mit dem
nächsten vollständigen Lauf, weil nur er löschen darf (Entscheidung 3); Hinzufügen ebenso. Jede
Änderung der Bereiche oder des Endpoints verwirft den Wiederaufnahmezustand (Entscheidung 3).
`ListBuckets` ist Komfort für den Wizard: Ein Schlüssel ohne `s3:ListAllMyBuckets` bekommt keine
Fehlermeldung, sondern den Hinweis, den Bucket-Namen einzutragen — eingeschränkte Schlüssel sind
der Normalfall.

### 3. Genau eine Betriebsart — der Vollabgleich — und ein Ereignislauf, der nie durch Abwesenheit löscht

`S3IndexingExecutor` deklariert `FULL → REMOVE_ON_ABSENCE` und, mit dem Push-Weg (Entscheidung 6),
`EVENT → KEEP_ON_ABSENCE`. **Es gibt keine inkrementelle Betriebsart**, und das ist keine Lücke:
S3 bietet keine Änderungssuche, und die vollständige Auflistung ist mit 1000 Schlüsseln je Aufruf
so billig, dass ein Vollabgleich über eine Million Objekte tausend Auflistungsaufrufe kostet — die
Größenordnung, die Confluence für zwanzigtausend Seiten braucht. Der Vollabgleich ist damit
zugleich die *Regel*betriebsart des Zeitplans, und das Zwei-Betriebsarten-Modell aus ADR-0023
(Vollabgleich selten, weil teuer) hat hier keinen Gegenstand.

Der Vollabgleich folgt dem Laufrahmen aus ADR-0023 ohne Sonderregel:

- Jeder Bereich wird **seitenweise vollständig** gelistet; jedes gesehene Objekt wird `markPresent`
  gemeldet — auch ein übersprungenes (Ordnermarker, Archivklasse, nicht unterstütztes Format, zu
  groß, nicht lesbar), weil unlesbar nicht verschwunden ist.
- `ListingOutcome.Complete` nur, wenn **jeder** Bereich bis zur letzten Seite gelistet wurde. Ein
  Bereich, dessen Bucket nicht existiert (`404 NoSuchBucket`) oder nicht gelistet werden darf
  (`403 AccessDenied`), macht die Auflistung `Incomplete`; alle nicht listbaren Bereiche stehen als
  `bucket/prefix` in `unreadableContainerKeys` — die übrigen Bereiche werden trotzdem verarbeitet,
  bereinigt wird nichts, jeder betroffene Bereich steht im Laufprotokoll. **Rechteentzug ist kein
  Löschbefund**, wie bei Confluence.
- Ein erschöpftes Anfragebudget beendet den Lauf als `Truncated`: keine Bereinigung, der nächste
  Lauf setzt an. **Wiederaufnahme heißt nicht „ab dem Token weiterlisten".** `currentPaths` — die
  Menge, gegen die `StaleDocumentCleanupService#reconcile` löscht — ist je Lauf neu; ein Lauf, der
  nur den Rest einer abgebrochenen Auflistung listet und dann `Complete` meldet, würde alles
  entfernen, was der Vorlauf gesehen hat. Deshalb **listet ein wiederaufsetzender Lauf jeden
  Bereich erneut von vorn** — das ist billig (tausend Aufrufe je Million Objekte) — und spart nur
  die Downloads: Ein Objekt, dessen Merkmal (Entscheidung 4) bereits gespeichert ist, kostet keinen
  weiteren Aufruf. Die Kette der Wiederaufnahmeläufe konvergiert damit, solange das Budget die
  Auflistung aller Bereiche plus eine Handvoll Downloads übersteigt; ein Lauf, der trotz
  erschöpftem Budget kein Objekt neu aufgenommen hat, meldet das als Fehler („reicht für diese
  Bibliothek nicht aus") — dieselbe Regel wie bei Confluence (#1141). Der `ContinuationToken` gilt
  nur innerhalb eines Laufs und wird nie gespeichert. Die Zustandstabelle `s3_sync_state` je
  Bibliothek trägt, was ein Neustart der Kette braucht: die Kennung des laufenden Vollabgleichs,
  die in ihm bereits vollständig gelisteten Bereiche **als `bucket/prefix`, nicht als
  Listenindex** (ein Index verschiebt sich, wenn ein Bereich entfernt wird) — sie bestimmen nur die
  Reihenfolge, unvollendete Bereiche zuerst — und den Zeitpunkt des letzten vollständigen Laufs.
  Änderung der Bereiche oder des Endpoints verwirft den Zustand.
- Eine Bibliothek, deren Bereiche zusammen mehr als `max-objects-per-run` Objekte listen, ist für
  einen Lauf zu groß: Der Lauf endet **sichtbar als Fehler** mit dem Hinweis, die Bereiche
  enger zu fassen — nicht als `Truncated`, das nie `Complete` würde und nie bereinigte, und nicht
  als stiller Schnitt (dieselbe Haltung wie `maxListingPages` bei Confluence). Die Grenze ist eine
  Notbremse für Speicher und Laufzeit (`currentPaths` hält jeden Pfad im Heap), keine
  Regelgrenze; #1380 misst, wo sie liegen muss.
- Die Bereinigung ist die gemeinsame `StaleDocumentCleanupService#reconcile`, begrenzt auf
  `(Bibliothek, S3)` und die Menge `currentPaths`; ein abgewählter Bereich steuert nichts bei und
  fällt weg. Ein Lauf, der null Objekte sieht, löscht nichts (bestehender Failsafe).

Der **Ereignislauf** (`EVENT`) ist eine neue Betriebsart im geteilten Enum `IndexingRunMode`
(Spec-Änderung nach ADR-0006, Changeset für `chk_indexing_jobs_run_mode`, Label im Frontend), nicht
ein weiterer `INCREMENTAL`: Er listet nichts, sondern prüft gemeldete Schlüssel einzeln; ihn
„inkrementell" zu nennen, würde im Laufprotokoll eine Änderungssuche vortäuschen, die es nicht gibt.
Er läuft nur mit `JobTriggerSource.WEBHOOK`: Der Anstoß-Endpunkt und der Zeitplan weisen
`runMode = EVENT` mit einer deutschen `400`-Meldung ab (`DocumentIndexingService#resolveRunMode`
akzeptiert heute jeden deklarierten Modus — für `EVENT` braucht es die Bindung an den Auslöser),
`S3IndexingExecutor#defaultRunMode` liefert konstant `FULL`, und der Ereignislauf meldet
`ListingOutcome.Partial`, den einzigen für `KEEP_ON_ABSENCE` zulässigen Wert. Er löscht nie durch
Abwesenheit und rührt den Wiederaufnahmezustand nicht an. Löschen darf er — nach der Regel aus ADR-0023, die dieses ADR
unverändert übernimmt: **Löschung braucht einen positiven Befund der Quelle.** Für S3 ist der
Befund billig und eindeutig: Ein `ObjectRemoved`-Ereignis stößt ein `HeadObject` an; antwortet der
Speicher `404 NoSuchKey` (eindeutig, weil `s3:ListBucket` Pflichtrecht ist — sonst wäre es `403`),
ist das Objekt weg und das Dokument wird samt Anhängen entfernt; antwortet er `200`, war das
Ereignis veraltet oder falsch, und nichts geschieht; antwortet er `403` oder anders, bleibt das
Dokument bis zum nächsten Vollabgleich stehen. Die Vorentscheidung „Löschereignisse dürfen sofort
löschen" wird damit so gelesen: sofort im Ereignislauf, nicht erst im nächsten Vollabgleich — aber
nach einem Aufruf, der aus der Nachricht einen Befund macht. Der Aufruf kostet nichts, was der
Verzicht wert wäre: Ein wiedereingespieltes oder mit gestohlenem Token gesendetes Löschereignis
kann so den Index nicht leeren.

Das ist ein Nachtrag zu ADR-0017, Entscheidung 5 und ADR-0023, Entscheidung 4: Die Regel „Löschung
braucht einen positiven Befund" gilt für jeden Konnektor; was als Befund zählt, benennt der jeweilige
ADR — für Confluence `status = trashed`, für S3 ein `404 NoSuchKey` unter Pflichtrecht
`s3:ListBucket` (oder das Fehlen in einer vollständigen Auflistung).

### 4. Änderungserkennung: der ETag ist das Änderungsmerkmal, die SHA-256 die Prüfsumme

Das Änderungsmerkmal im Sinne von ADR-0017, Entscheidung 2 — geprüft **vor** dem Download — ist
für ein S3-Objekt die Kombination **ETag und Größe**, gespeichert in `documents.last_modified_remote`
als `e:<ETag ohne Anführungszeichen>|<Größe in Bytes>` (für die bei AWS, MinIO und Ceph
vorkommenden Formen höchstens rund 55 Zeichen; die Spalte fasst 64, Confluence hält dort die
Versionsnummer). Der ETag ändert sich laut Herstellerdokumentation nur mit dem Inhalt, nie mit
Metadaten — genau die Eigenschaft eines Änderungsmerkmals —, und die Größe fängt den theoretischen
Fall eines Speichers ab, der einen ETag wiederverwendet. Stimmt das Merkmal mit dem gespeicherten
überein, wird das Objekt **nicht heruntergeladen** und als übersprungen gezählt; weicht es ab,
wird es geladen und der Dokumentstrecke übergeben.

**Das weicht von Vorentscheidung 1 des Epics ab**, die „ETag, Größe und `LastModified`" nennt (so
auch das Abnahmekriterium des Epics und #1378): `LastModified` ist **nicht** Teil des Merkmals. Ein
erneutes Hochladen desselben Inhalts (Kopie, Metadatenänderung per `CopyObject` auf sich selbst,
Replikation) setzt den Zeitstempel neu, ohne dass sich etwas geändert hat; der ETag bleibt dann
gleich, und der Download unterbleibt zu Recht — mit `LastModified` im Merkmal würde er
stattfinden. Umgekehrt gibt es keinen Fall, in dem sich der Inhalt ändert und ETag *und* Größe
gleich bleiben, den `LastModified` fangen könnte. Fehlt der ETag (ein Speicher, der ihn nicht
liefert) oder wäre das Merkmal länger als 64 Zeichen (ein S3-kompatibler Speicher mit eigener
ETag-Form), tritt die Rückfallform `t:<LastModified als Epochenmillisekunden>|<Größe>` an seine
Stelle; die beiden Präfixe `e:` und `t:` halten die Formen unterscheidbar, gleich wie ein ETag
aussieht. `LastModified` erscheint im Laufprotokoll und in der Dokumentanzeige, wird aber nicht als
Kernfeld „Datum/Stand" gesetzt — ADR-0024 leitet Datum aus dem Inhalt ab, ein Upload-Zeitpunkt
ist kein Dokumentdatum.

**Verbindlich bleibt die SHA-256 nach dem Download** (`ChecksumService`, `FileProcessingService#ingest`):
Ein geändertes Merkmal bei gleicher Prüfsumme (Multipart-Upload desselben Inhalts, Wechsel der
Verschlüsselung, SSE-KMS-Schlüsselrotation) führt zu `refreshProvenance` — Dokument-ID und
Zerlegung bleiben, nur das Merkmal wird nachgetragen. Der ETag ersetzt die Prüfsumme nicht und
wird nie mit ihr verglichen.

### 5. Identität, Ordner, Versionen, Archivklassen

- **`file_path` = `s3://<bucket>/<key>`**, der Schlüssel unverändert (nicht URL-kodiert; ein
  Schlüssel ist bis 1024 Bytes lang, die Spalte fasst 2000). Das ist die Identität je Bibliothek
  (`uk_documents_library_path`) und der Grund für die Überlappungsregel in Entscheidung 2.
  **Umbenennen ist ein neues Dokument** — S3 kennt kein Umbenennen, nur Kopie und Löschung, und ein
  Objekt hat keine schlüsselunabhängige Kennung. `file_name` ist das letzte Pfadsegment des
  Schlüssels; der Bucket steht als `source_container_key`, das Präfix relativ zum Bereich als
  `source_hierarchy_path` (die mit ADR-0023 angelegten Spalten).
- **Kein Beleg-Link im ersten Ausbau.** `Document#getDeepLinkSourceUrl` liefert heute für jeden
  Typ mit `isRemote()` den `file_path`; für S3 wäre das ein `s3://`-Link, den kein Browser öffnet.
  Die Methode liefert für `S3` **`null`**; Beleg und Dokumentliste zeigen den Pfad als Text.
  Vorsignierte Links (`GetObject` mit Ablauf) sind Zielbild, weil sie eine Signatur mit dem
  Bibliotheksschlüssel an den Browser geben und ein eigenes Rechtemodell brauchen (wer den Beleg
  sieht, darf dann das Objekt laden).
- **Ordner:** Die Präfixsegmente eines Schlüssels relativ zum Präfix seines Bereichs werden als
  schreibgeschützte Ordner gespiegelt (`SourceFolderMirror`, #1277; `S3` kommt in
  `LibraryFolderService.MIRRORED_SOURCE_TYPES`). Bei genau **einem** Bereich ist dessen Präfix die
  Wurzel der Bibliothek; bei **mehreren** Bereichen steht über den Präfixsegmenten eine
  **Segmentkette** aus dem Bucket-Namen und den Segmenten des Bereichspräfixes — nie ein
  zusammengesetzter Einzelname `bucket/prefix`, denn ein Ordnername ist schrägstrichfrei und auf
  255 Zeichen begrenzt (`LibraryFolderService#validateName`, `library_folders.name`). So bleiben
  gleichnamige Pfade aus zwei Buckets getrennt. Ein Schlüssel, dessen Ordnerkette tiefer wäre als
  `LibraryFolderService.MAX_DEPTH`, liegt im tiefsten zulässigen Ordner, mit Warnung im
  Anwendungsprotokoll — der Konnektorpfad prüft die Tiefe heute nicht, ein S3-Schlüssel darf
  beliebig tief sein. Aufgeräumt wird nur nach vollständiger Auflistung, wie bei `HTTP_DIRECTORY`.
  **Ordnermarker** — Schlüssel, die auf `/` enden, mit null Bytes — werden übersprungen und nie zu
  Dokumenten; sie erzeugen auch keinen leeren Ordner (Ordner entstehen nur entlang gefundener
  Dateien, #824).
- **Versionierte Buckets:** nur die aktuelle Version. `ListObjectsV2` zeigt nichts anderes; ein
  Schlüssel mit Löschmarker fehlt und gilt als gelöscht; `versionId` wird weder gespeichert noch
  angefragt. Eine Wiederherstellung einer alten Version ist für OPAA eine Änderung (neuer ETag).
- **Archivklassen:** Objekte in `GLACIER` und `DEEP_ARCHIVE` sowie Objekte, deren
  `HeadObject` einen nicht abgeschlossenen Archivstatus (`ArchiveStatus`, `RestoreStatus`) meldet,
  werden mit eigener Protokollkategorie übersprungen — ein `GetObject` darauf antwortet
  `InvalidObjectState`, eine Wiederherstellung anzustoßen wäre ein Schreibvorgang mit Kosten.
  `GLACIER_IR` ist sofort lesbar und wird normal verarbeitet. Ein übersprungenes Archivobjekt
  gilt als gesehen (`markPresent`).
- **Vorfilter vor dem Download:** Die Auflistung liefert Schlüssel, ETag, Größe, Zeitstempel und
  Speicherklasse — **keinen `Content-Type`**; der steht nur in `HeadObject` und `GetObject`. Der
  Vorfilter entscheidet deshalb primär über die **Endung** des Schlüssels gegen
  `SupportedDocumentFormats` und die Größe gegen `max-object-size-bytes`. Ein Objekt mit
  unbekannter Endung wird nicht geladen. Ein Objekt **ohne** Endung kostet genau einen
  `HeadObject` (gezählt im `S3RequestMeter`): Ist sein `Content-Type` zugelassen, wird es geladen,
  sonst übersprungen. Die verbindliche Formaterkennung bleibt die aus dem Inhalt in der
  Dokumentstrecke — der Vorfilter spart Bandbreite, entscheidet aber nichts.
- **Anhänge:** Mail-Objekte (`.eml`, `.msg`) laufen über `AttachmentIndexer` mit
  `AttachmentSource.LocalFile` — die Bytes hat die Zugriffsschicht bereits in eine temporäre Datei
  geladen, wie bei Confluence. Anhänge hängen per `parent_document_id` an ihrem Objekt und werden
  mit ihm entfernt (ADR-0022, Entscheidung 3). Ein Objekt, dessen Bytes geladen und dessen
  Anhangsmenge neu aufgezählt wurden, meldet `markReprocessed`; jedes andere gesehene Objekt
  `markPresent` — nur so faltet `StaleDocumentCleanupService` die alten Anhänge eines *nicht*
  neu verarbeiteten Elternobjekts ein und entfernt die eines neu verarbeiteten, die es nicht mehr
  gibt (ADR-0022, Entscheidung 3, Nachtragsfall).
- **Objektmetadaten (`x-amz-meta-*`) und Tags** sind Zielbild (ADR-0024), nicht erster Ausbau; der
  Bucket als Metadatum genügt.

### 6. Der Push-Weg: ein Endpunkt je Bibliothek, drei Absicherungsformen, jedes Ereignis ein Hinweis

`POST /api/v1/libraries/{libraryId}/s3-events` ist nach `confluence-webhook` der zweite
schreibende Eingang unter `/api/v1`, der ohne Sitzung erreichbar ist, und folgt dessen Muster:
Rate-Limit-Topf `webhook` (dessen Pfadmuster in `RateLimitConfiguration` heute nur
`confluence-webhook` kennt und erweitert wird), Antwort `202 Accepted` wie dort, jede nicht
authentifizierte Anfrage antwortet gleichförmig `401` — auch für eine unbekannte Bibliothek, einen
anderen Quellentyp oder eine Bibliothek ohne Token. Angenommen werden **drei** Formen, weil die
Anbieter sie vorgeben (Belege oben): `Authorization: Bearer <Token>` (MinIO baut genau das aus
einem einteiligen `auth_token`), `Authorization: Basic` mit dem Token als Passwort und beliebigem
Benutzernamen (Ceph kann nur `user:password` in der Endpunkt-URI — dort ist `https` Pflicht, das
Handbuch sagt es) und die Kopfzeile `X-OPAA-Webhook-Secret: <Token>` (EventBridge API-Destination
mit API-Key-Verbindung, jeder Absender mit freier Kopfzeile). Alle drei Vergleiche sind
zeitkonstant.

**Die Bearer-Form braucht eine eigene Sicherheitskette.** Im `oidc`-Profil hängt
`OidcSecurityConfig` den `BearerTokenAuthenticationFilter` des Resource-Servers vor *jede*
Anfrage; `permitAll` überspringt nur die Autorisierung, nicht diesen Filter. Ein
`Authorization: Bearer <Token>`, der kein JWT eines bekannten Anbieters ist, würde dort mit `401`
abgewiesen, bevor der Ereignis-Endpunkt je läuft — und die Zusicherung „jede nicht
authentifizierte Anfrage antwortet `401`" ließe den Defekt wie korrektes Verhalten aussehen. Der
Endpunkt bekommt deshalb eine **eigene, früher geordnete `SecurityFilterChain`** mit
`securityMatcher("/api/v1/libraries/*/s3-events")`, ohne Resource-Server, ohne Sitzung, ohne
CSRF, in beiden Profilen (`oidc` und `dev`); die Prüfung des Tokens ist allein Sache des
Endpunkts. Das ist ein bewusster Eingriff in die Auth-Konfiguration und Abnahmekriterium von
#1381, mit Test gegen die echte `oidc`-Kette. Als Rückfall für einen Betreiber, der die Kette
nicht ändern kann, dokumentiert das Handbuch den zweiteiligen `auth_token` (`Basic <Base64>`):
MinIO setzt einen Wert mit Leerzeichen unverändert als `Authorization` und präfixt nur einteilige
Werte mit `Bearer` (Quelltext oben).

**Das Token ist das Push-Geheimnis der Bibliothek**, verwaltet über
`POST`/`DELETE /api/v1/libraries/{libraryId}/s3-events-token` (MANAGER+, einmal angezeigt,
verschlüsselt gespeichert wie Zugangsdaten, im Audit als `LIBRARY_SOURCE_UPDATED` mit Feldnamen).
Es lebt in der Spalte, die heute `source_confluence_webhook_secret` heißt: Die Spalte wird zu
`source_webhook_secret` **umbenannt** und trägt je Bibliothek das eine Geheimnis ihres
Push-Eingangs, gleich welchen Typs — dieselbe Semantik, derselbe Verschlüsselungspfad, derselbe
Bearbeitungsweg. Eine zweite Geheimnisspalte je Konnektor wäre genau die Spaltenvermehrung, die
Entscheidung 1 beendet; in `source_settings` darf ein Geheimnis nicht (unverschlüsselt). Die
Endpunkte und der Property-Block bleiben dagegen typspezifisch benannt (`s3-events`,
`s3-events-token`, `opaa.indexing.s3.events.*` neben `confluence-webhook`,
`confluence-webhook-secret`, `opaa.indexing.confluence.webhook.*`), weil Nutzlast,
Absicherungsformen und Einrichtungsanleitung je Typ verschieden sind — gemeinsam ist nur die
Ablage des Geheimnisses.

**Nutzlast.** Der Endpunkt liest drei Formen und erkennt sie am Aufbau, nicht am Absender:

- das S3-`Records`-Format (AWS über SNS/SQS-Weiterleitung, Ceph, MinIO — MinIOs Umschlag
  `EventName`/`Key` wird ignoriert, gelesen werden die `Records`): `s3.bucket.name`,
  `s3.object.key` **URL-dekodiert** (`application/x-www-form-urlencoded`, `+` ist ein
  Leerzeichen), `eventName` mit oder ohne Präfix `s3:`;
- den EventBridge-Umschlag (`detail-type` „Object Created"/„Object Deleted", `detail.bucket.name`,
  `detail.object.key` **roh**, `detail.deletion-type`);
- die Testnachricht `s3:TestEvent`, die AWS beim Einrichten sendet: `202`, keine Wirkung.

Ein Ereignis für einen Bucket oder Schlüssel **außerhalb der konfigurierten Bereiche** wird
verworfen und gezählt; ein Ereignis mit einem Schlüssel, der die Ein-/Ausschlussmuster nicht
passiert, ebenso. Der Körper ist auf dieselbe Grenze wie beim Confluence-Webhook begrenzt
(`ConfluenceWebhookController.MAX_BODY_BYTES`, 256 KiB, als geteilte Konstante) — reichlich, denn
AWS begrenzt eine Ereignisnachricht laut der oben zitierten Strukturbeschreibung auf 64 KB.

**Verarbeitung.** Ein Ereignis ist ein Hinweis, die Antwort des Speichers der Befund:
`ObjectCreated:*` → `HeadObject`, dann derselbe Weg wie im Vollabgleich (Merkmal vergleichen,
Vorfilter, Download, Dokumentstrecke); `ObjectRemoved:*` einschließlich `DeleteMarkerCreated` →
`HeadObject`, Löschung samt Anhängen nur bei `404 NoSuchKey` (Entscheidung 3); jede andere
Ereignisart → `HeadObject` und Behandlung nach dem Ergebnis. Gemeldete Schlüssel werden je
Bibliothek gesammelt (Entprellung, Standard fünf Sekunden) und in **einem** `EVENT`-Lauf mit
Auslöser `WEBHOOK` abgearbeitet; ein Stapel jenseits einer Obergrenze anstehender Schlüssel läuft
stattdessen als gewöhnlicher Vollabgleich; ein Stapel, der auf einen laufenden Lauf trifft, wartet
begrenzt und wird dann verworfen — der nächste Lauf deckt dieselben Schlüssel ab. Bewusst ohne
Replay-Schutz und ohne Auswertung des `sequencer`: Eine wiedereingespielte Nachricht kostet einen
`HeadObject` innerhalb der Ratenbegrenzung und ändert nichts, was der Speicher nicht bestätigt.

**Grenzen des Push-Wegs.** Der **SNS-Bestätigungs-Handshake** (`SubscriptionConfirmation` mit
abzurufender `SubscribeURL`) und die SNS-Signaturprüfung (Zertifikat von einer in der Nachricht
genannten URL laden) sind **nicht** Teil des ersten Ausbaus, sondern ein Folge-Issue: Beide holen
auf Zuruf des Absenders eine URL ab und öffnen damit genau die SSRF-Fläche, die
`TargetAddressValidator` schließt; für AWS ist die EventBridge API-Destination der gebaute Weg,
sie braucht keinen Handshake. Die **SQS-Abfrage** durch OPAA bleibt außerhalb des Epics. Der Push
ersetzt weder Zeitplan noch Vollabgleich: Ohne ihn ist nichts falsch, nur später; ein verlorenes
Ereignis holt der nächste geplante Lauf nach.

### 7. Zugangsdaten: statischer Schlüssel mit optionalem Session-Token; die Instanzrolle ist Zielbild

`source_credentials` trägt `accessKey:secretKey[:sessionToken]`, zerlegt am ersten und — falls
vorhanden — zweiten Doppelpunkt. Bei AWS-erzeugten Schlüsseln kommt ein Doppelpunkt nicht vor; bei
MinIO und Ceph wählt der Betreiber Access- und Secret-Key frei, und ein Doppelpunkt darin würde
still falsch zerlegt und als `SignatureDoesNotMatch` auf die falsche Ursache zeigen. Die Eingabe
weist Access- und Secret-Key mit Doppelpunkt deshalb mit deutscher `400`-Meldung ab („Zugangsdaten
dürfen keinen Doppelpunkt enthalten"); das Handbuch nennt die Regel beim Anlegen des Schlüssels.
Der Adapter setzt daraus `StaticCredentialsProvider` mit `AwsBasicCredentials` bzw.
`AwsSessionCredentials`. **Die Default-Credential-Kette des SDK wird nie benutzt**: Sie läse
Umgebungsvariablen, Profile und den Instanz-Metadatendienst (IMDS) des OPAA-Hosts und gäbe damit
*jeder* S3-Bibliothek die Identität des Hosts — wer eine Bibliothek anlegen darf (jeder
Berechtigte, ADR-0018, Entscheidung 6), könnte jeden Bucket lesen, den die Hostrolle lesen darf.
Zugangsdaten sind je Bibliothek, nicht je Host. Aus demselben Grund wird auch die
**Default-Region-Kette** nie benutzt: Der Client wird immer mit expliziter Region gebaut — die
konfigurierte, bei leerer Angabe `us-east-1`, was MinIO und Ceph erwarten —, damit keine Auflösung
des SDK je den Metadatendienst erreicht.

Die **schlüssellose Anmeldung über die Instanzrolle** bleibt deshalb Zielbild: Sie kommt, wenn
überhaupt, als ausdrücklich vom Betrieb freigeschaltete Zugangsdaten-Art
(`opaa.indexing.s3.instance-role-enabled`, Standard aus) mit eigener Anzeige an der Bibliothek, und
sie ist dann eine Betriebsentscheidung, keine Bibliothekseinstellung.

Die Regeln aus ADR-0018, Entscheidung 4 und ADR-0023, Entscheidung 3 gelten unverändert und
werden auf die neuen Stellen ausgedehnt: Ein Wertobjekt `S3Credentials` mit redigierendem
`toString()`, Übergabe an das SDK an genau einer Stelle, **kein Geheimnis in Antwort, Log,
Exception-Message, `getCause()` oder Fehlermeldung** — auch nicht in den vom SDK erzeugten
Ausnahmen, deren Meldungen die Anfrage-URL, die Access-Key-ID und Signaturdetails enthalten
können: Die Zugriffsschicht bildet jede `S3Exception` auf eine eigene, deutsche, geheimnisfreie
Meldung ab und hängt das Original nicht an. Die Ursprungsbindung (#516) gilt: Ein Wechsel von
Schema, Host oder Port des Endpoints verwirft die Zugangsdaten.

### 8. Zieladressprüfung und die Schutzmechanismen, die der Adapter nachbildet

Das SDK bringt seinen eigenen HTTP-Client mit und läuft an `SourceHttpClientFactory` vorbei. Was
`io.opaa.sourceaccess` für die anderen Netzkonnektoren zentral erzwingt, bildet der Adapter deshalb
bewusst nach — Stück für Stück, mit Test je Stück:

- **Zieladressprüfung:** `TargetAddressValidator.validate(URI)` auf den Endpoint-Host beim
  Speichern, im Verbindungstest und beim Bau jedes Clients — und, in derselben Granularität wie
  bei den HTTP-Konnektoren (`RedirectFollowingFetcher` prüft vor jedem Abruf), **vor jeder
  Anfrage**: Ein `ExecutionInterceptor` des SDK prüft in `beforeTransmission` den Host der
  signierten Anfrage, sodass das Rebinding-Fenster nicht länger ist als das im Javadoc von
  `TargetAddressValidator` benannte. Bei Virtual-Host-Adressierung spricht das SDK
  `<bucket>.<host>` an — geprüft wird deshalb je Bereich auch dieser Hostname, nicht nur der
  Endpoint, beim Bau des Clients und je Anfrage. Ein internes MinIO im privaten Adressbereich
  braucht wie ein Confluence Data Center den Eintrag in
  `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`; die Meldung nennt die Variable. Weil der Endpoint
  vor der Anlage geprüft wird, ist die Anlage netzabhängig wie bei Confluence.
- **Größenobergrenze:** `GetObject` liefert einen Stream; der Adapter kopiert ihn mit
  `BoundedStreams` in eine temporäre Datei und bricht beim Überschreiten von
  `max-object-size-bytes` ab, ohne die Datei zu übernehmen. Die Größe aus der Auflistung ist der
  Vorfilter, die Byte-Grenze beim Kopieren die Sicherung (die Auflistung kann lügen).
- **Timeouts:** Verbindungs- und Lese-Timeout am HTTP-Client und `apiCallAttemptTimeout` am
  Client, alle drei aus dem einen Wert `request-timeout` abgeleitet.
- **Proxy und TLS:** `source_proxy` als `ProxyConfiguration`, `source_insecure_ssl` als
  `TRUST_ALL_CERTIFICATES` des HTTP-Clients — dieselbe Warnung an der Oberfläche wie heute.
- **Weiterleitungen:** keine; ein `301 PermanentRedirect` ist ein Konfigurationsfehler (falsche
  Region oder falscher Adressstil) und wird als solcher gemeldet.
- **Wiederholungen:** `503 SlowDown` und `429` werden mit exponentiellem Backoff wiederholt
  (`max-retries`, `retry-backoff`), gezählt im `S3RequestMeter`, nie stillschweigend; die
  Wiederholungsgrenze beendet den Lauf mit Meldung.
- **Anfragebudget:** `S3RequestMeter` zählt jede Anfrage und jedes geladene Byte je Lauf; das
  Budget gilt für Läufe, nicht für die Sonden des Wizards.
- **Keine Politeness-Verzögerung:** Der Objektspeicher ist ein Dienst des Hauses oder ein
  bezahlter Dienst, kein fremder Webserver.
- **Fehlerabbildung** in deutsche, geheimnisfreie Meldungen: `403` beim Listen („`s3:ListBucket`
  fehlt") vs. beim Lesen („`s3:GetObject` fehlt"), `404 NoSuchBucket`, `301 PermanentRedirect`
  (Region oder Path-Style), `InvalidAccessKeyId`/`SignatureDoesNotMatch` (Zugangsdaten),
  `RequestTimeTooSkewed` (Uhr des OPAA-Hosts), TLS-Fehler (Zertifikat; der Schalter „TLS-Prüfung
  aussetzen" wird als letzte Option genannt), Zieladressprüfung (Allowlist-Variable).

### 9. AWS SDK for Java v2 mit Apache-HTTP-Client; MinIO im Container als Testdoppel

Bibliothek ist `software.amazon.awssdk:s3` mit `software.amazon.awssdk:apache5-client` (Apache 2.0),
deklariert in `libs.versions.toml`. Gründe gegen das MinIO-Java-SDK: Es kennt weder Session-Tokens
noch die AWS-Regionensignatur noch einen Weg zur Instanzrolle (Entscheidung 7, Zielbild), bringt
OkHttp als zweiten HTTP-Stack mit und ist auf AWS selbst weniger erprobt. Gründe für den
Apache-Client statt des leichteren `url-connection-client`: Proxy und `TRUST_ALL_CERTIFICATES`
lassen sich nur dort sauber setzen. Preis: rund zehn Megabyte zusätzlicher Abhängigkeiten und ein
zweiter HTTP-Client im Prozess — benannt, akzeptiert, weil das SDK Signatur, Paginierung,
Wiederholungen und Adressstile kennt, die eine Eigenimplementierung gegen vier Anbieter erst
lernen müsste. `chunkedEncodingEnabled` und die Prüfsummenberechnung für Anfragen werden bei
S3-kompatiblen Zielen so eingestellt, wie das Testdoppel es verlangt (MinIO und Ceph verstehen die
neueren `aws-chunked`-Prüfsummenkopfzeilen nicht in jeder Version); die Einstellung ist Teil der
Anbietervorlage.

Die Zugriffsschicht ist ein **Port `S3ObjectStore`** (Auflistung seitenweise mit Schlüssel, ETag,
Größe, `LastModified`, Speicherklasse; `headObject`; `getObject` mit Byte-Obergrenze in eine
temporäre Datei; `listBuckets`; `testAccess` je Bereich) mit **einem** Adapter auf das SDK — anders
als Confluence gibt es keine zweite Edition. Testdoppel sind ein `FakeS3ObjectStore` (In-Memory,
steuerbare Fehler) für Unit-Tests und **MinIO in Testcontainers**
(`org.testcontainers:testcontainers-minio`, `MinIOContainer`, Image gepinnt) im **regulären
`./gradlew test`**: MinIO ist klein genug für jeden Testlauf, anders als Confluence Data Center.
Paginierung, Path-Style, Signaturen, ETag-Verhalten und der Webhook werden gegen MinIO abgenommen;
Virtual-Host-Adressierung über die Anfragesignatur im Unit-Test, weil MinIO im Container keine
Bucket-Subdomains auflöst. S3 wird damit der erste Konnektor mit vollständigem Container-Nachweis
und die Referenz für Epic #1293.

### 10. Zielanbieter und die Grenze Nextcloud/ownCloud

Zielliste des ersten Ausbaus: **AWS S3, MinIO, Ceph RGW, Hetzner Object Storage** — und jeder
weitere Dienst, der `ListObjectsV2`, `HeadObject`, `GetObject` und `HeadBucket` mit Signature v4
bedient. Der Wizard bietet **Anbietervorlagen**, die Endpoint-Form, Region, Adressstil und
Hinweise vorbelegen (AWS: Region wählen, Endpoint abgeleitet, Virtual-Host; MinIO/Ceph/andere:
Endpoint-URL, Path-Style; Hetzner: Ort wählen, Virtual-Host, Hinweis „keine Benachrichtigungen,
nur SSE-C"); jede Vorbelegung bleibt editierbar, weil eine Vorlage eine Vermutung ist.

**Nextcloud und ownCloud sind kein Ziel.** Beide nutzen S3 als Primärspeicher mit opaken
Objektnamen (`urn:oid:<id>`); Dateinamen und Ordner stehen nur in ihrer Datenbank, und ein
Bucket-Lauf sähe tausende namenlose Objekte ohne Endung. Nur ein in Nextcloud als *externer
Speicher* eingebundener Bucket zeigt echte Dateien — und der ist dann schlicht ein S3-Bucket, den
OPAA direkt liest. Das Handbuch benennt die Grenze; der Vorfilter (Entscheidung 5) sortiert die
`urn:oid`-Objekte ohnehin aus, ein Betreiber soll aber nicht erst am Protokoll merken, dass er den
falschen Weg gewählt hat.

### 11. Namensraum `opaa.indexing.s3.*`

Grenzwerte des Konnektors leben unter `opaa.indexing.s3.*`, analog `opaa.indexing.confluence.*`,
mit Umgebungsvariablen `OPAA_INDEXING_S3_*`. Die Vorgabewerte sind Ausgangswerte, die #1380 und
#1382 gegen MinIO messen und begründen — nicht hier geraten, sondern hier benannt, damit jedes
Ticket dieselben Schlüssel benutzt:

| Schlüssel | Vorgabe | Wirkung |
|---|---|---|
| `list-page-size` | 1000 | `MaxKeys` je Auflistungsaufruf, höchstens 1000 |
| `max-objects-per-run` | 1 000 000 | gelistete Objekte je Lauf; erreicht → sichtbarer Fehler „Bereiche enger fassen" (Notbremse für Heap und Laufzeit, keine Regelgrenze) |
| `max-object-size-bytes` | 50 MiB | Vorfilter und Byte-Obergrenze beim Download |
| `request-budget-per-run` | 20 000 | Aufrufe je Lauf einschließlich Wiederholungen und `HeadObject` für endungslose Schlüssel; erschöpft → `Truncated`; 0 = unbegrenzt. Rechnung: ein Aufruf je 1000 gelistete Objekte, plus einer je endungslosem Objekt, plus einer je geändertem Objekt — 20 000 decken eine Million unveränderter Objekte mit Endung und rund 18 000 Downloads |
| `request-timeout` | 30 s | je Aufruf (Verbindungs-, Lese- und Versuchs-Timeout) |
| `download-concurrency` | 2 | gleichzeitige Downloads je Lauf (Semaphore) |
| `max-retries` / `retry-backoff` | 5 / 500 ms | Wiederholungen bei `503 SlowDown` und `429`, exponentiell |
| `events.debounce` | 5 s | Sammelzeit gemeldeter Schlüssel |
| `events.max-pending-keys` | 500 | darüber läuft ein Vollabgleich statt des Ereignislaufs |
| `events.max-deferrals` | 12 | wie oft ein Stapel auf einen laufenden Lauf wartet |

Die Obergrenze von **fünfzig Geltungsbereichen** ist keine Property, sondern eine feste Konstante
des Records (Entscheidung 2): Sie schützt vor Fehlbedienung, nicht vor Last, und ein
Kompaktkonstruktor liest keine Spring-Property.

## Ausdrücklich offen

- **Umzug der bestehenden Typen nach `source_settings`.** `CONFLUENCE` behält Edition, Spaces und
  Vollabgleichsrhythmus in ihren Spalten und Tabellen; ein Umzug wäre eine eigene Migration mit
  eigenem ADR-Nachtrag. Dieses ADR legt nur fest, dass **neue** Typen das Feld benutzen.
- **Vorsignierte Beleg-Links** (Entscheidung 5).
- **Objektmetadaten und Tags** als Metadatenquelle (ADR-0024).
- **SNS-Handshake und -Signaturprüfung**, **SQS-Abfrage** (Entscheidung 6).
- **Instanzrolle** als Zugangsdaten-Art (Entscheidung 7).
- **Rechteübernahme** aus Bucket-Policies oder IAM; **schreibender Zugriff**.
- **Schonzeitraum je Bibliothek** — quellentypübergreifend, `knowledge-sources.md`.
- **Ein Vertragstest für Konnektoren** (Epic #1293): Welche der MinIO-Szenarien sich als
  wiederverwendbarer Vertragstest herausziehen lassen, entscheidet #1382 mit dem Epic, nicht dieses
  ADR.

## Konsequenzen

### Einfacher

- **Ein neuer Konnektor braucht keine Spalten mehr.** Region, Adressstil, Muster und Bereiche
  sind ein Record; der nächste Typ bringt seinen eigenen mit, und die Constraint wächst um einen
  Zweig statt um fünf Spalten.
- **Eine Betriebsart, eine Löschregel.** Der Vollabgleich ist zugleich Regel- und Bereinigungslauf;
  es gibt keine Verschleppung von Löschungen bis zu einem selteneren Lauf, und das Handbuch muss
  keine zwei Betriebsarten erklären.
- **Die Löschregel bleibt ein Satz:** Löschung braucht einen positiven Befund. Für S3 ist er ein
  `404` unter Pflichtrecht oder das Fehlen in einer vollständigen Auflistung — der Ereignislauf
  darf löschen, ohne die Regel zu brechen.
- **Der Container-Nachweis läuft mit jedem Build.** MinIO ist klein; die Suite ersetzt das
  Testdoppel nicht, aber sie fängt, was Fakes nicht sehen (Paginierung, Signatur, ETag-Form,
  Webhook-Zustellung).
- **Der Push-Weg hat ein Geheimnis je Bibliothek, gleich welchen Typs**, über die umbenannte
  Spalte — kein zweiter Verschlüsselungs- und Verwaltungsweg.

### Schwieriger

- **`source_settings` ist für fünf von sechs Typen leer**, und die Datenbank prüft seinen Inhalt
  nur über `CHECK`-Ausdrücke auf `jsonb`, nicht über Spaltentypen. Die Validierung liegt im
  Record; ein Record, der einen Fall vergisst, wird von keiner Constraint gefangen. Deshalb ist der
  Record-Test (Bucket-Regeln, Normalisierung, Überlappung, Obergrenze) Abnahmekriterium von #1375,
  nicht Stilfrage.
- **Ein Vollabgleich lädt nie weniger als die vollständige Auflistung** — tausend Aufrufe je
  Million Objekte bei jedem Lauf, auch stündlich. Das ist billig, aber nicht kostenlos; bei AWS
  fallen `LIST`-Anfragen ins Entgelt. Das Handbuch sagt es beim Zeitplan.
- **`IndexingRunMode` bekommt einen dritten Wert**, den Laufprotokoll, Frontend-Label und
  `chk_indexing_jobs_run_mode` kennen müssen — für einen Konnektor.
- **Die Umbenennung der Geheimnisspalte** fasst den Confluence-Code an (Entity-Feld, Mapper,
  Service). Der Umbau ist mechanisch, aber er liegt in #1381, einem ohnehin großen Ticket.
- **Der Adapter trägt die Schutzmechanismen zweimal**: einmal zentral in `io.opaa.sourceaccess`
  für die HTTP-Konnektoren, einmal nachgebildet am SDK-Client. Eine Änderung an der einen Stelle
  erreicht die andere nicht von selbst; Entscheidung 8 listet die Stücke, damit ein Review sie
  abhaken kann.
- **Kein Beleg-Link** im ersten Ausbau; wer das Objekt öffnen will, braucht den Pfad und einen
  eigenen Zugang zum Speicher.
- **Die Anlage ist netzabhängig** (Endpoint-Prüfung vor dem Speichern), wie bei Confluence.
- **Umbenennen und Verschieben bauen Dokumente neu** (Identität ist der Schlüssel), mit neuer
  Zerlegung und neuen Einbettungen. Ein Bucket, in dem Präfixe regelmäßig umgeräumt werden, kostet
  entsprechend.

## Verworfene Alternativen

**Weitere Spalten an `knowledge_libraries` und eine Kindtabelle für die Bereiche (Muster
Confluence).** Sauberer für die Datenbank, jede Regel als Constraint. Verworfen, weil Region,
Adressstil, zwei Musterlisten und die Bereiche zusammen sechs neue Spalten und eine Tabelle wären
— und SharePoint oder ein DMS die nächsten sechs brächten. Die Constraint
`chk_knowledge_libraries_source_configuration` ist heute schon die komplexeste des Schemas
(ADR-0023, „Schwieriger"). Die Kindtabelle war für Confluence richtig, weil dort *nur* die Liste neu
war.

**Ein generisches Schlüssel-Wert-Schema (`library_settings(key, value)`).** Ohne Spalten, ohne
JSON. Verworfen, weil es keine Typisierung hat: Niemand sieht am Schema, welche Schlüssel ein Typ
kennt, und jede Validierung wäre Prosa. Der Record ist die Typisierung, die JSON fehlt.

**Eine Bibliothek je Bucket, verbunden über ein Verbindungsobjekt (Endpoint, Zugangsdaten).** Würde
die Freigabefolge entschärfen und Zugangsdaten zentral rotierbar machen. Verworfen als
Rahmenbedingung und aus denselben Gründen wie in ADR-0023: Das Verbindungsobjekt ist die
Konnektor-Tabelle, die ADR-0018 verworfen hat, mit eigenem Rechtemodell.

**Eine inkrementelle Betriebsart über `LastModified`** (nur Objekte mit Zeitstempel nach dem
Anker laden). Verworfen, weil S3 nicht danach filtern kann — die Auflistung wäre dieselbe, nur
der Vergleich anders — und weil `LastModified` bei Kopien und Replikation neu gesetzt wird, ohne
dass sich der Inhalt ändert. Der ETag-Vergleich im Vollabgleich leistet dasselbe ohne zweite
Betriebsart.

**ETag als Prüfsumme statt SHA-256.** Spart den Download für die Prüfsumme. Verworfen, weil der
ETag bei Multipart, SSE-KMS und SSE-C kein Inhaltshash ist (Beleg oben) und `ChecksumService` die
quellentypübergreifende Identität des Inhalts ist (Duplikaterkennung, `refreshProvenance`).

**`LastModified` als Teil des Änderungsmerkmals.** Verworfen (Entscheidung 4): erzeugt Downloads
für Kopien desselben Inhalts und fängt nichts, was der ETag nicht fängt.

**Ereignisse löschen ohne Nachprüfung.** Schnellste Aktualität, von der Vorentscheidung zugelassen.
Verworfen zugunsten eines `HeadObject` (Entscheidung 3): Der Aufruf ist billig, der Befund
eindeutig, und ein gestohlenes Token kann so keinen Bestand leeren. Die Vorentscheidung ist in ihrem
Kern — Löschereignisse wirken sofort, nicht erst im Vollabgleich — erfüllt.

**Ereignisse als `INCREMENTAL`-Lauf** (wie der Confluence-Webhook). Verworfen, weil das
Laufprotokoll damit eine Änderungssuche vortäuschte; der Confluence-Webhook *ist* ein
inkrementeller Lauf über gemeldete Seiten, der S3-Ereignislauf listet nichts.

**Ein Endpunkt je Anbieter** (`/minio-events`, `/eventbridge-events`). Klarer in der Dokumentation.
Verworfen, weil die drei Nutzlastformen sich am Aufbau erkennen lassen und ein Anbieter, der
morgen eine vierte Form sendet, keinen neuen Pfad brauchen soll.

**Eine eigene Geheimnisspalte für das S3-Token.** Verworfen (Entscheidung 6): dieselbe Semantik wie
das Confluence-Geheimnis, ein zweiter Verschlüsselungs- und Bearbeitungsweg wäre Vermehrung.

**SNS-Handshake im ersten Ausbau.** Verworfen (Entscheidung 6): SSRF-Fläche auf Zuruf, EventBridge
deckt AWS ab.

**MinIO-Java-SDK statt AWS SDK.** Verworfen (Entscheidung 9): kein Session-Token, kein Weg zur
Instanzrolle, zweiter HTTP-Stack.

**Default-Credential-Kette des SDK zulassen.** Verworfen (Entscheidung 7): Rechteausweitung auf
die Identität des Hosts für jeden, der eine Bibliothek anlegen darf.

**Adressunabhängige Identität (`s3:<bucket>:<key>` ohne Endpoint).** Ist bereits so: `file_path`
enthält den Endpoint nicht, ein Umzug des Speichers auf einen anderen Endpoint (Migration MinIO →
Ceph mit denselben Buckets) baut den Bestand **nicht** neu — anders als bei `HTTP_DIRECTORY` und
Confluence. Das ist beabsichtigt und der einzige Punkt, an dem S3 von den URL-basierten Typen
abweicht: Bucket und Schlüssel sind die Identität, der Endpoint ist der Weg.

**Vorsignierte Links als Beleg.** Verworfen für jetzt (Entscheidung 5).

## Zuschnitt der übrigen Sub-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR |
|---|---|
| #1374 Zugriffsschicht | Port `S3ObjectStore` mit einem Adapter; `apache5-client`; **nur** `StaticCredentialsProvider` und explizite Region; Zieladressprüfung beim Bau des Clients (Endpoint, Proxy, `<bucket>.<host>` bei Virtual-Host) **und je Anfrage** im `ExecutionInterceptor`; `getObject` mit `BoundedStreams` in eine temporäre Datei; `S3RequestMeter` zählt Aufrufe **und** Bytes; Fehlerabbildung aus Entscheidung 8 einschließlich `InvalidAccessKeyId`/`SignatureDoesNotMatch` und `InvalidObjectState`; Log-Capture-Test, dass keine SDK-Ausnahme mit Geheimnis durchsickert; `MinioFixture` im regulären `test`-Task; Anfrage-Prüfsummen `WHEN_REQUIRED` für S3-kompatible Ziele |
| #1375 Quellentyp | `source_settings jsonb` mit `@JdbcTypeCode(SqlTypes.JSON)` auf einem String und Record-Validierung; `CHECK` für `S3`-Zweig **und** `jsonb_typeof(scopes) = 'array' AND jsonb_array_length(scopes) >= 1`; Überlappungsregel und feste Obergrenze 50 im Record; Doppelpunkt in Access-/Secret-Key abgewiesen; `Document#getDeepLinkSourceUrl` liefert für `S3` `null`; kein `EVENT`-Laufmodus (kommt mit #1381); Platzhalter-Executor deklariert nur `FULL` |
| #1376 Verbindungstest | `HeadBucket`, `ListObjectsV2` (`MaxKeys` 1), `HeadObject` auf das erste Objekt; `301` → Region/Adressstil; `403` beim Listen vs. Lesen; `ListBuckets` ohne Recht → Rückfallmeldung, nie `500`; beide im Topf `source-test`; Zieladressprüfung je Bereich bei Virtual-Host |
| #1377 Wizard | Freigabefolge **vor** der Bereichsliste; Anbietervorlagen aus Entscheidung 10 (Hetzner: Virtual-Host, Hinweis auf fehlende Benachrichtigungen); Überlappungsmeldung; Wortliste „Herkunft" um „S3-Objektspeicher" |
| #1378 Vollabgleich | Merkmal `e:<ETag>\|<Größe>` in `last_modified_remote` (`t:`-Form als Rückfall); `LastModified` nur im Protokoll; Vorfilter über die Endung, `HeadObject` nur für endungslose Schlüssel; `markPresent` für Übersprungenes, `markReprocessed` für neu Verarbeitetes; `Incomplete` mit allen nicht listbaren Bereichen; `max-objects-per-run` als sichtbarer Fehler; Archivklassen laut Entscheidung 5 (`GLACIER_IR` lesbar); ein wiederaufsetzender Lauf listet jeden Bereich erneut |
| #1379 Ordner, Anhänge, Vorfilter | Wurzelregel „ein Bereich = Präfix ist Wurzel, mehrere = Segmentkette Bucket + Präfixsegmente je Bereich" (kein Einzelname `bucket/prefix`); `S3` in `LibraryFolderService.MIRRORED_SOURCE_TYPES`; Tiefe über `MAX_DEPTH` → tiefster zulässiger Ordner mit Warnung; `source_container_key` = Bucket, `source_hierarchy_path` = Präfix relativ zum Bereich; `AttachmentSource.LocalFile` mit `markReprocessed`; kein Beleg-Link |
| #1380 Betriebsgrenzen | `s3_sync_state` mit Kennung des laufenden Vollabgleichs, abgeschlossenen Bereichen als `bucket/prefix` (nicht Index, nur Reihenfolge) und Zeitstempel — **ohne** `ContinuationToken`; ein Wiederaufnahmelauf listet alle Bereiche erneut und spart nur Downloads; „Budget reicht nicht" als Fehler; Schlüssel aus Entscheidung 11; Vorgaben und den Heap-Bedarf von `currentPaths` gegen MinIO messen |
| #1381 Push | `IndexingRunMode.EVENT` (Spec, `typeMappings`, Changeset für `chk_indexing_jobs_run_mode`, Frontend-Label) mit Ablehnung im Anstoß-Endpunkt, `defaultRunMode = FULL`, `ListingOutcome.Partial`; eigene `SecurityFilterChain` für `/api/v1/libraries/*/s3-events` in `oidc` **und** `dev` mit Test gegen die echte `oidc`-Kette; Umbenennung `source_confluence_webhook_secret` → `source_webhook_secret` mit Changeset und Delta-Test; drei Absicherungsformen einschließlich HTTP Basic (Ceph); Nutzlastformen mit unterschiedlicher Schlüsseldekodierung; `s3:TestEvent` → `202`; Körpergrenze `MAX_BODY_BYTES` geteilt; `webhook`-Topf in `RateLimitConfiguration` um den Pfad erweitert; Löschung nur nach `HeadObject` mit `404`; SNS als Folge-Issue, nicht optional |
| #1382 Container-Tests | Suite im regulären `test`; Szenarien aus Entscheidung 3 (`Incomplete` je Bereich, `Truncated` mit erneuter Auflistung, Wiederaufnahme ohne Löschung des Vorlaufs) und 6 (MinIO-Webhook mit `Bearer` gegen die `oidc`-Kette); Virtual-Host nur im Unit-Test |
| #1383 Handbuch, Demo | 17 Abschnitte nach Confluence-Vorbild; Grenzen aus Entscheidung 5 (Archiv, Versionen, kein Beleg-Link), 6 (SNS, SQS, Hetzner ohne Benachrichtigungen, zweiteiliger `auth_token` als Rückfall), 7 (keine Instanzrolle, kein Doppelpunkt in Schlüsseln), 10 (Nextcloud/ownCloud); Kostenhinweis `LIST` bei AWS im Zeitplan-Abschnitt; `S3` in den Ordner- und Zielprüfungstabellen von `knowledge-sources.md` |

### Was in den Sub-Issues zu korrigieren ist

- **#1374:** Die Zieladressprüfung gilt nicht nur beim Bau des Clients, sondern je Anfrage
  (`ExecutionInterceptor`); die Region ist immer explizit gesetzt.
- **#1375:** ergänzt um `Document#getDeepLinkSourceUrl` → `null` für `S3`, die Überlappungsregel,
  die `jsonb_typeof`-Constraint und die Doppelpunkt-Regel für Zugangsdaten; die Zieladressprüfung
  beim Speichern prüft bei Virtual-Host je Bereich.
- **#1378:** Die Änderungserkennung vergleicht ETag und Größe, **nicht** `LastModified` (das
  Abnahmekriterium des Epics trägt dieselbe Formulierung); das Merkmal steht in
  `last_modified_remote`, eine neue Spalte entfällt. Der Vorfilter hat in der Auflistung keinen
  `Content-Type`; endungslose Schlüssel kosten einen `HeadObject`. Die Mengengrenze ist ein
  sichtbarer Fehler, kein `Truncated`. Ein wiederaufsetzender Lauf listet jeden Bereich erneut.
- **#1379:** „`lastModifiedRemote` aus `LastModified`" ist falsch — die Spalte trägt das
  ETag-Merkmal aus #1378; `LastModified` erscheint nur in Protokoll und Anzeige. Die Herkunftsanzeige
  ist ein Text, kein Link. Der Wurzelordner je Bereich ist eine Segmentkette, kein Name mit
  Schrägstrich; `MIRRORED_SOURCE_TYPES` und `MAX_DEPTH` sind zu behandeln; ein neu verarbeitetes
  Mail-Objekt meldet `markReprocessed` (Abnahmekriterium: `.eml` mit zwei Anhängen → Neufassung
  mit einem → ein Kinddokument).
- **#1380:** Der Zustand kennt Bereiche über `bucket/prefix`, nicht über den Bereichsindex, und
  speichert **keinen** `ContinuationToken`: Ein Wiederaufnahmelauf listet alle Bereiche erneut (nur
  Downloads werden gespart), sonst löscht der Folgelauf den Bestand des Vorlaufs.
- **#1381:** Löschereignisse löschen nach einem bestätigenden `HeadObject` (`404`), nicht blind; die
  Absicherung kennt drei Formen (Basic für Ceph kommt hinzu) und braucht eine eigene
  `SecurityFilterChain`, weil der Resource-Server-Filter jede Bearer-Form sonst vorher abweist;
  `EVENT` wird im Anstoß-Endpunkt abgewiesen, `defaultRunMode` bleibt `FULL`; die Geheimnisspalte
  wird umbenannt statt ergänzt; Antwort `202`; der SNS-Handshake ist ein Folge-Issue, nicht
  „optional, wenn der Aufwand vertretbar ist".
- **#1383:** Hetzner ist belegt ohne Bucket-Benachrichtigungen und nur mit SSE-C — beides in §2 und
  §14 nennen; §2 nennt die Doppelpunkt-Regel; §8 den zweiteiligen `auth_token` als Rückfall; §16
  nennt die `LIST`-Kosten bei AWS; die Ordner- und Zielprüfungstabellen in `knowledge-sources.md`
  nehmen `S3` auf.

## Referenzen

- [ADR-0017](0017-quellentypmodell-indizierung.md) — Quellentypmodell, Löschsemantik (Nachtrag hier:
  positiver Befund je Konnektor)
- [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) — Quellkonfiguration in der Bibliothek
  (Nachtrag hier: `source_settings`)
- [ADR-0020](0020-ordner-in-bibliotheken-navigation.md) — Ordner; #1277 für Konnektorbibliotheken
- [ADR-0022](0022-anhang-als-eigenes-dokument.md) — Anhang als eigenes Dokument
- [ADR-0023](0023-confluence-konnektor.md) — Laufrahmen, Anfragebudget, Wiederaufnahme, Webhook als
  Beschleuniger, `TargetAddressValidator`-Allowlist
- [ADR-0024](0024-metadatenschema-kernfelder.md) — Kernfelder; Objektmetadaten als Zielbild
- `docs/features/knowledge-sources.md` — Konnektor-Vertrag, Lebenszyklus, Ordner
- `docs/handbuch/indexierung.md` §4, §7, §10.3; `docs/handbuch/konnektor-confluence.md` als Vorbild
  für `konnektor-s3.md`
- Epic #1293 — E2E-Testkonzept für Konnektoren mit aufwändigen Quellsystemen
