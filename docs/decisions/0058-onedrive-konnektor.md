# ADR-0058: OneDrive-Konnektor

## Status

Akzeptiert · zuletzt geändert 2026-10-25 · ergänzt [ADR-0047](0047-sharepoint-konnektor.md)

## Kontext

Issue #2467 verlangt das OneDrive einer Person als private Bibliothek. ADR-0047 liest nur
Dokumentbibliotheken von SharePoint mit App-only-Anmeldung und weist OneDrive ab. Ein OneDrive
gehört einer Person; lesen soll es nur sie selbst, mit ihrer eigenen Zustimmung (delegiertes OAuth).
Der OAuth-Baustein (ADR-0048) und verbundene Konten mit privaten Bibliotheken (ADR-0049) lagen vor,
belegt nur mit einem Test-Konnektor. Graph-Zugriff, Delta-Store und `FakeGraphServer` aus ADR-0047
passen auf jedes Laufwerk. Ein eigenes Paket hätte den Store kopieren müssen, weil Konnektoren
einander nicht kennen; ein Store im Kern hätte Graph in den Kern gezogen.

## Entscheidung

### 1. Eigener Typ im Paket von SharePoint

Typ `ONEDRIVE` in `indexing.source.sharepoint`, neben `SHAREPOINT`. Beide teilen `DriveFileStore`
und den Laufkörper `DriveRun`; was sie unterscheidet, steht in einer Laufwerksregel (`DriveRule`):
zulässige `driveType`, Schema der Identität, Meldungen. Jeder Typ hat eigene Executor-Klasse,
eigene Einstellungen, eigene Freigabe (`CREATE_CONNECTOR_LIBRARY` je Typ) und eigenen Datenfluss
(`source-onedrive`). Damit gilt die Regel „jede Quellart im eigenen Unterpaket“ für dieses Paket
nicht; `connectorsDoNotKnowEachOther` bleibt unverändert.

### 2. Anmeldung: delegiertes OAuth einer Person

Profilangabe `REQUIRED`, feste Adresse `https://graph.microsoft.com`, genau eine Anmeldeart
`SignIn.oauth(…, PERSON)`: Code mit PKCE an `login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize`
und `…/token` (`Endpoint.WithTenant`), Scopes `Files.Read offline_access`, `prompt=select_account`,
`CLIENT_SECRET_POST`, kein Aussteller (`issuer` leer). Der Zugang trägt Client-ID, Secret und
Mandant; das Token liegt beim verbundenen Konto der Person (ADR-0049). Widerruf `Revocation.None`:
Entra bietet keinen Widerruf nach RFC 7009, „Trennen“ vergisst das Token nur. Eine
Ablaufwarnung gibt es nicht; ein abgelehnter Refresh lässt die Bibliothek ruhen wie jedes
verbundene Konto. Eine Bibliothek kann OneDrive nicht über ein Dienstkonto lesen.

### 3. Nur das eigene OneDrive for Business

Die Einstellungen speichern die `driveId`. Vor jeder Anfrage, die ein Laufwerk nennt, liest der
Konnektor `GET me/drive` mit dem Token und verlangt `id == driveId` und `driveType = business`:
im Lauf vor dem ersten Store-Zugriff (`DriveRun.Admission`), im Verbindungstest, in der Auflistung
und vor jedem Original. Sonst scheitert der Lauf ohne Abgleich, ohne Anfrage an das Laufwerk und mit
eigener Meldung („gehört zu einem anderen OneDrive“, „kein OneDrive for Business“, „kein OneDrive
bereitgestellt“); Auflistung und Test antworten mit derselben Meldung, ein Original gibt es nicht.
Private Microsoft-Konten (`personal`) werden so abgewiesen (Folge-Issue #3110). Reine
`me/drive/…`-Pfade würden nach einem Kontowechsel still ein anderes Laufwerk lesen.

### 4. Einstellungen, Auflistung, Identität

`source_settings`: `drive` mit `{driveId, folders?, name?}` wie ein Eintrag von ADR-0047,
Entscheidung 2, und `fullSyncIntervalDays`. Die Auflistung liefert ohne Parameter genau den Eintrag
`drive:<id>` aus `me/drive`, mit `drive` und optional `folder` die Unterordner als `folder:<id>`.
`file_path` ist `onedrive://<driveId>/<itemId>`. Verknüpfungen auf Elemente anderer Laufwerke
(`remoteItem`, etwa geteilte Ordner unter „Meine Dateien“) sind keine Dokumente und werden nie
geladen; die Auswahl bietet sie nicht an. Merkmal, Ordnerkette, Delta, Strom, Download,
Drosselung und Fehlerbilder folgen ADR-0047, Entscheidungen 3 bis 5.

### 5. Kontoanzeige ohne weitere Berechtigung

`connectedAccount` nennt `owner.user.email` aus `me/drive`, sonst `owner.user.displayName`, sonst
nichts. `User.Read` für `GET /me` wird nicht verlangt; eine leere Anzeige ist zulässig.

### 6. Grenzen und Adressen

OneDrive nutzt `SharePointProperties` (`opaa.indexing.sharepoint.*`) mit, auch das tägliche
Vollabgleich-Intervall; ein eigenes Präfix gibt es nicht. Adressen von Graph und Entra sind keine
Einstellung. Ein Test, der sie umlenkt, ersetzt die erzeugten Beans (`OneDriveTestSeam`);
die Graph-Verbindungen des Testkontexts vertrauen dann nur dem Testdoppel.

## Konsequenzen

**Einfacher:** Kein Kernumbau; Store, Graph-Client, Testdoppel und Verträge sind einmal gebaut.

**Schwieriger:** Ein Paket trägt zwei Quellarten; eine Änderung am Store prüft beide. Ein Lauf
kostet eine Anfrage mehr (`me/drive`). Die Umgebungsvariablen der Grenzen heißen
`OPAA_INDEXING_SHAREPOINT_*`.

**Restrisiken:** Nach „Trennen“ bleibt der Refresh-Token bei Microsoft gültig, bis er abläuft oder
dort widerrufen wird. Der Zugang muss den eigenen Mandanten nennen und die App einmandantenfähig
registriert sein: Mit `common` oder `organizations` ließe sich auch ein Arbeitskonto eines
fremden Mandanten verbinden und dessen OneDrive einlesen; die Prüfung aus Entscheidung 3 hielte es
nicht auf. Unbelegt ohne Mandant: ob `Files.Read` ohne Administratorzustimmung reicht, welche
Felder `me/drive` mit `Files.Read` liefert, wie lange Entra Refresh-Token hält, das Verhalten für
Gastkonten, in welcher Form `root/delta` Verknüpfungen mit `remoteItem` liefert.

## Verworfene Alternativen

- Zweite Anmeldeart am Typ `SHAREPOINT` — Team-Sites liefen delegiert in private Bibliotheken, keine getrennte Freigabe.
- Eigenes Paket `indexing.source.onedrive` — Kopie von Store und Graph-Hilfen.
- Store im Kern — neue Kante zu Graph, widerspricht ADR-0045.
- `User.Read` für die Kontoanzeige — zusätzliche Zustimmung für eine Anzeige.
- Eigene Properties `opaa.indexing.onedrive.*` — doppelte Konfiguration ohne eigenen Bedarf.

## Offen

- Private Microsoft-Konten (#3110).
- Lauf gegen einen echten Mandanten für die unbelegten Annahmen.
