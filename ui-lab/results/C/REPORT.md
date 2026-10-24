# Variante C – Zwei-Spalten-Auswahl

## Konzept
Auswahl als geteilte Ansicht: links Durchblättern (Breadcrumb, Suche ab 7 Einträgen, Liste, Ebene für Ebene; SharePoint Site → Bibliothek → Ordner mit Elternspalte als Miller-Spalte ab 960 px), rechts „Ausgewählt (n)“ mit vollen Pfaden und Entfernen.
„Verbindung“ ist ein eigener Abschnitt mit einem Test; nach Erfolg klappt er zu „✓ Verbunden · Adresse · Konto · Ändern“, erst dann erscheint die Auswahl. Unter 640 px Containerbreite stapeln sich die Spalten hinter Reitern „Durchsuchen | Ausgewählt (n)“.
Kleiner Fall (≤ 6 flache Einträge, z. B. 2 Confluence-Spaces, 1 Drive-Bereich) fällt auf eine schlichte Checkbox-Liste zurück; Dateisystem hat gar keine Liste (Pfad + Ausschlussmuster).

## Begriffssatz
- Schritte: **Quellart · Quelle · Name · Freigaben · Prüfen** (Überschrift von Schritt 2 bleibt „Quelle einrichten“)
- Abschnitte: **Verbindung · Auswahl · Zeitplan** (S3 zusätzlich **Muster**)
- Felder: **Adresse** (S3: **Endpoint**, Glossar §6) · **Konto** (Nutzer, Dienstkonto, E-Mail) · Geheimnis nach Art: **Passwort / App-Passwort / API-Token / Persönliches Token / Secret Key / Schlüsseldatei** · **Im Namen von** (Drive)
- Zustände/Knöpfe: **Verbindung testen · Wird getestet … · Verbunden · Ändern · gesetzt · Ersetzen · Gespeicherten Wert behalten**
- Prüfstufen: **Adresse · Anmeldung · Rechte**
- Auswahl: **Durchsuchen · Ausgewählt (n) · Alle n auswählen / Alle n Treffer auswählen · Alle entfernen · enthalten in „X“ · Von Hand eintragen**
- Fehler: „Bitte … eingeben/wählen“, Zusammenfassung „n Angaben fehlen oder stimmen nicht“

## Prüfliste (Belege: Dateinamen in diesem Ordner, Kennzahlen aus `metrics.jsonl`)
| # | Ergebnis | Beleg / Anmerkung |
|---|---|---|
| 1 | bestanden | Verbunden-Zeile: Haken + Wort, kein Chip/Tönung (`wizard-filled__*`) |
| 2 | bestanden | Schmal-Umschalter = MUI-Tabs Text + Unterstrich (`wizard-filled__nextcloud__scale__*__320`) |
| 3 | bestanden | keine Deko-Icons an Überschriften |
| 4 | teilweise | eigene Bausteine ohne Tönung/Vollrahmen; bestehende Zugangskacheln/Detailkopf unverändert getönt |
| 5 | bestanden | Akzent nur an Links/Knöpfen/Häkchen |
| 6 | bestanden | Zeile = Name + 1 Sekundärzeile (Pfad/Art), Navigation per Chevron |
| 7 | bestanden | roter Rahmen + Zeile am Feld (`wizard-validation__*`) |
| 8 | bestanden | einzige neue Nachfrage „Änderungen an der Quelle verwerfen?“ über `confirmAction`, zwei Knöpfe |
| 9 | bestanden | Schrittnamen je ein Wort |
| 10 | n. a. | keine Filterchips |
| 11 | bestanden | Gliederung über h3-Größe, Trennlinien statt Kästen |
| 12 | bestanden | Labels 1–3 Wörter |
| 13 | bestanden | Hilfetexte einzeilig, nur wo neu (z. B. „Folgt aus der Region.“) |
| 14 | bestanden | Platzhalter = Beispiele (`https://cloud.example.org`, `Archiv/**`) |
| 15 | teilweise | eigene Texte ein Satz; Backend-Meldungen (z. B. „HTTP 403“) unverändert durchgereicht |
| 16 | bestanden | „Verbunden“, „gesetzt“ |
| 17 | bestanden | Verben: Ändern, Ersetzen, Hinzufügen, Speichern, Bibliothek anlegen |
| 18 | bestanden | keine Redundanzketten |
| 19 | bestanden | Begriffssatz oben, für alle 8 Arten |
| 20 | bestanden | 450 Spaces/300 Bereiche/200 Ordner: Suche, „Weitere anzeigen“ in 100er-Schritten, Zähler (`wizard-filled__confluence__scale__*`) |
| 21 | bestanden | Breadcrumb + Elternspalte, Auswahl rechts mit vollem Pfad (`wizard-filled__sharepoint__scale__light__1280`) |
| 22 | bestanden | Ellipse + Tooltip, `overflowX` 0 in allen 592 Aufnahmen |
| 23 | bestanden | Skeleton erst nach 300 ms (`*__slow__*`), Fehler mit „Erneut versuchen“ (`*__failed__*`), leer (`*__empty__*`); 8 Ebenen per Breadcrumb mit Einklappen |
| 24 | bestanden | „Alle n auswählen / abwählen“, „Alle entfernen“ |
| 25 | bestanden (320) | 320 px ohne horizontales Scrollen; 200/400 % Zoom nicht gemessen |
| 26 | teilweise | Fokus nach „Weiter“ auf Schrittüberschrift, `document.title` „Schritt n von m: …“; komplette Tastaturstrecke nicht E2E geprüft |
| 27 | teilweise | 31 `focusObscured`-Stopps, überwiegend 320 px Fußzeile/Skiplink beim Scrollen; nicht behoben |
| 28 | bestanden | `disabledSubmits` = 0 überall; Weiter/Anlegen/Speichern als `BusyButton` (aria-disabled nur während Lauf) |
| 29 | bestanden | alle Fehler auf einmal + Zusammenfassung mit Sprunglinks, Fokus darauf, Titel „Fehler: …“ (`wizard-validation__s3__small__light__1280`) |
| 30 | teilweise | Zurück behält Eingaben, Verbindung wird beim Wiedereinstieg automatisch neu getestet; Browser-Zurück nicht umgesetzt |
| 31 | bestanden | Bearbeiten-Dialog fragt nur bei echter Änderung (Wertevergleich) |
| 32 | bestanden | Schritt „Prüfen“ mit „Ändern“ je Abschnitt, danach „Weiter zur Prüfung“ (`review__*`) |
| 33 | bestanden | ein Muster für alle: Knopf + automatisch bei Weiter/Speichern, Ergebnis inline mit Stufen, verfällt bei Feldänderung |
| 34 | bestanden | „gesetzt · Ersetzen“ (`edit__nextcloud__*`, `edit__s3__*`) |
| 35 | bestanden | fremde Objekte nur als Liste/Baum, Suche ab 7 |
| 36 | bestanden | Eltern mit gewählten Kindern `aria-checked="mixed"`, Wahl des Elternteils ersetzt die Kinder |
| 37 | bestanden | „Alle 37 Treffer auswählen“; Filter löscht keine Auswahl, Zähler rechts zählt alles |
| 38 | bestanden | „In X gibt es keine …“ · „Keine Treffer für …“ + „Suche zurücksetzen“ · „Kein Zugriff auf diese Ebene.“ |
| 39 | bestanden | Skeleton-Verzögerung 300 ms, Fortschritt im Knopf („Wird getestet …“) |
| 40 | n. a. | Indizierungsstatus nicht angefasst |
| 41 | bestanden | Speichern-Erfolg per `notify`, Fehler bleiben inline |
| 42 | n. a. | Nur-Lesen unverändert |
| 43 | teilweise | 35-Zeichen-Kompositum bei 320 px ohne Überlauf (gekürzt mit Tooltip); +40 %-Pseudo-Lokalisierung nicht gemessen |
| 44 | teilweise | axe hell ohne Befund bis auf 1× `scrollable-region-focusable`; dunkel 39× `color-contrast` – Einzelprobe derselben Seite ohne Befund, vermutlich Übergang beim Schemawechsel, nicht abschließend geklärt |

## Bekannte Schwächen
- Verbindungsprobe für SharePoint und S3 über die Auflistung statt `source-test` (der Test verlangt bereits gewählte Bibliotheken/Bereiche); ein S3-Schlüssel ohne ListBuckets gilt als verbunden, Anmeldefehler zeigt dann erst die Auflistung.
- Teilfehler je Bereich (`partial`) werden nur angezeigt, wenn ein Test mit Auswahl lief; der Verbindungstest läuft vor der Auswahl.
- Ordnerpfade mit Komma (Scale-Fixtures) sind nicht wählbar (Backend speichert kommagetrennt) – ehrlich gekennzeichnet, aber Einschränkung.
- Picker hält ganze Ebenen im Speicher, keine Virtualisierung (100er-Seiten).
- Detailkopf bei 320 px (bestehender `AssetDetailHeader`) unverändert.
- Flakes im Lauf: `wizard-empty smb scale` (Kachel nicht gefunden), `detail s3 slow` (Reiter zu spät); `wizard-validation dropbox small` ist kein Fehler – ohne Pflichtfeld rückt der Schritt zu Recht vor.
- `__full`-Bilder über 4000 px sind oben beschnitten, nicht skaliert.

## Gebrochene Tests (86, alle unter `frontend/src/features/library/`)
Sie prüfen das alte Layout (Texte, „Edition erkennen“, Inline-Alerts, deaktivierte Knöpfe, 4 statt 5 Schritte):
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > Confluence library (#1135, ADR-0023) > names the missing e-mail address when a new Cloud token is typed instead of silently disabling the test
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > Confluence library (#1135, ADR-0023) > requires a fresh connection test once a new token is typed
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > Confluence library (#1135, ADR-0023) > shows the fixed edition, loads the spaces with the stored token and saves the new selection without touching credentials
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > Confluence library (#1135, ADR-0023) > still sends the libraryId testing a newly typed token - #1868 review: a MANAGER without CREATE_CONNECTOR_LIBRARY must not have this fail 403 right before a save updateLibrary would allow
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > Confluence library (#1135, ADR-0023) > tells that the stored token does not follow a host change and stops relying on it
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > S3 library (#1377, ADR-0027) > prefills the stored settings, keeps the stored key and saves the new scopes as a whole
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > S3 library (#1377, ADR-0027) > requires both halves of a new key and drops the stored one on a host change
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > library on a connection profile (#2162) > edits a SharePoint library on its profile and saves the document libraries as a whole (#2153)
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > library on a connection profile (#2162) > edits under what the library names of its profile, also without the right to create one
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > library on a connection profile (#2162) > keeps the stored values and refuses to save when the library names too little of its profile
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > prefills the directory path for a FILESYSTEM library and never shows a credentials field
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > prefills the stored exclusion patterns and saves the edited ones (#2184)
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > rejects a relative directory path for a FILESYSTEM library
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > requires an http(s) URL for an HTTP_DIRECTORY library
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > saves a new directory path together with the unrelated name/description fields
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog > shows the hint that the change only takes effect on the next indexing run
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > S3 form > opens the collapsed advanced section for a pattern error and focuses the field
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > S3 form > reopens the advanced section when the same pattern error is reported again
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > S3 form > ties a missing secret key to its field and focuses it
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > S3 form > ties a scope error to the row it is about
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > S3 form > ties an empty endpoint to the endpoint field
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > shows an issue of a field the form does not show in the live region and focuses it
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > ties a wrong address to the URL field and clears the error with the next input
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > ties a wrong directory path to its field and focuses it
- components/EditLibrarySourceDialog.test.tsx > EditLibrarySourceDialog errors at their field (#2193) > ties a wrong exclusion pattern to the pattern field, not to the path
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Aktion am Sperrhinweis > opens the source configuration where the managers enter the secret anew
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > says when a switch to another server discarded the secret and offers to edit the source
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > derives no endpoint from the region under a profile, even with a provider template behind it
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > prefills the NEXTCLOUD address from the profile and asks for the library’s own secret
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > prefills the SMB address from the profile and asks for the library’s own secret
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > shows the settings a profile fixes read-only with its value, and no provider template
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > takes the edition a profile fixes instead of detecting it
- components/sources/connectedSourceForms.test.tsx > source forms under a connection profile > tests and lists Confluence without the edition the profile sets
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > asks to connect again when the pending connection can no longer be used
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > comes back on the source step with the connection, its account and its focus
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > creates the library with the pending connection, the creator answering for it
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > lets the owning group answer for the connection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > moves the selection off a tile that turns out to be hidden
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > shows an empty step naming the system administration when nothing may be created
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > detects the edition before asking for credentials and shows Cloud fields for Cloud
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > drops a late test answer once the address changed in the meantime
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > joins e-mail and token for Cloud and drops verification when the address changes
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > keeps the space picker usable after "Zurück" re-enters the source step
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > offers a retry when the space listing fails, keeping the selection visible
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > sends an entered full-sync rhythm as the number, not as the update path’s zero
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows a blocked or unreachable address as an error with the backend wording, and never a credentials field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows the PAT field for Data Center and refuses to continue without a tested selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > skips the first run when the immediate-indexing switch is turned off
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > verifies credentials, loads the spaces, and sends edition, credentials and selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > creates a private library over the own account, without a secret and without „Freigaben“
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > keeps the shareable way on a profile admitting both as an explicit choice of its own
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > tests the connection of a private library through the own account
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates a MinIO library with the template values and sends its settings
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates an AWS library from the template with a derived virtual-host endpoint
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > offers the S3 tile, states the sharing consequence before the scopes, and blocks Weiter until the stages are complete
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > refuses overlapping scopes before anything is sent
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > SharePoint (ADR-0047) > creates a library through the profile with the chosen document library, without a secret
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sends no schedule at all for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sets the schedule together with the library, for a connector type that never had one here
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > creates a library through the chosen profile, its address prefilled
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > does not carry address or fixed values of the previous way over to the next
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > drops the edition detected for an own address once a profile clears that address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers no certificate switch under the chosen profile and sends none
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers the own address alone while no profile exists yet
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > sends no profile for the own address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > asks before noting a group of an external provider and notes nothing on cancel
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > blocks Weiter without a name and says so
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates a group-owned library, offering only the groups returned for the user
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates the library and navigates to its detail page
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > announces an issue of a field the form does not show in the step’s live region
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing S3 access key to its field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing address to the URL field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a wrong path to its field, focuses it and lets the step stand
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > goes to the new library when a noted grant is refused, and names the grant
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names a failed source-type list in German and does not go on with the upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names the steps and drops "Quelle einrichten" for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > notes a person with the shared subject picker and grants on the asset after creation
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > switches the connection form with the chosen kind and validates its fields
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > explains what a Geltungsbereich is and shows a VIEWER the Umfang only
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > edits the source configuration through the dialog, resending the unrelated Stammdaten fields untouched
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > offers "Bearbeiten" for a MANAGER on a connector library but hides it for a VIEWER
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows a Confluence library read-only with edition and spaces, and still offers "Bearbeiten" (#1135)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows an S3 library with its scopes in the page head, the configuration card and "Bearbeiten" (#1377)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows no incomplete-listing warning while the assessment is clean (#1191)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the webhook row to a manager of a Confluence library only (#1140)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > states the Confluence sharing consequence to a VIEWER as well, and never for other types (#1138)

## git diff --stat gegen origin/feature/3084_ui-lab-base (Code, ohne results/)
```
 .../features/asset/components/AssetNameFields.tsx  |   7 +-
 .../library/components/CreateReviewStep.tsx        | 182 +++++
 .../library/components/CreateSourceSetupStep.tsx   |  52 +-
 .../library/components/EditLibrarySourceDialog.tsx | 121 +--
 .../library/components/LibraryCreateFooter.tsx     |  31 +-
 .../library/components/LibrarySourceSection.tsx    |  36 +-
 .../features/library/components/S3SourceForm.tsx   |   9 +-
 .../library/components/libraryCreateWizard.ts      |  14 +-
 .../library/components/setup/ConnectionStage.tsx   | 241 ++++++
 .../library/components/setup/IssueSummary.tsx      |  62 ++
 .../library/components/setup/PickedList.tsx        | 109 +++
 .../library/components/setup/SecretField.tsx       |  75 ++
 .../library/components/setup/SourceField.tsx       |  79 ++
 .../library/components/setup/SourceSummaryView.tsx |  63 ++
 .../library/components/setup/SplitPicker.tsx       | 811 +++++++++++++++++++++
 .../library/components/setup/collectIssues.ts      |  49 ++
 .../library/components/setup/connectionGate.ts     |  26 +
 .../library/components/setup/connectionOutcome.ts  |  52 ++
 .../library/components/setup/focusIssue.ts         |  13 +
 .../components/setup/forms/AccountFolderForm.tsx   | 187 +++++
 .../components/setup/forms/ConfluenceForm.tsx      | 184 +++++
 .../library/components/setup/forms/DropboxForm.tsx | 118 +++
 .../components/setup/forms/FilesystemForm.tsx      |  80 ++
 .../components/setup/forms/GoogleDriveForm.tsx     | 304 ++++++++
 .../library/components/setup/forms/S3Form.tsx      | 386 ++++++++++
 .../components/setup/forms/SharePointForm.tsx      | 201 +++++
 .../library/components/setup/pathSelection.ts      |  50 ++
 .../features/library/components/setup/pickedOf.ts  | 131 ++++
 .../library/components/setup/pickerTypes.ts        |  50 ++
 .../library/components/setup/summaryOfLibrary.ts   |  20 +
 .../components/sources/confluenceSource.tsx        |  67 +-
 .../library/components/sources/dropboxSource.tsx   |  30 +-
 .../library/components/sources/genericSources.tsx  |  19 +-
 .../components/sources/googleDriveSource.tsx       |  56 +-
 .../library/components/sources/nextcloudSource.tsx |  72 +-
 .../library/components/sources/s3Source.tsx        |  87 ++-
 .../components/sources/sharePointSource.tsx        |  31 +-
 .../library/components/sources/smbSource.tsx       |  70 +-
 .../components/sources/sourceIssueContext.tsx      |  16 +-
 .../features/library/components/sources/types.ts   |  24 +
 .../features/library/pages/LibraryCreatePage.tsx   | 226 ++++--
 ui-lab/harness/capture.mjs                         |  16 +-
 42 files changed, 4217 insertions(+), 240 deletions(-)
```
