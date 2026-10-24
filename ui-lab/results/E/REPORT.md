# Variante E – Auswahldialog

## Konzept
Muster Dateiauswahl: Das Formular zeigt je Quelle nur „Umfang“ als knappe Zusammenfassung (höchstens 5 Einträge, „und n weitere“, Knopf „Auswählen …“/„Ändern“).
Ausgewählt wird in **einem** Dialog für alle Konnektoren (`sourceKit/SelectionDialog.tsx`): Breadcrumb, Suche in der Ebene (SharePoint-Sites serverseitig), Checkbox-Liste mit Teilauswahl, „Alle n auswählen“, Fußzeile „n ausgewählt · Auswahl ansehen · Übernehmen“.
Flache (Confluence, Drive) und hierarchische Quellen (SharePoint Site › Bibliothek › Ordner, S3 Bucket › Präfix, Nextcloud/SMB/Dropbox Ordnerbaum) laufen über Adapter in `sourceKit/selectionSources.ts`; Formulare je Konnektor unter `components/sourceForms/`, gemeinsamer Rahmen über die Registry (`issues`, `onTested`, `summary`).

## Begriffssatz
Gruppen: **Verbindung · Anmeldung · Umfang · Zeitplan** (+ „Erweiterte Einstellungen“ bei S3). Felder: **Adresse**, **Kennung**, **Kennwort** (Herstellerbegriff nur im Hilfetext: App-Passwort, API-Token, Access/Secret Key), **Verzeichnis**, **Schlüsseldatei**. Objekte: Ordner, Spaces, Buckets und Präfixe, Ablagen und Ordner, Dokumentbibliotheken und Ordner. Handlungen: **Auswählen … · Ändern · Übernehmen · Verbindung testen · Ersetzen · Quelle bearbeiten · Speichern**. Zustände: **Gesetzt**, „n ausgewählt“, „teilweise“. Prüfstufen: **Adresse · Anmeldung · Rechte**. Fehlertexte einheitlich „Bitte … eingeben/auswählen.“ Schritte: Quellart · Quelle · Name · Freigaben · Prüfen.

## Messlauf (volle Matrix, 8 Typen × 6 Szenarien × hell/dunkel × 1280/320)
732 Aufnahmen (+ `__full`), 12 not-found: Dropbox-Validierung (leeres Formular ist gültig, „/“ = alles), SMB `scale` Detail/Bearbeiten (Reiter „Quelle“ unter Last nicht gefunden – Zeitproblem des Skripts, im Lauf davor vorhanden). Zusätzliche Zustände: `wizard-select` (Dialog offen) und `edit-select`.
Kennzahlen: `overflowX` 0 · `disabledSubmits` 0 · axe hell: **0 Befunde** · axe dunkel: 52× `color-contrast`, fast nur an vorhandenen Bausteinen (Zugangszeile `connection-profile`, h2), schwankend je Aufnahme; Gegenprobe mit direkt im Dunkelschema geladener Seite: 0 Befunde → Artefakt des Schemawechsels im Skript (150 ms Wartezeit, `body`-Farbübergang). `clippedText` trifft nur die visuell versteckte h1 (Bestand).
Iterationen: Lauf 1 (quick, 4 Typen) → Komma-Ordner, Pfadanzeige; Lauf 2 (Matrix ohne full) → Überlauf durch falsch versteckten Text, axe `aria-input-field-name`/`aria-allowed-role`/`aria-prohibited-attr`, Vollrahmen (Projektregel), Anmeldedaten im Bearbeiten; Lauf 3/4 → Textfarbe beim Schemawechsel, `aria-checked="mixed"`.

## Prüfliste
| # | Ergebnis | Beleg |
|---|---|---|
| 1 | bestanden | Prüfstufen nur Zeichen ✓/✗ + Wort; keine Chips/Badges neu |
| 2 | bestanden | Reiter unverändert (Text + Unterstrich) |
| 3 | bestanden | Gruppenüberschriften ohne Icon (`wizard-filled__*`) |
| 4 | bestanden | keine Tönung; Fehlerzusammenfassung/Auswahl nur 3‑px-Kante links |
| 5 | bestanden | Akzent nur an Knöpfen/Links |
| 6 | bestanden | Dialogzeile: Name + max. 1 Zusatz („teilweise“/Sperrgrund), Chevron „Öffnen“ |
| 7 | bestanden | roter Rahmen + Zeile am Feld (`wizard-validation__*`) |
| 8 | bestanden | „Geänderte Auswahl verwerfen?“ / „Änderungen an der Quelle verwerfen?“, zwei Knöpfe |
| 9 | bestanden | Schrittleiste mit einem Wort je Schritt |
| 10 | n. a. | keine Filter |
| 11 | bestanden | Hierarchie über Größe/Gewicht |
| 12 | bestanden | Labels 1–3 Wörter |
| 13 | teilweise | Hilfetexte einzeilig bei 1280; bei 320 brechen einige um (SMB-Adresse) |
| 14 | bestanden | Platzhalter = Beispiel (`https://cloud.example.org`, `/Projekte/2026`) |
| 15 | teilweise | eigene Meldungen ≤ 1 Satz; Backend-Meldungen werden wörtlich gezeigt (z. B. „HTTP 504“) |
| 16 | bestanden | „Gesetzt“, „teilweise“ |
| 17 | bestanden | Verben: Übernehmen, Ersetzen, Speichern, Verbindung testen |
| 18 | bestanden | keine Doppelungen |
| 19 | bestanden | Begriffssatz oben, über alle Konnektoren gleich |
| 20 | bestanden | `wizard-select__*__scale`: Suche ab 7 Einträgen, „12 ausgewählt“, 100 Zeilen + „Weitere anzeigen“ (600 Buckets) |
| 21 | bestanden | Breadcrumb Sites / Rathaus / Dokumente / Ordner 001; Auswahl mit „· in …“-Pfad, „Auswahl ansehen“ über alle Ebenen |
| 22 | bestanden | Ellipse + Tooltip (Zeile, Breadcrumb, Zusammenfassung) |
| 23 | bestanden | 8 Ebenen per Breadcrumb; Skeleton nach 300 ms (`slow`); Fehler mit „Erneut versuchen“ (`failed`); unvollständig mit Meldung (`partial`) |
| 24 | bestanden | „Alle n auswählen“ mit Mischzustand |
| 25 | bestanden | `overflowX` 0 bei 320 px; Dialog bei < 600 px vollflächig |
| 26 | teilweise | Fokus auf Schrittüberschrift, `document.title` „Schritt n von m: … – Neue Wissensbibliothek“; Tastaturstrecke nicht vollständig manuell geprüft |
| 27 | teilweise | 24 von 732 Aufnahmen mit `focusObscured`, Ursache Tooltip nach Mausposition des Skripts; keine Sticky-Leisten |
| 28 | bestanden | `disabledSubmits` 0; Weiter/Anlegen/Speichern als BusyButton (aria-disabled nur während Anfrage) |
| 29 | teilweise | alle Fehler auf einmal + Zusammenfassung mit Sprunglinks + Titel „Fehler: “; Prüfung beim Absenden und Neuprüfung beim Tippen, **keine** Prüfung beim Verlassen des Feldes |
| 30 | teilweise | Assistent-Zurück verliert nichts; Browser-Zurück nicht eigens behandelt |
| 31 | bestanden | Nachfrage nur bei Änderung (Dialog Auswahl, Bearbeiten, Assistent) |
| 32 | bestanden | Schritt „Prüfen“ mit „Ändern“ je Abschnitt, danach „Zur Prüfung“ (`review__*`) |
| 33 | bestanden | automatisch bei Weiter/Speichern + Knopf; Stufen Adresse · Anmeldung · Rechte inline; verfällt bei jeder Änderung der Anfrage (strenger: auch bei Auswahländerung); „Trotzdem fortfahren“ |
| 34 | bestanden | „Kennung und Kennwort: Gesetzt · Ersetzen“ (`edit__*`), Drive „Gesetzt · Ersetzen“ |
| 35 | bestanden | fremde Objekte nur als Liste/Baum mit Suche, keine Kacheln |
| 36 | bestanden | Elternordner mit Auswahl darunter: Strich + `aria-checked="mixed"` (geprüft per Playwright) |
| 37 | bestanden | „Alle 37 Treffer auswählen“; Suche löscht keine Auswahl |
| 38 | bestanden | „In „X“ liegen keine Ordner.“ · „Keine Treffer für „…“.“ + „Suche zurücksetzen“ · „Kein Zugriff auf „X“.“ |
| 39 | bestanden | Skeleton erst nach 300 ms; Testfortschritt im auslösenden Knopf |
| 40 | n. a. | Indizierungsstatus nicht im Umfang (unverändert) |
| 41 | bestanden | Fehler inline; Toast nur für Erfolg („Quelle gespeichert.“, „Bibliothek … angelegt.“) |
| 42 | n. a. | unverändert |
| 43 | teilweise | 35-Zeichen-Kompositum bei 320 px mit Ellipse/Tooltip; keine Pseudo-Lokalisierung gemessen |
| 44 | teilweise | axe hell 0; dunkel siehe oben (Artefakt) |

## Bekannte Schwächen
- Zwei Klicks mehr im kleinen Fall (1–3 Objekte): immer über den Dialog, auch für einen einzelnen Space.
- Auswahl braucht funktionierende Auflistung; ohne sie nur „von Hand eintragen“ im Dialog (S3, Drive, Ordner, SharePoint-Adresse; Confluence ohne).
- Ordnerpfade mit Komma sind sichtbar, aber gesperrt (Backend trennt per Komma) – im `scale`-Fixture betrifft das jeden 3. Ordner samt Unterbaum.
- Mischzustand nur für im Dialog gewählte Einträge vollständig; gespeicherte SharePoint-Ordner kennen ihre Site nicht.
- „Anbindung“ auf der Detailseite (StoredViews, Zugangszeile) nicht vereinheitlicht; alte Formularkomponenten bleiben als toter Code liegen (Tests importieren sie).
- `LibraryCreatePage.tsx` > 800 Zeilen (ESLint-Warnung).

## Gebrochene Unit-Tests (bewusst, altes Verhalten)
8 Dateien, 98 Fälle: `EditLibrarySourceDialog.test.tsx` (33), `LibraryCreatePage.test.tsx` (43), `LibraryDetailPage.test.tsx` (7), `sources/connectedSourceForms.test.tsx` (7), `LibraryCreatePage.sourceConsent.test.tsx` (4), `LibrarySourceSection.test.tsx` (2), `sources/registry.test.ts` (2). (`PageSection.test.tsx` wieder grün.) `pnpm run lint` (0 Fehler, 1 Warnung max-lines), `pnpm exec tsc -b`, `pnpm run build`, `format:check` grün.

## git diff --stat origin/feature/3084_ui-lab-base (ohne results/)
```
 .../features/asset/components/AssetNameFields.tsx  |   5 +
 .../library/components/CreateReviewStep.tsx        |  80 +++
 .../library/components/CreateSourceSetupStep.tsx   |  59 +-
 .../library/components/EditLibrarySourceDialog.tsx | 163 ++++--
 .../library/components/LibraryCreateFooter.tsx     |  34 +-
 .../library/components/LibraryScheduleForm.tsx     |   4 +-
 .../library/components/LibrarySourceSection.tsx    |   8 +-
 .../features/library/components/S3SourceForm.tsx   |   3 +-
 .../library/components/SourceConsentPanel.tsx      |  22 +-
 .../library/components/libraryCreateWizard.ts      |  19 +-
 .../components/sourceForms/ConfluenceForm.tsx      | 160 ++++++
 .../library/components/sourceForms/DriveForm.tsx   | 158 ++++++
 .../library/components/sourceForms/DropboxForm.tsx |  58 ++
 .../components/sourceForms/FilesystemForm.tsx      |  45 ++
 .../components/sourceForms/FolderShareForm.tsx     | 115 ++++
 .../library/components/sourceForms/S3Form.tsx      | 214 +++++++
 .../components/sourceForms/SharePointForm.tsx      |  44 ++
 .../components/sourceKit/ConnectionCheck.tsx       | 106 ++++
 .../library/components/sourceKit/ErrorSummary.tsx  |  76 +++
 .../components/sourceKit/SelectionDialog.tsx       | 622 +++++++++++++++++++++
 .../components/sourceKit/SelectionSummary.tsx      | 227 ++++++++
 .../components/sourceKit/StoredSelection.tsx       |  31 +
 .../library/components/sourceKit/collectIssues.ts  |  49 ++
 .../library/components/sourceKit/fields.tsx        | 242 ++++++++
 .../library/components/sourceKit/selection.ts      | 100 ++++
 .../components/sourceKit/selectionSources.ts       | 342 +++++++++++
 .../library/components/sourceKit/summaries.ts      |  22 +
 .../components/sourceKit/useConnectionCheck.ts     | 112 ++++
 .../components/sources/confluenceSource.tsx        |  88 ++-
 .../library/components/sources/dropboxSource.tsx   |  37 +-
 .../library/components/sources/genericSources.tsx  |  18 +-
 .../components/sources/googleDriveSource.tsx       |  63 ++-
 .../library/components/sources/nextcloudSource.tsx |  73 ++-
 .../library/components/sources/s3Source.tsx        |  74 ++-
 .../components/sources/sharePointSource.tsx        |  50 +-
 .../library/components/sources/smbSource.tsx       |  71 ++-
 .../library/components/sources/sourceFrame.ts      |  32 ++
 .../components/sources/sourceIssueContext.tsx      |  16 +-
 .../features/library/components/sources/types.ts   |   9 +
 .../features/library/pages/LibraryCreatePage.tsx   | 290 ++++++++--
 ui-lab/harness/capture.mjs                         |  81 ++-
 41 files changed, 3717 insertions(+), 305 deletions(-)
```
Dazu `ui-lab/results/E/`: 1464 JPEG (Qualität 70, ≤ 1280 px breit, `__full` auf 4000 px Höhe beschnitten), `metrics.jsonl`, `run.json`, dieser Bericht.
