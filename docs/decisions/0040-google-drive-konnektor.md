# ADR-0040: Google-Drive-Konnektor

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0027](0027-s3-konnektor.md),
[ADR-0038](0038-steckbare-konnektoren.md) (Entscheidung 7, Anmeldeart Dienstkonto-Schlüssel) und
[ADR-0041](0041-verbindungen-als-eigenes-modul.md) · ergänzt durch
[ADR-0045](0045-gemeinsamer-datei-abgleich.md), das Entscheidung 1 ersetzt

## Kontext

Google Drive soll als erste weitere Dateiablage nach S3 angebunden werden (#2148, Epic #2146). Drei
Dinge unterscheiden Drive von S3: ein Änderungsprotokoll (`changes.list`), Google-Formate, die sich
nur exportieren lassen (höchstens 10 MB je Export), und ein festes Ziel der Zugangsdaten
(`googleapis.com`). Dienstkonten brauchen keine Verifizierung und besitzen keine Dateien; eine
zentrale OPAA-App bräuchte für den eingeschränkten Scope `drive.readonly` eine jährliche
Sicherheitsprüfung. Organisationen verbieten die Schlüsselerzeugung standardmäßig
(`iam.managed.disableServiceAccountKeyCreation`). `md5Checksum` gibt es nur für Binärdateien,
`version` steigt bei jeder Änderung, auch unsichtbaren. Verknüpfungen haben einen eigenen MIME-Typ;
jede Datei hat genau einen Elternordner.

## Entscheidung

### 1. Gemeinsamer Datei-Abgleich

Entfallen mit #2702, siehe [ADR-0045](0045-gemeinsamer-datei-abgleich.md) Entscheidung 1.

### 2. Anmeldung: Dienstkonto-Schlüssel, signiert im Kern

Jeder Betreiber nutzt sein eigenes GCP-Projekt und ein Dienstkonto mit JSON-Schlüssel; eine
OPAA-eigene Google-App gibt es nicht. Die Anmeldeart „Dienstkonto-Schlüssel“ (`ServiceAccountKeyAuth`,
ADR-0038, Entscheidung 7) läuft als JWT-Assertion nach RFC 7523 (RS256) gegen den Token-Endpunkt der
Beschreibung (`GoogleDriveSourceConnector.TOKEN_ENDPOINT`), Scope `drive.readonly`, Zugriffstoken
für eine Stunde, kein Refresh-Token, kein Eintrag im Token-Speicher, Besitzart nur Bibliothek.
Der Konnektor sieht den Schlüssel nie: `ServiceAccountTokens` (Kern) signiert, ohne Zugang über
`LibrarySourceConnectionResolver`, am Zugang über `ProfileSignIn` (ADR-0048, Entscheidung 1); der
Konnektor bekommt über `SourceSettings` nur ein erneuerbares Geheimnis der Art Zugriffstoken, das er
je Anfrage beim Port abfragt. Gelesen werden nur `client_email`, `private_key_id` und `private_key`
(`ServiceAccountKey`); `token_uri` und die übrigen Felder bestimmen kein Ziel. Die Client-ID ist die
`client_email` des Schlüssels und wird nicht abgefragt; ein Schlüssel eines anderen Kontos ist eine
Änderung der Registrierung (ADR-0041, Entscheidung 12). Ohne Zugang liegt der
Schlüssel in `source_credentials`, am Zugang in `client_secret_ciphertext`; beide Spalten sind `text`,
die Spec-Grenzen `sourceCredentials` und `clientSecret` 4096, damit die Grenze typneutral gilt und
RSA-4096-Schlüssel passen. Die Standard-Anmeldekette von Google wird nie benutzt (wie ADR-0027,
Entscheidung 7). Das Handbuch nennt die Organisationsrichtlinie und die Ausnahme für das eine Projekt.

### 3. Ursprungsbindung bei festem Ziel

`sourceUrl` ist fest `https://www.googleapis.com` (`API_BASE`); die Validierung setzt den Wert, das
Formular zeigt ihn nicht. Material aus den Zugangsdaten geht an genau zwei Ziele: die Assertion an den
Token-Endpunkt der Beschreibung (POST über `SourceFormPost` in `sourceaccess`: Zielprüfung, Proxy,
Zeitlimit, Größengrenze, keine Weiterleitung), das Zugriffstoken an `API_BASE`. Kein Ziel kommt aus
der Schlüsseldatei, aus `source_settings` oder einer Antwort; `exportLinks`, `webContentLink` und die
Download-URI von `files.download` werden nie mit Token abgerufen. API-Aufrufe und Downloads nutzen
`REJECT_OFF_ORIGIN`. Gespeicherte Zugangsdaten übernimmt der Kern nur bei gleichem, gegen die
normalisierte Adresse verglichenem Ursprung (`KnowledgeLibraryService`), seit #2496 geprüft an einer
Änderung ohne Verbindungsfeld.

### 4. Profilangabe und domänenweite Delegation

Profilangabe `OPTIONAL`: Der Schlüssel trägt seine App-Registrierung selbst. Ohne Delegation sieht das
Dienstkonto, was mit ihm geteilt ist (geteilte Ablagen als Mitglied, freigegebene Ordner). Mit
Delegation nennt die Einstellung `subject` das imitierte Konto; der Konnektor liefert es dem Kern über
`SourceConnector#assertionSubject`. Weil der Kern gespeicherte Zugangsdaten vor `validateChange`
ergänzt, gilt: **Er übernimmt sie nur, wenn Ursprung und `assertionSubject` gleich bleiben**, sonst
sind die Zugangsdaten Pflicht — sonst lenkte jede Person mit Verwaltungsrecht einen vorhandenen
Delegationsschlüssel auf ein beliebiges Konto um. Die Regel gilt für jede Anmeldeart mit Subjekt.
Das Konto steht sichtbar in den Bibliotheksdetails und im Audit; technisch prüfen, ob es ein
Funktionskonto ist, kann OPAA nicht. Am Zugang setzt nur der Zugang `subject` (`DefaultKey#profileOnly`);
ein Wert der Bibliothek ist `400`, fehlt er am Zugang, wird niemand imitiert. Eine Änderung verwirft
den Abgleichstand aller Bibliotheken auf dem Zugang (ihr nächster Lauf ist ein Vollabgleich), nennt
vorher die Zahl der Bibliotheken, benachrichtigt danach ihre Verwaltenden und wird abgelehnt,
solange eine läuft; der Schlüssel bleibt am Zugang (seit #2220). Das Handbuch verlangt ein
Funktionskonto und empfiehlt für Delegation einen Zugang. Die Prüfung einer Bibliothek vor dem
Speichern meldet sich nicht bei Google an (`EffectiveSourceSettings#ofDraftToValidate`),
Verbindungstest und Auflistung tun es. Persönliche Ablagen echter Personen sind nicht Teil dieses
ADR.

### 5. Geltungsbereiche

`source_settings.scopes`: ein bis fünfzig Bereiche (`GoogleDriveScope`): `{"drive": id}` (geteilte
Ablage), `{"folder": id}` (Ordner samt Unterordnern) oder `{"myDrive": true}` (nur mit `subject`).
Container-Schlüssel `drive:<id>`, `folder:<id>`, `myDrive`. Überlappung wird nicht abgewiesen: Die
Identität ist die Datei-ID, der Ordner kommt aus dem ersten Bereich, der sie listet. Eine geteilte
Ablage wird flach gelistet (`corpora=drive`), ein Ordnerbereich ebenenweise. `SourceBrowser` nennt
`drives.list` und die freigegebenen Ordner. Vor jedem Lauf prüft der Konnektor jeden Bereich
(`drives.get` bzw. `files.get`); ein nicht erreichbarer ist `ContainerUnlistable`.

### 6. Zwei Betriebsarten: Vollabgleich und Änderungsprotokoll

`FULL → REMOVE_ON_ABSENCE`, `INCREMENTAL → KEEP_ON_ABSENCE`, `fullSyncInterval` Vorgabe 7 Tage, je
Bibliothek verlängerbar. Jede geteilte Ablage hat einen eigenen Strom (`changes.list` mit
`driveId`), alle übrigen Bereiche teilen den Strom des Kontos; Cursor, Startcursor vor der
Auflistung, verfallene Cursor und der Änderungslauf folgen ADR-0045, Entscheidung 3. Löschbefund im
Änderungslauf: `removed=true`, `file.trashed=true` oder eine Datei, die in keinem Bereich mehr liegt;
ein `404` auf `files.get` ist keiner. Ein Ordner-Ereignis (verschoben, umbenannt, gelöscht) meldet
`fullSyncNeeded`, der nächste Lauf ist ein Vollabgleich. Der Store gibt keine Marke; ein
Vollabgleich gleicht nur ab, wenn er alles selbst gelistet hat.

### 7. Identität, Änderungsmerkmal, Ordner, Deep Link

`file_path` = `https://drive.google.com/open?id=<fileId>`, zugleich Deep Link; Umbenennen und
Verschieben behalten das Dokument. Merkmal: Binärdatei `m:<md5Checksum>|<size>` (ohne MD5
`t:<modifiedTime>|<size>`), Google-Format `g:<modifiedTime in ms>|<Zielendung>`, sodass ein
Wechsel des Zielformats einen neuen Export auslöst; `version` taugt nicht (Fehlalarme). Verbindlich
bleibt die SHA-256 nach dem Laden. Ordner: Elternkette bis zur Bereichswurzel über
`SourceFolderMirror`, bei mehreren Bereichen der Name der Ablage bzw. des Ordners vorn; Namen, die
keine Ordnerzeile tragen kann, und zu tiefe Ketten folgen `SourceFolderPath`.

### 8. Google-Formate: Export in Office-Formate

| Typ | Zielformat | über 10 MB |
|---|---|---|
| Docs | docx | erneut als `text/plain`, Protokollhinweis |
| Slides | pptx | erneut als `text/plain`, Protokollhinweis |
| Sheets | xlsx | übersprungen (`REJECTED`); csv enthielte nur das erste Blatt |
| Drawings, Forms, Sites, Vids, Apps Script, sonstige | — | übersprungen (`UNSUPPORTED_FORMAT`) |

Office statt PDF, weil die Formate daraus Überschriften, Tabellen und Blätter lesen (`GoogleFormats`).
`fileName` trägt die Zielendung, die Größe eines Google-Formats gilt als unbekannt. Dateien mit
`canDownload=false` oder Missbrauchsmarkierung werden übersprungen, `acknowledgeAbuse` nie gesetzt.
Der Textweg hängt am Grund `exportSizeLimitExceeded`; jedes andere `403` des Exports, das keine
Drosselung ist, gilt als nicht lesbar.

### 9. Verknüpfungen werden übersprungen

`application/vnd.google-apps.shortcut` wird nie aufgelöst, gilt als gesehen und erscheint im
Sammelhinweis: Liegt das Ziel in einem Bereich, wird es dort indexiert; sonst erweiterte Auflösen den
Geltungsbereich still.

### 10. Quoten und Anfragebudget

Das Budget zählt Anfragen je Lauf (`RequestBudget`), keine Google-Einheiten. Drosselung ist `429`
sowie `403` mit Grund `rateLimitExceeded` oder `userRateLimitExceeded`, mit exponentiellem Backoff
über `RequestBudget#throttled`; `dailyLimitExceeded` beendet den Lauf. Das Handbuch nennt die von
Google angekündigte Abrechnung. Grenzwerte in
`GoogleDriveProperties` (`opaa.indexing.google-drive.*`) nach dem Muster von ADR-0027, Entscheidung 11.

### 11. REST über `io.opaa.sourceaccess` statt Google-Client-Bibliothek

`DriveApi` spricht die Drive-REST-API v3 über `SourceHttpClientFactory` und
`RedirectFollowingFetcher`, JSON mit Jackson; die Assertion signiert der Kern mit Nimbus. Keine neue
Abhängigkeit, und Zielprüfung, Proxy, Byte-Grenze, Weiterleitungsregel und Anfragezähler gelten
zentral statt wie bei S3 am SDK nachgebaut.

### 12. Rechte und Push

Rechte aus Drive werden nicht übernommen; die Bibliothek bestimmt, wer liest. `changes.watch` ist
ein Folge-Issue: Push bräuchte einen öffentlich erreichbaren Eingang mit Kanalerneuerung.

## Konsequenzen

**Einfacher:** Umbenennen und Verschieben bauen keine Dokumente neu; keine Verifizierung, keine
OPAA-eigene Google-App; `source_credentials` nimmt für jeden Konnektor bis zu 4096 Zeichen.

**Schwieriger:** Paging, Fehlerkörper und API-Änderungen pflegt OPAA selbst; viele Ordnerbereiche
kosten je Ordner eine Auflistung; Exporte über 10 MB fehlen bei Sheets ganz und sind bei Docs und
Slides nur Text; Delegation ohne Zugang hängt am Handbuch (Funktionskonto).

**Restrisiken:** Startet ein Lauf zwischen der Prüfung „keine Bibliothek läuft“ und dem Speichern
eines neuen `subject`, bleibt sein Abgleichstand gemischt, bis der Fingerabdruck (ADR-0045,
Entscheidung 8) ihn verwirft.

## Verworfene Alternativen

- Zentrale OPAA-OAuth-App — Verifizierung, jährliche Sicherheitsprüfung, ein Geheimnis für alle
  Installationen.
- `google-api-services-drive` mit `google-auth-library` — zweiter HTTP-Stack, Schutzmechanismen doppelt, `token_uri` aus der Schlüsseldatei.
- Schlüssel verdichtet speichern, Spalte unverändert — die Spec-Grenze 4096 sagte Werte zu, die die
  Spalte nicht trägt.
- Spec-Grenze nur im Drive-Konnektor prüfen — jeder andere Konnektor bekäme ein `409` statt `400`.
- `version` als Merkmal — zu viele Fehlalarme.
- PDF als einheitliches Zielformat — verliert Überschriften, Tabellen und Blätter.
- `exportLinks` über 10 MB — Token an ein Ziel aus der Antwort, Grenze nicht belegt.
- Verknüpfungen auflösen — erweitert den Geltungsbereich still.
- Nur Vollabgleich wie S3 — Drive hat ein verlässliches Änderungsprotokoll.
- Profilpflicht nur mit Delegation — die Profilangabe gilt einmal je Typ.
- Dienstkonto-Schlüssel als Client-Credentials — anderer Ablauf, Profilpflicht.
- Signieren im Konnektor — der Konnektor sähe den Schlüssel (ADR-0041, Entscheidung 3).
- Bibliotheken nach `subject`-Wechsel ruhen lassen, bis neu verbunden — der Schlüssel liegt am
  Zugang und wird nicht je Bibliothek bestätigt.

## Offen

- Gegen ein echtes Workspace noch nicht belegt (`GoogleDriveWorkspaceIntegrationTest`, README im
  Testpaket): Fehlerbild über der Exportgrenze, Weiterleitung von `alt=media`, ob der Strom des Kontos
  Änderungen geteilter Ablagen und nur per Ordnerfreigabe sichtbarer Ordner liefert, wie oft
  Ordneränderungen einen Vollabgleich auslösen, ob `open?id=` zum richtigen Editor führt, ob Exporte
  bytegleich wiederholbar sind, `modifiedTime` bei Kommentar oder Umbenennung.
- `files.download` (LRO) als Ausweg für Exporte über 10 MB, sobald Grenze und Ziel-Host belegt sind.
- Schlüssellose Anmeldung (Workload Identity Federation); persönliche Ablagen über verbundene Konten
  (Epic #2147); `changes.watch`.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-10-03 | #2148 | Erstfassung mit gemeinsamem Datei-Abgleich | 1–12 |
| 2026-10-03 | #2152 | Nachtrag Ordnergedächtnis und Nextcloud (heute ADR-0045, Entscheidungen 4 und 11) | 1 |
| 2026-10-04 | #2201 | Nachtrag lange Hierarchiepfade und Löschvermerk (ADR-0045, 5 und 10) | 1 |
| 2026-10-04 | #2155 | Nachtrag SMB (heute ADR-0046) | — |
| 2026-10-04 | #2202 | Nachtrag Abgleichsrunde über mehrere Läufe (ADR-0045, 6 und 7) | 1 |
| 2026-10-04 | #2220 | Google Drive über Zugang: Schlüssel am Zugang, `subject` nur vom Zugang, Speichern fragt Google nicht | 2, 4 |
| 2026-10-05 | #2153 | Nachtrag SharePoint (heute ADR-0047) | — |
| 2026-10-09 | #2408 | Nachtrag `CHANGE_FEED`-Abschluss (ADR-0045, 7) | 1 |
| 2026-10-10 | #2153 | Nachträge SharePoint gebaut und Formular (ADR-0047) | — |
| 2026-10-10 | #2268 | Nachtrag Fingerabdruck der Einstellungen (ADR-0045, 8) | 1 |
| 2026-10-12 | #2496 | Proxy je Bibliothek entfernt; Übernahme der Zugangsdaten ohne Verbindungsfeld geprüft | 3 |
| 2026-10-14 | #2702 | Auf Drive beschränkt; Abgleich, SMB und SharePoint in ADR-0045 bis 0047 | alle |
