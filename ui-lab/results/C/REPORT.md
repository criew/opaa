# Variante C · „Steckbrief mit Abschluss“ – Bericht (Runde 3)

Branch `feature/3084_ui-lab-detail-var-c` · Matrix: 11 Arten × 6 Szenarien + privat/3 Leser × 3 Szenarien, hell/dunkel, 1280/320, Bereich `abschluss` = `?justCreated=1`. Belege ohne `.jpg`.

## Konzept

- **Kopf schmal und gleich:** Chips · Titel mit Beschreibung in einer Zeile · Hauptaktion (Konnektor „Jetzt indizieren“, Upload „Hochladen“) · ☆ ⋯. Keine Kennzahlen im Kopf.
- **Steckbrief rechts (280 px, ab 1100 px), auf allen Reitern:** feste Reihenfolge Zustand → Quelle (Upload: Hochladen) → Bestand → Nächste Schritte.
  - Zustand: ein Wort aus D2, eine Zusatzangabe, das Wort führt mit „›“ zur Ursache.
  - Quelle: Kurzform Adresse · Umfang · Zeitplan · nächster Lauf; im Reiter Quelle ausgeblendet (keine Doppelung).
  - Unter 1100 px: **immer zugeklappt**, eine Zeile „Steckbrief ● Zustand [· n Schritte offen]“.
- **Abschluss nach dem Anlegen:** nur mit `justCreated` (Tab-Sitzung), ✓/○ ohne Balken und ohne Icons, „Ausblenden“ verwirft dauerhaft; der Schritt des offenen Reiters fehlt dort.
- **Reiter:** ein Zeilenraster Titel | Inhalt | Textaktion (Quelle, Metadaten, Freigaben), eine Überschriftenform (15/600), Zeilentitel 14/600, Textaktionen oben rechts, Suche ab 8.
- **Läufe** im Reiter Quelle als Tabelle (Zustand · Beginn · Dauer · Auslöser · Ergebnis), aufgeklappt nach Grund gruppiert.

## Status je D-Punkt

| Nr | Status | Beleg |
|---|---|---|
| D1 | erfüllt nach Maintainer-Entscheidung (≤ 360 px bei 320, kein Abschneiden): `tabsTop` 134 px bei 1280 in allen Arten und Szenarien; bei 320 Titel in kleinerer Stufe (16 px) ungekürzt, 242 px bei kurzem, bis 322 px bei langem Namen; privat ohne Quellart-Chip | `dokumente__*__small__light__1280`, `dokumente__smb__scale__light__320`, `dokumente__privat__small__light__320` |
| D2 | erfüllt in der Steckbrief-Spalte statt im Kopf (Richtung C); Wort aus der Liste + Datum/Prozent, Link zur Ursache mit „›“ | `dokumente__confluence__failed__light__1280`, `dokumente__s3__slow__light__1280` |
| D3 | erfüllt: Zustand, Laufzeile und Gruppen aus einer Zählung (Fixtures angeglichen) | `laeufe__smb__partial__light__1280__full` |
| D4 | erfüllt: Zustand auf jedem Reiter (Spalte bzw. 320-Zeile) | `freigaben__nextcloud__failed__light__1280`, `…__320` |
| D5 | erfüllt: Kennzahlen im Bestand als Links, Listenfuß nur bei Filter/Seiten | `dokumente__dropbox__small__light__1280` |
| D6 | erfüllt: nur Chip „Leser“, kein Rollenhinweis | `quelle__confluence-leser__small__light__1280` |
| D7 | erfüllt: „Läuft · 62 %“ einmal im Steckbrief, gleiche Zahl in der Laufzeile, keine Leiste | `quelle__nextcloud__slow__light__1280` |
| D8 | erfüllt: nicht lesbare Bereiche mit Namen in der Liste der Zeile Umfang markiert | `quelle__confluence__partial__light__1280`, `quelle__gdrive__partial__light__1280` |
| D9 | erfüllt: Web/RSS mit Zeile Umfang („Ganzes Verzeichnis“, „Alle Einträge des Feeds“), Confluence/S3 mit Zeile Live-Aktualisierung | `quelle__web__small__light__1280`, `quelle__s3__small__light__1280` |
| D10 | erfüllt: „Anmeldung ok · zuletzt …“ / „Anmeldung abgelehnt“; ohne Anmeldung „erreichbar“/„nicht erreichbar“ | `quelle__smb__failed__light__1280`, `quelle__filesystem__failed__light__1280` |
| D11 | erfüllt: Vollabgleich im Zeitplan und am Lauf („Zeitplan · Vollabgleich“), Dropbox jetzt auch im Zeitplan | `quelle__dropbox__small__light__1280`, `laeufe__confluence__scale__light__1280__full` |
| D12 | erfüllt: feste Spalten, eine Zeile bei 1280, Zeile als Listeneintrag ohne Überschrift; bei 320 Ergebnis in Zeile 2 | `laeufe__sharepoint__scale__light__1280` |
| D13 | erfüllt: Gruppen nach Grund mit Anzahl, 5 Fundorte, „alle n anzeigen“; „Fehler“ nach Meldung gruppiert; Hinweise getrennt und zugeklappt | `laeufe__smb__scale__light__1280__full` |
| D14 | erfüllt: kein Status-/Grundsatz im Detail, Technische Angaben zugeklappt, Läufe ohne Detail ohne Chevron | `laeufe__confluence__failed__light__1280` |
| D15 | erfüllt: 10 Läufe, Upload ohne Läufe | `laeufe__*__scale__light__1280__full` |
| D16 | erfüllt: eine Inhaltsbreite links (alle Reiter), Textaktionen an derselben Kante | Querbogen je Reiter, `quelle__s3__small__light__1280` gegen `freigaben__s3__small__light__1280` |
| D17 | erfüllt: Abschnitte 15/600 Satzschrift, keine Mono-Versalien | `metadaten__smb__small__light__1280` |
| D18 | erfüllt: „Freigeben“, „In Space verwenden“, „Feld anlegen“ als Textaktion oben rechts | `zuordnungen__rss__small__light__1280` |
| D19 | teilweise: Suche ab 8 in Freigaben, Zuordnungen, Wertelisten; ab 50 nicht gruppiert (45 Freigaben im Mock erreichen die Schwelle nicht) | `freigaben__sharepoint__scale__light__1280` |
| D20 | erfüllt: kein Dauerhinweis | `dokumente__sharepoint__small__light__1280` |
| D21 | erfüllt: leer nur ein Satz (Konnektor) bzw. Ablagefläche (Upload); Aktion im Kopf | `dokumente__rss__empty__light__1280`, `dokumente__upload__empty__light__1280` |
| D22 | erfüllt: Füllgrad nur im Reiter Metadaten, im Reiter Dokumente Filter „ohne …“ | `dokumente__smb__small__light__1280`, `metadaten__smb__small__light__1280` |
| D23 | erfüllt mit Einschränkung: gleicher Kopf und Steckbrief; Zustand „Kontingent voll · 5 fehlgeschlagen“, „Teilweise fehlgeschlagen · mind. n fehlgeschlagen“, „Läuft“; die Zahl stammt aus der geladenen Seite („mind.“, solange nicht alles geladen ist), weil die API keine Zählung liefert | `dokumente__upload__failed__light__1280`, `dokumente__upload__partial__light__1280` |
| D24 | erfüllt, siehe Liste unten | – |

**D24 (Inventur Kap. 8):** Webhook und S3-Ereignisse → Quelle › Live-Aktualisierung · „letzte geplante Läufe fehlgeschlagen“ → Zustand und Zeile Zeitplan · Vollabgleich → an der Laufliste · Neu verbinden/Zugang → Quelle › Verbindung (unverändert) · Obergrenze „an Alle“ → Freigaben · Diagnosesperre → Zeile Suchdiagnose · Externer Zugang → Zeile Extern · Herleitung → Zeile · Felder, Extraktion → Metadaten · Eigentum → Zeile · In Space verwenden → ⋯, Zuordnungen, Nächste Schritte · Upload/Ordner → Kopf „Hochladen“ (Dateien, Ordner, Neuer Ordner) · Feld setzen → Sammelaktion · Name/Beschreibung → Stift · Status unbekannt → Steckbrief „Erneut abrufen“.

**E (Entscheidungen) und F (Fragen):** E1–E6 eingehalten (Läufe im Reiter Quelle, kein Balken, Upload ohne Reiter Quelle). F2 als B umgesetzt, aber nur „Vollabgleich starten“ an der Laufliste (ein zweites „Jetzt indizieren“ wäre P50). F3 als B, soweit das Frontend kann (Fehler am Dokument, Abgelehnte im Steckbrief).

## Review 1

| R1 | Ergebnis |
|---|---|
| 1 | erledigt: Block Quelle im Reiter Quelle ausgeblendet |
| 2 | erledigt: Laufdetail ohne Grund, Zeitplan-Hinweis nur ohne Anmeldefehler und mit Plan |
| 3 | erledigt: aufgeklappt bei 320 ohne Block Zustand |
| 4 | erledigt: kein Knopf im Leerzustand, Bestand ohne Nullzeilen, Schritt des offenen Reiters ausgeblendet |
| 5 | erledigt |
| 6 | erledigt: Listenfuß nur bei Suche/Filter/Seiten |
| 7 | erledigt: kein Popup mehr |
| 8 | erledigt: Markierung in der Liste, Restnamen darunter |
| 9 | erledigt |
| 10 | erledigt: Befristung nur, wenn gesetzt |
| 11 | erledigt bei 320 für alle außer privat (Art-Chip ausgeblendet, Hauptaktion als Icon in der Chipzeile, kurze Quellart-Chips) |
| 12 | erledigt: „Hochladen“ im Kopf |
| 13 | erledigt: feste Blockfolge |
| 14 | erledigt: Linkfarbe, „›“ am Zustand |
| 15 | erledigt: Signalfarbe nur im Punkt |
| 16 | erledigt: Adresse · Umfang · Zeitplan · nächster Lauf für alle |
| 17 | erledigt: Titel „Spaces“, Suchfelder voll breit |
| 18 | erledigt: Textaktionen „Entziehen“, „Lösen“, „Löschen“ |
| 19 | erledigt: Felder im Raster, Zeilentitel 14 px, Füllgrad mit festen Spalten |
| 20 | erledigt |
| 21 | erledigt: „nicht lesbar“ nur für Arten mit gewähltem Umfang, Zahl = Schlüssel |
| 22 | teilweise: „Confluence-Spaces“ im Steckbrief, „Feld anlegen“; Zählnomen der Umfangszusammenfassung („1 Bucket oder Präfix“) bewusst nicht: kommen aus dem geteilten Assistenten-Raster |
| 23 | erledigt: Fortschritt im Knopf, nicht ausgegraut |
| 24 | bewusst nicht: „Noch kein Inhalt“ (G 5.5) für leeren Upload; abgelehnte Dateien bleiben sitzungsgebunden, weil das Backend sie nicht speichert |
| 25 | bewusst nicht: Ordnerkante folgt der Checkbox-Spalte, Leser ohne Spalte (gleich wie Dokumente) |
| 26 | teilweise: Dauer „3 h 12 min“, RSS-Umfang ohne „Ändern“ korrekt (keine Wahl), Bestand „· zuständig“; Beschreibung neben langem Titel bewusst gekürzt (D1 feste Höhe); „Live-Aktualisierung“ darf zweizeilig sein |
| 27 | erledigt: unter 1100 px immer zugeklappt |
| 28 | erledigt: Abschluss nur mit `justCreated` (sessionStorage), Mock ohne Space/Feld bei `justCreated` |
| 29 | erledigt: Fixture-Ereignisse = fehlgeschlagene Dokumente, Ergebnis mit Einheit, eigene Gruppe „Bereiche nicht lesbar“ |
| 30 | erledigt: Zielhöhe 24 px |
| 31 | erledigt: sichtbarer Text am Anfang des Namens |
| 32 | erledigt: Fixtures in Ortszeit |
| 33 | teilweise: Zustand aus den geladenen Dokumenten (Teilweise fehlgeschlagen/Läuft), ohne Zahl |
| 34 | teilweise: Menü „Hochladen“ nennt „Speicherkontingent voll“, Einträge gesperrt; ohne Fehlerzahl |
| 35 | erledigt: Leser ohne Link am Zustand |
| 36 | bewusst nicht: Lesende dürfen nach ADR-0039 in eigene Spaces zuordnen |
| 37 | erledigt: Zeile „Extern“, ohne Daten nicht angezeigt |
| 38 | erledigt: OWNER-Freigabe nicht in der Liste |
| 39 | erledigt: Zeile Kontextpräfix nennt eigene Felder |
| 40 | teilweise: bei 0 Dokumenten weggelassen; eigene Felder fehlen, weil die Qualitäts-API nur Kernfelder liefert |
| 41 | erledigt: aktiver Reiter in Sicht, Scrollpfeile |
| 42 | erledigt: Kurzzeile ohne Datum, mit Ellipse |
| 43 | erledigt: Ergebnis zweite Zeile bei 320, Abstand zum Chevron |
| 44 | erledigt: Steckbrief-Zeilen einzeilig mit Ellipse und Tooltip |
| 45 | erledigt: „seit …“, Name einzeilig |
| 46 | erledigt: Statuspunkt bei 320, Freigabe-Aktionen als Textlink |
| 47 | erledigt: Zeilenaktionen bei 320 im ⋯, Titel ungekürzt |
| 48 | erledigt (kein Popup) |
| 49 | erledigt: „erreichbar/nicht erreichbar“, Zustand „Fehlgeschlagen“ ohne Anmeldung |
| 50 | erledigt |
| 51 | erledigt |
| 52 | erledigt: unter Technische Angaben |
| 53 | erledigt |
| 54 | erledigt: „Ihre Rolle: …“ |
| 55 | erledigt: Zeitplan „aus“, Privat-Satz entfernt, Upload-Leerzustand ein Satz |
| 56 | erledigt: kein Regex, kein Code im Chip |
| 57 | erledigt: Leiste entfernt |
| 58 | erledigt: Chip „Wissen“ ohne Icon |
| 59–62 | erledigt im Werkzeug: Warten auf Details, `prepare` nach jedem Resize (Lauf in Sicht, Fokus/Zeiger in Ruhe) |
| 63 | erledigt mit R1-28 |
| 64–66 | erledigt: Ergebnis = Bestand, Anmeldefehler 4 s, Pfade je Art, Feed-URLs |
| 67 | offen: Speicher- und Space-Zahlen der Fixtures nicht plausibilisiert |
| 68–69 | Werkzeug des Orchestrators |
| Fehlt | REPORT.md (hier), `tabsTop` in `measure.mjs` ergänzt |

## Nachcheck 1 (Runde 3)

| Punkt | Ergebnis |
|---|---|
| R1-33/34 Upload-Zustand | Zahl aus der geladenen Seite: „Kontingent voll · 5 fehlgeschlagen“, „Teilweise fehlgeschlagen · mind. n fehlgeschlagen“; Fehlgeschlagene vor Wartenden |
| R1-28 Abschluss | „Quelle verbunden ✓“ als erster Schritt, ○ für offene; bei 320 stehen die Schritte offen unter der Steckbrief-Zeile |
| D1/R1-27 Kopf 320 | nach Maintainer-Entscheidung: Titel 16 px, ungekürzt; `tabsTop` 242–322 (vorher bis 407); privat ohne Quellart-Chip; `tabsTop` jetzt Budget in `budgetViolations` |
| Maintainer 1 | private Bibliotheken in Spaces: „in n Spaces“ bei privat bleibt (Fixture unverändert) |
| R1-19 Füllgrad 320 | bei 320 nur Feld und Leer |
| R1-25 Upload-Raster | Ordnerzeile mit Abständen der Dokumentzeile, ohne Ordner-Icon, zweite Zeile „Ordner · n Dokumente“: Namen an einer Kante, nicht mehr gekürzt |
| R1-4 Upload leer | nur die Ablagefläche, Handlung im Kopf |
| R1-49 | Laufliste „Nicht erreichbar“ für Quellen ohne Anmeldung |
| R1-43 | Ergebnis bricht bei 1280 um statt abzuschneiden; Spalten enger; Fundorte des Dateisystems relativ |
| R1-45 | Zuordnung einzeilig, Name kürzt, „· seit …“ bleibt |
| R1-46/47 | Dokumentzeile bei 320: Größe · Status in Zeile 2; Freigabe bei 320 ohne leere Befristung, Aktion in der Zeile; „· —“ entfällt |
| R1-42 | Steckbrief-Zeile mit Ellipse; die abgeschnittenen Bilder waren Aufnahmefehler (Recorder) |
| Regression 4/9 | Steckbrief „Zeitplan aus“; „Noch keine Freigaben.“ ohne Suche; Zeilentitel mit Silbentrennung („Aufbewahrungs-frist“) |
| Regression 5 | Abstand unter der Reiterleiste bei 320 (verdeckte Gesamtauswahl) entfällt (`useFlexGap`) |
| R1-64 | partial: einer von zwei Bereichen nicht lesbar; Läufe von Hand 09:12 statt 06:00 (keine zwei gleichzeitigen Läufe); Wartende über die Liste verteilt |
| R1-67 | teilweise: Upload-Speicher = Summe der Dokumentgrößen; Spaces und Füllgrad unverändert (Fixtures geteilt mit anderen Tests) |
| F Aufnahme | Recorder wartet nach jedem Resize auf `innerWidth` und zwei Frames, wiederholt weiße Bilder und meldet sie als `blank` statt `ok` |
| R1-25 Begründung | zurückgenommen, umgesetzt (siehe oben) |
| R1-26 Pfadtrenner | bewusst nicht: die Zusammenfassungen kommen aus dem geteilten Assistenten-Raster |

## Nachcheck 2 (Runde 4, Teilmatrix hell)

| Punkt | Ergebnis |
|---|---|
| Füllgrad @320 | nur Feld und Leer (Spalten Regel/Modell/Hand ab sm) – `metadaten__*__{small,scale}__light__320` |
| R1-45 Zuordnung | einzeilig: Name kürzt mit Tooltip, „· seit …“ bleibt in der Zeile, „zugeordnet von“ im Tooltip – `zuordnungen__*__scale__light__{1280,320}` |
| Abschluss | „✓ Quelle verbunden“ nur ohne Sperre und ohne abgelehnte Anmeldung, sonst erster Schritt „Zugang prüfen“ (○, Link auf Verbindung); „Ausblenden“ in der Kopfzeile; bei 320 nur offene Schritte – `abschluss__*` |
| Kleinkram | Zustandszeile ohne „› ·“ (Pfeil am Ende), Freigaben-Tabellenkopf nur mit Einträgen, Ordner mit ⋯ wie Dokumente |

Teilmatrix: 200 Aufnahmen hell (Metadaten 320, Zuordnungen scale, Abschluss alle Arten und Szenarien plus Sonderfälle), axe 0, `overflowX` 0, 2 × `blank` einzeln nachgeholt.

## Videos

`videos/` mit `manifest.json`: 21 MP4 (hell, 1280 × 800), je Ablauf ok (`small`), viele (`scale`) und fehler (`failed`), aus `ui-lab/harness/flows/library-detail-c-videos.mjs`:

| Ablauf | Bibliothek | Inhalt |
|---|---|---|
| dokumente | Confluence | Suche, Filter „ohne Titel“, Angaben ⓘ, Zustand im Steckbrief führt zur Ursache |
| quelle | Confluence | Reiter Quelle (fehler: „Ändern“ an der Verbindung), Läufe, neuester Lauf aufgeklappt, „alle n anzeigen“, Technische Angaben |
| metadaten | SMB | Wertesuche und „Alle n anzeigen“ (viele), Zeilen Kontextpräfix und Extraktion |
| freigaben | SharePoint | Suche (viele), „Freigeben“ und Abbrechen, Herleitung |
| zuordnungen | S3 | Suche (viele), Dialog „In Space verwenden“ |
| upload | Upload | Menü „Hochladen“, zwei Dateien, abgelehnte Dateien (fehler), Zustand |
| abschluss | Confluence, `justCreated` | Nächste Schritte (fehler: „Zugang prüfen“), Schritte führen in die Reiter, „Ausblenden“ |

Ein Probelauf fand zwei Fehler, beide vor der Aufnahme behoben: Der Zustand flackerte beim Laden kurz als „Noch nie indiziert“ (jetzt Platzhalter bis zur ersten Statusantwort, P39), und der Ereignisdialog schloss als leerer Rahmen. Bekannt: In `dokumente__viele` findet der Ablauf den Knopf „Seite 2“ nicht. Bei `upload__fehler` bricht „Kontingent voll · mind. 4 fehlgeschlagen“ im Steckbrief auf zwei Zeilen um.

## Schwächen

- **Abschluss bei 320 über dem Kopf-Budget:** `tabsTop` 234–354 px bei bis zu drei offenen Schritten; mit vier offenen Schritten (`failed`: „Zugang prüfen“ zusätzlich) oder langem Titel (`scale`) bis 412 px (14 Bilder). Gewollt: Die Schritte stehen nur nach dem Anlegen offen und verschwinden mit „Ausblenden“ oder sobald alles erledigt ist.

- **Zwei Spalten:** Unter 1100 px bleibt eine zusätzliche Zeile über den Reitern (Steckbrief-Zeile, 40 px); aufgeklappt wird sie wieder zum Zwischenbereich.
- **Zustand nicht im Kopf:** D2 verlangt den Kopf; C setzt ihn in die Spalte. Bei 320 steht nur das Wort, Ursache erst nach Aufklappen bzw. im Reiter.
- **Upload ohne Zählungen:** Ohne Fehler-/Wartezahl im Backend leitet C den Zustand aus der geladenen Seite ab („Teilweise fehlgeschlagen“ ohne Zahl).
- **Abgelehnte Uploads** nur in der Tab-Sitzung (Backend speichert sie nicht).
- **Geteilte Komponenten geändert:** `AssetGrantsSection`, `AssetOwnerSection`, `AssetSpacesList`, `AssetDetailHeader` (Prompt-Bibliothek betroffen; Zeilenform nur unter `PlainPageSections`).
- **Freigaben ab 50** nicht gruppiert.

## Gebrochene Unit-Tests

Basis (`feature/3084_ui-lab-detail-base`) hat bereits 108 rote Tests in 13 Dateien (Assistent, Quellformulare, Detailseite). C bricht zusätzlich (Stand Runde 1, gleiche Dateien in Runde 2):

| Datei | rot (C / Basis) | Grund |
|---|---|---|
| `asset/components/AssetGrantsSection.test.tsx` | 4 / 0 | Zeilen ohne Vergabezeile, Rollen zugeklappt, Textaktion statt Papierkorb |
| `library/components/LibraryDocumentList.test.tsx` | 9 / 0 | Herkunft hinter ⓘ, keine Ordner-Checkbox, Auswahlleiste erst mit Auswahl |
| `library/components/LibrarySourceSection.test.tsx` | 21 / 18 | Laufliste neu, kein Leser-Satz |
| `library/components/metadata/LibraryMetadataFieldsSection.test.tsx` | 2 / 0 | Wertelisten-Hinweis, Kontextpräfix als Zeile |
| `library/components/metadata/MetadataExtractionSettingsSection.test.tsx` | 4 / 0 | Extraktion als Zeile, Füllgrad als Tabelle |
| `library/pages/LibraryDetailPage.test.tsx` | 68 / 19 | Kopf ohne Kennzahlen, Steckbrief, Kontingent und Lauf nicht mehr im Kopf |
| `library/pages/LibraryDetailPage.privateLibrary.test.tsx` | 2 / 0 | Privat-Hinweis entfernt, Speicher im Steckbrief |

## Kennzahlen (Runde 3)

Nach der Vorgabe des Maintainers wurde nur das helle Schema voll aufgenommen (1056 Aufnahmen `ok`, 18 `not-found` erwartet). Dazu kam ein dunkler Kontrast-Stichlauf (Confluence und Upload, small, 1280, alle Bereiche). Die übrigen dunklen Bilder im Ordner sind Altstand aus Runde 2.

| Kennzahl | Wert |
|---|---|
| `tabsTop` 1280 | 134 px überall |
| `tabsTop` 320 | 234–322 px; Ausnahme `abschluss`: 380–462 px, weil die Schritte nach dem Anlegen auf Wunsch offen über den Reitern stehen (einmalig, bis erledigt oder ausgeblendet) |
| `budgetViolations` | nur `tabsTop` in `abschluss` bei 320 (64), sonst 0 |
| `overflowX` | 0 |
| Kontrast hell / dunkel (Stichlauf) | 0 / 0 |
| axe (hell) | 1 × `target-size` |
| `uiWords` small, max | Dokumente 97 · Quelle 72 · Läufe 72 · Metadaten 78 · Freigaben 87 · Zuordnungen 56 · Upload 113 · Abschluss 97 |
| Aufnahme | 0 × `blank` nach Wiederholung (2 weiße Bilder einzeln nachgeholt) |

## git diff --stat (gegen `feature/3084_ui-lab-detail-base`, ohne Ergebnisse)

```
 .../components/AssetAccessDerivationSection.tsx    |  21 +
 .../asset/components/AssetDetailHeader.tsx         |  87 ++-
 .../asset/components/AssetGrantsSection.tsx        | 326 +++++++----
 .../asset/components/AssetHeadlineEditor.tsx       |  77 +++
 .../asset/components/AssetOwnerSection.tsx         |  47 ++
 .../features/asset/components/AssetSpacesList.tsx  | 186 +++---
 .../asset/components/AssetSpacesSection.tsx        |   7 +-
 .../features/asset/components/UseInSpaceButton.tsx |  35 +-
 .../library/components/LibraryDetailTabBar.tsx     |  17 +-
 .../library/components/LibraryDocumentList.tsx     | 474 ++++++++++------
 .../components/LibraryDocumentUploadArea.tsx       | 172 ++++--
 .../library/components/LibraryDocumentsNotices.tsx |  15 -
 .../library/components/LibraryDocumentsSection.tsx | 109 ++--
 .../components/LibraryExternalAccessSection.tsx    | 122 ++--
 .../library/components/LibraryMetadataTab.tsx      |  17 +-
 .../features/library/components/LibraryRunList.tsx | 502 +++++++++++++++++
 .../library/components/LibrarySharingTab.tsx       | 109 ++--
 .../library/components/LibrarySourceRows.tsx       |  36 +-
 .../library/components/LibrarySourceSection.tsx    | 415 +++++---------
 .../library/components/metadata/FieldValues.tsx    |  49 ++
 .../metadata/LibraryMetadataFieldsSection.tsx      | 385 +++++++------
 .../metadata/MetadataExtractionSettingsSection.tsx | 251 +++++----
 .../library/components/setup/SourceSetupRows.tsx   |  34 +-
 .../library/components/setup/SummaryRow.tsx        |  37 +-
 .../library/components/setup/scheduleSummary.ts    |   8 +-
 .../components/steckbrief/LibrarySteckbrief.tsx    | 513 +++++++++++++++++
 .../library/components/steckbrief/libraryState.ts  | 190 +++++++
 .../library/components/steckbrief/nextSteps.ts     | 134 +++++
 .../library/components/steckbrief/sourceSummary.ts | 145 +++++
 .../library/mocks/detailScenarioFixtures.ts        |  35 +-
 .../library/mocks/libraryScenarioFixtures.ts       |  76 ++-
 .../features/library/pages/LibraryDetailPage.tsx   | 624 +++++++++++----------
 .../components/GroupMembersDisclosure.tsx          |   5 +-
 frontend/src/shared/mocks/mockScenario.ts          |   9 +
 frontend/src/shared/ui/PageSection.tsx             |  43 +-
 frontend/src/shared/ui/PlainRow.tsx                |  85 +++
 frontend/src/shared/ui/plainSections.ts            |   9 +
 ui-lab/harness/flows/library-detail.mjs            |  30 +-
 ui-lab/harness/lib/measure.mjs                     |  48 +-
 39 files changed, 3946 insertions(+), 1538 deletions(-)
```
