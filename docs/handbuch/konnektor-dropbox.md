# Konnektor: Dropbox (DROPBOX)

> **Entwurf.** Dieses Kapitel beschreibt den Konnektor für Dropbox. Er liest Ordner einer Dropbox,
> auch den Team-Bereich eines Dropbox-Teams, über die Dropbox-API und hält sie über das
> Änderungsprotokoll von Dropbox aktuell. Der gemeinsame Ablauf eines Indexierungslaufs, die
> Dokumentstrecke und „Quelle verbinden“ stehen im Kapitel [Indexierung](indexierung.md).

**Kurzfassung für den eiligen Betrieb**

1. In der Dropbox App Console eine App mit „Scoped access“ anlegen, die Berechtigungen
   `account_info.read`, `files.metadata.read` und `files.content.read` setzen und die
   Rücksprungadresse von OPAA eintragen (Abschnitt 2).
2. Unter **Administration → Zugänge** einen Zugang für Dropbox mit Anmeldeart OAuth, App key als
   Client-ID und App secret als Client-Secret anlegen (Abschnitt 2.4). Dropbox ist nur über einen
   Zugang nutzbar.
3. Die Quellart Dropbox ist ab Werk für niemanden außer der Systemverwaltung freigegeben. Wer außer
   ihr Bibliotheken anlegen soll, bekommt das Anlegerecht „Konnektorbibliotheken anlegen“ für die
   Quellart Dropbox ([Bibliotheken und Berechtigungen](bibliotheken-und-berechtigungen.md),
   Abschnitt 9).
4. Bibliothek anlegen: Zugang wählen, „Quelle verbinden“ und bei Dropbox mit einem **Dienstkonto**
   zustimmen, Ordner wählen, „Verbindung testen“ (Abschnitt 3). Alles aus den gewählten Ordnern ist
   für **alle** Leseberechtigten der Bibliothek sichtbar.
5. Zeitplan setzen. Der erste Lauf ist ein Vollabgleich, danach liest jeder Lauf nur das
   Änderungsprotokoll; im Vollabgleichsrhythmus folgt wieder ein Vollabgleich.
6. Im Laufprotokoll auf „nicht sichtbar“, „herausgibt noch exportiert“ und „nicht mehr annimmt“
   achten.

## 1. Wofür er gedacht ist

Eine Dropbox-Bibliothek in OPAA liest über **einen** Zugang **einen bis fünfzig Ordner** des
Dropbox-Kontos, dem die Bibliothek zugestimmt hat, jeweils samt Unterordnern. `/` liest die ganze
Dropbox des Kontos, auch Ordner, die andere mit ihm geteilt haben. Gehört das Konto zu einem
Dropbox-Team, liest die Bibliothek auf Wunsch stattdessen Ordner des **Team-Bereichs**.

Indiziert werden Dateien in den unterstützten Formaten. **Paper-Dokumente** exportiert Dropbox als
Markdown; sie erscheinen in OPAA mit der Endung `.md`.

**Rechte aus Dropbox übernimmt OPAA nicht.** Wer die Bibliothek in OPAA lesen darf, liest alles,
was das verbundene Konto in den gewählten Ordnern sieht, unabhängig davon, wer die Datei in Dropbox
sehen dürfte. Vertrauliche Ordner gehören deshalb in eine eigene Bibliothek mit passendem
Leserkreis oder bleiben außerhalb der Auswahl.

Ein Dokument ist über seinen Pfad in Kleinbuchstaben identifiziert (`dropbox:<pfad>`). Umbenennen
und Verschieben machen aus der Datei deshalb ein neues Dokument; das alte wird entfernt
(Abschnitt 5). Einen Link in die Dropbox-Oberfläche hat ein Dokument nicht (Abschnitt 9). Ist
`dropbox:<pfad>` länger als die Identität eines Dokuments fassen kann, wird die Datei nicht
geladen. Sie erscheint bei jedem Vollabgleich und nach jeder Änderung der Datei als abgewiesen im
Protokoll (Kapitel
[Indexierung](indexierung.md), „Schritt 1: Prüfsumme und Identität“).

## 2. Einrichtung bei Dropbox

OPAA meldet sich mit einer **App des Betreibers** bei Dropbox an. Eine zentrale OPAA-App gibt es
nicht. Jede Bibliothek stimmt in dieser App einmal selbst zu; die Zustimmung gehört danach der
Bibliothek, nicht der Person, die sie erteilt hat.

### 2.1 App anlegen

1. In der Dropbox App Console „Create app“ wählen, „Scoped access“ und als Zugriffsart „Full
   Dropbox“. Mit „App folder“ sieht die App nur ihren eigenen Ordner.
2. Unter „Permissions“ genau diese Berechtigungen setzen und speichern:
   `account_info.read`, `files.metadata.read`, `files.content.read`. Schreibende Berechtigungen
   braucht OPAA nicht.
3. Unter „Settings“ bei „Redirect URIs“ die Rücksprungadresse eintragen:
   `<öffentliche Adresse von OPAA>/connections/callback`. Die öffentliche Adresse ist die aus
   `OPAA_PUBLIC_BASE_URL` ([Deployment](deployment.md)).
4. „App key“ und „App secret“ notieren.

Eine App im Entwicklungsmodus genügt für wenige Konten. Für mehr Konten gibt Dropbox die App nach
einem Antrag für den Produktivbetrieb frei.

### 2.2 Was OPAA mit den Zugangsdaten tut

- App key und App secret gehen nur an den Token-Endpunkt von Dropbox
  (`https://api.dropboxapi.com/oauth2/token`, als HTTP-Basic-Anmeldung). Die Zustimmung fragt OPAA
  mit `token_access_type=offline` an, damit Dropbox ein Refresh-Token ausgibt.
- Das Refresh-Token liegt verschlüsselt im Token-Speicher von OPAA, Besitzer ist die Bibliothek. Das
  Zugriffstoken holen die Läufe selbst neu, wenn es abläuft.
- Das Zugriffstoken geht nur an die beiden Hosts der Dropbox-API: `https://api.dropboxapi.com` für
  Auflistungen und `https://content.dropboxapi.com` für Downloads und Exporte. Weiterleitungen folgt
  der Konnektor nicht.
- Der Konnektor sieht weder App secret noch Refresh-Token, nur das Zugriffstoken. Nichts davon
  erscheint in einer Antwort, einem Protokoll oder einer Fehlermeldung.
- Trennen, Neu verbinden mit anderem Konto, die Notabschaltung und das Löschen des Zugangs
  widerrufen das Token bei Dropbox (`/2/auth/token/revoke`).

### 2.3 Dienstkonto

Bei „Quelle verbinden“ meldet sich eine verwaltende Person bei Dropbox an. Dafür gehört ein
**eigenes Dropbox-Konto für OPAA** her, kein persönliches Konto: Die Bibliothek liest alles, was
dieses Konto in den gewählten Ordnern sieht, und die Verbindung bleibt bestehen, wenn die Person
ausscheidet. OPAA nennt das Konto nach der Zustimmung mit seiner E-Mail-Adresse („Verbunden als …“).

### 2.4 Der Zugang

| Feld am Zugang | Bedeutung |
|---|---|
| Server-Adresse | fest `https://api.dropboxapi.com`; das Formular fragt sie nicht ab |
| Anmeldeart | OAuth |
| Besitzart | Bibliothek |
| Client-ID | App key |
| Client-Secret | App secret |
| Scopes | leer lassen; OPAA fragt `account_info.read files.metadata.read files.content.read` |
| Proxy | optional, `host:port`; gilt für Anmeldung und Abrufe aller Bibliotheken darauf |

Die Bibliothek trägt kein Secret, nur ihre Zustimmung und ihre Ordner. Was eine geänderte
Registrierung, die Notabschaltung und das Löschen des Zugangs mit den Zustimmungen tun, steht im
Kapitel [Indexierung](indexierung.md), Abschnitt „Zugänge“.

## 3. Quellkonfiguration

| Feld | Bedeutung |
|---|---|
| Zugang (`connectionProfileId`) | Pflicht, ein Zugang für Dropbox |
| Zustimmung (`sourceConsent.pendingConnectionId`) | beim Anlegen die Zustimmung aus „Quelle verbinden“ |
| Adresse (`sourceUrl`) | fest `https://api.dropboxapi.com`; jede andere wird abgewiesen |
| Ordner (`sourceSettings.folders`) | Pflicht, ein bis fünfzig Pfade wie `/Projekte`, `/` für alles; kein Ordner darf in einem anderen liegen (ohne Unterschied von Groß- und Kleinschreibung) |
| Team-Bereich (`sourceSettings.teamSpace`) | optional, `true` liest die Ordner im Team-Bereich statt in den Ordnern des Kontos |
| Eigener Vollabgleichsrhythmus (`sourceSettings.fullSyncIntervalDays`) | optional, ganze Tage; ohne Angabe gilt der Rhythmus der Installation |
| Zertifikatsprüfung aussetzen (`sourceInsecureSsl`) | nicht möglich, wird abgewiesen |

Eine geänderte Ordnerliste oder ein umgeschalteter Team-Bereich verwirft den Abgleichstand; der
nächste Lauf ist ein Vollabgleich.

### 3.1 Verbindungstest und Auflistung

Der Verbindungstest prüft über die Zustimmung der Bibliothek jeden Ordner. Mögliche Befunde:

| Befund | Ursache |
|---|---|
| „Bitte zuerst die Quelle verbinden.“ | Es gibt noch keine Zustimmung |
| „Dropbox hat das Zugriffstoken abgewiesen.“ | Zustimmung widerrufen oder App gelöscht; die Quelle neu verbinden |
| „Der Dropbox-App fehlt eine Berechtigung …“ | eine der Berechtigungen aus Abschnitt 2.1 fehlt; danach die Quelle neu verbinden |
| „Der Ordner … ist für das Konto nicht sichtbar.“ | Pfad falsch, Ordner gelöscht oder nicht mit dem Konto geteilt |
| „Das verbundene Dropbox-Konto gehört zu keinem Team mit Team-Bereich.“ | Team-Bereich gewählt, das Konto hat keinen |

Die Auflistung („Ordner laden“) zeigt die Ordner direkt unter einem Pfad (`path`, Vorgabe `/`), im
Team-Bereich, wenn er gewählt ist (`teamSpace`).

### 3.2 Im Formular

Im Assistenten „Neue Bibliothek“ und unter **Quelle → Anbindung → Bearbeiten** fragt das Formular
weder Adresse noch Zugangsdaten ab.

1. **Zugang:** Im Assistenten steht über dem Formular die Wahl des Zugangs. „Eigene Adresse“ gibt
   es für Dropbox nicht.
2. **Quelle verbinden:** wie im Kapitel [Indexierung](indexierung.md), Abschnitt „Quelle
   verbinden“. Erst danach laden Ordnerauswahl und Verbindungstest etwas.
3. **Team-Bereich lesen:** Schalter; aus liest die Ordner des Kontos, an die des Team-Bereichs.
4. **Ordner:** ein Pfad je Zeile. „Ordner anzeigen unter“ nennt den Pfad, dessen Unterordner „Ordner
   laden“ als Vorschläge zeigt; ein Klick übernimmt einen Vorschlag als Zeile.
5. **Verbindung testen:** prüft jeden Ordner mit den Befunden aus Abschnitt 3.1.

Unter **Quelle → Umfang** stehen die Ordner für alle Leseberechtigten der Bibliothek, beim
Team-Bereich mit dem Zusatz „im Team-Bereich“. Erreicht der letzte Lauf einen Ordner nicht, nennt
der Kopf der Bibliothek ihn. Den eigenen Vollabgleichsrhythmus setzt „Vollabgleich alle … Tage“ im
Assistenten oder unter „Zeitplan bearbeiten“.

## 4. Betriebsart

| Lauf | Wann | Was er tut |
|---|---|---|
| **Vollabgleich** | erster Lauf, nach einer Änderung der Ordner, nach einem verfallenen Änderungsstand, nach einem gelöschten, umbenannten oder verschobenen Ordner, im Vollabgleichsrhythmus | listet alle Ordner vollständig und entfernt, was nicht mehr vorkommt |
| **Änderungslauf** | jeder geplante Lauf dazwischen | liest das Änderungsprotokoll jedes Ordners ab dem gespeicherten Stand und lädt nur Geändertes |

- Jeder Ordner hat einen eigenen Änderungsstrom.
- Der Vollabgleich merkt sich den Stand jedes Stroms **vor** der Auflistung. Was sich während der
  Auflistung ändert, liest der nächste Änderungslauf.
- Ein Änderungslauf übernimmt den neuen Stand eines Stroms nur, wenn keine Datei vorübergehend
  scheiterte. Sonst liest der nächste Lauf dieselben Änderungen noch einmal; Unverändertes erkennt
  er am Änderungsmerkmal.
- **Vollabgleich über mehrere Läufe:** Reicht `request-budget-per-run` nicht für alle Ordner,
  endet der Lauf geordnet als „unvollständig, wird fortgesetzt“. Der nächste Lauf listet zuerst die
  Ordner, die noch nicht gelistet sind, danach die schon gelisteten erneut, soweit das Budget
  reicht. Sind alle Ordner einmal gelistet, ist der Abgleich fertig und entfernt, was keiner seiner
  Läufe gesehen hat: Die Identität ist der Pfad, eine Datei an keinem gelisteten Pfad gibt es nicht
  mehr. Ein einzelner Ordner wird dabei immer in einem Lauf ganz gelistet (Abschnitt 13).
- War ein Ordner beim Beginn eines Vollabgleichs nicht erreichbar, hat sein Strom keinen Stand;
  der nächste Lauf ist wieder ein Vollabgleich.

## 5. Löscherkennung, Umbenennen und Verschieben

| Ereignis in Dropbox | Verhalten in OPAA |
|---|---|
| Datei gelöscht | Der nächste Änderungslauf entfernt das Dokument, sonst der nächste Vollabgleich |
| Datei umbenannt oder verschoben | Der nächste Änderungslauf entfernt das alte Dokument und nimmt die Datei unter dem neuen Pfad neu auf |
| Ordner gelöscht | Dropbox meldet nur den Ordner, nicht die Dateien darin. OPAA erkennt das und macht den nächsten Lauf zum Vollabgleich, der die Dateien entfernt |
| Ordner umbenannt oder verschoben | Die Dateien kommen unter dem neuen Pfad neu an; die alten entfernt der folgende Vollabgleich wie bei einem gelöschten Ordner |
| Gewählter Ordner gelöscht oder nicht mehr geteilt | Der Ordner gilt als nicht erreichbar, es wird nichts entfernt; das Protokoll nennt ihn |

Ob ein gemeldeter gelöschter Pfad eine Datei oder ein Ordner war, fragt OPAA bei Dropbox nach
(eine Anfrage je gelöschtem Pfad); bei unklarer Antwort folgt sicherheitshalber ein Vollabgleich.

## 6. Ordner

Die Ordner unterhalb jedes gewählten Ordners werden als Ordner der Bibliothek gespiegelt; der
gewählte Ordner selbst ist die Wurzel. Bei mehreren Ordnern steht vorn der Name des gewählten
Ordners, bei `/` „Dropbox“. Namen, die keine Ordnerzeile tragen kann, und zu tiefe Ketten folgen den
Regeln des Kapitels [Indexierung](indexierung.md).

## 7. Übersprungene Dateien

- **Dateien, die Dropbox weder herausgibt noch exportiert** (etwa Verknüpfungen auf Webseiten):
  kein Dokument, ein Sammelhinweis je Lauf, kein Download.
- **Paper-Dokumente** werden als Markdown exportiert, nicht übersprungen.
- Dateien in nicht unterstützten Formaten und über der Größengrenze wie bei jedem Konnektor
  ([Indexierung](indexierung.md)).

## 8. Änderungserkennung

Vor jedem Download vergleicht OPAA ein Änderungsmerkmal: die Inhaltsprüfsumme von Dropbox
(`content_hash`), bei Paper-Dokumenten die Revision. Verbindlich bleibt die Prüfsumme nach dem
Laden: Ändert sich nur das Merkmal, nicht der Inhalt, wird nichts neu verarbeitet.

## 9. Original öffnen

Dropbox-Dokumente haben **keinen Link** in die Dropbox-Oberfläche: Dropbox nennt für einen Pfad
keine verlässliche Web-Adresse. „Original öffnen“ lädt die Datei über OPAA, mit der Zustimmung der
Bibliothek und derselben
Größengrenze wie der Lauf, und nur aus einem Ordner, den die Bibliothek noch gewählt hat. Eine
Datei, die Dropbox nicht mehr kennt, ist kein Original mehr.

## 10. Drosselung und Anfragebudget

- OPAA zählt **Anfragen** je Lauf (`request-budget-per-run`). Ist das Budget erschöpft, endet der
  Lauf geordnet als „unvollständig, wird fortgesetzt“.
- Drosselt Dropbox (`429`), wartet OPAA so lange, wie Dropbox mit `Retry-After` verlangt, höchstens
  `max-retry-wait` je Wartezeit, und wiederholt bis zu `max-retries` Mal.
- Ein Änderungslauf ohne Änderungen kostet zwei Anfragen je Ordner: die Prüfung der
  Erreichbarkeit und eine Protokollseite. Mit Team-Bereich kommt je Lauf eine Abfrage des Kontos
  hinzu. Jede weitere Protokollseite kostet eine Anfrage, ein gelöschter Pfad eine weitere, solange
  der Strom noch keine Strukturänderung gemeldet hat (Abschnitt 5).

## 11. Protokoll

| Eintrag | Bedeutung |
|---|---|
| Der Ordner „…“ ist für das Konto nicht sichtbar. | nicht erreichbar, Bestand bleibt |
| … Dateien übersprungen, die Dropbox weder herausgibt noch exportiert | Sammelhinweis je Lauf |
| Dropbox hat das Zugriffstoken abgewiesen. | die Zustimmung gilt nicht mehr; die Quelle neu verbinden |
| Der Dropbox-App fehlt eine Berechtigung … | Berechtigungen der App ergänzen (Abschnitt 2.1), danach neu verbinden |
| Das verbundene Dropbox-Konto gehört zu keinem Team mit Team-Bereich. | Team-Bereich abschalten oder ein Teamkonto verbinden |
| Dropbox drosselt die Anfragen weiterhin | eine Anfrage scheiterte nach allen Wiederholungen; der Strom wird erneut gelesen |
| Die Übertragung von Dropbox dauerte länger als … | Download über `download-timeout`; die Datei gilt als vorübergehend fehlgeschlagen |
| Dropbox nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr an. | Strom verworfen, nächster Lauf ist ein Vollabgleich |
| Der Ordner „…“ war beim Beginn des letzten Vollabgleichs nicht erreichbar. | sein Strom hat keinen Stand; der nächste Lauf ist ein Vollabgleich |

## 12. Konfiguration

Alle Schlüssel unter `opaa.indexing.dropbox.*`.

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `page-size` | `OPAA_INDEXING_DROPBOX_PAGE_SIZE` | 2000 | Einträge je Auflistungs- und Änderungsseite; höchstens 2000 |
| `max-file-size-bytes` | `OPAA_INDEXING_DROPBOX_MAX_FILE_SIZE_BYTES` | 52428800 (50 MiB) | Obergrenze je Download oder Export, auch für „Original öffnen“ |
| `request-timeout` | `OPAA_INDEXING_DROPBOX_REQUEST_TIMEOUT` | 30s | Zeitlimit je Anfrage |
| `download-timeout` | `OPAA_INDEXING_DROPBOX_DOWNLOAD_TIMEOUT` | 10m | Gesamtfrist je Download oder Export, vom Antwortbeginn bis zum letzten Byte |
| `max-retries` | `OPAA_INDEXING_DROPBOX_MAX_RETRIES` | 5 | Wiederholungen einer gedrosselten Anfrage |
| `max-retry-wait` | `OPAA_INDEXING_DROPBOX_MAX_RETRY_WAIT` | 5m | längste einzelne Wartezeit, auch wenn `Retry-After` mehr verlangt |
| `request-budget-per-run` | `OPAA_INDEXING_DROPBOX_REQUEST_BUDGET_PER_RUN` | 20000 | Anfragen je Lauf, bevor er als „unvollständig, wird fortgesetzt“ endet |
| `max-files-per-run` | `OPAA_INDEXING_DROPBOX_MAX_FILES_PER_RUN` | 1000000 | gelistete Dateien je Vollabgleich, bevor der Lauf sichtbar als Fehler endet |
| `download-concurrency` | `OPAA_INDEXING_DROPBOX_DOWNLOAD_CONCURRENCY` | 2 | gleichzeitige Downloads je Lauf |
| `full-sync-interval` | `OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL` | 7d | Rhythmus des Vollabgleichs; eine Bibliothek kann einen eigenen setzen |
| `api-base`, `content-base`, `authorization-endpoint`, `token-endpoint`, `revocation-endpoint` | `OPAA_INDEXING_DROPBOX_API_BASE` usw. | die Adressen von Dropbox | nur für einen Ersatzserver in Tests; im Betrieb nie ändern |

Die Obergrenze von fünfzig Ordnern je Bibliothek ist eine feste Konstante.

## 13. Grenzen

- **Noch nicht an einem echten Dropbox-Konto bestätigt.** Bis dahin beruht das Verhalten auf der
  Dokumentation von Dropbox und einem Testdoppel:
  - die Form der Meldungen bei gelöschten und verschobenen Ordnern im Änderungsprotokoll,
  - der Export von Paper-Dokumenten als Markdown,
  - der Team-Bereich über die Wurzel des Teams (`Dropbox-API-Path-Root`).
- Ein Ordner wird immer in einem Lauf ganz gelistet; einen Fortsetzungspunkt innerhalb eines
  Ordners gibt es nicht. Ein Ordner mit mehr Einträgen, als ein Lauf auflisten kann, kommt deshalb
  nie zum Ende, und jeder Lauf endet als „unvollständig, wird fortgesetzt“. Abhilfe:
  `request-budget-per-run` anheben oder den Ordner in mehrere Ordner oder Bibliotheken aufteilen.
- Umbenennen und Verschieben machen ein neues Dokument (Abschnitt 1); die Datei wird dabei neu
  geladen und verarbeitet.

## 14. Nicht gebaut

- **Team-Verwaltung** über eine Team-App (`team/namespaces/list`, Lesen im Namen beliebiger
  Mitglieder); gelesen wird, was das verbundene Konto sieht
- **Persönliche Verbindung** (Besitzart Person, private Bibliotheken)
- **Rechteübernahme** aus Dropbox
- **Push-Benachrichtigungen** über Webhooks; der Änderungslauf im Zeitplan ersetzt sie
- **Fortsetzungspunkt innerhalb eines Ordners**; über mehrere Läufe setzt ein Vollabgleich nur
  ordnerweise fort (Abschnitt 4)
