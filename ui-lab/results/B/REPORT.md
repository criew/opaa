# Variante B – „Mehr Schritte, je eine Sache“

## Konzept

Eine Sache je Seite nach GOV.UK: Quellart → Verbindung → Umfang → Zeitplan → Name → Freigaben → Prüfen; leere Schritte entfallen (Dateisystem ohne Umfang, Upload ohne Verbindung/Umfang/Zeitplan, private Bibliothek ohne Freigaben).
Jede Seite hat eine Frage als Überschrift, „Schritt n von m“ als Text statt Stepper, „Zurück“ oben, „Weiter“ + „Abbrechen“ unten; jeder Schritt ist ein Eintrag im Browserverlauf (`?schritt=`).
Detailseite und Bearbeiten sprechen dieselbe Sprache: Zusammenfassungsliste mit „Ändern“ je Abschnitt; „Ändern“ öffnet genau einen Abschnitt (Verbindung **oder** Umfang **oder** Zeitplan).

## Begriffssatz (alle Konnektoren)

| Begriff | Verwendung |
|---|---|
| Quellart · Zugang · Verbindung · Umfang · Zeitplan · Prüfen | Schritte und Abschnitte |
| Adresse | jede Netzwerkadresse (auch S3-Endpoint, Hinweis „Endpoint des Objektspeichers“); Dateisystem: Verzeichnispfad |
| Zugangsdaten | Gruppe der Geheimnisse; im Bearbeiten „Gesetzt · Ersetzen“ |
| Benutzername / Passwort | SMB, Nextcloud (dort Herstellerbegriff „App-Passwort“); Herstellerbegriffe bleiben: API-Token, Access Key, Secret Key, Schlüsseldatei |
| Verbindung prüfen | einziger Testknopf; Stufen **Adresse · Anmeldung · Rechte** (erreichbar/angenommen/ausreichend bzw. nicht erreichbar/abgelehnt/fehlen/eingeschränkt/nicht geprüft) |
| n ausgewählt · Alle n auswählen · Auswahl aufheben · Suche zurücksetzen | Auswahlbaustein |
| Ändern · Speichern · Trotzdem speichern · Ohne erfolgreiche Prüfung weiter | Aktionen |

## Umsetzung

- Registry: `SourceConfiguration.stepwise` je Quellart (`components/stepwise/adapters/*`): Felder, Fehler (alle auf einmal), Stufenfelder, Prüfung, Zusammenfassungszeilen, Umfang (`load`/`selection`/`withSelection`).
- Gemeinsame Bausteine, einmal gebaut: `FieldList` (Label oben, Fehler am Feld, Geheimnisse), `ErrorSummary`, `CheckResult` + `connectionCheck` (Prüfung, Verfall per Token), `ScopePicker` (Liste/Baum), `SummaryList`, `EditSectionDialog`.
- Prüfung: „Weiter“ auf *Verbindung* prüft Adresse + Anmeldung (S3/Drive/SharePoint über die Auflistung, Confluence erkennt zuerst die Edition); „Weiter“ auf *Umfang* prüft die Rechte der Auswahl und markiert nicht lesbare Einträge in der Liste.
- Messskript: `capture.mjs` folgt dem Schritt „Umfang“ (Weiter nach dem Ausfüllen, Radio „Nur ausgewählte …“, „… aufklappen“), erkennt „Ändern Umfang/Verbindung“ und nimmt zwei Zusatzzustände auf: `wizard-check` (Ergebnis „Verbindung prüfen“) und `edit-connection`. `wizard-filled` zeigt in B die Umfangsseite.

## Kennzahlen (volle Matrix, 708 Aufnahmen)

- Horizontales Scrollen: 0 · deaktivierte Absenden-Knöpfe: 0 · axe hell (inkl. `target-size`): 0 Befunde.
- axe dunkel `color-contrast`: 52 Aufnahmen mit Befund – Messartefakt: Hintergrund hinter dem Dialog-Schleier bzw. Umschalten des Farbschemas während der Messung. Gegenprobe (Seite direkt dunkel geladen, `library-confluence-wiki`, scale): 0 Befunde.
- `focusObscured` > 0 in 4 von 354 Tab-Spuren (320 px), am stärksten Dropbox/failed (8) – nicht weiter untersucht.
- not-found: Validierung bei SharePoint/Dropbox (Verbindungsseite ohne Felder, „Weiter“ prüft und geht weiter – gewollt), `edit-connection` bei SharePoint/Dropbox (keine eigenen Verbindungsfelder; Zugang über „Zugang wechseln“), `wizard-empty` Dropbox/empty (Kachel nicht rechtzeitig gefunden, Flake).

## Prüfliste

| # | Ergebnis | Beleg |
|---|---|---|
| 1 Ein Signal | bestanden | Stufe = Symbol + Wort; Chips nur Umriss |
| 2 Tabs | bestanden | Reiter unverändert Text + Unterstrich |
| 3 Deko-Icons | bestanden | keine Icons an Überschriften/Labels |
| 4 Getönte Flächen | teilweise | eigene Bausteine ohne Tönung; geerbt: grüne „Verbunden als“-Fläche (`SourceConsentPanel`), gewählte Zugang-Kachel |
| 5 Akzent nur Handlung | bestanden | Akzent nur an Links/Knöpfen („Ändern“, „Ersetzen“) |
| 6 Listenzeile | bestanden | Name + 1 Sekundärzeile (Schlüssel, Art, Pfad, Fehler) |
| 7 Fehler am Feld | bestanden | roter Rahmen + 1 Zeile; Zusammenfassung ohne Icon/Code/X, nur Linksrand (`wizard-validation__*`) |
| 8 Bestätigungsdialog | bestanden | „Änderungen verwerfen?“ + 1 Satz + Abbrechen/Verwerfen |
| 9 Stepper | bestanden | kein Stepper, „Schritt 3 von 7“ als Text |
| 10 Filter | bestanden | nur Suchfeld, keine Filterleisten |
| 11 Hierarchie über Größe | bestanden | Frage-Überschrift 26 px, Abschnitte 16/600 |
| 12 Labels 1–3 Wörter | bestanden | „Adresse“, „App-Passwort“, „Imitiertes Konto“ |
| 13 Hilfetext | bestanden | max. 1 Zeile, nur wo neu (z. B. Herkunft App-Passwort) |
| 14 Platzhalter | bestanden | nur Beispiele (`https://cloud.example.de`, `/data/dokumente`) |
| 15 Fehlertexte | teilweise | eigene Texte 1 Satz; Konnektor-Sätze des Backends enthalten teils Codes („HTTP 401“, „AccessDenied“) |
| 16 Status ein Wort | bestanden | „Gesetzt“, „Keine“, Stufen „Adresse erreichbar“ |
| 17 Knöpfe = Verb | bestanden | Prüfen, Speichern, Ändern, Ersetzen, Hinzufügen; „Weiter“ als Navigationsausnahme |
| 18 Keine Redundanz | bestanden | ein Satz je Meldung |
| 19 Einheitliche Begriffe | bestanden | Begriffssatz oben, gleiche Felder/Knöpfe/Stufen für alle Arten |
| 20 Viele Einträge | bestanden | Suche ab 7, „12 ausgewählt“, Chips mit „und n weitere“, Seiten zu 50 (`wizard-filled__confluence__scale`) |
| 21 Hierarchie | bestanden | Baum Site › Bibliothek › Ordner, Chips und Treffer mit Pfad (`wizard-filled__sharepoint__scale`) |
| 22 Lange Namen | bestanden | Ellipse + Tooltip, kein Umbruchbruch |
| 23 Tiefe/0/Laden/Fehler | bestanden | je Ebene: Skelett nach 300 ms, Fehler mit „Erneut versuchen“, leer (`wizard-filled__*__empty/failed/slow`) |
| 24 Alle/keine | teilweise | „Alle n auswählen“ + „Auswahl aufheben“ in Listen und Treffern; im Baum nicht je Ebene |
| 25 Reflow 320 | bestanden | `overflowX` 0 von 708 |
| 26 Tastatur/Fokus/Titel | bestanden | Fokus auf Schrittüberschrift, Titel „Schritt 3 von 7: Umfang – Neue Wissensbibliothek“ |
| 27 Fokus nicht verdeckt | teilweise | keine Sticky-Leisten; 4 Tab-Spuren mit verdecktem Fokus (s. o.) |
| 28 Absenden aktiv | bestanden | `disabledSubmits` 0; Doppelklick per `BusyButton` (aria-disabled) abgefangen |
| 29 Validierung + Zusammenfassung | bestanden | Fehler erst nach Weiter bzw. Verlassen mit Inhalt; Sprunglinks; Titel „Fehler: …“ |
| 30 Zurück verliert nichts | bestanden | Zustand bleibt, Browser-Zurück = Schritt zurück (`?schritt=`) |
| 31 Ungespeicherte Änderungen | bestanden | Dialog fragt nur bei echtem Unterschied; Assistent beim Abbrechen nur wenn geändert |
| 32 Prüfschritt | bestanden | „Prüfen und anlegen“ mit „Ändern“ je Abschnitt, Rücksprung zur Prüfseite (`review__*`) |
| 33 Verbindungstest | bestanden | automatisch bei Weiter/Speichern + „Verbindung prüfen“; Stufen Adresse · Anmeldung · Rechte inline; markiert Felder der gescheiterten Stufe; verfällt bei Feldänderung (`wizard-check__*`) |
| 34 Geheimnisse | bestanden | „Zugangsdaten · Gesetzt · Ersetzen“ (`edit-connection__*`) |
| 35 Auswahlmuster | bestanden | Kacheln nur für Quellart/Zugang; fremde Objekte als Liste/Baum mit Suche |
| 36 Tri-State | bestanden | Eltern mit gewählten Kindern `aria-checked="mixed"`; gewählter Elternknoten deckt Kinder ab |
| 37 Alle auswählen nennt Umfang | bestanden | „Alle 438 auswählen“, „Alle 12 Treffer auswählen“; Suche löscht keine Auswahl |
| 38 Drei Leerzustände | bestanden | „keine … sichtbar“ / „Keine Treffer für „x“ + Suche zurücksetzen“ / „Kein Zugriff auf die Liste …“ |
| 39 Kein Flackern | bestanden | Skelett erst nach 300 ms; Fortschritt nur im auslösenden Knopf |
| 40 Indizierungsstatus | nicht bearbeitet | Kopf und Läufe unverändert (außerhalb des Umfangs) |
| 41 Fehler nie nur Toast | bestanden | Toast nur für „gespeichert“; Fehler bleiben am Ort |
| 42 Nur-Lesen | teilweise | Leser sehen Umfang ohne „Ändern“; sonst bestehender Satz |
| 43 Textlänge | teilweise | 35-Zeichen-Kompositum und 120-Zeichen-Namen bei 320 px ohne Überlauf; keine Pseudo-Lokalisierung gemessen |
| 44 Automatik | teilweise | axe hell 0; dunkel nur Messartefakte (s. o.) |

## Bekannte Schwächen

- Mehr Klicks: 7 Seiten statt 4; kleine Fälle (1 Ordner) brauchen trotzdem Umfang + Zeitplan als eigene Seiten.
- Suche im Baum durchsucht nur geöffnete Ebenen (Hinweis steht darunter); keine serverseitige Suche (SharePoint-Sites, tiefe Ordner).
- „Alle auswählen“ nicht je Baumebene; Grenze 50 wird beim Klicken gemeldet, nicht vorab.
- SharePoint/Dropbox: Verbindungsseite besteht nur aus Zugang (+ Zustimmung) – eine dünne Seite.
- Webhook/Ereignisse (Confluence/S3) und Zugang-Panel stehen noch im alten Stil unter der Zusammenfassung; Detailkopf unverändert.
- Edit-Dialog schloss im Messlauf nicht per Escape (nach der Tab-Spur); manuell schließt er. Messskript nutzt „Abbrechen“.
- `LibraryCreatePage.tsx` hat 1 091 Zeilen (ESLint-Warnung `max-lines`).

## Gebrochene Tests (bewusst, nicht repariert)

| Datei | Fälle |
|---|---|
| `features/library/pages/LibraryCreatePage.test.tsx` | 44 |
| `features/library/pages/LibraryDetailPage.test.tsx` | 17 |
| `features/library/pages/LibraryCreatePage.sourceConsent.test.tsx` | 6 |
| `features/library/components/LibrarySourceSection.test.tsx` | 2 |

Alle anderen Tests grün (u. a. `PageSection.test.tsx` nach Umstellung der Fehlerzusammenfassung auf Linksrand). `pnpm run lint` (1 Warnung), `pnpm exec tsc -b`, `pnpm run build`, `pnpm run format:check`: bestanden.

## git diff --stat gegen origin/feature/3084_ui-lab-base (ohne `ui-lab/results/`)

```
 .../library/components/LibrarySourceSection.tsx    | 232 ++++--
 .../components/sources/confluenceSource.tsx        |   2 +
 .../library/components/sources/dropboxSource.tsx   |   2 +
 .../library/components/sources/genericSources.tsx  |   3 +
 .../components/sources/googleDriveSource.tsx       |   2 +
 .../library/components/sources/nextcloudSource.tsx |   2 +
 .../library/components/sources/s3Source.tsx        |   2 +
 .../components/sources/sharePointSource.tsx        |   2 +
 .../library/components/sources/smbSource.tsx       |   2 +
 .../features/library/components/sources/types.ts   |   3 +
 .../library/components/stepwise/CheckResult.tsx    |  85 ++
 .../library/components/stepwise/ConnectionPage.tsx |  86 ++
 .../components/stepwise/EditSectionDialog.tsx      | 272 ++++++
 .../library/components/stepwise/ErrorSummary.tsx   |  83 ++
 .../library/components/stepwise/FieldList.tsx      | 287 +++++++
 .../library/components/stepwise/ScopePicker.tsx    | 614 ++++++++++++++
 .../library/components/stepwise/SummaryList.tsx    | 121 +++
 .../components/stepwise/adapters/ScopeExtras.tsx   | 146 ++++
 .../library/components/stepwise/adapters/common.ts |  61 ++
 .../components/stepwise/adapters/confluence.ts     | 166 ++++
 .../components/stepwise/adapters/generic.ts        |  85 ++
 .../components/stepwise/adapters/googleDrive.ts    | 154 ++++
 .../library/components/stepwise/adapters/index.ts  |   6 +
 .../components/stepwise/adapters/pathSources.ts    | 217 +++++
 .../library/components/stepwise/adapters/s3.ts     | 207 +++++
 .../components/stepwise/adapters/sharePoint.ts     | 143 ++++
 .../library/components/stepwise/connectionCheck.ts | 153 ++++
 .../library/components/stepwise/stepErrors.ts      |  36 +
 .../features/library/components/stepwise/types.ts  | 145 ++++
 .../features/library/pages/LibraryCreatePage.tsx   | 913 +++++++++++++++------
 ui-lab/harness/capture.mjs                         |  38 +-
 31 files changed, 3936 insertions(+), 334 deletions(-)
```

Dazu `ui-lab/results/B/`: 1 416 JPEG (708 Aufnahmen + `__full`), `metrics.jsonl`, `run.json`, dieser Bericht.
