# Variante M (Mischung, Gerüst A) – Bericht (25.10.2026)

Branch `feature/3084_ui-lab-detail-var-m`, abgezweigt vom Endstand A (Runde 4, `97b61b3`). **Messlauf** (Maintainer-Vorgabe: keine volle Bildmatrix) über alle 11 Arten × `small scale failed empty partial slow` × 1280/320, nur hell, ohne `__full`, dazu `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small scale failed`; neuer Bereich `abschluss` (Seite mit `?justCreated=1`). Kennzahlen in `metrics.jsonl` (1 036 Messungen) und `metrics-extra.jsonl`. Als Bilder liegen nur **29 Kernbildschirme** bei (Liste unten). Dazu der **klickbare Prototyp** (`node e2e/ui-lab/build-prototype.mjs`).

## Konzept

Alles wie A (eine Spalte 720 px, ein Zustandswort aus `libraryState.ts`, nichts zwischen Kopf und Reitern, `DetailSection`/`ListSearch`/`RowMenu`, Läufe als Tabelle im Reiter Quelle), dazu:

| Muster | Herkunft | Umsetzung in M |
|---|---|---|
| Titelstufe | C | Titel 20/28 px ab 600 px (eine Zeile, Tooltip), 17/22 px darunter, ungekürzt mit Silbentrennung, **keine reservierten Leerzeilen** |
| Kompakte Zustandszeile bei 320 px | C | Unter 600 px steht nur „● Wort · Zusatz“ mit einem Umschalter ⌄; die Kennzahlen (Bestand, Spaces, Eigentümer) auf Wunsch darunter. Ab 600 px unverändert eine Kennzahlzeile mit dem Wort am Ende |
| Nächste Schritte nach dem Anlegen | C | Nur mit `?justCreated=1` (Übergabe des Assistenten), unter der Reiterleiste: „Zugang prüfen“ zuerst, „Quelle verbunden ✓“ erst nach einem Lauf, der die Quelle gelesen hat, „In Space verwenden“, „Live-Abgleich einrichten“ (Confluence, S3), „Feld anlegen“, Upload „Dateien hochladen“. „Ausblenden“ für immer; alles erledigt → Block weg. Bei 320 px nur die offenen Schritte |
| Zeilen Titel · Wert · Textaktion für Einzelwerte | C | `ValueRow` im Raster des Reiters Quelle: Eigentümer (Übergeben), Suchdiagnose (Sperren/Entsperren), Herleitung (Anzeigen), Kontextpräfix (Ändern), Extraktion (Ändern). Ersetzt die Abschnitte mit eigener Überschrift |
| „Abgelehnte Dateien anzeigen“ | C | „Schließen“ faltet die Upload-Meldung zu „Abgelehnte Dateien anzeigen (n) · Verwerfen“ an derselben Stelle, bis zum nächsten Upload |
| Filter „Nur mit Fehlern (n)“ | B | „Alle · Nur mit Fehlern (n)“ (derselbe Umschalter wie in Freigaben) über der Laufliste, nur wenn er sie kürzt |
| „Verbindung ändern ›“ im Laufdetail | B | Ein an der Verbindung gescheiterter Lauf (Anmeldung abgelehnt, Nicht erreichbar, Zustimmung abgelaufen) ist wieder aufklappbar und führt mit „Verbindung ändern ›“ zur Zeile Verbindung |

Nicht übernommen (Juroren „nicht übernehmen“): Statusband und Reiter Verlauf (B), Steckbrief-Spalte und Link-Spalte (C), „Entziehen“ je Zeile (C), „mind.“, „Hand“, „Neuestes“.

## Messwerte (Messlauf, hell)

| Wert | M | A (Runde 4) |
|---|---|---|
| `tabsTop` 1280 | 212 in allen Bildern; 234 einmal (`abschluss__privat__scale`: privat + „in keinem Space“ + 120-Zeichen-Titel, die Kennzahlzeile bricht um) | 212 |
| `tabsTop` 320 | 266 (kurzer Titel), 320 (Leser ohne Hauptaktion), 342 (120-Zeichen-Titel, fünf Zeilen) – alle ≤ 360 | 331 / 350 |
| axe | 5 × `target-size` an `.MuiIconButton-colorInherit` (Kopfleiste der App bei 320 px, je ein Bild, nicht reproduzierbar; wie in A Runde 2) | 0 |
| Kontrast · Budget · `overflowX` | 0 · 0 · 0 | 0 · 0 · 0 |
| `uiWords` (Max) | Dokumente 80, Quelle 67, Läufe 67, Metadaten 53, Freigaben 52, Zuordnungen 40, Upload 86, Abschluss 82 | 81 / 67 / 67 / 47 / 50 / 40 / 87 |
| offener Lauf `scale` | +745 px | +745 px |

D1 bei 320 px: Die Reiterleiste steht nicht mehr für jeden Titel gleich hoch, sondern folgt dem Titel (Maintainer: Leerraum kompakter lösen, ≤ 360 px). Ab 600 px bleibt sie konstant.

## Kernbildschirme (29)

- Confluence `small` 1280: alle sechs Reiter (`dokumente`, `quelle`, `laeufe`, `metadaten`, `freigaben`, `zuordnungen`)
- SharePoint `scale` 1280: alle sechs Reiter
- Nextcloud `failed` 1280: `dokumente`, `quelle`, `laeufe`
- Dateisystem `partial` 320: `dokumente`, `quelle`, `laeufe`
- Leser (Confluence) `small` 320: `dokumente`, `metadaten`, `freigaben`
- privat `small` 1280: `dokumente`, `zuordnungen`
- Upload `small`: `dokumente` 1280, `upload` 1280 und 320
- Abschluss: Confluence `small` 1280, Confluence `failed` 320, Upload `small` 1280

## Prototyp

Werkzeug aus `feature/3084_ui-lab-prototyp` (Commit `b1adf448`, per Cherry-Pick, ohne den restlichen `main`-Stand des Branches). `node e2e/ui-lab/build-prototype.mjs` → `e2e/ui-lab/out/prototype/` (219 Dateien, 4,2 MB, ein Upload-Paket). Geprüft: `index.html?mockScenario=small#/libraries/library-nextcloud-projekte` und `?mockScenario=failed#/libraries/library-confluence-wiki?tab=quelle` laden unter einem Unterpfad ohne Seitenfehler; die nächsten Schritte mit `?justCreated=1` vor dem Hash.

## Schwächen

1. „Live-Aktualisierung“ (Begriff der Richtung) passt nicht in die 128-px-Titelspalte des Reiters Quelle, die der Assistent mitbenutzt; M bleibt bei „Live-Abgleich“.
2. Die nächsten Schritte brauchen die Übergabe `justCreated=1` aus dem Assistenten; im Prototyp über die Adresse, der Assistent selbst ist unverändert.
3. Bei 320 px stehen Bestand, Spaces und Eigentümer erst nach „⌄“; der Zustand bleibt immer sichtbar.
4. Status-Filter und Fehlerzahl im Upload brauchen den Parameter `status` an `GET /libraries/{id}/documents` (wie A).

## Gebrochene Unit-Tests

`vitest run`: 233 von 3 988 Tests rot (A Runde 4: 228; 107 davon schon auf der Basis rot). Neu gegenüber A: `AssetOwnerSection` (+4, Zeile statt Abschnitt), `LibraryMetadataFieldsSection` (+1, Kontextpräfix als Zeile). Die Tests des Prototyp-Werkzeugs (`inPageMocks`, `PrototypeBar`) laufen grün. Nur aufgelistet.

| Datei | rot |
|---|---|
| `library/pages/LibraryDetailPage.test.tsx` | 79 |
| `library/pages/LibraryCreatePage.test.tsx` | 46 |
| `library/components/LibrarySourceSection.test.tsx` | 24 |
| `asset/components/AssetGrantsSection.test.tsx` | 12 |
| `library/stores/documentStore.test.ts` | 10 |
| `library/components/metadata/LibraryMetadataFieldsSection.test.tsx` | 10 |
| `library/components/LibraryDocumentList.test.tsx` | 8 |
| `asset/components/AssetOwnerSection.test.tsx` | 5 |
| `library/components/LibrarySourceSection.sourceConnection.test.tsx` | 5 |
| `library/pages/LibraryCreatePage.sourceConsent.test.tsx` | 5 |
| `library/pages/LibraryDetailPage.privateLibrary.test.tsx` | 5 |
| `library/components/ConnectionProfileSelect.test.tsx` | 4 |
| `library/components/metadata/MetadataExtractionSettingsSection.test.tsx` | 4 |
| `prompt/pages/PromptLibraryFlow.test.tsx` | 3 |
| `library/components/S3SourceForm.test.tsx` | 2 |
| `library/utils/googleDriveSource.test.ts` | 2 |
| `library/components/sources/registry.test.ts` | 2 |
| `asset/components/AssetAccessDerivationSection.test.tsx` | 1 |
| `asset/components/AssetDetailHeader.test.tsx` | 1 |
| `asset/components/AssetSpacesList.test.tsx` | 1 |
| `library/components/GoogleDriveSourceForm.test.tsx` | 1 |
| `library/pages/ConnectionCallbackPage.test.tsx` | 1 |
| `library/utils/s3Source.test.ts` | 1 |
| `library/utils/sharePointSource.test.ts` | 1 |

`pnpm run lint`, `tsc -b`, `pnpm run build` und `pnpm run build:prototype` bestehen.

## `git diff --stat` gegen A (Runde 4, `97b61b3`)

```
 e2e/ui-lab/README.md                                            |  45 ++++
 e2e/ui-lab/build-prototype.mjs                                  |  72 ++++++
 frontend/AGENTS.md                                              |   5 +-
 frontend/eslint.config.js                                       |   2 +-
 frontend/package.json                                           |   1 +
 frontend/src/app/App.tsx                                        |  12 +-
 frontend/src/app/main.tsx                                       |  15 +-
 frontend/src/app/mocks/inPageMocks.test.ts                      |  71 ++++++
 frontend/src/app/mocks/inPageMocks.ts                           | 184 ++++++++++++++
 frontend/src/app/mocks/prototype/PrototypeBar.test.tsx          |  38 +++
 frontend/src/app/mocks/prototype/PrototypeBar.tsx               | 175 +++++++++++++
 frontend/src/app/mocks/prototype/prototypeConfig.ts             |  64 +++++
 frontend/src/app/mocks/prototype/startPrototype.tsx             |  57 +++++
 .../features/asset/components/AssetAccessDerivationSection.tsx  |  35 +--
 frontend/src/features/asset/components/AssetDetailHeader.tsx    |  87 +++++--
 frontend/src/features/asset/components/AssetHeadlineEditor.tsx  |  20 +-
 frontend/src/features/asset/components/AssetOwnerSection.tsx    |  80 +++---
 .../src/features/library/components/DiagnosticsLockControl.tsx  |  24 +-
 .../src/features/library/components/LibraryDocumentsNotices.tsx |  22 +-
 frontend/src/features/library/components/LibraryMetadataTab.tsx |  13 +-
 frontend/src/features/library/components/LibraryNextSteps.tsx   |  81 ++++++
 frontend/src/features/library/components/LibraryRunList.tsx     |  55 +++-
 frontend/src/features/library/components/LibrarySharingTab.tsx  |  66 ++---
 .../src/features/library/components/LibrarySourceSection.tsx    |   1 +
 frontend/src/features/library/components/libraryState.ts        |   2 +-
 .../components/metadata/LibraryMetadataFieldsSection.tsx        | 379 ++++++++++++++--------------
 .../components/metadata/MetadataExtractionSettingsSection.tsx   |  99 +++++---
 frontend/src/features/library/components/nextSteps.ts           | 140 ++++++++++
 frontend/src/features/library/mocks/detailScenarioFixtures.ts   |  10 +-
 frontend/src/features/library/mocks/libraryScenarioFixtures.ts  |   3 +-
 frontend/src/features/library/pages/LibraryDetailPage.tsx       |  28 +-
 frontend/src/shared/mocks/mockScenario.ts                       |   9 +
 frontend/src/shared/ui/ValueRow.tsx                             |  69 +++++
 frontend/src/shared/uiLabVariant.ts                             |  33 +++
 frontend/vite.config.ts                                         |  11 +-
 ui-lab/harness/flows/library-detail.mjs                         |  16 +-
 36 files changed, 1632 insertions(+), 392 deletions(-)
```
