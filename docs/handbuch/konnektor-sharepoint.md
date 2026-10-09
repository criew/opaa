# Konnektor: SharePoint (SHAREPOINT)

> **Entwurf.** Dieses Kapitel beschreibt den Konnektor für SharePoint. Er liest
> Dokumentbibliotheken von SharePoint Online über Microsoft Graph und hält sie über das
> Änderungsprotokoll von Graph aktuell. Der gemeinsame Ablauf eines Indexierungslaufs und die
> Dokumentstrecke stehen im Kapitel [Indexierung](indexierung.md).

**Kurzfassung für den eiligen Betrieb**

1. In Microsoft Entra eine App-Registrierung anlegen, ein Client-Secret erzeugen und die
   Anwendungsberechtigung `Sites.Selected` für Microsoft Graph mit Administratorzustimmung erteilen
   (Abschnitt 2).
2. Jede Site, die OPAA lesen soll, für die App mit der Rolle `read` freigeben (Abschnitt 2.3).
3. Unter **Administration → Zugänge** einen Zugang für SharePoint mit Mandant, Client-ID und
   Client-Secret anlegen (Abschnitt 2.5). SharePoint ist nur über einen Zugang nutzbar.
4. Die Quellart SharePoint ist ab Werk für niemanden außer der Systemverwaltung freigegeben. Wer
   außer ihr Bibliotheken anlegen soll, bekommt das Anlegerecht „Konnektorbibliotheken anlegen“
   für die Quellart SharePoint ([Bibliotheken und Berechtigungen](bibliotheken-und-berechtigungen.md),
   Abschnitt 9).
5. Bibliothek anlegen: Zugang wählen, Dokumentbibliotheken und bei Bedarf Ordner wählen,
   „Verbindung testen“. Alles aus den gewählten Dokumentbibliotheken ist für **alle**
   Leseberechtigten der Bibliothek sichtbar. Das Formular dafür fehlt in der Oberfläche noch;
   bis dahin entsteht eine Bibliothek über die Programmierschnittstelle (Abschnitt 14).
6. Zeitplan setzen. Der erste Lauf ist ein Vollabgleich, danach liest jeder Lauf nur das
   Änderungsprotokoll; im Vollabgleichsrhythmus folgt wieder ein Vollabgleich.
7. Im Laufprotokoll auf „nicht sichtbar“, „keine SharePoint-Dokumentbibliothek“ und „nicht mehr
   vorhanden“ achten.

## 1. Wofür er gedacht ist

Eine SharePoint-Bibliothek in OPAA liest über **einen** Zugang **eine bis fünfzig
Dokumentbibliotheken** von SharePoint Online, auch aus verschiedenen Sites. Je Dokumentbibliothek
lässt sich der Umfang auf **Ordner** samt Unterordnern einschränken.

Indiziert werden Dateien in den unterstützten Formaten. Office-Dateien kommen so, wie sie in
SharePoint liegen; ein Export ist nicht nötig.

**OneDrive gehört nicht dazu.** Ein Laufwerk, das keine Dokumentbibliothek ist, liest der Konnektor
nicht, auch wenn die App es sehen dürfte.

**Rechte aus SharePoint übernimmt OPAA nicht.** Wer die Bibliothek in OPAA lesen darf, liest alles,
was die App in den gewählten Dokumentbibliotheken lesen darf, unabhängig davon, wer die Datei in
SharePoint sehen dürfte. Vertrauliche Bereiche gehören deshalb in eine eigene Bibliothek mit
passendem Leserkreis oder bleiben außerhalb der Auswahl.

Ein Dokument ist über die Kennung des Laufwerks und der Datei identifiziert
(`sharepoint://<Laufwerk>/<Datei>`). Umbenennen und Verschieben innerhalb einer Dokumentbibliothek
erhalten das Dokument; eine Datei, die in eine andere Dokumentbibliothek wandert, ist dort ein neues
Dokument.

## 2. Einrichtung bei Microsoft

OPAA meldet sich als **Anwendung** an, nicht im Namen einer Person: Client-Credentials einer
App-Registrierung im Mandanten des Betreibers. Eine zentrale OPAA-App gibt es nicht.

### 2.1 App-Registrierung und Secret

1. Im Microsoft-Entra-Admin-Center „App-Registrierungen“ → „Neue Registrierung“, etwa
   `opaa-sharepoint`, nur Konten dieses Organisationsverzeichnisses, ohne Umleitungs-URI.
2. Auf der Übersichtsseite die **Anwendungs-ID (Client-ID)** und die **Verzeichnis-ID
   (Mandanten-ID)** notieren.
3. „Zertifikate & Geheimnisse“ → „Neuer geheimer Clientschlüssel“. Den **Wert** sofort kopieren; er
   wird nur einmal angezeigt. Das Ablaufdatum am Zugang eintragen, damit OPAA rechtzeitig erinnert.

### 2.2 Berechtigung

Unter „API-Berechtigungen“ → „Berechtigung hinzufügen“ → „Microsoft Graph“ →
„Anwendungsberechtigungen“ genau eine der beiden wählen:

| Berechtigung | Wirkung | Empfehlung |
|---|---|---|
| `Sites.Selected` | Die App liest nur Sites, die ihr einzeln freigegeben sind (Abschnitt 2.3) | **empfohlen** |
| `Sites.Read.All` | Die App liest alle Sites des Mandanten | dokumentierte Alternative, etwa für eine Erprobung |

Danach „Administratorzustimmung für … erteilen“. Ohne Zustimmung erhält OPAA kein Token.

`Files.Read.All` ist nicht nötig und nicht empfohlen: Es schließt die OneDrives aller Personen ein.

### 2.3 Sites für die App freigeben (`Sites.Selected`)

Mit `Sites.Selected` sieht die App zunächst nichts. Jede Site, deren Dokumentbibliotheken OPAA
lesen soll, gibt eine Person mit dem Recht dazu (etwa die SharePoint-Administration) für die App
mit der Rolle **`read`** frei, zum Beispiel:

- mit PnP PowerShell: `Grant-PnPAzureADAppSitePermission -AppId <Client-ID> -DisplayName opaa-sharepoint -Site <Adresse der Site> -Permissions Read`,
- oder über Microsoft Graph: `POST /sites/{Site-ID}/permissions` mit der Rolle `read` und der
  Client-ID der App.

Eine nicht freigegebene Site meldet der Verbindungstest als „nicht sichtbar“. Unter
`Sites.Selected` funktioniert die Suche nach Sites in der Auflistung nicht; dort die Adresse der
Site eingeben (Abschnitt 3.1).

### 2.4 Was OPAA mit den Zugangsdaten tut

- Client-ID und Client-Secret gehen nur an den Token-Endpunkt von Entra des eingetragenen
  Mandanten (`https://login.microsoftonline.com/<Mandant>/oauth2/v2.0/token`), im Formular der
  Anfrage. Das Zugriffstoken geht nur an `https://graph.microsoft.com`.
- Der Konnektor sieht das Client-Secret nie, nur das Zugriffstoken. Beides erscheint in keiner
  Antwort, keinem Protokoll und keiner Fehlermeldung.
- **Downloads** leitet Graph auf eine Adresse von SharePoint im Mandanten weiter, die eine
  kurzlebige Freigabe in sich trägt. OPAA folgt dieser Weiterleitung nur über `https`, prüft das
  Ziel wie jede Quelladresse und sendet dorthin **kein** Zugriffstoken. Die Adresse erscheint in
  keinem Protokoll.

### 2.5 Der Zugang

| Feld am Zugang | Bedeutung |
|---|---|
| Server-Adresse | fest `https://graph.microsoft.com`; das Formular fragt sie nicht ab |
| Anmeldeart | Client-Credentials |
| Mandant | Verzeichnis-ID oder Domäne des Mandanten, etwa `contoso.onmicrosoft.com` |
| Client-ID | Anwendungs-ID der App-Registrierung |
| Client-Secret | Wert des geheimen Clientschlüssels |
| Ablaufdatum des Secrets | optional, für die Erinnerung |
| Scopes | leer lassen; OPAA fragt `https://graph.microsoft.com/.default`, also alle erteilten Anwendungsberechtigungen. Ein anderer Wert scheitert bei Entra |
| Proxy | optional, `host:port`; gilt für Anmeldung und Abrufe aller Bibliotheken darauf |

- Die Bibliothek trägt weder Secret noch Token, nur ihre Dokumentbibliotheken.
- **Abgelehntes Secret:** Weist Entra die App ab (Secret abgelaufen oder falsch, Zustimmung
  fehlt), zeigen alle Bibliotheken des Zugangs „Abgelaufen“ mit der Systemverwaltung als
  zuständig, und kein Lauf fragt Entra erneut. Ein neues Secret oder „Anmeldung testen“ am Zugang
  hebt das auf.
- **„Alle Verbindungen trennen“** löscht das Client-Secret am Zugang unwiderruflich. Bei Microsoft
  bleibt es gültig: Widerrufen wird es in der App-Registrierung unter „Zertifikate &
  Geheimnisse“.

## 3. Quellkonfiguration

| Feld | Bedeutung |
|---|---|
| Zugang (`connectionProfileId`) | Pflicht, ein Zugang für SharePoint |
| Adresse (`sourceUrl`) | fest `https://graph.microsoft.com`; jede andere wird abgewiesen |
| Dokumentbibliotheken (`sourceSettings.libraries`) | Pflicht, ein bis fünfzig Einträge `{"driveId": "<ID>"}`, je Laufwerk höchstens einer; optional mit `"folders": ["<ID>", …]` (höchstens fünfzig Ordner) und einem Anzeigenamen `"name"` |
| Eigener Vollabgleichsrhythmus (`sourceSettings.fullSyncIntervalDays`) | optional, ganze Tage; ohne Angabe gilt der Rhythmus der Installation |
| Zertifikatsprüfung aussetzen (`sourceInsecureSsl`) | nicht möglich, wird abgewiesen |

- Mit Ordnern liest OPAA aus der Dokumentbibliothek nur Dateien in oder unter einem dieser Ordner.
- Eine geänderte Auswahl (andere Dokumentbibliotheken oder Ordner) verwirft den Abgleichstand; der
  nächste Lauf ist ein Vollabgleich. Ein geänderter Anzeigename tut das nicht.

### 3.1 Verbindungstest und Auflistung

Der Verbindungstest meldet sich über den Zugang an und prüft jede Dokumentbibliothek samt ihren
Ordnern. Mögliche Befunde:

| Befund | Ursache |
|---|---|
| „Microsoft Graph hat das Zugriffstoken abgewiesen“ | Secret, Mandant oder Zustimmung stimmen nicht |
| „Die Dokumentbibliothek ist für die Anwendung nicht sichtbar …“ | Site nicht für die App freigegeben (`Sites.Selected`) oder Kennung falsch |
| „Das Laufwerk ist keine SharePoint-Dokumentbibliothek …“ | ein OneDrive oder ein anderes Laufwerk |
| „Der gewählte Ordner … ist in der Dokumentbibliothek nicht mehr vorhanden …“ | Ordner gelöscht oder in eine andere Dokumentbibliothek verschoben |

Die Auflistung geht in Stufen:

1. **Site finden:** über einen Suchbegriff (`search`, nur mit `Sites.Read.All`) oder über die
   Adresse der Site (`siteUrl`, etwa `https://contoso.sharepoint.com/sites/team`). Die Adresse
   dient nur als Name für Graph; OPAA ruft sie nicht selbst ab.
2. **Dokumentbibliotheken der Site** (`site`): nur Laufwerke vom Typ Dokumentbibliothek.
3. **Ordner** einer Dokumentbibliothek (`drive`, optional `folder` für die Unterordner eines
   Ordners).

## 4. Betriebsart

| Lauf | Wann | Was er tut |
|---|---|---|
| **Vollabgleich** | erster Lauf, nach einer Änderung der Auswahl, nach einem verfallenen Änderungsstand, im Vollabgleichsrhythmus | listet alle Dokumentbibliotheken vollständig und entfernt, was nicht mehr vorkommt |
| **Änderungslauf** | jeder geplante Lauf dazwischen | liest das Änderungsprotokoll jeder Dokumentbibliothek ab dem gespeicherten Stand und lädt nur Geändertes |

- Jede Dokumentbibliothek hat einen eigenen Änderungsstrom.
- Der Vollabgleich merkt sich den Stand jedes Stroms **vor** der Auflistung. Was sich während der
  Auflistung ändert, liest der nächste Änderungslauf.
- Ein Änderungslauf übernimmt den neuen Stand eines Stroms nur, wenn keine Datei vorübergehend
  scheiterte (Drosselung, Serverfehler, abgebrochene Übertragung). Sonst liest der nächste Lauf
  dieselben Änderungen noch einmal; Unverändertes erkennt er am Änderungsmerkmal. Eine Datei, die
  SharePoint dauerhaft verweigert, hält den Strom nicht an (Abschnitt 11).
- Meldet Graph dieselbe Datei in einem Lauf mehrfach, gilt die letzte Meldung.

### 4.1 Vollabgleich über mehrere Läufe

Reicht `request-budget-per-run` nicht für die Auflistung, endet der Lauf geordnet als
„unvollständig, wird fortgesetzt“, und OPAA merkt sich die Stelle in der Auflistung. Der nächste
Lauf setzt dort fort; fertige Dokumentbibliotheken listet er nicht erneut.

Am Ende eines solchen Abgleichs liest OPAA das Änderungsprotokoll ab dem Stand, der zu seinem
Beginn galt, bis zum Ende. Erst wenn das fehlerfrei gelingt, entfernt der Abgleich, was weder
die Auflistung noch das Protokoll gesehen hat. Eine Datei, die zwischen zwei Läufen aus einem noch
nicht gelisteten in einen schon gelisteten Teil gewandert ist, bleibt so erhalten.

- Scheitert das Lesen des Protokolls vorübergehend, bleibt der Abgleich offen; der nächste Lauf
  liest nur das Protokoll erneut.
- Ist das Protokoll verfallen, endet der Abgleich ohne Entfernen; die Protokollnotiz sagt das, und
  ein späterer Vollabgleich holt das Entfernen nach.
- Ist eine Dokumentbibliothek nicht erreichbar, wird nichts entfernt, und der nächste Lauf beginnt
  einen neuen Abgleich.
- Braucht das Protokoll seit Beginn des Abgleichs mehr Anfragen, als ein Lauf hat, kommt der
  Abgleich nicht zum Ende (Abschnitt 13).

## 5. Löscherkennung, Umbenennen und Verschieben

| Ereignis in SharePoint | Verhalten in OPAA |
|---|---|
| Datei gelöscht | Der nächste Änderungslauf entfernt das Dokument, sonst der nächste Vollabgleich |
| Datei umbenannt oder in einen anderen Ordner derselben Dokumentbibliothek verschoben | Das Dokument bleibt; die Datei wird einmal geladen, Titel, Ordner und Pfad folgen. Gleicher Inhalt wird nicht neu verarbeitet |
| Datei aus den gewählten Ordnern heraus verschoben | Der nächste Änderungslauf entfernt das Dokument |
| Ordner umbenannt oder verschoben | Die Dateien darunter meldet Graph nicht einzeln. Der nächste Vollabgleich führt den Ordner in OPAA ohne Download nach; der Pfad am Dokument bleibt, bis sich die Datei ändert |
| Ordner aus den gewählten Ordnern heraus verschoben | Die Dateien darunter bleiben bis zum nächsten Vollabgleich durchsuchbar |
| Ordner gelöscht | Dateien, die Graph einzeln als gelöscht oder außerhalb der Dokumentbibliothek meldet, entfernt der nächste Änderungslauf, alle übrigen der nächste Vollabgleich |
| Gewählter Ordner gelöscht | Die Dokumentbibliothek gilt als nicht erreichbar, es wird nichts entfernt; das Protokoll verlangt eine angepasste Ordnerauswahl |
| Datei in eine andere Dokumentbibliothek verschoben | dort ein neues Dokument; das alte entfernt der nächste Änderungslauf, sobald Graph die Datei in der bisherigen Dokumentbibliothek als gelöscht meldet, sonst der nächste Vollabgleich |
| Dokumentbibliothek nicht mehr erreichbar (Freigabe entzogen) | keine Löschung; der Bestand bleibt, bis sie wieder erreichbar ist, und das Protokoll nennt sie |
| Freigabe während eines Laufs entzogen (Graph verweigert einen Ordner oder eine Datei) | keine Löschung: Der Änderungslauf liest den Strom beim nächsten Lauf erneut, ein Vollabgleich nennt die Dokumentbibliothek nicht listbar, eine einzelne Datei gilt als nicht lesbar und behält ihren Stand |

Für Ordneränderungen gilt also ein Fenster bis zum nächsten Vollabgleich. Wie lang es höchstens
ist, bestimmt der Vollabgleichsrhythmus (`full-sync-interval`, Abschnitt 12); die Vorgabe ist
bewusst kurz, weil eine Auflistung wenig kostet.

## 6. Ordner

Die Ordnerkette ab der Wurzel der Dokumentbibliothek wird als Ordner der Bibliothek gespiegelt. Bei
mehreren Dokumentbibliotheken steht ihr Name vorn. Namen, die keine Ordnerzeile tragen kann, und zu
tiefe Ketten folgen den Regeln des Kapitels [Indexierung](indexierung.md).

## 7. Übersprungene Dateien

- **OneNote-Notizbücher** und ihre Abschnitte: kein Dokument, ein Sammelhinweis je Lauf, kein
  Download.
- **Dateien, die Microsoft als Schadsoftware kennzeichnet:** übersprungen mit einem Eintrag je
  Datei, kein Download; ein vorhandenes Dokument behält seinen Stand.
- Dateien in nicht unterstützten Formaten und über der Größengrenze wie bei jedem Konnektor
  ([Indexierung](indexierung.md)).

## 8. Änderungserkennung

Vor jedem Download vergleicht OPAA ein Änderungsmerkmal: die Inhaltsprüfsumme von SharePoint
(`quickXorHash`), ersatzweise die Inhaltsversion (`cTag`) oder die letzte Änderungszeit, jeweils
mit Größe, Name und Ordner der Datei. Eine umbenannte oder verschobene Datei wird deshalb einmal
geladen. Verbindlich bleibt die Prüfsumme nach dem Laden: Ändert sich nur das Merkmal, nicht der
Inhalt, wird nichts neu verarbeitet.

## 9. Original öffnen

SharePoint-Dokumente haben **keinen Link** in die SharePoint-Oberfläche. „Original öffnen“ lädt
die Datei über OPAA, mit denselben Zugangsdaten und derselben Größengrenze wie der Lauf, und nur aus
einer Dokumentbibliothek, die die Bibliothek noch gewählt hat.

## 10. Drosselung und Anfragebudget

- OPAA zählt **Anfragen** je Lauf (`request-budget-per-run`). Ist das Budget erschöpft, endet der
  Lauf geordnet als „unvollständig, wird fortgesetzt“.
- Drosselt Graph (`429`, `503`), wartet OPAA so lange, wie Graph mit `Retry-After` verlangt,
  höchstens `max-retry-wait` je Wartezeit, und wiederholt bis zu `max-retries` Mal. Die Summe aller
  Wartezeiten eines Laufs begrenzt `max-throttle-wait-per-run`; danach endet der Lauf geordnet wie
  beim erschöpften Budget.
- Ein Änderungslauf ohne Änderungen kostet eine Anfrage je Dokumentbibliothek für die Prüfung der
  Erreichbarkeit, eine je gewähltem Ordner und eine je Protokollseite.

## 11. Protokoll

| Eintrag | Bedeutung |
|---|---|
| Geltungsbereich „drive:…“: Die Dokumentbibliothek ist für die Anwendung nicht sichtbar … | nicht erreichbar, Bestand bleibt |
| … keine SharePoint-Dokumentbibliothek … | OneDrive oder anderes Laufwerk, nicht gelesen |
| Der gewählte Ordner … ist in der Dokumentbibliothek nicht mehr vorhanden … | Ordnerauswahl anpassen; bis dahin bleibt der Bestand |
| … OneNote-Notizbücher und ihre Abschnitte übersprungen | Sammelhinweis je Lauf |
| Microsoft meldet in dieser Datei Schadsoftware … | Datei übersprungen |
| Der Downloadserver von Microsoft Graph hat mit HTTP 4xx geantwortet. Die Datei wird übersprungen … | SharePoint verweigert die Datei dauerhaft; Strom und Abgleich gehen weiter, ein späterer Vollabgleich versucht es erneut |
| Microsoft Graph drosselt die Anfragen weiterhin | eine Anfrage scheiterte nach allen Wiederholungen; der Strom wird erneut gelesen |
| Die Übertragung von Microsoft Graph dauerte länger als … | Download über `download-timeout`; die Datei gilt als vorübergehend fehlgeschlagen (Abschnitt 13) |
| Microsoft Graph nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr an | Strom verworfen, nächster Lauf ist ein Vollabgleich |
| Microsoft Graph nimmt den Fortsetzungspunkt der Auflistung nicht mehr an … beginnt neu | die Auflistung dieser Dokumentbibliothek beginnt im selben Abgleich von vorn |
| Die Dokumentbibliothek war beim Beginn des letzten Vollabgleichs nicht erreichbar | ihr Strom hat keinen Stand; der nächste Lauf ist ein Vollabgleich |
| … Der nächste Lauf setzt die Auflistung am zuletzt gesicherten Punkt fort | Graph antwortete während der Auflistung nicht verlässlich; der Lauf endet als Fehler, der nächste setzt fort |

## 12. Konfiguration

Alle Schlüssel unter `opaa.indexing.sharepoint.*`.

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `page-size` | `OPAA_INDEXING_SHAREPOINT_PAGE_SIZE` | 200 | Einträge je Auflistungs- und Änderungsseite; höchstens 1000 |
| `max-file-size-bytes` | `OPAA_INDEXING_SHAREPOINT_MAX_FILE_SIZE_BYTES` | 52428800 (50 MiB) | Obergrenze je Download, auch für „Original öffnen“ |
| `request-timeout` | `OPAA_INDEXING_SHAREPOINT_REQUEST_TIMEOUT` | 30s | Zeitlimit je Anfrage |
| `download-timeout` | `OPAA_INDEXING_SHAREPOINT_DOWNLOAD_TIMEOUT` | 10m | Gesamtfrist je Download, vom Antwortbeginn bis zum letzten Byte; danach gilt die Datei als vorübergehend fehlgeschlagen und folgt im nächsten Lauf |
| `max-retries` | `OPAA_INDEXING_SHAREPOINT_MAX_RETRIES` | 5 | Wiederholungen einer gedrosselten Anfrage |
| `max-retry-wait` | `OPAA_INDEXING_SHAREPOINT_MAX_RETRY_WAIT` | 60s | längste einzelne Wartezeit, auch wenn `Retry-After` mehr verlangt |
| `max-throttle-wait-per-run` | `OPAA_INDEXING_SHAREPOINT_MAX_THROTTLE_WAIT_PER_RUN` | 30m | Summe der Wartezeiten je Lauf, bevor er als „unvollständig, wird fortgesetzt“ endet |
| `request-budget-per-run` | `OPAA_INDEXING_SHAREPOINT_REQUEST_BUDGET_PER_RUN` | 20000 | Anfragen je Lauf, bevor er als „unvollständig, wird fortgesetzt“ endet |
| `max-files-per-run` | `OPAA_INDEXING_SHAREPOINT_MAX_FILES_PER_RUN` | 1000000 | gelistete Dateien je Vollabgleich, bevor der Lauf sichtbar als Fehler endet |
| `download-concurrency` | `OPAA_INDEXING_SHAREPOINT_DOWNLOAD_CONCURRENCY` | 2 | gleichzeitige Downloads je Lauf |
| `full-sync-interval` | `OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL` | 1d | Rhythmus des Vollabgleichs und damit das längste Fenster, in dem umbenannte, verschobene oder gelöschte Ordner noch am alten Ort stehen (Abschnitt 5); eine Bibliothek kann einen eigenen setzen |

Die Obergrenzen von fünfzig Dokumentbibliotheken und fünfzig Ordnern je Dokumentbibliothek sind
feste Konstanten.

## 13. Grenzen

- **Noch nicht an einem echten Mandanten bestätigt.** Bis dahin beruht das Verhalten auf der
  Dokumentation von Microsoft und einem Testdoppel:
  - das Fehlerbild einer nicht freigegebenen Site unter `Sites.Selected` und das Verhalten der
    Site-Suche dort,
  - die Fehlermeldungen von Entra bei falschem oder abgelaufenem Secret und fehlender Zustimmung,
  - die Ziele der Download-Weiterleitung und ob die Zieladressprüfung sie durchlässt,
  - Haltbarkeit der Fortsetzungspunkte zwischen zwei Läufen und Form der Antwort auf einen
    verfallenen Stand,
  - ob Graph bei einem gelöschten Ordner seine Dateien einzeln meldet,
  - Sonderfälle wie ausgecheckte Dateien, Entwürfe und Dateien mit Vertraulichkeitsbezeichnung.
- Eine Datei, die die `download-timeout`-Frist dauerhaft reißt (sehr große Datei, langsame
  Leitung), gilt in jedem Lauf als vorübergehend fehlgeschlagen und hält den Änderungsstrom und
  einen Abgleich über mehrere Läufe an. Abhilfe: `download-timeout` erhöhen.
- Ordneränderungen erreichen die Dokumente erst mit dem nächsten Vollabgleich (Abschnitt 5).
- Lehnt Graph einen gemerkten Fortsetzungspunkt oder einen Ordner der Kette dauerhaft ab (ein
  anderer Fehler als „verfallen“, etwa „ungültige Anfrage“), beginnt die Auflistung dieser
  Dokumentbibliothek im selben Abgleich neu. Geschieht das wieder, gilt sie in diesem Lauf als nicht
  listbar, und es wird nichts entfernt; das Protokoll nennt sie mit „… beginnt neu“.
- Ein Ordnerfilter spart keine Anfragen: Auflistung und Änderungsprotokoll umfassen immer die ganze
  Dokumentbibliothek.
- Braucht das Änderungsprotokoll seit Beginn eines Abgleichs über mehrere Läufe mehr Anfragen, als
  ein Lauf hat, kommt der Abgleich nicht zum Ende; dasselbe gilt für einen Änderungslauf mit mehr
  Protokollseiten als Budget. Entfernt wird dabei nichts. Abhilfe: höheres Budget (#2413).

## 14. Nicht gebaut

- **Formular in der Oberfläche** für die Quellkonfiguration (#2153); bis dahin legt die
  Programmierschnittstelle (`POST /api/v1/libraries` mit `sourceType` `SHAREPOINT`) die Bibliothek
  an, und `POST /api/v1/source-types/SHAREPOINT/browse` liefert die Auflistung aus Abschnitt 3.1
- **OneDrive** und Anmeldung im Namen einer Person (delegiert, verbundenes Konto)
- **Freigabe je Dokumentbibliothek** über `Lists.SelectedOperations.Selected`
- **Nationale Clouds** (etwa Microsoft Cloud for US Government, 21Vianet); die Adresse ist fest
- **Rechteübernahme** aus SharePoint
- **Push-Benachrichtigungen** über Webhooks; der Änderungslauf im Zeitplan ersetzt sie
- **Link in die SharePoint-Oberfläche** für ein Dokument
