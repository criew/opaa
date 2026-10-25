# ADR-0047: SharePoint-Konnektor

## Status

Akzeptiert · zuletzt geändert 2026-10-25 · ergänzt [ADR-0045](0045-gemeinsamer-datei-abgleich.md),
[ADR-0038](0038-steckbare-konnektoren.md) und [ADR-0048](0048-oauth-kern-und-zugangs-geheimnisse.md)
· ergänzt durch [ADR-0058](0058-onedrive-konnektor.md)

## Kontext

Dokumentbibliotheken von SharePoint Online sollen über Microsoft Graph gelesen werden. Graph bietet
ein Delta-Protokoll je Laufwerk, dessen Token auch als Fortsetzungsmarke taugt, leitet Downloads auf
einen vorab signierten Fremdhost weiter und meldet bei jeder Änderung die Elternordner mit. Die
Anmeldung ist App-only mit Client-Credentials und Mandant; `ProfileOwned` und `ProfileSignIn` lagen
vor (#2257). Ein ID-stabiler, im Browser öffnbarer Link ist ohne Tenant nicht belegbar.
Maintainer-Entscheidungen vom 05.10.2026: Identität `sharepoint://<driveId>/<itemId>` ohne Deep
Link (M1), täglicher Vollabgleich statt Kern-Umbau für verschobene Ordner (M2), `Sites.Selected`
empfohlen (M3), `CHANGE_FEED`-Abschluss vor dem Konnektor (K1, #2408).

## Entscheidung

### 1. Paket, Graph-Zugriff, Anmeldung

Paket `indexing.source.sharepoint`, Typ `SHAREPOINT`; der Graph-Zugriff liegt tokenneutral in
`io.opaa.msgraph` (foundation, `GraphClient`, Vorbild `io.opaa.s3`), damit Exchange (#2172) ihn
mitnutzt. Nur App-only: `SignIn.clientCredentials` mit `Endpoint.WithTenant` auf
`login.microsoftonline.com`, Scope `https://graph.microsoft.com/.default`, `CLIENT_SECRET_POST`.
Profilangabe `REQUIRED`, Adresse fest `https://graph.microsoft.com`; am Zugang bleibt das Feld
„Scopes“ leer, ein anderer Wert als `.default` scheitert bei Entra. Keine Anforderungslücke am Typ,
weil die Registry sie nur für umschaltbare Profilpflicht zulässt. Empfohlen ist `Sites.Selected`
(Anwendung, Rolle `read` je Site), Alternative `Sites.Read.All`; `Files.Read.All` nennt das Handbuch
nicht, weil es OneDrives einschließt. Unter `Sites.Selected` weist Graph jede nicht freigegebene Site
und die Site-Suche mit `403 accessDenied` ab; die Auflistung nennt dann die fehlende Freigabe bzw.
`Sites.Read.All`. Der Typ `SHAREPOINT` liest kein OneDrive: Die
Erreichbarkeitsprüfung liest `driveType`, alles außer `documentLibrary` ist `ContainerUnlistable`.
Das OneDrive einer Person ist der eigene Typ `ONEDRIVE` im selben Paket, mit delegierter Anmeldung
und demselben Store ([ADR-0058](0058-onedrive-konnektor.md)).

### 2. Quelle, Einstellungen, Auflistung

`source_settings`: `libraries` mit 1 bis 50 Einträgen `{driveId, folders?}`, dazu
`fullSyncIntervalDays` (Vorgabe 1, M2). Container ist die Dokumentbibliothek (`drive:<driveId>`);
Ordner sind ein Filter im Store, kein eigener Container, je Laufwerk eine Aufzählung und ein Strom.
Ein Ordner darf `{"id", "name"}` tragen; der Name ist nur Anzeige, `settingsState` und der
Fingerabdruck vergleichen Kennungen. Der Name der Bibliothek ist „Bibliothek (Site)“; die Site wird
nicht gespeichert. `SourceBrowser` in Stufen über Parameter der Anfrage: Site (Suche bei
`Sites.Read.All`, sonst Adresse über `/sites/{host}:/{pfad}`), Bibliotheken, Ordner. Eine geänderte
Auswahl verwirft den Abgleichstand (`onSourceChanged`).

### 3. Identität, Merkmal, Ordner

`file_path` ist `sharepoint://<driveId>/<itemId>`; Umbenennen und Verschieben im Laufwerk behalten
das Dokument, ein Wechsel der Bibliothek ist Löschen und Neuanlegen (im Vertrag nur für Stores mit
`keepsIdentityAcrossContainers`). Kein Deep Link; das Original liefert `OriginalAccess` über Graph.
Merkmal `x:<quickXorHash>|<size>|<h(name, parentId)>`, ersatzweise `c:<cTag>|…` oder
`t:<lastModified>|…`; Bestandteile über 32 Zeichen gehen gehasht ein. Der Ortsteil sorgt dafür, dass
eine umbenannte oder verschobene Datei einmal geholt wird; bei gleicher SHA-256 führt die Aufnahme
Titel und Pfad nach. Die Ordnerkette baut der Store je Lauf aus den Ordner-Einträgen und holt
fehlende Ordner einzeln; nur ein `404` auf einen Elternordner macht die Datei zu „außerhalb der
Bibliothek“ (Auflistung: nicht geliefert, Strom: `Removed`). Ein `403` ist kein Abwesenheitsbefund,
weil die Freigabe für die ganze Site gilt: in `head` `Unreadable`, im Strom `Transient` (der Strom
bleibt stehen), in der Auflistung `ContainerUnlistable`. Als geliefert gilt ein Element erst mit
der zuletzt aufgenommenen Meldung, weil Graph Elemente mehrfach liefert. Der Vollabgleich führt den
gespiegelten Ordner eines umbenannten Ordners ohne Download nach (`placeSeen`).

### 4. Delta, Marke, Strom

Auflistung über `GET /drives/{id}/root/delta` mit `$select` und `$top`; `continuation` und
`checkpoint` sind der Token des `nextLink`, gespeichert wird nur der Token, nie die Adresse. `410`
ist `CheckpointExpired`; ein dauerhafter 4xx (außer `408`/`429`) auf Seite, Marke oder Ordner ebenso,
`403`/`404` der Bibliothek `ContainerUnlistable`, jede andere Störung `RunEnding`, damit die gesicherte
Marke bleibt. `ChangeFeed`: `feedKey` ist der Container-Schlüssel, `startCursor` kommt über
`delta?token=latest`; antwortet Graph dort `403`/`404`, liefert der Store `UNREACHABLE_AT_START`.
`410` im Strom ist `CursorExpired`, sonst `Transient`. Gelöschte Einträge werden `Removed`, auch
Ordner, ohne `fullSyncNeeded`: Graph meldet Elternordner bei jeder Änderung darunter mit, die
Drive-Regel machte fast jeden Änderungslauf zum Vollabgleich; den Rest deckt der tägliche
Vollabgleich (M2). `absenceProof()` ist `CHANGE_FEED` (ADR-0045, Entscheidung 7). Der
Ordnerfilter wird vor dem Lauf gelesen; fehlt ein Ordner, ist die Bibliothek nicht listbar.

### 5. Download, Drosselung, Fehler

`GET …/items/{id}/content` leitet auf einen vorab signierten Fremdhost weiter; nur dieser Aufruf
nutzt `RedirectPolicy.DROP_AUTHORIZATION_HTTPS_ONLY_OFF_ORIGIN` (`GraphClient#download`, nur
https, Zielprüfung je Sprung), und die Ziel-URL erscheint nie in Log oder Meldung. Größe aus dem
Listing als Vorfilter, Byte-Deckel beim Kopieren. OneNote-Pakete und Verknüpfungen in ein anderes
Laufwerk (`remoteItem`) sind `NotADocument` und werden nie geladen, Einträge mit `malware`-Facette
`Unavailable`; die Ordnerauswahl bietet keine Verknüpfung an. `429` und `503` mit `Retry-After` warten über
`RateLimitPolicy#waitFor` mit Deckel und höchstens `max-retries` mal, die Gesamtwartezeit je Lauf
begrenzt `RequestBudget(maxThrottleWait)`. Download: `404` → `Gone`, `403` → `Unreadable`,
`GraphException.Kind.TRANSIENT` mit einem 4xx außer `408`/`429` → `Unavailable` (dauerhaft, hält
weder Strom noch Runde offen), übrige → `Transient`. Rechte werden nicht übernommen: Was die App
lesen darf, sehen alle Leser der Bibliothek. Grenzwerte in `SharePointProperties`
(`opaa.indexing.sharepoint.*`).

## Konsequenzen

**Einfacher:** Kein Graph-SDK; Zielprüfung, Proxy, Byte-Grenze und Drosselung gelten zentral.

**Schwieriger:** Der reguläre Build prüft nur gegen `FakeGraphServer`. Gegen einen echten Mandanten
prüft `./gradlew sharePointIntegrationTest`, nur lokal beim Maintainer und nur lesend, ohne CI-Job und
ohne Secret (README neben `SharePointTenantIntegrationTest`): Anmeldung, Auflistung, Verbindungstest,
Abweisung außerhalb der Freigabe, Vollabgleich, Änderungslauf und Originalabruf mit einem Token, das
nur `Sites.Selected` trägt. Ein herausgeschobener Ordner bleibt bis zum täglichen Vollabgleich
durchsuchbar.

**Restrisiken:** Überdauern `nextLink`-Token keine Läufe, beginnt ein Container neu; nach zwei
Verfällen ist er im Lauf nicht listbar. Nicht am Mandanten belegt: Stabilität von `driveId`/`itemId`
über Zeit, Entra-Fehlercodes (falsches oder abgelaufenes Secret, fehlende Zustimmung), Haltbarkeit
der Token, Delta-Semantik bei verschobenen Ordnern, `quickXorHash` für alle Dateiarten, Sonderfälle
(ausgecheckte Fassungen, Vertraulichkeitsbezeichnungen, private Kanäle).

## Verworfene Alternativen

- Offizielles Graph-SDK — rund 60 MB für eine Handvoll GET-Aufrufe.
- `webUrl` als Identität — pfadbasiert, jede Umbenennung ein neues Dokument.
- Ordner als Container — vervielfachte Aufzählungen und Ströme je Laufwerk.
- `fullSyncNeeded` bei Ordner-Einträgen — fast jeder Änderungslauf würde Vollabgleich; eine dauerhafte Ordnertabelle wäre ein Kern-Umbau.
- `Files.Read.All` als Empfehlung — schließt OneDrives ein.
- Ordnernamen beim Öffnen des Formulars nachschlagen — eine Anfrage je Ordner, scheitert gerade bei
  entzogener Freigabe.

## Offen

- Nationale Clouds (feste Adresse), Freigabe je Bibliothek über `Lists.SelectedOperations.Selected`,
  Rechte der Quelle.
