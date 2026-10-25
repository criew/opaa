# Variante A „Alles an seinen Ort“ – Bericht (Runde 1, 25.10.2026)

Branch `feature/3084_ui-lab-detail-var-a`, Basis `feature/3084_ui-lab-detail-base`. Matrix: `detail` aller 11 Arten × `small scale failed empty partial slow` × hell/dunkel × 1280/320, dazu `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small scale failed`. Bilder `<bereich>__<art>__<szenario>__<schema>__<breite>[__full].jpg` (JPEG q70; `__full` nur hell, halbe Breite), Kennzahlen in `metrics.jsonl` und `metrics-extra.jsonl`.

## Konzept

- **Eine Spalte, 720 px** – Kopf, Reiterleiste und jeder Reiter haben die Breite von Assistent und Bearbeiten (`CONTENT_WIDTH`). Keine zweite Inhaltsbreite, keine zweite rechte Kante.
- **Kopf für alle Arten gleich:** Chips (Art · Quellart in Kurzform · Rolle) · Titel · Beschreibung (eine Zeile, auch leer reserviert) · **eine** Kennzahlzeile. Hauptaktion rechts oben: „Jetzt indizieren“, bei Upload „Hochladen“ an derselben Stelle.
- **Ein Zustandswort** als letztes Element der Kennzahlzeile: Punkt + Wort + Datum/Prozent/Zahl, Link zum Ort der Ursache (`?tab=quelle#verbindung`, `#laeufe` mit offenem neuestem Lauf, bei Upload Dokumente gefiltert). Abgeleitet an einer Stelle (`libraryState.ts`); Laufzeile und Kopf nutzen dieselbe Funktion `runWord`.
- **Zwischen Kopf und Reitern steht nichts.** Teilfehler markiert die Zeile Umfang mit Namen, abgelehnte Anmeldung die Zeile Verbindung, fehlgeschlagene geplante Läufe die Zeile Zeitplan.
- **Abschnitte** aller Reiter in einem Stil (`DetailSection`: 15 px, 600, Hinzufügen-Aktion als Textknopf rechts auf der Kante von „Ändern“).
- **Läufe** im Reiter Quelle als Tabelle (Zustand · Beginn · Dauer · Auslöser · Ergebnis), Zeile = Knopf in einer Liste; aufgeklappt Fehler nach Grund (5 Fundorte, „alle n anzeigen“ im Dialog), Hinweise und technische Angaben zugeklappt.

## Status je Punkt

Belege ohne Endung `.jpg`; „Querbogen“ = gleicher Bildschirm aller Arten.

| Nr | Status | Beleg |
|---|---|---|
| D1 Ein Kopf, Reiter auf gleicher Höhe | erfüllt für alle 11 Arten | `metrics.jsonl` `tabsTop` je Szenario über alle Arten **und** Reiter identisch: 1280 px 228 (scale 238), 320 px 282 (scale 358); Budget ≤ 260 / ≤ 360 |
| D2 Ein Zustandsfeld | erfüllt | `dokumente__nextcloud__failed__light__1280` („Anmeldung abgelehnt · 23.10.2026“), `dokumente__s3__slow__light__1280` („Läuft · 62 %“), `dokumente__confluence__small__light__1280` („Aktuell“ ohne Punkt, G 5.5) |
| D3 Zustand ist wahr | erfüllt | `quelle__nextcloud__partial__light__1280`: Kopf und erste Laufzeile „Teilweise fehlgeschlagen“, gleiches Datum; ein Lauf mit Fehlern, nicht lesbaren Bereichen oder `incomplete` ist nie „Abgeschlossen“ (`runPartlyFailed`) |
| D4 Ernstfall von jedem Reiter | erfüllt | `freigaben__smb__failed__light__1280` (Kopf), `quelle__smb__failed__light__1280` (Verbindung „Anmeldung abgelehnt am …“ · Ändern) |
| D5 Kennzahlen ohne Doppelung | erfüllt | Spaces und Eigentümer sind Links auf ihre Reiter; „Letzter Lauf“ und „Aktualisiert am“ entfallen; Listenfuß nennt die Zahl nur bei Seiten oder Filter |
| D6 Rolle einmal | erfüllt | `quelle__confluence-leser__small__light__1280` (nur Chip „Leser“, kein Alert, kein Satz) |
| D7 Laufender Vorgang einmal | erfüllt | `laeufe__s3__slow__light__1280`: „Läuft · 62 %“ im Kopf und in der Laufzeile, Ergebnis „831 von 1.340“; kein Balken, Knopftext unverändert |
| D8 Nichts zwischen Kopf und Reitern | erfüllt | `quelle__gdrive__partial__light__1280` („2 nicht lesbar: Projektablage, Freigegebene Akten“, keine `drive:`-IDs) |
| D9 Zeilenraster für alle Konnektoren | erfüllt | `quelle__web__small__light__1280` („Ganzes Verzeichnis“), `quelle__rss__…` („Alle Einträge des Feeds“), `quelle__confluence__small__light__1280` und `quelle__s3__…` mit vierter Zeile „Live-Abgleich · aus · Einrichten“ |
| D10 Verbindung mit letztem Zustand | erfüllt | „Anmeldung ok · zuletzt 23.10.2026“ bzw. „erreichbar · …“ (Dateisystem, Web, RSS); nach eigenem Test zeigt die Zeile wieder das Testergebnis |
| D11 Betriebsart einheitlich | erfüllt | Zeitplan „Vollabgleich alle n Tage“ und Auslöser „Vollabgleich“ am Lauf genau bei Confluence, SharePoint, Drive, Dropbox (Dropbox-Fixture bekam `fullSyncIntervalDefaultDays`) |
| D12 Laufzeile als Tabellenzeile | erfüllt | `laeufe__sharepoint__scale__light__1280`; Zeile ist `button[aria-expanded]` in `ul`, keine Überschrift |
| D13 Ereignisse gruppiert | erfüllt | `laeufe__smb__scale__light__1280__full`; Hinweise getrennt und zugeklappt, „fehlgeschlagen“ zählt nur Fehler |
| D14 Laufdetail ohne Wiederholung | erfüllt | kein „Indizierung abgeschlossen“, kein „Nichts übersprungen“; Detail zeigt nur Grund (bei Fehlschlag), Gruppen, Technische Angaben |
| D15 Läufe als Liste von 10 | erfüllt | Upload hat keine Läufe; Platz der Liste gleich |
| D16 Eine Inhaltsbreite | erfüllt | alle Reiter 720 px, rechte Kante von „Ändern“, „Freigeben“, „Feld anlegen“, „In Space verwenden“ identisch |
| D17 Ein Überschriftenstil | erfüllt | keine Mono-Versalien mehr im Detail (`DetailSection`) |
| D18 Hinzufügen oben rechts | erfüllt | `freigaben__*`, `metadaten__*`, `zuordnungen__*` |
| D19 Suche ab 8 | erfüllt | `freigaben__sharepoint__scale__light__1280` (Suche + Alle/Gruppen/Personen, ab 50 „weitere anzeigen“), `zuordnungen__s3__scale__…`, Metadatenwerte (Suche im aufgeklappten Feld), Upload-Ordner (erste 8, „alle 60 Ordner anzeigen“) |
| D20 Kein Dauerhinweis | erfüllt | Bestands-Alert entfällt; `metrics.jsonl`: Alerts nur noch bei Upload-Ablehnung (22 von 1 790 Bildern, Ist 726) |
| D21 Leerzustände | erfüllt | `dokumente__*__empty__light__1280`: nur „Noch keine Dokumente. Jetzt indizieren“ bzw. Ablagefläche; kein Filter, keine Suche, kein Füllgrad |
| D22 Füllgrad an einem Ort | erfüllt | Abschnitt „Füllgrad“ nur im Reiter Metadaten; im Reiter Dokumente nur Filter „ohne …“ |
| D23 Upload gleich gebaut | erfüllt | `dokumente__upload__failed__light__1280` („Kontingent voll“), `upload__upload__failed__light__1280` (Grund einmal, Dateien danach) |
| D24 Nichts verlieren | erfüllt mit Abstrichen | Liste unten |

**Wortbudget (`uiWords`, small, hell, 1280, Maximum über Arten):** Dokumente 86 · Quelle 69 · Läufe 69 · Metadaten 66 · Freigaben 57 · Zuordnungen 32 · Upload 135 (Ist: 218 · 92 · 118 · 363 · 261 · 52 · 197).

**Weitere Kennzahlen gegenüber Ist:** Kontrast 0 (Ist 0) · `overflowX` 0 · Budgetverletzungen 0 · axe: `heading-order`, `landmark-unique`, `aria-progressbar-name` entfallen; **neu** `label-content-name-mismatch` an Textknöpfen mit `aria-label` (siehe Schwächen) · Höhe `laeufe__*__scale__full` max. 2 255 px (Ist 24 492) · verschiedene Schriftgrößen max. 7 (Ist 11).

### D24 – Inventur Kapitel 8 und F

| Funktion | Ort in A |
|---|---|
| Confluence-Webhook, S3-Ereignisse | Reiter Quelle, Zeile „Live-Abgleich“ (Einrichten öffnet die bestehenden Abschnitte) |
| „letzte geplante Läufe fehlgeschlagen“ | Zeile Zeitplan, roter Punkt |
| Vollabgleich starten | an der Überschrift „Läufe“ (F2 B, statt ⋯-Menü) |
| Status unbekannt / erneut abrufen | Zustand „Läuft · Stand unbekannt“, „Stand erneut abrufen“ an „Läufe“ |
| Neu verbinden, Zugang zuordnen/wechseln/lösen | Zeile Verbindung, unverändert aus F |
| Teilfehler je Bereich | Zeile Umfang |
| Metadaten-Pflege („Anzeigen“) | Reiter Metadaten › Füllgrad › Anzeigen; Filter in Dokumente |
| Upload: Dateien, Ordner, Neuer Ordner, Drag & Drop | Kopf „Hochladen“ (Menü), „Neuer Ordner“ in der Werkzeugleiste, Ablegen auf der Liste |
| Diagnosesperre, Eigentum übergeben, Herleitung | Reiter Freigaben, je Abschnitt mit Textknopf |
| Rollen-Legende | als Tooltip an den Rolleneinträgen (Abstrich: nur per Maus) |
| Mitglieder einer Gruppe | Textknopf „Mitglieder“ in der Freigabezeile |
| Seitengröße „20/50/100 je Seite“ | **entfällt** (Abstrich) |
| Herkunft eines Dokuments (Space › Seite, URL) | Tooltip am Namen (Abstrich: kein Link mehr auf die URL) |

## Schwächen (bekannt, für Runde 2)

1. **axe `label-content-name-mismatch`** an Textknöpfen mit `aria-label`, das den sichtbaren Text nicht enthält („Ändern“ ↔ „Feld … bearbeiten“, „Mitglieder“, „Anzeigen“, „Sperre lösen“). Lösung: sichtbaren Text + visuell versteckter Zusatz wie in `SummaryRow`.
2. **Leser bei 320 px:** Ohne Hauptaktion passen ☆ ⋯ in die Chipzeile, die Reiterleiste liegt 29 px höher (253 statt 282). Lösung: Aktionen bei 320 px immer in eigener Zeile.
3. Aufgeklappter Lauf in `scale` wurde nach der Hauptaufnahme einzeilig gestellt (Fundorte mit Tooltip); die Bilder `laeufe__*` stammen aus der Nachaufnahme.
4. Der Status-Filter und die Fehlerzahl im Upload-Kopf brauchen einen Parameter `status` an `GET /libraries/{id}/documents` (Spec und Backend); im Prototyp nur im Mock.
5. Herkunft eines Konnektor-Dokuments nur noch im Tooltip; Seitengröße entfällt.
6. `sourceBlock`-Hinweis und „Zugang entfernt“ stehen weiter als getönte Alerts im Reiter Quelle (in keinem Szenario sichtbar).
7. Messartefakt `clippedText` in `quelle`/`laeufe`: zählt den visuell versteckten Zusatz von „Ändern Verbindung“ (wie in F).

## Gebrochene Unit-Tests

Lauf `npx vitest run`: 207 Tests in 23 Dateien rot. **Davon sind 107 schon auf dem Basis-Branch rot** (Abgleich in einem Worktree der Basis: `LibraryCreatePage` 46, `LibraryDetailPage` 19, `LibrarySourceSection` 18, `ConnectionProfileSelect` 4, Quellformulare und -utils 20). Durch A zusätzlich rot (erwartet: Texte, Rollen und Aufbau geändert, nur aufgelistet):

| Datei | Basis | A |
|---|---|---|
| `library/pages/LibraryDetailPage.test.tsx` | 19 | 72 |
| `library/components/LibrarySourceSection.test.tsx` | 18 | 24 |
| `library/stores/documentStore.test.ts` (neuer Parameter `status`) | 0 | 10 |
| `library/components/LibraryDocumentList.test.tsx` | 0 | 9 |
| `library/pages/LibraryDetailPage.privateLibrary.test.tsx` | 0 | 5 |
| `library/components/metadata/LibraryMetadataFieldsSection.test.tsx` | 0 | 5 |
| `asset/components/AssetGrantsSection.test.tsx` | 0 | 4 |
| `library/components/metadata/MetadataExtractionSettingsSection.test.tsx` | 0 | 3 |
| `prompt/pages/PromptLibraryFlow.test.tsx` (geteilter Kopf, Zuordnungen) | 0 | 2 |
| `asset/components/AssetDetailHeader.test.tsx`, `AssetOwnerSection.test.tsx`, `AssetAccessDerivationSection.test.tsx` | 0 | je 1 |

`pnpm run lint`, `tsc -b` (über `pnpm run build`) und `vite build` bestehen.

## `git diff --stat`

Code und Werkzeuge gegenüber der Basis (ohne Bilder):

```
 .../components/AssetAccessDerivationSection.tsx    |  21 +-
 .../asset/components/AssetDetailHeader.tsx         | 110 ++--
 .../asset/components/AssetGrantsSection.tsx        | 457 ++++++++------
 .../asset/components/AssetHeadlineEditor.tsx       |  31 +-
 .../asset/components/AssetOwnerSection.tsx         |  27 +-
 .../features/asset/components/AssetSpacesList.tsx  | 147 +++--
 .../asset/components/AssetSpacesSection.tsx        |  60 +-
 frontend/src/features/library/api/libraryApi.ts    |   4 +
 .../library/components/DiagnosticsLockControl.tsx  |  49 +-
 .../library/components/LibraryDocumentList.tsx     | 356 ++++++-----
 .../components/LibraryDocumentUploadArea.tsx       | 160 +----
 .../library/components/LibraryDocumentsNotices.tsx |  56 +-
 .../library/components/LibraryDocumentsSection.tsx | 203 +++++--
 .../library/components/LibraryLiveUpdateRow.tsx    |  51 ++
 .../library/components/LibraryMetadataTab.tsx      |  31 +-
 .../features/library/components/LibraryRunList.tsx | 416 +++++++++++++
 .../library/components/LibrarySharingTab.tsx       |  55 +-
 .../library/components/LibrarySourceRows.tsx       |  12 +-
 .../library/components/LibrarySourceSection.tsx    | 398 ++++---------
 .../library/components/LibraryStateLink.tsx        |  76 +++
 .../library/components/LibraryUploadInputs.tsx     |  59 ++
 .../features/library/components/libraryState.ts    | 187 ++++++
 .../metadata/CoreContextPrefixSwitch.tsx           |   1 +
 .../metadata/LibraryMetadataFieldsSection.tsx      | 307 ++++++----
 .../metadata/MetadataExtractionSettingsSection.tsx | 255 +++++---
 .../src/features/library/components/scopeMarks.ts  |  62 ++
 .../library/components/setup/SourceSetupRows.tsx   |  41 +-
 .../library/components/setup/SummaryRow.tsx        |   5 +-
 .../features/library/hooks/useDocumentsUrlState.ts |  16 +
 .../library/mocks/libraryDocumentHandlers.ts       |  17 +
 .../library/mocks/libraryScenarioFixtures.ts       |  27 +-
 .../library/mocks/sourceLibraryFixtures.ts         |   2 +
 .../features/library/pages/LibraryDetailPage.tsx   | 662 ++++++++++-----------
 .../src/features/library/stores/documentStore.ts   |  14 +-
 frontend/src/shared/ui/DetailSection.tsx           |  92 +++
 ui-lab/harness/flows/library-detail.mjs            |   7 +-
 ui-lab/harness/lib/measure.mjs                     |   8 +
 37 files changed, 2810 insertions(+), 1672 deletions(-)
```
