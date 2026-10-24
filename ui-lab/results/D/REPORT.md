# Variante D – Zusammenfassungszeilen

## Konzept
- Jeder Abschnitt (Verbindung · Umfang · Zeitplan) ist eine ruhige Zeile mit Zusammenfassung und „Ändern“; immer höchstens eine Zeile offen. Dieselben Zeilen dienen Assistent, Prüfschritt und Reiter „Quelle“ (dort Bearbeiten an Ort und Stelle statt Dialog, Speichern je Zeile).
- Vorgaben machen den kleinen Fall fast eingabefrei: „/ (alles)“ bei Ordnerquellen, „alles unter dem Pfad“ beim Dateisystem, Zeitplan „täglich 02:00“, Confluence-Edition wird beim Tippen erkannt. Dropbox kommt nach der Zustimmung ohne jede Pflichteingabe durch.
- Fremde Objekte über eine Combo-Box mit Mehrfachauswahl und Tippfilter, ab 5 Chips „+n weitere“; hierarchische Quellen (Ordner, Präfixe, SharePoint-Ordner) mit Pfad in Kopfzeile und Chips, Nachladen per Pfeil oder getipptem Pfad („Projekte/2024“).

## Begriffssatz (alle Konnektoren)
Verbindung · Umfang · Zeitplan (Zeilen) · Zugang (Profil) · Adresse (S3: Endpoint, Glossar) · Kennung · Passwort / Token / Geheimer Schlüssel / Schlüsseldatei · Zugangsdaten = Kennung + Geheimnis · „gesetzt“ + „Ersetzen“ · Verbindung testen → Adresse · Anmeldung · Rechte · Status „verbunden“ / „teilweise lesbar“ / „Test fehlgeschlagen“ · Ändern · Fertig · Speichern / Testen und speichern · Abbrechen · Prüfen.

## Messung
Volle Matrix (8 Typen × small/scale/empty/slow/failed/partial × hell/dunkel × 1280/320): 604 Aufnahmen + `__full`; Scale-Detail/-Bearbeiten nach dem Kontrast-Fix neu aufgenommen und gemergt. axe: **0 Befunde**, Kontrast 0, `overflowX` 0, deaktivierte Absenden-Knöpfe 0. `not-found`: nur `wizard-validation dropbox small` (leeres Formular ist gültig, Weiter geht durch – gewollt). `focusObscured`: 13 Tab-Stopps in `wizard-empty dropbox`, verdeckt durch die bestehende Popup-Meldung „Quelle ist verbunden“. Drei Läufe: `--quick`, Matrix ohne `__full`, finale Matrix.

## Prüfliste
| # | | Beleg |
|---|---|---|
| 1 | ✓ | Chips nur Umriss; Teststufen nur Zeichen in Signalfarbe, Text in fg (`ConnectionCheckView`) |
| 2 | ✓ | Reiter unverändert Text + Unterstrich |
| 3 | ✓ | Zeilen ohne Icons; Icons nur in Zugangs-Kacheln (§5.11) |
| 4 | teilweise | Zeilen/Fehlerliste ohne Tönung (Kante links); getönter Erfolgs-Alert der bestehenden Zustimmungs-Komponente (Dropbox) bleibt |
| 5 | ✓ | Akzent nur an Knöpfen/Links/Auswahlzeichen |
| 6 | ✓ | Zeile = Titel + 1 Zusammenfassung + „Ändern“ |
| 7 | ✓ | roter Rahmen + kurze Zeile am Feld; Zusammenfassung ohne Icon/X (`wizard-validation__*`) |
| 8 | ✓ | „Änderungen an „Umfang“ verwerfen?“ + 1 Satz, Abbrechen/Verwerfen |
| 9 | ✓ | Quellart · Quelle · Name · Freigaben · Prüfen |
| 10 | n/a | keine Filterleiste |
| 11 | ✓ | Gliederung über Zeilen/Gewicht, keine Kästen |
| 12 | ✓ | „Adresse“, „Kennung (Access Key)“, „Ordner“ |
| 13 | teilweise | Hinweise neu, aber Ordner-/S3-Hinweis bei 320 px zwei-/dreizeilig |
| 14 | ✓ | Platzhalter = Beispiele (`/data/dokumente`, `smb://dateiserver/Ablage`) |
| 15 | teilweise | eigene Meldungen ≤ 1 Satz („Adresse fehlt“); Backend-Texte werden durchgereicht, teils mit HTTP-Code (`wizard-filled__confluence__failed`) |
| 16 | ✓ | „verbunden“, „Test fehlgeschlagen“, „gesetzt“ |
| 17 | teilweise | Verben überall, Ausnahme „Fertig“ (schließt eine Zeile) |
| 18 | ✓ | keine Doppelungen |
| 19 | ✓ | Begriffssatz oben, in allen 8 Specs gleich |
| 20 | ✓ | 450 Spaces, 17×25 SharePoint, 200 Ordner: Tippfilter, „n ausgewählt“, max. 100 Optionen + „Noch n weitere – Suchbegriff eingrenzen“ (`*__scale__*`) |
| 21 | teilweise | Ebene als Kopf „In Ordner 001 / Vorgang 002“, „Zurück zu …“, Pfad in Chips; kein sichtbarer Baum |
| 22 | ✓ | Ellipse + `title`/Tooltip in Optionen und Chips; overflowX 0 |
| 23 | ✓ | 8 Ebenen per Pfeil/Pfad; „Wird geladen …“ (> 300 ms), Fehler mit „Erneut laden“ dauerhaft unter dem Feld (`*__failed__*`) |
| 24 | ✓ | „Alle n Treffer auswählen“, „Alle entfernen“ |
| 25 | ✓ | 320 px ohne horizontales Scrollen (alle 320er Aufnahmen); 400 % Zoom nicht gemessen |
| 26 | teilweise | Fokus auf Schrittüberschrift, Titel „Quelle einrichten – Schritt 2 von 5 – …“, Pfeil rechts/links im Combo; keine manuelle End-to-End-Tastaturprüfung |
| 27 | teilweise | nur durch bestehende Popup-Meldung verdeckt (Dropbox, s. o.) |
| 28 | ✓ | Weiter/Anlegen nie `disabled`; Anlegen als BusyButton (`aria-disabled` während des Sendens) |
| 29 | ✓ | alle Fehler auf einmal, Liste mit Sprunglinks oben, Titel „Fehler: …“; Fehler verschwindet, sobald das Feld gültig ist |
| 30 | teilweise | Assistenten-Zurück behält alles; Browser-Zurück unverändert |
| 31 | ✓ | Reiter Quelle: Nachfrage nur bei geänderter Zeile (JSON-Vergleich) |
| 32 | ✓ | Schritt „Prüfen“, „Ändern“ öffnet die passende Zeile, „Zur Prüfung“ führt zurück (`review__*`) |
| 33 | ✓ | ein Knopf „Verbindung testen“ für alle; automatisch bei Weiter/„Testen und speichern“; Stufen Adresse · Anmeldung · Rechte; Ergebnis verfällt bei jeder Änderung der Anfrage; „Trotzdem fortfahren“ |
| 34 | ✓ | „Zugangsdaten gesetzt · Ersetzen“, Schlüsseldatei „gesetzt · Ersetzen“, Einfügen + Anzeigen erlaubt (`edit__*`) |
| 35 | ✓ | Quellart Kacheln; fremde Objekte Combo/Liste, nie Kacheln |
| 36 | teilweise | Elternoption mit Strich-Symbol + „teilweise ausgewählt“; Optionen tragen `aria-selected`, kein `aria-checked="mixed"` |
| 37 | ✓ | „Alle 37 Treffer auswählen“ wirkt nur auf Treffer; Filter löscht keine Auswahl |
| 38 | ✓ | „Keine Ordner vorhanden.“ · „Keine Treffer für „x“.“ + „Suche zurücksetzen“ · Fehler-/Rechtemeldung |
| 39 | ✓ | Ladezeile erst nach 300 ms; Test-Knopf „Wird geprüft …“ |
| 40 | n/a | Indizierungsstatus/Läufe nicht im Umfang, unverändert |
| 41 | teilweise | Speicherfehler inline an der Zeile, Erfolg als Popup; Timer-Pause des Popups nicht umgesetzt |
| 42 | ✓ | Leser sehen dieselbe Umfang-Zeile ohne „Ändern“ |
| 43 | teilweise | 35-Zeichen-Kompositum bei 320 px gekürzt mit Ellipse, kein Umbruch-Abschnitt; keine Pseudo-Lokalisierung |
| 44 | ✓ | axe 0, Kontrast 0, hell/dunkel × 320/1280 |

## Bekannte Schwächen
- Zustände der Auswahl (leer, Fehler) erscheinen erst, wenn die Combo-Box einmal geöffnet wurde (Laden auf Abruf).
- Verbindungstest prüft auch den Umfang; eine Umfangsänderung macht „verbunden“ in der Verbindungszeile zu „nicht geprüft“, bis Weiter erneut testet.
- Bei Fehlern in geschlossenen Zeilen stehen die Meldungen dort nur als Zeile unter der Zusammenfassung; am Feld erst nach Sprung/Öffnen (Konzept: eine Zeile offen).
- SharePoint: gewählte Site wird nicht gespeichert; im Bearbeiten muss die Site neu gesucht werden, bevor weitere Bibliotheken erscheinen.
- Webhook-/Ereignis-Abschnitte (Confluence, S3) und StoredView-Details fehlen im neuen Reiter.
- Detailkopf (bestehend) zeigt bei 320 px abgeschnittene Knöpfe/Titel; im dunklen Schema bei langen Titeln dunkler Titeltext – nicht Teil der Variante.
- Unit-Tests der umgebauten Seiten nicht angepasst (unten).

## Gebrochene Tests (86 in 5 Dateien, `pnpm exec vitest run`)
- `LibraryCreatePage.test.tsx` 42 · `LibraryDetailPage.test.tsx` 17 · `LibrarySourceSection.test.tsx` 18 · `LibrarySourceSection.sourceConnection.test.tsx` 5 · `LibraryCreatePage.sourceConsent.test.tsx` 4

<details><summary>Einzeln</summary>

- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > names the account, who answers for it and since when it is connected
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > names why an ended connection ended, and offers no disconnect then
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > offers no reconnect once the profile is gone, and points to „Zugang zuordnen“
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > disconnects only after the question, then reloads the library
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > reconnects only with the service account confirmed, and remembers where to return
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang (#2160) > names the connection profile of the library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers „Zugang zuordnen“ on the lock of the own address and connects through the chosen profile
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers the way back after „Zugang entfernt“
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > switches a connected library to another profile, never offering the current one
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > tests the new profile before switching and saves after a failed test only on request
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > shows the result of „Verbindung prüfen“ without saving, and an error of the test itself
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers a manager only the profiles released to her, asking with the library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > keeps the status of a library whose profile is locked, with its reason and the way out
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > releases a library from its profile after a confirmation
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers no release while the type is usable only through profiles
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers „Zugang zuordnen“ for a library with its own address of a type with profiles
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > names a deleted profile as removed, not as an own address
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > offers no release from the profile
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > offers only the profiles the owner has a connected account on, one for persons only included
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > never offers a profile for persons only to a shared library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > leads to "Verbundene Konten" when no other profile carries an account of the owner
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > says when a switch to another server discarded the secret and offers to edit the source
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Aktion am Sperrhinweis > opens the source configuration where the managers enter the secret anew
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > comes back on the source step with the connection, its account and its focus
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > creates the library with the pending connection, the creator answering for it
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > lets the owning group answer for the connection
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > asks to connect again when the pending connection can no longer be used
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names a failed source-type list in German and does not go on with the upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names the steps and drops "Quelle einrichten" for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > blocks Weiter without a name and says so
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > shows an empty step naming the system administration when nothing may be created
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > moves the selection off a tile that turns out to be hidden
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > notes a person with the shared subject picker and grants on the asset after creation
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > goes to the new library when a noted grant is refused, and names the grant
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > asks before noting a group of an external provider and notes nothing on cancel
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sets the schedule together with the library, for a connector type that never had one here
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sends no schedule at all for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > offers the S3 tile, states the sharing consequence before the scopes, and blocks Weiter until the stages are complete
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates a MinIO library with the template values and sends its settings
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates an AWS library from the template with a derived virtual-host endpoint
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > refuses overlapping scopes before anything is sent
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > detects the edition before asking for credentials and shows Cloud fields for Cloud
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows the PAT field for Data Center and refuses to continue without a tested selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > verifies credentials, loads the spaces, and sends edition, credentials and selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > sends an entered full-sync rhythm as the number, not as the update path’s zero
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > skips the first run when the immediate-indexing switch is turned off
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > joins e-mail and token for Cloud and drops verification when the address changes
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows a blocked or unreachable address as an error with the backend wording, and never a credentials field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > keeps the space picker usable after "Zurück" re-enters the source step
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > drops a late test answer once the address changed in the meantime
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > offers a retry when the space listing fails, keeping the selection visible
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > switches the connection form with the chosen kind and validates its fields
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a wrong path to its field, focuses it and lets the step stand
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing address to the URL field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing S3 access key to its field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > announces an issue of a field the form does not show in the step’s live region
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > keeps entered values when navigating back
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates the library and navigates to its detail page
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates a group-owned library, offering only the groups returned for the user
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > creates a library through the chosen profile, its address prefilled
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > does not carry address or fixed values of the previous way over to the next
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > drops the edition detected for an own address once a profile clears that address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers no certificate switch under the chosen profile and sends none
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > sends no profile for the own address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers the own address alone while no profile exists yet
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > SharePoint (ADR-0047) > creates a library through the profile with the chosen document library, without a secret
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > never chooses the private way by itself, even where it is the only one
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > creates a private library over the own account, without a secret and without „Freigaben“
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > keeps the shareable way on a profile admitting both as an explicit choice of its own
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the configuration and an indexing trigger for a connector library
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > offers "Bearbeiten" for a MANAGER on a connector library but hides it for a VIEWER
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows an S3 library with its scopes in the page head, the configuration card and "Bearbeiten" (#1377)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows a Confluence library read-only with edition and spaces, and still offers "Bearbeiten" (#1135)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the webhook row to a manager of a Confluence library only (#1140)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > states the Confluence sharing consequence to a VIEWER as well, and never for other types (#1138)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows no incomplete-listing warning while the assessment is clean (#1191)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > edits the source configuration through the dialog, resending the unrelated Stammdaten fields untouched
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the current schedule and the next planned run for a MANAGER
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows a warning when the last two scheduled runs failed
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > edits the schedule through the dialog, resending the unrelated Stammdaten fields untouched
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the full-sync rhythm of a Confluence library and lets a MANAGER lengthen it (#1200)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > returns the rhythm to the instance default when the field is cleared (#1200)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > resends the whole Google Drive settings with a changed rhythm (ADR-0040)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > names the sections behind the MANAGER bar in order
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > explains what a Geltungsbereich is and shows a VIEWER the Umfang only
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > tests the stored configuration from the Anbindung section, without opening the editor

</details>

Grün: `pnpm run lint`, `pnpm exec tsc -b`, `pnpm run build`, Prettier.

## git diff --stat gegen origin/feature/3084_ui-lab-base (ohne results/)
```
 .../library/components/ConnectionProfileSelect.tsx |   6 +-
 .../library/components/CreateSourceSetupStep.tsx   | 145 +++--
 .../library/components/LibraryCreateFooter.tsx     |  24 +-
 .../library/components/LibraryReviewStep.tsx       |  68 +++
 .../library/components/LibrarySourceRows.tsx       | 148 +++++
 .../library/components/LibrarySourceSection.tsx    | 359 +++++++-----
 .../library/components/SourceConsentPanel.tsx      |   2 +-
 .../library/components/libraryCreateWizard.ts      |  17 +-
 .../components/setup/ConnectionCheckView.tsx       | 119 ++++
 .../library/components/setup/CredentialFields.tsx  | 105 ++++
 .../library/components/setup/ErrorSummary.tsx      |  65 +++
 .../library/components/setup/PickCombo.tsx         | 608 +++++++++++++++++++++
 .../library/components/setup/SecretField.tsx       | 128 +++++
 .../library/components/setup/SetupTextField.tsx    |  68 +++
 .../library/components/setup/SourceSetupRows.tsx   | 402 ++++++++++++++
 .../library/components/setup/SummaryRow.tsx        | 125 +++++
 .../library/components/setup/connectionCheck.ts    | 187 +++++++
 .../setup/connectors/ConfluenceSetupFields.tsx     | 165 ++++++
 .../setup/connectors/DropboxSetupFields.tsx        |  52 ++
 .../components/setup/connectors/FolderScope.tsx    |  79 +++
 .../setup/connectors/GenericSetupFields.tsx        |  81 +++
 .../setup/connectors/GoogleDriveSetupFields.tsx    | 150 +++++
 .../setup/connectors/NextcloudSetupFields.tsx      |  75 +++
 .../components/setup/connectors/S3SetupFields.tsx  | 248 +++++++++
 .../setup/connectors/SharePointSetupFields.tsx     | 125 +++++
 .../components/setup/connectors/SmbSetupFields.tsx |  74 +++
 .../library/components/setup/connectors/common.ts  |  95 ++++
 .../components/setup/connectors/confluenceSetup.ts |  72 +++
 .../components/setup/connectors/driveNodes.ts      |  23 +
 .../components/setup/connectors/dropboxSetup.ts    |  35 ++
 .../components/setup/connectors/folderNodes.ts     |  40 ++
 .../components/setup/connectors/genericSetups.ts   |  72 +++
 .../setup/connectors/googleDriveSetup.ts           |  78 +++
 .../components/setup/connectors/nextcloudSetup.ts  |  60 ++
 .../library/components/setup/connectors/s3Nodes.ts |  28 +
 .../library/components/setup/connectors/s3Setup.ts |  99 ++++
 .../components/setup/connectors/sharePointNodes.ts |  60 ++
 .../components/setup/connectors/sharePointSetup.ts |  59 ++
 .../components/setup/connectors/smbSetup.ts        |  63 +++
 .../library/components/setup/scheduleSummary.ts    |  27 +
 .../library/components/setup/setupTypes.ts         |  51 ++
 .../library/components/sources/registry.ts         |  39 +-
 .../features/library/components/sources/types.ts   |   3 +
 .../features/library/pages/LibraryCreatePage.tsx   | 160 ++++--
 ui-lab/harness/capture.mjs                         |  66 ++-
 45 files changed, 4462 insertions(+), 293 deletions(-)
```
