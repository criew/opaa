# Konnektor: Google Drive (GOOGLE_DRIVE)

> **Entwurf.** Dieses Kapitel beschreibt den Konnektor für Google Drive. Er liest geteilte
> Ablagen und Ordner eines Google-Workspace-Mandanten und hält sie über das Änderungsprotokoll von
> Drive aktuell. Der gemeinsame Ablauf eines Indexierungslaufs und die Dokumentstrecke stehen im
> Kapitel [Indexierung](indexierung.md).

**Kurzfassung für den eiligen Betrieb**

1. Im eigenen GCP-Projekt die Drive-API aktivieren, ein Dienstkonto anlegen und einen
   JSON-Schlüssel erzeugen (Abschnitt 2). Verbietet die Organisationsrichtlinie das, eine
   Ausnahme für genau dieses Projekt setzen.
2. Die gewünschten Ordner für die Adresse des Dienstkontos als **Betrachter** freigeben bzw. das
   Dienstkonto als Mitglied einer geteilten Ablage aufnehmen. Domänenweite Delegation ist nur für
   „Meine Ablage" eines Kontos nötig (Abschnitt 2.4).
3. Die Quellart Google Drive ist ab Werk für niemanden außer der Systemverwaltung freigegeben. Wer
   außer ihr Bibliotheken anlegen soll, bekommt das Anlegerecht „Konnektorbibliotheken anlegen"
   für die Quellart Google Drive ([Bibliotheken und Berechtigungen](bibliotheken-und-berechtigungen.md),
   Abschnitt 9).
4. Bibliothek anlegen: Schlüsseldatei hochladen, Bereiche aus der Auflistung wählen, „Verbindung
   testen". Alles aus allen Bereichen ist für **alle** Leseberechtigten der Bibliothek sichtbar.
5. Zeitplan setzen. Der erste Lauf ist ein Vollabgleich, danach liest jeder Lauf nur das
   Änderungsprotokoll; im eingestellten Rhythmus folgt wieder ein Vollabgleich.
6. Im Laufprotokoll auf „nicht sichtbar", „Als Text exportiert" und „Tageskontingent" achten.

## 1. Wofür er gedacht ist

Eine Google-Drive-Bibliothek liest mit **einem** Dienstkonto-Schlüssel **einen bis fünfzig
Bereiche**:

- eine ganze **geteilte Ablage**,
- einen **Ordner** samt Unterordnern, in einer geteilten Ablage oder freigegeben,
- **„Meine Ablage"** des imitierten Kontos, nur mit domänenweiter Delegation.

Indiziert werden Dateien in den unterstützten Formaten und **Google-Formate als Export**
(Abschnitt 7). Rechte aus Drive übernimmt OPAA nicht: Wer die Bibliothek lesen darf, liest alles
aus allen Bereichen.

Ein Dokument ist über die Datei-ID identifiziert. Umbenennen und Verschieben innerhalb der Bereiche
erhalten das Dokument; „Original öffnen" führt zur Datei in Google Drive.

## 2. Einrichtung bei Google

Jede Installation nutzt ihr **eigenes GCP-Projekt**. Eine zentrale OPAA-App gibt es nicht. Ein
Dienstkonto, das nur eigene oder ihm freigegebene Daten liest, braucht keine Verifizierung durch
Google.

### 2.1 Projekt und Drive-API

1. In der Google Cloud Console ein Projekt anlegen, etwa `opaa-drive`.
2. „APIs & Dienste" → „Bibliothek" → „Google Drive API" → „Aktivieren".

### 2.2 Dienstkonto und Schlüssel

1. „IAM & Verwaltung" → „Dienstkonten" → „Dienstkonto erstellen". Das Konto braucht keine
   Projektrolle.
2. Beim Dienstkonto „Schlüssel" → „Schlüssel hinzufügen" → „Neuen Schlüssel erstellen" → „JSON".
   Die Datei wird einmal heruntergeladen.
3. **Organisationen verbieten Schlüssel oft standardmäßig**
   (`iam.managed.disableServiceAccountKeyCreation`). Dann setzt der Administrator der Organisation
   unter „IAM & Verwaltung" → „Organisationsrichtlinien" eine Ausnahme für **genau dieses
   Projekt** und erzeugt danach den Schlüssel.

OPAA liest aus der Datei nur `client_email`, `private_key_id` und `private_key` und speichert nur
diese drei Felder, verschlüsselt. Kein Ziel kommt aus der Datei: Die signierte Anmeldung geht
immer an `https://oauth2.googleapis.com/token`, die Abrufe gehen immer an
`https://www.googleapis.com`. Der Schlüssel erscheint in keiner Antwort, keinem Protokoll und
keiner Fehlermeldung.

### 2.3 Freigaben ohne Delegation (Regelfall)

Das Dienstkonto sieht, was mit ihm geteilt ist:

- **Geteilte Ablage:** Dienstkonto als Mitglied mit der Rolle „Betrachter" aufnehmen.
- **Ordner:** Ordner für die Adresse des Dienstkontos (`…@….iam.gserviceaccount.com`) als
  „Betrachter" freigeben.

Ein Dienstkonto besitzt selbst keine Dateien; seine eigene „Meine Ablage" ist leer.

> **Noch nicht an einem echten Workspace bestätigt:** ob ein Dienstkonto Mitglied einer geteilten
> Ablage werden kann, wenn die Organisation externe Freigaben einschränkt. Siehe Abschnitt 13.

### 2.4 Domänenweite Delegation

Mit Delegation imitiert das Dienstkonto ein Konto der Domäne (Feld „Imitiertes Konto") und liest
alles, was dieses Konto sieht, auch seine „Meine Ablage".

1. In der Admin-Konsole „Sicherheit" → „Zugriffs- und Datenverwaltung" → „API-Steuerung" →
   „Domainweite Delegation" → „Neu hinzufügen".
2. Die Client-ID des Dienstkontos und den Scope
   `https://www.googleapis.com/auth/drive.readonly` eintragen.

Ein Schlüssel mit Delegation kann **jedes** Konto der Domäne lesen. Deshalb gilt:

- Als imitiertes Konto ein **Funktionskonto** wählen, kein persönliches. Prüfen kann OPAA das
  nicht. Das Konto steht in den Bibliotheksdetails und im Audit.
- Ändert sich das imitierte Konto, verlangt OPAA den Schlüssel neu. So lässt sich ein
  gespeicherter Schlüssel nicht ohne ihn auf ein anderes Konto umlenken.

## 3. Quellkonfiguration

| Feld | Bedeutung |
|---|---|
| Adresse (`sourceUrl`) | fest `https://www.googleapis.com`; das Formular zeigt sie nicht, jede andere wird abgewiesen |
| Dienstkonto-Schlüssel (`sourceCredentials`) | Pflicht, die JSON-Schlüsseldatei. Beim Bearbeiten bleibt der gespeicherte Schlüssel, solange das imitierte Konto gleich bleibt |
| Imitiertes Konto (`sourceSettings.subject`) | optional, die E-Mail-Adresse eines Kontos der Domäne; nur mit domänenweiter Delegation |
| Bereiche (`sourceSettings.scopes`) | Pflicht, ein bis fünfzig Einträge `{"drive": "<ID>"}`, `{"folder": "<ID>"}` oder `{"myDrive": true}`; „Meine Ablage" nur mit imitiertem Konto |
| Eigener Vollabgleichsrhythmus (`sourceSettings.fullSyncIntervalDays`) | optional, ganze Tage; ohne Angabe gilt der Rhythmus der Installation |
| Proxy (`sourceProxy`) | optional, `host:port`; gilt für Anmeldung und Abrufe |
| Zertifikatsprüfung aussetzen (`sourceInsecureSsl`) | nicht möglich, wird abgewiesen |

Überlappende Bereiche werden nicht abgewiesen. Eine Datei bleibt ein Dokument; ihr Ordner kommt
aus dem ersten Bereich, der sie listet.

### 3.1 Verbindungstest und Auflistung

Der Verbindungstest meldet sich mit dem Schlüssel an und prüft jeden Bereich. Mögliche Befunde:

| Befund | Ursache |
|---|---|
| „Der Dienstkonto-Schlüssel von … wird nicht angenommen" | Schlüssel ungültig, widerrufen oder Dienstkonto gelöscht |
| „Die domänenweite Delegation fehlt" | Client-ID oder Scope nicht in der Admin-Konsole eingetragen |
| „Das imitierte Konto … ist in der Domäne nicht bekannt" | Tippfehler oder Konto gelöscht |
| „Dem Zugriffstoken fehlt der Scope drive.readonly" | Delegation ohne diesen Scope |
| „Die geteilte Ablage ist für das Konto nicht sichtbar" / „Der Ordner ist für das Konto nicht sichtbar" | Freigabe oder Mitgliedschaft fehlt, oder die ID ist falsch |

Die Auflistung bietet die geteilten Ablagen des Kontos und die ihm freigegebenen Ordner an, mit
imitiertem Konto zusätzlich „Meine Ablage".

## 4. Betriebsart

| Lauf | Wann | Was er tut |
|---|---|---|
| **Vollabgleich** | erster Lauf, nach einer Änderung von Bereichen oder imitiertem Konto, nach einer Ordneränderung, nach einem verfallenen Änderungsstand, im Vollabgleichsrhythmus | listet alle Bereiche vollständig und entfernt, was nicht mehr vorkommt |
| **Änderungslauf** | jeder geplante Lauf dazwischen | liest das Änderungsprotokoll von Drive ab dem gespeicherten Stand und lädt nur Geändertes |

- Jede geteilte Ablage hat einen eigenen Änderungsstrom, alle übrigen Bereiche teilen den des
  Kontos.
- Der Vollabgleich merkt sich den Stand des Protokolls **vor** der Auflistung. Was sich während der
  Auflistung ändert, liest der nächste Änderungslauf.
- Ein Änderungslauf übernimmt den neuen Stand eines Stroms nur, wenn keine Datei vorübergehend
  scheiterte. Sonst liest der nächste Lauf dieselben Änderungen noch einmal; Unverändertes erkennt
  er am Änderungsmerkmal und lädt es nicht.
- Wird ein Ordner umbenannt, verschoben oder gelöscht, ist der nächste Lauf ein Vollabgleich.

> **Noch nicht an einem echten Workspace bestätigt:** ob Änderungen in einem Ordner einer geteilten
> Ablage, der dem Konto nur per Ordnerfreigabe sichtbar ist, im Strom des Kontos erscheinen.
> Erscheinen sie dort nicht, erkennt erst der nächste Vollabgleich diese Änderungen.

## 5. Löscherkennung

Ein Dokument wird entfernt,

- im Vollabgleich, wenn die Datei in keinem vollständig gelisteten Bereich mehr vorkommt,
- im Änderungslauf, wenn Drive sie als gelöscht oder im Papierkorb meldet oder sie in keinem Bereich
  mehr liegt.

Ein Bereich, den das Konto nicht mehr sieht, führt zu **keiner** Löschung: Ein entzogenes Recht
ist kein Löschbefund. Sein Bestand bleibt, bis er wieder erreichbar ist, und das Protokoll nennt
ihn.

## 6. Ordner

Die Ordnerkette bis zum Bereich wird als Ordner der Bibliothek gespiegelt. Bei mehreren Bereichen
steht der Name der Ablage bzw. des Ordners vorn. Namen, die keine Ordnerzeile tragen kann, und zu
tiefe Ketten folgen den Regeln des Kapitels [Indexierung](indexierung.md).

## 7. Google-Formate

| Typ | Exportiert als | Über der Exportgrenze von Google |
|---|---|---|
| Google Docs | `.docx` | erneut als Text exportiert, Protokolleintrag „Als Text exportiert" |
| Google Präsentationen | `.pptx` | wie Docs |
| Google Tabellen | `.xlsx` | übersprungen; CSV enthielte nur das erste Blatt |
| Zeichnungen, Formulare, Websites und weitere | — | übersprungen, Sammelhinweis |

Die Exportgrenze legt Google fest; sie liegt bei 10 MB. Eine als Text exportierte Datei verliert
Überschriften und Tabellen.

> **Noch nicht an einem echten Workspace bestätigt:** das Fehlerbild, mit dem Google einen Export
> über der Grenze ablehnt. OPAA erwartet den Grund `exportSizeLimitExceeded`. Meldet Google einen
> anderen, erscheint die Datei als „nicht lesbar“ statt als Textexport bzw. übersprungene Tabelle.

Außerdem übersprungen werden:

- **Verknüpfungen.** Ihr Ziel wird nur indexiert, wenn es selbst in einem Bereich liegt. So
  erweitert eine Verknüpfung den Umfang nie still.
- **Dateien mit gesperrtem Download**, mit einem Eintrag je Datei.

## 8. Änderungserkennung

Vor jedem Download vergleicht OPAA ein Änderungsmerkmal:

- bei hochgeladenen Dateien die MD5-Prüfsumme und Größe,
- bei Google-Formaten die letzte Änderungszeit und das Zielformat.

Verbindlich bleibt die Prüfsumme nach dem Laden: Ändert sich nur das Merkmal, nicht der Inhalt,
wird nichts neu verarbeitet.

> **Noch nicht an einem echten Workspace bestätigt:** ob Google einen unveränderten Export
> bytegleich wiederholt. Ist das nicht so, verarbeitet OPAA ein Google-Format nach jeder Änderung
> seiner Änderungszeit neu, auch wenn sich nur ein Kommentar geändert hat.

## 9. Original öffnen

„Original öffnen" führt zu `https://drive.google.com/open?id=<Datei-ID>`. Wer die Datei in Drive
nicht sehen darf, sieht dort eine Zugriffsanfrage. Der Abruf über OPAA lädt dieselbe Fassung wie
der Lauf, bei Google-Formaten den Export.

## 10. Kontingente und Anfragebudget

- Google zählt Kontingent-Einheiten je Projekt und je Nutzer. OPAA zählt **Anfragen** je Lauf
  (`request-budget-per-run`). Ist das Budget erschöpft, endet der Lauf geordnet als „unvollständig,
  wird fortgesetzt".
- Drosselt Google (`429`, `403 rateLimitExceeded`, `403 userRateLimitExceeded`), wartet OPAA mit
  wachsendem Abstand und wiederholt.
- **Tageskontingent erschöpft** (`403 dailyLimitExceeded`): Der Lauf endet mit Meldung, der nächste
  geplante Lauf setzt an.
- Ein Änderungslauf ohne Änderungen kostet eine Anfrage je Strom und Bereich. Die Auflistung einer
  großen Ablage kostet eine Anfrage je Seite.
- Google hat eine Abrechnung oberhalb eines Tageskontingents angekündigt. Vor stündlichen
  Zeitplänen über sehr große Ablagen die Kontingentseite des Projekts prüfen.

## 11. Protokoll

| Eintrag | Bedeutung |
|---|---|
| Geltungsbereich „…": … nicht sichtbar | Bereich nicht erreichbar, Bestand bleibt |
| Als Text exportiert … | Doc oder Präsentation über der Exportgrenze |
| Tabelle übersprungen … | Tabelle über der Exportgrenze |
| … Verknüpfungen übersprungen | Sammelhinweis je Lauf |
| … Google-Dateien ohne Exportformat … übersprungen | Sammelhinweis je Lauf |
| Der Download dieser Datei ist in Google Drive gesperrt | Datei übersprungen |
| Google Drive drosselt die Anfragen weiterhin | eine Datei scheiterte nach allen Wiederholungen; der Strom wird erneut gelesen |
| Das Tageskontingent der Drive-API ist erschöpft | Lauf beendet |
| Google Drive nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr an | Strom verworfen, nächster Lauf ist ein Vollabgleich |

## 12. Konfiguration

Alle Schlüssel unter `opaa.indexing.google-drive.*`.

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `page-size` | `OPAA_INDEXING_GOOGLE_DRIVE_PAGE_SIZE` | 1000 | Einträge je Auflistungs- und Änderungsseite; höchstens 1000 |
| `max-file-size-bytes` | `OPAA_INDEXING_GOOGLE_DRIVE_MAX_FILE_SIZE_BYTES` | 52428800 (50 MiB) | Obergrenze je Download oder Export |
| `request-timeout` | `OPAA_INDEXING_GOOGLE_DRIVE_REQUEST_TIMEOUT` | 30s | Zeitlimit je Anfrage |
| `max-retries` | `OPAA_INDEXING_GOOGLE_DRIVE_MAX_RETRIES` | 5 | Wiederholungen einer gedrosselten Anfrage |
| `retry-backoff` | `OPAA_INDEXING_GOOGLE_DRIVE_RETRY_BACKOFF` | 1s | Basis des wachsenden Abstands zwischen Wiederholungen |
| `request-budget-per-run` | `OPAA_INDEXING_GOOGLE_DRIVE_REQUEST_BUDGET_PER_RUN` | 20000 | Anfragen je Lauf, bevor er als „unvollständig, wird fortgesetzt" endet |
| `max-files-per-run` | `OPAA_INDEXING_GOOGLE_DRIVE_MAX_FILES_PER_RUN` | 1000000 | gelistete Dateien je Vollabgleich, bevor der Lauf sichtbar als Fehler endet |
| `download-concurrency` | `OPAA_INDEXING_GOOGLE_DRIVE_DOWNLOAD_CONCURRENCY` | 2 | gleichzeitige Downloads je Lauf |
| `full-sync-interval` | `OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL` | 7d | Rhythmus des Vollabgleichs; eine Bibliothek kann einen eigenen setzen |

Die Obergrenze von fünfzig Bereichen ist eine feste Konstante.

## 13. Grenzen

- Die vier mit „noch nicht an einem echten Workspace bestätigt“ gekennzeichneten Stellen beruhen
  auf der Dokumentation von Google, nicht auf einer Messung.

- Tabellen über der Exportgrenze fehlen ganz, Docs und Präsentationen darüber nur als Text.
- Viele Ordnerbereiche kosten je Ordner eine Auflistung.
- Eine Ordneränderung im Änderungsstrom löst immer einen Vollabgleich aus, auch wenn der Ordner
  außerhalb der Bereiche liegt.
- Delegation hängt an der Sorgfalt der Verwaltenden (Funktionskonto), solange Google Drive keine
  Zugänge kennt.

## 14. Nicht gebaut

- **Push-Benachrichtigungen** (`changes.watch`); der Änderungslauf im Zeitplan ersetzt sie
- **Zugänge** (Verbindungsprofile) für Google Drive, an denen die Systemverwaltung Schlüssel und
  imitiertes Konto festlegt; heute liegt der Schlüssel an der Bibliothek
- **Persönliche Ablagen** über verbundene Konten (#2147)
- **Großer Export** über die Exportgrenze hinaus
- **Rechteübernahme** aus Drive
